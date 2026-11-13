package com.eynnzerr.telemetrycleaner.data

import java.time.Instant
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Collects the package service response and nearby system messages for an uninstall denial. */
internal object PackageDiagnostics {
    /** Bounds the log query so a diagnostic cannot hold up the batch indefinitely. */
    private const val LOG_TIMEOUT_SECONDS = 2L

    /** Formats diagnostics for one target whose uninstall did not succeed. */
    fun uninstallDenial(
        target: ChangeTarget,
        record: PackageRecord,
        before: PackageState,
        after: PackageState?,
        startedAt: Long,
        attempt: Result<PackageShellResult>,
        fallback: PackageFallbackResult?,
        disableAttempt: Result<PackageShellResult>?,
        afterDisable: PackageState?,
    ): String = buildString {
        appendLine("Time: ${Instant.ofEpochMilli(startedAt)}")
        appendLine("Package: ${target.packageName}, user: ${target.userId}")
        appendLine("Package version: ${record.versionName ?: "unknown"} (${record.versionCode ?: "unknown"})")
        appendLine("System package: ${record.hasSystemCopy ?: "unknown"}")
        appendLine("State before request: $before; after primary request: ${after ?: "unknown"}")
        appendAttempt("Package service", attempt)
        if (fallback == null) {
            appendLine("Helper: not run")
        } else {
            val status = if (fallback.submitted) "submitted, removal not confirmed" else "submission not confirmed"
            appendLine("Helper: $status")
            appendLine(fallback.detail)
        }
        if (disableAttempt != null) {
            appendAttempt("Disable fallback", disableAttempt)
            appendLine("State after disable request: ${afterDisable ?: "unknown"}")
        }
        appendLine("Related system log:")
        append(readSystemLog(target.packageName, startedAt))
    }

    /** Writes the service output or exception from a single package command. */
    private fun StringBuilder.appendAttempt(label: String, attempt: Result<PackageShellResult>) {
        val result = attempt.getOrNull()
        if (result != null) {
            appendLine("$label exit code: ${result.exitCode}")
            appendLine(result.output.trim().take(4_000).ifBlank { "No package service output" })
        } else {
            appendLine("$label exception: ${attempt.exceptionOrNull()}")
        }
    }

    /** Reads bounded system-UID messages matching this package or the uninstall restriction. */
    private fun readSystemLog(packageName: String, startedAt: Long): String {
        val since = String.format(Locale.ROOT, "%d.%03d", startedAt / 1_000, startedAt % 1_000)
        val exactPackage = packageName.replace(".", "\\.")
        val filter = "(^|[^a-zA-Z0-9_.])$exactPackage($|[^a-zA-Z0-9_.])|User is restricted: no_uninstall_apps"
        val process = try {
            startShizukuProcess(arrayOf(
                "/system/bin/logcat", "-b", "system", "-b", "main", "--uid=1000",
                "-d", "-T", since, "-v", "threadtime", "-m", "24", "-e", filter,
            ))
        } catch (_: Exception) {
            return "System log unavailable"
        }
        // Drain stdout while the remote command runs so its pipe cannot fill up.
        val output = FutureTask {
            val logs = process.inputStream.bufferedReader().useLines { lines ->
                lines.filterNot { it.startsWith("---------") }
                    .take(24).joinToString("\n") { it.take(500) }
            }
            if (process.waitFor() == 0) logs.ifBlank { "No matching entries" } else "System log unavailable"
        }
        return try {
            Thread(output, "uninstall-system-log").apply { isDaemon = true }.start()
            output.get(LOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            "System log unavailable"
        } finally {
            output.cancel(true)
            runCatching { process.destroy() }
        }
    }
}
