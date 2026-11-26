package com.eynnzerr.telemetrycleaner.data.packagemanager

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.DhizukuStatus
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on-device tests for [DhizukuBridge] against real Android PMS, PackageInstallerService,
 * and DevicePolicyManagerService.
 *
 * Skips cleanly when Dhizuku is not activated and authorized on the connected test device.
 */
@RunWith(AndroidJUnit4::class)
class DhizukuBridgeInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** Uses real `dumpsys package` via UiAutomation while simulating OEM shell-UID blocks on mutations. */
    private fun shellBlockedExecutor(): PackageExecutor = object : PackageExecutor {
        override fun execute(args: List<String>): PackageShellResult = when (args.firstOrNull()) {
            "uninstall" -> PackageShellResult(1, "Failure [DELETE_FAILED_USER_RESTRICTED]")
            "disable-user" -> PackageShellResult(1, "SecurityException: Cannot disable no root permission")
            "install-existing" -> PackageShellResult(1, "Failure [INSTALL_FAILED_USER_RESTRICTED]")
            else -> runUiAutomationCommand(listOf("cmd", "package") + args)
        }

        override fun dump(vararg args: String): PackageShellResult =
            runUiAutomationCommand(listOf("dumpsys", "package") + args.toList())
    }

    private fun runUiAutomationCommand(args: List<String>): PackageShellResult {
        val cmd = args.joinToString(" ")
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        val output = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        return PackageShellResult(0, output)
    }

    private fun pickHarmlessSystemPackage(): String {
        val candidates = listOf(
            "com.android.dreams.basic",
            "com.android.egg",
            "com.android.bookmarkprovider",
            "com.android.wallpaperbackup",
        )
        val pm = context.packageManager
        return candidates.firstOrNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        } ?: error("No harmless test system package found on emulator")
    }

    @Test
    fun dhizukuUninstallAndRestore_realSystemPackageWhenShellBlocked() = runBlocking {
        val bridge = DhizukuBridge(context)
        val status = bridge.refreshStatus()
        assumeTrue("Requires active and authorized Dhizuku (actual=$status)", status == DhizukuStatus.READY)

        val pkg = pickHarmlessSystemPackage()
        val target = ChangeTarget(pkg, userId = 0)
        val commands = PackageCommands(shellBlockedExecutor())

        // Ensure starting state is INSTALLED.
        bridge.setApplicationHidden(pkg, 0, hidden = false)
        bridge.installExistingPackage(pkg, 0)
        val initial = commands.read(pkg) as PackageReading.Found
        assertEquals(PackageState.INSTALLED, initial.record.users[0])

        try {
            // 1. Uninstall falls back to Dhizuku (IPackageInstaller + DELETE_SYSTEM_APP) and truly uninstalls for user 0.
            val uninstallOutcome = commands.change(
                target = target,
                action = PackageAction.UNINSTALL,
                requireVerifiedSystemIdentity = false,
                captureDenialLog = true,
                deleteThroughDhizuku = bridge::uninstall,
            )
            assertTrue("Expected Dhizuku uninstall success, got: $uninstallOutcome", uninstallOutcome.success)
            assertFalse(uninstallOutcome.hiddenInstead)
            assertNull(uninstallOutcome.failure)

            val afterUninstall = commands.read(pkg) as PackageReading.Found
            assertEquals(PackageState.RESTORABLE, afterUninstall.record.users[0])

            // 2. Restore falls back to Dhizuku (dpm.installExistingPackage) when shell install-existing fails.
            val restoreOutcome = commands.change(
                target = target,
                action = PackageAction.RESTORE,
                requireVerifiedSystemIdentity = false,
                captureDenialLog = false,
                installExistingThroughDhizuku = bridge::installExistingPackage,
            )
            assertTrue("Expected Dhizuku restore success, got: $restoreOutcome", restoreOutcome.success)
            assertNull(restoreOutcome.failure)

            val afterRestore = commands.read(pkg) as PackageReading.Found
            assertEquals(PackageState.INSTALLED, afterRestore.record.users[0])
        } finally {
            bridge.setApplicationHidden(pkg, 0, hidden = false)
            bridge.installExistingPackage(pkg, 0)
        }
    }

    @Test
    fun dhizukuHideAndUnhide_realSystemPackageWhenDisableBlocked() = runBlocking {
        val bridge = DhizukuBridge(context)
        val status = bridge.refreshStatus()
        assumeTrue("Requires active and authorized Dhizuku (actual=$status)", status == DhizukuStatus.READY)

        val pkg = pickHarmlessSystemPackage()
        val target = ChangeTarget(pkg, userId = 0)
        val commands = PackageCommands(shellBlockedExecutor())

        bridge.setApplicationHidden(pkg, 0, hidden = false)
        bridge.installExistingPackage(pkg, 0)
        val initial = commands.read(pkg) as PackageReading.Found
        assertEquals(PackageState.INSTALLED, initial.record.users[0])

        try {
            // 1. Manual disable falls back to Dhizuku hide when shell disable-user is blocked.
            val hideOutcome = commands.change(
                target = target,
                action = PackageAction.DISABLE,
                requireVerifiedSystemIdentity = false,
                captureDenialLog = false,
                hideThroughDhizuku = { p, u -> bridge.setApplicationHidden(p, u, hidden = true) },
            )
            assertTrue("Expected Dhizuku hide success, got: $hideOutcome", hideOutcome.success)
            assertTrue("Expected hiddenInstead=true, got: $hideOutcome", hideOutcome.hiddenInstead)
            assertNull(hideOutcome.failure)

            val afterHide = commands.read(pkg) as PackageReading.Found
            assertEquals(PackageState.HIDDEN, afterHide.record.users[0])

            // 2. Restore unhides the package back to INSTALLED.
            val unhideOutcome = commands.change(
                target = target,
                action = PackageAction.RESTORE,
                requireVerifiedSystemIdentity = false,
                captureDenialLog = false,
                unhideThroughDhizuku = { p, u -> bridge.setApplicationHidden(p, u, hidden = false) },
            )
            assertTrue("Expected Dhizuku unhide success, got: $unhideOutcome", unhideOutcome.success)
            assertNull(unhideOutcome.failure)

            val afterUnhide = commands.read(pkg) as PackageReading.Found
            assertEquals(PackageState.INSTALLED, afterUnhide.record.users[0])
        } finally {
            bridge.setApplicationHidden(pkg, 0, hidden = false)
            bridge.installExistingPackage(pkg, 0)
        }
    }
}
