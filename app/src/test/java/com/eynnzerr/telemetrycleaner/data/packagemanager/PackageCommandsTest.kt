package com.eynnzerr.telemetrycleaner.data.packagemanager

import android.content.pm.PackageManager
import com.eynnzerr.telemetrycleaner.data.ChangeFailure
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageScanIssue
import com.eynnzerr.telemetrycleaner.data.PackageState
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PackageCommandsTest {
    private class FakePackageExecutor(
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
    fun read_queriesPmsDumpDirectlyWithoutShellDumpCommand() {
        val pkg = "com.example.app"
        val executor = FakePackageExecutor(
            onExecute = { error("pm dump shell command must not be called") },
            onDump = {
                PackageShellResult(
                    0,
                    packageDump(pkg, userLines = listOf("User 0: installed=true enabled=0")),
                )
            },
        )

        val reading = PackageCommands(executor).read(pkg)
        assertTrue(reading is PackageReading.Found)
        assertTrue(executor.executedCommands.isEmpty())
        assertEquals(listOf(listOf(pkg)), executor.dumpArgs)
    }

    @Test
    fun scan_usesSingleBatchPackagesDumpAndIgnoresHiddenSystemPackagesSection() = runTest {
        val presentA = PackageEntry("com.mobiletools.systemhelper", "SystemHelper", emptySet())
        val presentB = PackageEntry("com.oppo.ctautoregist", "CTAutoRegist", emptySet())
        val absent = PackageEntry("com.xiaomi.registration", "AutoRegistration", emptySet())

        val batchOutput = """
            Packages:
              Package [android] (1111):
                appId=1000
                pkg=Package{1111 android}
                User 0: installed=true enabled=0
              Package [${presentA.packageName}] (2222):
                appId=10236
                pkg=Package{2222 ${presentA.packageName}}
                codePath=/product/priv-app/SystemHelper
                versionCode=31 minSdk=31 targetSdk=33
                versionName=3.0.1
                pkgFlags=[ SYSTEM HAS_CODE ]
                User 0: installed=false enabled=0
                User 10: installed=true enabled=${PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER}
              Package [${presentB.packageName}] (3333):
                appId=10229
                pkg=Package{3333 ${presentB.packageName}}
                codePath=/product/priv-app/CTAutoRegist
                versionCode=16000000 minSdk=33 targetSdk=35
                versionName=16.0.0
                pkgFlags=[ SYSTEM HAS_CODE ]
                User 0: installed=true enabled=0
                User 10: installed=false enabled=0

            Hidden system packages:
              Package [${absent.packageName}] (4444):
                appId=10999
                pkg=Package{4444 ${absent.packageName}}
                pkgFlags=[ SYSTEM HAS_CODE ]
                User 0: installed=true enabled=0
        """.trimIndent()

        val executor = FakePackageExecutor(
            onExecute = { args ->
                if (args == listOf("list", "users")) PackageShellResult(0, usersOutput())
                else error("Unexpected shell command: $args")
            },
            onDump = { args ->
                assertEquals(listOf("p"), args)
                PackageShellResult(0, batchOutput)
            },
        )

        val snapshot = PackageCommands(executor).scan(
            callerUser = 0,
            entries = listOf(presentA, presentB, absent),
            permissionManager = null,
        )

        // Single batch PMS dump, zero per-package dumps and zero pm dump shell calls.
        assertEquals(listOf(listOf("p")), executor.dumpArgs)
        assertEquals(listOf(listOf("list", "users")), executor.executedCommands)

        val detectedA = snapshot.packages[0]
        assertEquals("3.0.1", detectedA.versionName)
        assertEquals(31L, detectedA.versionCode)
        assertEquals(PackageState.RESTORABLE, detectedA.users[0])
        assertEquals(PackageState.DISABLED, detectedA.users[10])
        assertEquals(PackageState.UNKNOWN, detectedA.users[11])
        assertEquals(setOf(10), detectedA.userDisabledIds)

        val detectedB = snapshot.packages[1]
        assertEquals("16.0.0", detectedB.versionName)
        assertEquals(PackageState.INSTALLED, detectedB.users[0])
        assertEquals(PackageState.RESTORABLE, detectedB.users[10])

        val detectedAbsent = snapshot.packages[2]
        assertEquals(
            mapOf(0 to PackageState.NOT_FOUND, 10 to PackageState.NOT_FOUND, 11 to PackageState.NOT_FOUND),
            detectedAbsent.users,
        )
    }

    @Test
    fun scan_batchDumpWithZeroMatchingTargetsMarksAllEntriesMissing() = runTest {
        val absentA = PackageEntry("com.xiaomi.registration", "AutoRegistration", emptySet())
        val absentB = PackageEntry("com.vivo.devicereg", "Device Management", emptySet())

        val executor = FakePackageExecutor(
            onExecute = { args ->
                if (args == listOf("list", "users")) PackageShellResult(0, usersOutput())
                else error("Unexpected shell command: $args")
            },
            onDump = { args ->
                assertEquals(listOf("p"), args)
                PackageShellResult(
                    0,
                    packageDump("android", userLines = listOf("User 0: installed=true enabled=0")),
                )
            },
        )

        val snapshot = PackageCommands(executor).scan(0, listOf(absentA, absentB), permissionManager = null)

        assertEquals(listOf(listOf("p")), executor.dumpArgs)
        assertTrue(snapshot.packages.all { pkg -> pkg.users.values.all { it == PackageState.NOT_FOUND } })
    }

    @Test
    fun scan_fallsBackToPerPackageDumpWhenBatchDumpIsIncompleteOrThrows() = runTest {
        val present = PackageEntry("com.oppo.ctautoregist", "CTAutoRegist", emptySet())
        val absent = PackageEntry("com.example.missing", "Missing", emptySet())

        val brokenBatchResponses: List<() -> PackageShellResult> = listOf(
            { PackageShellResult(0, "Packages:\n") },
            { PackageShellResult(0, "Unrecognized header") },
            { PackageShellResult(-1, "*** SERVICE 'package' DUMP TIMEOUT") },
            { throw IOException("pipe broken") },
        )

        for (brokenBatch in brokenBatchResponses) {
            val executor = FakePackageExecutor(
                onExecute = { args ->
                    if (args == listOf("list", "users")) PackageShellResult(0, usersOutput())
                    else PackageShellResult(1, "")
                },
                onDump = { args ->
                    when (args.singleOrNull()) {
                        "p" -> brokenBatch()
                        present.packageName -> PackageShellResult(
                            0,
                            packageDump(
                                present.packageName,
                                userLines = listOf("User 0: installed=true enabled=0", "User 10: installed=false enabled=0", "User 11: installed=false enabled=0"),
                            ),
                        )
                        absent.packageName -> PackageShellResult(0, "Unable to find package: ${absent.packageName}")
                        else -> PackageShellResult(1, "")
                    }
                },
            )

            val snapshot = PackageCommands(executor).scan(0, listOf(present, absent), permissionManager = null)
            assertEquals(listOf(listOf("p"), listOf(present.packageName), listOf(absent.packageName)), executor.dumpArgs)
            assertEquals(PackageState.INSTALLED, snapshot.packages[0].users[0])
            assertEquals(PackageState.NOT_FOUND, snapshot.packages[1].users[0])
        }
    }

    @Test
    fun scan_batchDumpContinuesPastUnindentedSharedLibraryOverlayPathsOnAndroid12To15() = runTest {
        val presentA = PackageEntry("com.xiaomi.registration", "AutoRegistration", emptySet())
        val presentB = PackageEntry("com.miui.analytics", "Analytics", emptySet())
        val hiddenOnly = PackageEntry("com.miui.dmregservice", "RegService", emptySet())

        // Exact AOSP Android 12-15 Settings.dumpPackageLPr() formatting:
        // pw.print(prefix); pw.println("    "); pw.print(key); pw.println(" overlay paths:");
        val batchOutput = listOf(
            "Packages:",
            "  Package [android] (1111):",
            "    appId=1000",
            "    pkg=Package{1111 android}",
            "    User 0: installed=true enabled=0",
            "      ",
            "com.miui.rom overlay paths:",
            "          /product/overlay/MiuiFrameworkResOverlay.apk",
            "        ",
            "com.miui.system legacy overlay paths:",
            "        /product/overlay/MiuiSystemOverlay.apk",
            "    User 10: installed=true enabled=0",
            "  Package [com.example.harmful] (1234):",
            "    appId=10100",
            "    pkg=Package{1234 com.example.harmful}",
            "    User 0: installed=true enabled=0",
            "      harmfulAppWarning: First line",
            "Second unindented line from multiline warning",
            "  Package [${presentA.packageName}] (2222):",
            "    appId=10201",
            "    pkg=Package{2222 ${presentA.packageName}}",
            "    codePath=/system_ext/priv-app/AutoRegistration",
            "    versionCode=14 minSdk=31 targetSdk=34",
            "    versionName=1.4.0",
            "    pkgFlags=[ SYSTEM HAS_CODE ]",
            "    User 0: installed=true enabled=0",
            "      ",
            "com.miui.rom overlay paths:",
            "          /product/overlay/AutoRegOverlay.apk",
            "    User 10: installed=false enabled=0",
            "    User 11: installed=true enabled=${PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER}",
            "  Package [${presentB.packageName}] (3333):",
            "    appId=10202",
            "    pkg=Package{3333 ${presentB.packageName}}",
            "    codePath=/product/priv-app/AnalyticsCore",
            "    versionCode=20260101 minSdk=29 targetSdk=34",
            "    versionName=3.1.0",
            "    pkgFlags=[ SYSTEM HAS_CODE ]",
            "    User 0: installed=true enabled=0",
            "    User 10: installed=true enabled=0",
            "    User 11: installed=false enabled=0",
            "",
            "Renamed packages:",
            "  com.old.pkg,com.new.pkg",
            "",
            "Hidden system packages:",
            "  Package [${hiddenOnly.packageName}] (4444):",
            "    appId=10999",
            "    pkg=Package{4444 ${hiddenOnly.packageName}}",
            "    pkgFlags=[ SYSTEM HAS_CODE ]",
            "    User 0: installed=true enabled=0",
        ).joinToString("\n")

        val executor = FakePackageExecutor(
            onExecute = { args ->
                if (args == listOf("list", "users")) PackageShellResult(0, usersOutput())
                else error("Unexpected shell command: $args")
            },
            onDump = { args ->
                assertEquals(listOf("p"), args)
                PackageShellResult(0, batchOutput)
            },
        )

        val snapshot = PackageCommands(executor).scan(
            callerUser = 0,
            entries = listOf(presentA, presentB, hiddenOnly),
            permissionManager = null,
        )

        assertEquals(listOf(listOf("p")), executor.dumpArgs)

        val detectedA = snapshot.packages[0]
        assertEquals("1.4.0", detectedA.versionName)
        assertEquals(14L, detectedA.versionCode)
        assertEquals(PackageState.INSTALLED, detectedA.users[0])
        assertEquals(PackageState.RESTORABLE, detectedA.users[10])
        assertEquals(PackageState.DISABLED, detectedA.users[11])
        assertEquals(setOf(11), detectedA.userDisabledIds)

        val detectedB = snapshot.packages[1]
        assertEquals(PackageState.INSTALLED, detectedB.users[0])
        assertEquals(PackageState.INSTALLED, detectedB.users[10])
        assertEquals(PackageState.RESTORABLE, detectedB.users[11])

        val detectedHiddenOnly = snapshot.packages[2]
        assertEquals(
            mapOf(0 to PackageState.NOT_FOUND, 10 to PackageState.NOT_FOUND, 11 to PackageState.NOT_FOUND),
            detectedHiddenOnly.users,
        )
    }

    @Test
    fun scan_fallsBackWhenBatchDumpHasZeroMatchesAndNoFrameworkPackage() = runTest {
        val present = PackageEntry("com.xiaomi.registration", "AutoRegistration", emptySet())
        val absent = PackageEntry("com.example.missing", "Missing", emptySet())

        val executor = FakePackageExecutor(
            onExecute = { args ->
                if (args == listOf("list", "users")) PackageShellResult(0, usersOutput())
                else PackageShellResult(1, "")
            },
            onDump = { args ->
                when (args.singleOrNull()) {
                    "p" -> PackageShellResult(
                        0,
                        packageDump("com.example.unrelated", userLines = listOf("User 0: installed=true enabled=0")),
                    )
                    present.packageName -> PackageShellResult(
                        0,
                        packageDump(
                            present.packageName,
                            userLines = listOf("User 0: installed=true enabled=0", "User 10: installed=false enabled=0", "User 11: installed=false enabled=0"),
                        ),
                    )
                    absent.packageName -> PackageShellResult(0, "Unable to find package: ${absent.packageName}")
                    else -> PackageShellResult(1, "")
                }
            },
        )

        val snapshot = PackageCommands(executor).scan(0, listOf(present, absent), permissionManager = null)
        assertEquals(listOf(listOf("p"), listOf(present.packageName), listOf(absent.packageName)), executor.dumpArgs)
        assertEquals(PackageState.INSTALLED, snapshot.packages[0].users[0])
        assertEquals(PackageState.NOT_FOUND, snapshot.packages[1].users[0])
    }

    @Test
    fun read_singlePackageDumpPreservesUsersPastUnindentedOverlayPathsAndStopsAtSharedUsers() {
        val pkg = "com.miui.analytics"
        val singleDump = listOf(
            "Packages:",
            "  Package [$pkg] (deadbeef):",
            "    appId=10234",
            "    sharedUser=SharedUserSetting{aaaa com.miui.analytics.uid/10234}",
            "    pkg=Package{deadbeef $pkg}",
            "    codePath=/product/priv-app/AnalyticsCore",
            "    versionCode=100 minSdk=29 targetSdk=34",
            "    versionName=3.1.0",
            "    pkgFlags=[ SYSTEM HAS_CODE ]",
            "    User 0: ceDataInode=1 installed=true hidden=false enabled=0",
            "      ",
            "com.miui.rom overlay paths:",
            "          /product/overlay/MiuiFrameworkResOverlay.apk",
            "        ",
            "com.miui.system legacy overlay paths:",
            "        /product/overlay/MiuiSystemOverlay.apk",
            "      runtime permissions:",
            "        android.permission.READ_PHONE_STATE: granted=true, flags=[ USER_SET ]",
            "    User 999: ceDataInode=2 installed=true hidden=false enabled=0",
            "",
            "Hidden system packages:",
            "  Package [$pkg] (cafebabe):",
            "    appId=10234",
            "    pkg=Package{cafebabe $pkg}",
            "    pkgFlags=[ SYSTEM HAS_CODE ]",
            "    User 0: installed=false enabled=0",
            "",
            "Shared users:",
            "  SharedUser [com.miui.analytics.uid] (aaaa):",
            "    appId=10234",
            "    User 0: ",
            "      runtime permissions:",
            "        android.permission.READ_PHONE_STATE: granted=true",
        ).joinToString("\n")

        val executor = FakePackageExecutor(onDump = { PackageShellResult(0, singleDump) })
        val reading = PackageCommands(executor).read(pkg)
        assertTrue(reading is PackageReading.Found)
        val record = (reading as PackageReading.Found).record
        // User 0 must remain INSTALLED (not overwritten by Shared users' "User 0: "),
        // and User 999 must not be truncated by the column-0 overlay paths lines.
        assertEquals(PackageState.INSTALLED, record.users[0])
        assertEquals(PackageState.INSTALLED, record.users[999])
    }

    @Test
    fun userGrants_preservesRuntimePermissionsPastUnindentedOverlayPathsAndSuspendParams() {
        val packageBlock = listOf(
            "    appId=10234",
            "    User 0: ceDataInode=1 installed=true hidden=false suspended=true enabled=0",
            "      installReason=0",
            "    Suspend params:",
            "      suspendingPackage=com.android.shell",
            "      ",
            "com.miui.rom overlay paths:",
            "          /product/overlay/MiuiFrameworkResOverlay.apk",
            "        ",
            "com.miui.system legacy overlay paths:",
            "        /product/overlay/MiuiSystemOverlay.apk",
            "      runtime permissions:",
            "        android.permission.READ_PHONE_STATE: granted=true, flags=[ USER_SET ]",
            "        android.permission.ACCESS_FINE_LOCATION: granted=false",
            "    User 999: ceDataInode=2 installed=true hidden=false enabled=0",
            "      runtime permissions:",
            "        android.permission.READ_PHONE_STATE: granted=false",
        )

        assertEquals(
            mapOf(
                "android.permission.READ_PHONE_STATE" to true,
                "android.permission.ACCESS_FINE_LOCATION" to false,
            ),
            packageBlock.userGrants(0),
        )
        assertEquals(
            mapOf("android.permission.READ_PHONE_STATE" to false),
            packageBlock.userGrants(999),
        )
    }

    @Test
    fun readBounded_readsWithinLimitAndRejectsOversizedOutput() {
        assertEquals("hello world", PackageBinder.readBounded(StringReader("hello world"), maxChars = 11))
        try {
            PackageBinder.readBounded(StringReader("0123456789!"), maxChars = 10)
            fail("Expected IOException when output exceeds maxChars")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("10"))
        }
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

        var capturedVersionCode: Int? = null
        val commands = PackageCommands(executor, clock = { now.also { now += 100L } })
        val outcome = commands.change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = false,
            deleteThroughAppProcess = { _, _, versionCode ->
                capturedVersionCode = versionCode
                removed = true
                PackageFallbackResult(submitted = true, detail = "Success")
            },
        )

        assertTrue(outcome.success)
        assertNull(outcome.failure)
        assertEquals(123, capturedVersionCode)
    }

    @Test
    fun change_autoCleanupSkipsDisabledPackageWithoutSubmittingOrDeleting() = runTest {
        val pkg = "com.oppo.ctautoregist"
        val target = ChangeTarget(pkg, 0)
        var canSubmitCalled = false

        val executor = FakePackageExecutor(
            onDump = {
                PackageShellResult(
                    0,
                    packageDump(
                        pkg,
                        userLines = listOf(
                            "User 0: installed=true enabled=${PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER}",
                        ),
                    ),
                )
            },
        )

        val outcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = true,
            captureDenialLog = false,
            disableOnUninstallFailure = false,
            canSubmit = {
                canSubmitCalled = true
                true
            },
        )

        assertTrue(outcome.success)
        assertNull(outcome.failure)
        assertFalse(canSubmitCalled)
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun change_skipsFallbackDeleteWhenPackageBecomesDisabledAfterFirstFailure() = runTest {
        val pkg = "com.oppo.ctautoregist"
        val target = ChangeTarget(pkg, 0)
        var dumpCount = 0
        var fallbackCalled = false

        val executor = FakePackageExecutor(
            onExecute = { PackageShellResult(1, "Failure [DELETE_FAILED_USER_RESTRICTED]") },
            onDump = {
                dumpCount += 1
                val enabled = if (dumpCount == 1) {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }
                PackageShellResult(0, packageDump(pkg, userLines = listOf("User 0: installed=true enabled=$enabled")))
            },
        )

        val outcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = false,
            deleteThroughAppProcess = { _, _, _ ->
                fallbackCalled = true
                PackageFallbackResult(true, "Should not run")
            },
        )

        assertFalse(outcome.success)
        assertFalse(fallbackCalled)
    }

    @Test
    fun change_abortsFallbackDeleteAndDisableWhenVersionChangesMidOperation() = runTest {
        val pkg = "com.example.custom"
        val target = ChangeTarget(pkg, 0)
        var dumpCount = 0
        var fallbackCalled = false

        val executor = FakePackageExecutor(
            onExecute = { PackageShellResult(1, "Failure [DELETE_FAILED_INTERNAL_ERROR]") },
            onDump = {
                dumpCount += 1
                val ver = if (dumpCount == 1) 100L else 101L
                PackageShellResult(
                    0,
                    packageDump(
                        pkg,
                        versionCode = ver,
                        flags = "HAS_CODE",
                        userLines = listOf("User 0: installed=true enabled=0"),
                    ),
                )
            },
        )

        val outcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = true,
            deleteThroughAppProcess = { _, _, _ ->
                fallbackCalled = true
                PackageFallbackResult(true, "Should not run")
            },
        )

        assertFalse(outcome.success)
        assertFalse(outcome.disabledInstead)
        assertFalse(fallbackCalled)
        // Only the initial uninstall command should have run; no disable-user fallback on the updated version.
        assertEquals(1, executor.executedCommands.size)
        assertEquals("uninstall", executor.executedCommands.single().first())
    }

    @Test
    fun change_abortsWithZeroWritesWhenCanSubmitRejectsStaleSpaceIdentity() = runTest {
        val pkg = "com.oppo.ctautoregist"
        val target = ChangeTarget(pkg, userId = 10, userSerialNumber = 42L)

        val executor = FakePackageExecutor(
            onDump = {
                PackageShellResult(0, packageDump(pkg, userLines = listOf("User 10: installed=true enabled=0")))
            },
        )

        val outcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = true,
            canSubmit = { false },
            submitDeniedFailure = ChangeFailure.SpaceStateUnverified,
        )

        assertFalse(outcome.success)
        assertEquals(ChangeFailure.SpaceStateUnverified, outcome.failure)
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun change_reportsSpaceStateUnverifiedAndPreservesFirstSystemErrorWhenSubmitDeniedOnRetry() = runTest {
        val pkg = "com.oppo.ctautoregist"
        val target = ChangeTarget(pkg, userId = 10, userSerialNumber = 42L)
        var submitChecks = 0
        var fallbackCalled = false

        val executor = FakePackageExecutor(
            onExecute = { PackageShellResult(1, "Failure [DELETE_FAILED_INTERNAL_ERROR]") },
            onDump = {
                PackageShellResult(0, packageDump(pkg, userLines = listOf("User 10: installed=true enabled=0")))
            },
        )

        val outcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = true,
            disableOnUninstallFailure = true,
            canSubmit = {
                submitChecks += 1
                submitChecks == 1
            },
            submitDeniedFailure = ChangeFailure.SpaceStateUnverified,
            deleteThroughAppProcess = { _, _, _ ->
                fallbackCalled = true
                PackageFallbackResult(true, "Should not run")
            },
        )

        assertFalse(outcome.success)
        assertFalse(outcome.disabledInstead)
        assertFalse(fallbackCalled)
        assertEquals(ChangeFailure.SpaceStateUnverified, outcome.failure)
        assertEquals(1, executor.executedCommands.size)
        assertEquals("uninstall", executor.executedCommands.single().first())
        val diagnostics = outcome.denialLog.orEmpty()
        assertTrue(diagnostics.contains("DELETE_FAILED_INTERNAL_ERROR"))
        assertTrue(diagnostics.contains("Retry aborted"))
    }

    @Test
    fun change_skipsFallbackDeleteWhenVersionCodeExceedsIntMaxValue() = runTest {
        val pkg = "com.example.longversion"
        val target = ChangeTarget(pkg, 0)
        var fallbackCalled = false

        val executor = FakePackageExecutor(
            onExecute = { PackageShellResult(1, "Failure [DELETE_FAILED_INTERNAL_ERROR]") },
            onDump = {
                PackageShellResult(
                    0,
                    packageDump(
                        pkg,
                        versionCode = Int.MAX_VALUE.toLong() + 42L,
                        flags = "HAS_CODE",
                        userLines = listOf("User 0: installed=true enabled=0"),
                    ),
                )
            },
        )

        val outcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = false,
            deleteThroughAppProcess = { _, _, _ ->
                fallbackCalled = true
                PackageFallbackResult(true, "Should not run")
            },
        )

        assertFalse(outcome.success)
        assertFalse(fallbackCalled)
        assertEquals(
            ChangeFailure.SystemError("Failure [DELETE_FAILED_INTERNAL_ERROR]"),
            outcome.failure,
        )
    }

    @Test
    fun change_skipsFallbackDeleteAndDisableWhenInitialVersionCodeIsMissing() = runTest {
        val pkg = "com.example.noversion"
        val target = ChangeTarget(pkg, 0)
        var fallbackCalled = false

        val dumpWithoutVersion = """
            Packages:
              Package [$pkg] (deadbeef):
                appId=10123
                pkg=Package{abc $pkg}
                codePath=/data/app/$pkg
                pkgFlags=[ HAS_CODE ]
                User 0: installed=true enabled=0
        """.trimIndent()

        val executor = FakePackageExecutor(
            onExecute = { PackageShellResult(1, "Failure [DELETE_FAILED_INTERNAL_ERROR]") },
            onDump = { PackageShellResult(0, dumpWithoutVersion) },
        )

        val outcome = PackageCommands(executor).change(
            target = target,
            action = PackageAction.UNINSTALL,
            requireVerifiedSystemIdentity = false,
            captureDenialLog = false,
            disableOnUninstallFailure = true,
            deleteThroughAppProcess = { _, _, _ ->
                fallbackCalled = true
                PackageFallbackResult(true, "Should not run")
            },
        )

        assertFalse(outcome.success)
        assertFalse(outcome.disabledInstead)
        assertFalse(fallbackCalled)
        assertEquals(1, executor.executedCommands.size)
        assertEquals("uninstall", executor.executedCommands.single().first())
        assertEquals(
            ChangeFailure.SystemError("Failure [DELETE_FAILED_INTERNAL_ERROR]"),
            outcome.failure,
        )
    }

    @Test
    fun change_autoCleanupRejectsMissingOrUnrecognizedEnabledStateWithZeroWrites() = runTest {
        val pkg = "com.oppo.ctautoregist"
        val target = ChangeTarget(pkg, 0)

        for (userLine in listOf("User 0: installed=true hidden=false", "User 0: installed=true enabled=9")) {
            var canSubmitCalled = false
            val executor = FakePackageExecutor(
                onDump = { PackageShellResult(0, packageDump(pkg, userLines = listOf(userLine))) },
            )

            val outcome = PackageCommands(executor).change(
                target = target,
                action = PackageAction.UNINSTALL,
                requireVerifiedSystemIdentity = true,
                captureDenialLog = false,
                disableOnUninstallFailure = false,
                canSubmit = {
                    canSubmitCalled = true
                    true
                },
            )

            assertFalse(outcome.success)
            assertEquals(ChangeFailure.SpaceStateUnverified, outcome.failure)
            assertFalse(canSubmitCalled)
            assertTrue(executor.executedCommands.isEmpty())
        }
    }

    @Test
    fun change_cancellingDuringPreReadPreventsFirstMutationCommand() = runTest {
        val pkg = "com.oppo.ctautoregist"
        val target = ChangeTarget(pkg, 0)
        var jobToCancel: kotlinx.coroutines.Job? = null

        val executor = FakePackageExecutor(
            onDump = {
                jobToCancel?.cancel(CancellationException("Activity left"))
                PackageShellResult(0, packageDump(pkg, userLines = listOf("User 0: installed=true enabled=0")))
            },
        )

        var caughtCancellation = false
        jobToCancel = launch {
            try {
                PackageCommands(executor).change(
                    target = target,
                    action = PackageAction.UNINSTALL,
                    requireVerifiedSystemIdentity = false,
                    captureDenialLog = false,
                    disableOnUninstallFailure = false,
                )
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        jobToCancel.join()

        assertTrue(caughtCancellation)
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun toLookupResult_rejectsStaleNotFoundRemnantsAndUnknownRecords() {
        val pkg = "com.example.remnant"
        val remnantExecutor = FakePackageExecutor(
            onDump = {
                PackageShellResult(
                    0,
                    """
                    Packages:
                      Package [$pkg] (abc):
                        pkg=null
                        User 0: installed=false enabled=0
                    """.trimIndent(),
                )
            },
        )
        assertEquals(PackageLookupResult.Missing, PackageCommands(remnantExecutor).read(pkg).toLookupResult())

        val unknownExecutor = FakePackageExecutor(
            onDump = {
                PackageShellResult(
                    0,
                    """
                    Packages:
                      Package [$pkg] (abc):
                        pkg=null
                        User 0: enabled=0
                    """.trimIndent(),
                )
            },
        )
        assertEquals(PackageLookupResult.Unavailable, PackageCommands(unknownExecutor).read(pkg).toLookupResult())

        val restorableExecutor = FakePackageExecutor(
            onDump = {
                PackageShellResult(
                    0,
                    packageDump(pkg, userLines = listOf("User 0: installed=false enabled=0")),
                )
            },
        )
        assertTrue(PackageCommands(restorableExecutor).read(pkg).toLookupResult() is PackageLookupResult.Found)

        // Bare codePath without pkg=, non-system, and all users installed=false cannot prove APK existence.
        val bareCodePathExecutor = FakePackageExecutor(
            onDump = {
                PackageShellResult(
                    0,
                    """
                    Packages:
                      Package [$pkg] (abc):
                        codePath=/data/app/~~stale/$pkg
                        pkgFlags=[ HAS_CODE ]
                        User 0: installed=false enabled=0
                    """.trimIndent(),
                )
            },
        )
        val bareReading = PackageCommands(bareCodePathExecutor).read(pkg)
        assertTrue(bareReading is PackageReading.Found)
        assertEquals(PackageState.NOT_FOUND, (bareReading as PackageReading.Found).record.users[0])
        assertEquals(PackageLookupResult.Unavailable, bareReading.toLookupResult())

        // Verified system package without pkg= remains Found/RESTORABLE.
        val systemWithoutPkgLine = FakePackageExecutor(
            onDump = {
                PackageShellResult(
                    0,
                    """
                    Packages:
                      Package [$pkg] (abc):
                        codePath=/system_ext/app/Sample
                        pkgFlags=[ SYSTEM HAS_CODE ]
                        User 0: installed=false enabled=0
                    """.trimIndent(),
                )
            },
        )
        assertTrue(PackageCommands(systemWithoutPkgLine).read(pkg).toLookupResult() is PackageLookupResult.Found)

        // Non-system package without pkg= but installed in another user remains Found (User 10 RESTORABLE).
        val installedOtherUser = FakePackageExecutor(
            onDump = {
                PackageShellResult(
                    0,
                    """
                    Packages:
                      Package [$pkg] (abc):
                        codePath=/data/app/~~live/$pkg
                        pkgFlags=[ HAS_CODE ]
                        User 0: installed=true enabled=0
                        User 10: installed=false enabled=0
                    """.trimIndent(),
                )
            },
        )
        val multiUserReading = PackageCommands(installedOtherUser).read(pkg) as PackageReading.Found
        assertEquals(PackageState.RESTORABLE, multiUserReading.record.users[10])
        assertTrue(multiUserReading.toLookupResult() is PackageLookupResult.Found)
    }
}
