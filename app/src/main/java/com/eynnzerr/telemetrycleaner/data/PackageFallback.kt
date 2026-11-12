package com.eynnzerr.telemetrycleaner.data

import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/** Runs one deletion through app_process when the package command layer refuses it. */
internal object PackageFallback {
    /** The helper class passed to app_process; release rules keep this name unchanged. */
    private const val ENTRY_CLASS = "com.eynnzerr.telemetrycleaner.shell.PackageUninstaller"

    /** Bounds a helper that never exits, for example while Shizuku is restarting. */
    private const val PROCESS_TIMEOUT_SECONDS = 8L

    /** Printed by [ENTRY_CLASS] after the package manager accepts the deletion request. */
    private const val REQUEST_MARKER = "DELETE_REQUESTED"

    /**
     * Asks the hidden package manager interface to delete [packageName] for [userId].
     *
     * @return Whether the helper submitted the deletion. The caller still verifies the result.
     */
    fun uninstall(apkPath: String, packageName: String, userId: Int): Boolean {
        val process = try {
            if (!Shizuku.pingBinder()) return false
            newProcess(command(apkPath, packageName, userId))
        } catch (_: Throwable) {
            return false
        }
        return try {
            if (!process.waitForTimeout(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return false
            val output = process.inputStream.bufferedReader().use { it.readText() }
            output.lineSequence().any { it.startsWith(REQUEST_MARKER) }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { process.destroy() }
        }
    }

    /** Starts the helper with this APK as its class path and merges its error stream. */
    private fun command(apkPath: String, packageName: String, userId: Int): Array<String> {
        val script = "CLASSPATH=${apkPath.shellQuoted()} /system/bin/app_process /system/bin " +
            "$ENTRY_CLASS ${packageName.shellQuoted()} $userId 2>&1"
        return arrayOf("/system/bin/sh", "-c", script)
    }

    /** Starts a remote shell process through Shizuku's process factory. */
    private fun newProcess(command: Array<String>): ShizukuRemoteProcess {
        // Shizuku 13 made newProcess private; it remains the bridge for launching shell commands.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as ShizukuRemoteProcess
    }

    /** Quotes one fixed argument for the shell that starts app_process. */
    private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"
}
