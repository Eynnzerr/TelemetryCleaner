package com.eynnzerr.telemetrycleaner.data.packagemanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageState
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [PackageBinder.capturePipeOutput] pipe/timeout guarantees and
 * [PackageCommands.scan] permission enrichment with a real Android [android.content.pm.PackageManager].
 */
@RunWith(AndroidJUnit4::class)
class PackageCommandsInstrumentedTest {

    @Test
    fun capturePipeOutput_drainsPayloadLargerThanPipeBufferWithoutBlocking() {
        val payload = "A".repeat(256 * 1024)
        val captured = PackageBinder.capturePipeOutput(timeoutSeconds = 5L) { writeEnd ->
            FileOutputStream(writeEnd.fileDescriptor).use { out ->
                out.write(payload.toByteArray())
                out.flush()
            }
        }
        assertEquals(payload.length, captured.length)
        assertEquals(payload, captured)
    }

    @Test
    fun capturePipeOutput_closesReadEndOnTimeoutAndUnblocksStalledWriter() {
        val writerBroken = AtomicBoolean(false)
        val writerExited = CountDownLatch(1)
        val chunk = ByteArray(16 * 1024) { 'X'.code.toByte() }

        try {
            PackageBinder.capturePipeOutput(timeoutSeconds = 1L) { writeEnd ->
                try {
                    FileOutputStream(writeEnd.fileDescriptor).use { out ->
                        // Keep writing past the timeout until the reader closes the pipe.
                        while (!Thread.currentThread().isInterrupted) {
                            out.write(chunk)
                            Thread.sleep(50)
                        }
                    }
                } catch (_: IOException) {
                    writerBroken.set(true)
                } catch (_: InterruptedException) {
                    writerBroken.set(true)
                } finally {
                    writerExited.countDown()
                }
            }
            fail("Expected TimeoutException")
        } catch (_: TimeoutException) {
            // Expected.
        }

        assertTrue("Writer thread should unblock promptly after timeout", writerExited.await(3, TimeUnit.SECONDS))
        assertTrue("Writer should observe broken pipe or interruption", writerBroken.get())
    }

    @Test
    fun capturePipeOutput_rejectsOversizedOutputAndBreaksPipeForWriter() {
        val writerBroken = AtomicBoolean(false)
        val writerExited = CountDownLatch(1)
        val chunk = ByteArray(16 * 1024) { 'B'.code.toByte() }

        try {
            PackageBinder.capturePipeOutput(timeoutSeconds = 5L, maxChars = 32 * 1024) { writeEnd ->
                try {
                    FileOutputStream(writeEnd.fileDescriptor).use { out ->
                        repeat(20) {
                            out.write(chunk)
                            out.flush()
                        }
                    }
                } catch (_: IOException) {
                    writerBroken.set(true)
                } finally {
                    writerExited.countDown()
                }
            }
            fail("Expected IOException for oversized output")
        } catch (_: IOException) {
            // Expected.
        }

        assertTrue(writerExited.await(3, TimeUnit.SECONDS))
        assertTrue(writerBroken.get())
    }

    @Test
    fun capturePipeOutput_propagatesSecurityExceptionDirectly() {
        try {
            PackageBinder.capturePipeOutput(timeoutSeconds = 5L) {
                throw SecurityException("Permission Denial")
            }
            fail("Expected SecurityException")
        } catch (expected: SecurityException) {
            assertEquals("Permission Denial", expected.message)
        }
    }

    @Test
    fun scan_enrichesOnlyPresentPackagesAndDegradesGracefullyWhenDetailDumpFails() = runBlocking {
        val packageManager = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val presentOk = PackageEntry("com.mobiletools.systemhelper", "SystemHelper", emptySet())
        val presentSlow = PackageEntry("com.oppo.ctautoregist", "CTAutoRegist", emptySet())
        val absent = PackageEntry("com.xiaomi.registration", "AutoRegistration", emptySet())

        val dumpArgs = mutableListOf<List<String>>()
        val executor = object : PackageExecutor {
            override fun execute(args: List<String>): PackageShellResult = when (args) {
                listOf("list", "users") -> PackageShellResult(
                    0,
                    """
                    Users:
                      UserInfo{0:Owner:4c13} running
                      UserInfo{10:Clone:20000410}
                    """.trimIndent(),
                )
                else -> error("Unexpected shell command: $args")
            }

            override fun dump(vararg args: String): PackageShellResult {
                val list = args.toList()
                dumpArgs += list
                return when (list.singleOrNull()) {
                    "p" -> PackageShellResult(
                        0,
                        """
                        Packages:
                          Package [${presentOk.packageName}] (1111):
                            appId=10236
                            sharedUser=SharedUserSetting{aaaa com.mobiletools.systemhelper.uid/10236}
                            pkg=Package{1111 ${presentOk.packageName}}
                            codePath=/product/priv-app/SystemHelper
                            versionCode=31 minSdk=31 targetSdk=33
                            versionName=3.0.1
                            pkgFlags=[ SYSTEM HAS_CODE ]
                            User 0: installed=true enabled=0
                            User 10: installed=false enabled=0
                          Package [${presentSlow.packageName}] (2222):
                            appId=10229
                            pkg=Package{2222 ${presentSlow.packageName}}
                            codePath=/product/priv-app/CTAutoRegist
                            versionCode=16000000 minSdk=33 targetSdk=35
                            versionName=16.0.0
                            pkgFlags=[ SYSTEM HAS_CODE ]
                            User 0: installed=false enabled=0
                            User 10: installed=true enabled=0
                        """.trimIndent(),
                    )
                    presentOk.packageName -> PackageShellResult(
                        0,
                        """
                        Packages:
                          Package [${presentOk.packageName}] (1111):
                            appId=10236
                            sharedUser=SharedUserSetting{aaaa com.mobiletools.systemhelper.uid/10236}
                            pkg=Package{1111 ${presentOk.packageName}}
                            codePath=/product/priv-app/SystemHelper
                            versionCode=31 minSdk=31 targetSdk=33
                            versionName=3.0.1
                            pkgFlags=[ SYSTEM HAS_CODE ]
                            requested permissions:
                              android.permission.INTERNET
                              android.permission.READ_PHONE_STATE
                            User 0: installed=true enabled=0
                            User 10: installed=false enabled=0

                        Shared users:
                          SharedUser [com.mobiletools.systemhelper.uid] (aaaa):
                            appId=10236
                            install permissions:
                              android.permission.INTERNET: granted=true
                            User 0:
                              runtime permissions:
                                android.permission.READ_PHONE_STATE: granted=true
                        """.trimIndent(),
                    )
                    presentSlow.packageName -> throw TimeoutException("Simulated single-package dump timeout")
                    else -> error("Absent package must not be queried individually: $list")
                }
            }
        }

        val snapshot = PackageCommands(executor).scan(
            callerUser = 0,
            entries = listOf(presentOk, presentSlow, absent),
            permissionManager = packageManager,
        )

        // Batch 'p' plus targeted enrichment for the 2 present entries only; absent entry never queried.
        assertEquals(
            listOf(listOf("p"), listOf(presentOk.packageName), listOf(presentSlow.packageName)),
            dumpArgs,
        )

        val okDetected = snapshot.packages[0]
        assertEquals(PackageState.INSTALLED, okDetected.users[0])
        assertEquals(PackageState.RESTORABLE, okDetected.users[10])
        val permissions = okDetected.permissions
        assertNotNull(permissions)
        assertEquals(
            mapOf("android.permission.INTERNET" to true, "android.permission.READ_PHONE_STATE" to true),
            permissions!!.associate { it.name to it.grants[0] },
        )

        // Targeted detail dump timed out, yet verified state from batch dump is preserved cleanly.
        val slowDetected = snapshot.packages[1]
        assertEquals(PackageState.RESTORABLE, slowDetected.users[0])
        assertEquals(PackageState.INSTALLED, slowDetected.users[10])
        assertNull(slowDetected.permissions)

        val absentDetected = snapshot.packages[2]
        assertEquals(PackageState.NOT_FOUND, absentDetected.users[0])
        assertEquals(PackageState.NOT_FOUND, absentDetected.users[10])
    }
}
