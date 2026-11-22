package com.eynnzerr.telemetrycleaner.data.packagemanager

import android.content.pm.PackageManager
import com.eynnzerr.telemetrycleaner.data.ChangeFailure
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageScanIssue
import com.eynnzerr.telemetrycleaner.data.PackageState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PackageCommandsTest {
    private class FakePackageExecutor(
        override val prefersShellDump: Boolean = false,
        private val onExecute: (List<String>) -> PackageShellResult = { PackageShellResult(0, "") },
        private val onDump: (List<String>) -> PackageShellResult = { PackageShellResult(0, "") },
    ) : PackageExecutor {
        val executedCommands = mutableListOf<List<String>>()
        val dumpArgs = mutableListOf<List<String>>()

        override fun execute(args: List<String>): PackageShellResult {
            executedCommands += args
            return onExecute(args)
        }

        override fun dump(vararg args: String): PackageShellResult {
            val list = args.toList()
            dumpArgs += list
            return onDump(list)
        }
    }

    private fun usersOutput(): String = """
        Users:
          UserInfo{0:Owner:4c13} running
          UserInfo{10:system_clone:1000010} running
          UserInfo{11:Work:30} running
    """.trimIndent()

    private fun packageDump(
        packageName: String,
        versionName: String = "1.2.3",
        versionCode: Long = 123L,
        flags: String = "SYSTEM HAS_CODE",
        appId: Int = 10123,
        codePath: String = "/system_ext/app/Sample",
        pkgField: String = "Package{abc $packageName}",
        userLines: List<String>,
    ): String = buildString {
        appendLine("Packages:")
        appendLine("  Package [$packageName] (deadbeef):")
        appendLine("    appId=$appId")
        appendLine("    pkg=$pkgField")
        appendLine("    codePath=$codePath")
        appendLine("    versionCode=$versionCode minSdk=29 targetSdk=35")
        appendLine("    versionName=$versionName")
        appendLine("    pkgFlags=[ $flags ]")
        userLines.forEach { appendLine("    $it") }
    }

    @Test
    fun scan_parsesMultiUserStatesSystemIdentityAndUserDisabledIds() = runTest {
        val entry = PackageEntry("com.oppo.ctautoregist", "CTAutoRegist", emptySet())
        val missingEntry = PackageEntry("com.example.missing", "Missing", emptySet())
        val deniedEntry = PackageEntry("com.example.denied", "Denied", emptySet())

        val executor = FakePackageExecutor(
            onExecute = { args ->
                if (args == listOf("list", "users")) PackageShellResult(0, usersOutput())
                else PackageShellResult(1, "")
            },
            onDump = { args ->
                when (args.singleOrNull()) {
                    entry.packageName -> PackageShellResult(
                        0,
                        packageDump(
                            packageName = entry.packageName,
                            userLines = listOf(
                                "User 0: ceDataInode=1 installed=true hidden=false enabled=0",
                                "User 10: ceDataInode=2 installed=true hidden=false enabled=${PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER}",
                                "User 11: ceDataInode=3 installed=false hidden=false enabled=0",
                            ),
                        ),
                    )
                    missingEntry.packageName -> PackageShellResult(0, "Unable to find package: ${missingEntry.packageName}")
                    deniedEntry.packageName -> PackageShellResult(1, "Permission Denial: can't dump package")
                    else -> PackageShellResult(1, "")
                }
            },
        )

        val snapshot = PackageCommands(executor).scan(0, listOf(entry, missingEntry, deniedEntry), permissionManager = null)

        assertEquals(listOf(0, 10, 11), snapshot.users.map { it.id })

        val detected = snapshot.packages[0]
        assertEquals("1.2.3", detected.versionName)
        assertEquals(123L, detected.versionCode)
        assertEquals("/system_ext/app/Sample", detected.systemPath)
        assertTrue(detected.hasVerifiedSystemIdentity)
        assertEquals(true, detected.hasSystemCopy)
        assertEquals(PackageState.INSTALLED, detected.users[0])
        assertEquals(PackageState.DISABLED, detected.users[10])
        assertEquals(PackageState.RESTORABLE, detected.users[11])
        assertEquals(setOf(10), detected.userDisabledIds)

        val missing = snapshot.packages[1]
        assertEquals(mapOf(0 to PackageState.NOT_FOUND, 10 to PackageState.NOT_FOUND, 11 to PackageState.NOT_FOUND), missing.users)

        val denied = snapshot.packages[2]
        assertEquals(PackageState.UNKNOWN, denied.users[0])
        assertEquals(PackageScanIssue.PERMISSION_DENIED, denied.scanIssues[0])
    }

    @Test
    fun scan_throwsWhenCallerUserMissingFromUserList() = runTest {
        val executor = FakePackageExecutor(
            onExecute = { PackageShellResult(0, "Users:\n  UserInfo{10:Clone:10} running") },
        )
        try {
            PackageCommands(executor).scan(callerUser = 0, entries = emptyList(), permissionManager = null)
            fail("Expected UserListScanException")
        } catch (expected: UserListScanException) {
            assertEquals(PackageScanIssue.USER_STATE_MISSING, expected.issue)
        }
    }

    @Test
    fun read_fallsBackFromShellDumpToBinderDumpWhenPreferredShellDumpFails() {
        val pkg = "com.example.app"
        val executor = FakePackageExecutor(
            prefersShellDump = true,
            onExecute = { PackageShellResult(1, "Error: dump failed") },
            onDump = {
                PackageShellResult(
                    0,
                    packageDump(pkg, userLines = listOf("User 0: installed=true enabled=0")),
                )
            },
        )

        val reading = PackageCommands(executor).read(pkg)
        assertTrue(reading is PackageReading.Found)
        assertEquals(listOf(listOf("dump", pkg)), executor.executedCommands)
        assertEquals(listOf(listOf(pkg)), executor.dumpArgs)
    }

    @Test
    fun change_uninstallSuccessAndFallbackDisableWhenBlocked() = runTest {
        val pkg = "com.oppo.ctautoregist"
        val target = ChangeTarget(pkg, 0)
        var disabled = false

        val executor = FakePackageExecutor(
            onExecute = { args ->
                when (args.firstOrNull()) {
                    "uninstall" -> PackageShellResult(1, "Failure [DELETE_FAILED_USER_RESTRICTED]")
                    "disable-user" -> {
                        disabled = true
                        PackageShellResult(0, "Package $pkg new state: disabled-user")
                    }
                    else -> PackageShellResult(1, "")
                }
            },
            onDump = {
                val enabled = if (disabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
                PackageShellResult(
                    0,
                    packageDump(pkg, userLines = listOf("User 0: installed=true enabled=$enabled")),
                )
            },
        )

        // With fallback enabled, should disable instead
        val disabledOutcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = true,
        )
        assertFalse(disabledOutcome.success)
        assertTrue(disabledOutcome.disabledInstead)
        assertNull(disabledOutcome.failure)

        // Reset and run with fallback disabled -> preserves exact system error text
        disabled = false
        val deniedOutcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = false,
        )
        assertFalse(deniedOutcome.success)
        assertFalse(deniedOutcome.disabledInstead)
        assertEquals(
            ChangeFailure.SystemError("Failure [DELETE_FAILED_USER_RESTRICTED]"),
            deniedOutcome.failure,
        )
    }

    @Test
    fun change_refusesSystemDisabledRestoreAndUnverifiedSystemIdentity() = runTest {
        val pkg = "com.example.test"
        val target = ChangeTarget(pkg, 0)

        val systemDisabledExecutor = FakePackageExecutor(
            onDump = {
                PackageShellResult(
                    0,
                    packageDump(
                        pkg,
                        flags = "HAS_CODE", // not SYSTEM
                        userLines = listOf(
                            "User 0: installed=true enabled=${PackageManager.COMPONENT_ENABLED_STATE_DISABLED}",
                        ),
                    ),
                )
            },
        )

        val unverified = PackageCommands(systemDisabledExecutor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = true,
            captureDenialLog = false,
            disableOnUninstallFailure = false,
        )
        assertFalse(unverified.success)
        assertEquals(ChangeFailure.SystemIdentityNotVerified, unverified.failure)

        val sysDisabledRestore = PackageCommands(systemDisabledExecutor).change(
            target = target,
            action = PackageAction.RESTORE,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = false,
        )
        assertFalse(sysDisabledRestore.success)
        assertEquals(ChangeFailure.DisabledBySystem, sysDisabledRestore.failure)
    }

    @Test
    fun change_retriesUninstallViaAppProcessFallbackAndVerifiesRemoval() = runTest {
        val pkg = "com.oppo.ctautoregist"
        val target = ChangeTarget(pkg, 0)
        var removed = false
        var now = 0L

        val executor = FakePackageExecutor(
            onExecute = { PackageShellResult(1, "Failure [DELETE_FAILED_INTERNAL_ERROR]") },
            onDump = {
                PackageShellResult(
                    0,
                    packageDump(
                        pkg,
                        userLines = listOf("User 0: installed=${!removed} enabled=0"),
                    ),
                )
            },
        )

        val commands = PackageCommands(executor, clock = { now.also { now += 100L } })
        val outcome = commands.change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = false,
            deleteThroughAppProcess = { _, _ ->
                removed = true
                PackageFallbackResult(submitted = true, detail = "Success")
            },
        )

        assertTrue(outcome.success)
        assertNull(outcome.failure)
    }
}
