package com.eynnzerr.telemetrycleaner.data

import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * The observed outcome of invoking the uninstall helper.
 *
 * @property submitted Whether the helper reported submitting the request.
 * @property detail The helper output or the reason it could not run.
 */
internal data class PackageFallbackResult(val submitted: Boolean, val detail: String)

/** Runs one deletion through app_process when the package command layer refuses it. */
internal object PackageFallback {
    /** The helper class passed to app_process; release rules keep this name unchanged. */
    private const val ENTRY_CLASS = "com.eynnzerr.telemetrycleaner.shell.PackageUninstaller"

    /** Bounds a helper that never exits, for example while Shizuku is restarting. */
    private const val PROCESS_TIMEOUT_SECONDS = 8L

    /** Printed by [ENTRY_CLASS] when the request returns without a synchronous exception. */
    private const val REQUEST_MARKER = "DELETE_REQUESTED"

    /**
     * Asks the hidden package manager interface to delete [packageName] for [userId].
     *
     * The caller still verifies whether the requested state change took effect.
     */
    fun uninstall(apkPath: String, packageName: String, userId: Int): PackageFallbackResult {
        val process = try {
            if (!Shizuku.pingBinder()) return PackageFallbackResult(false, "Shizuku disconnected")
            startShizukuProcess(command(apkPath, packageName, userId))
        } catch (failure: Throwable) {
            return PackageFallbackResult(false, "Could not start helper: ${failure.cause ?: failure}")
        }
        return try {
            if (!process.waitForTimeout(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return PackageFallbackResult(false, "Helper timed out")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            PackageFallbackResult(
                submitted = output.lineSequence().any { it.startsWith(REQUEST_MARKER) },
                detail = output.trim().take(4_000).ifBlank { "No helper output" },
            )
        } catch (failure: Throwable) {
            PackageFallbackResult(false, "Could not read helper result: $failure")
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

    /** Quotes one fixed argument for the shell that starts app_process. */
    private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"
}
