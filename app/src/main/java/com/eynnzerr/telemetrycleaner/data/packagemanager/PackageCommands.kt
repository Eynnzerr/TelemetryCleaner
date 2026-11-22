package com.eynnzerr.telemetrycleaner.data.packagemanager

import android.content.pm.PackageManager
import android.os.SystemClock
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.ChangeFailure
import com.eynnzerr.telemetrycleaner.data.ChangeOutcome
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.ComponentPermission
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageScanIssue
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.data.permits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The state of one active package-manager record, excluding old system versions.
 *
 * @property hasVerifiedSystemIdentity Whether the record identifies a system package with an application-range ID.
 * @property hasSystemCopy Whether the package flags indicate a system copy, or null if flags are unavailable.
 * @property enabledSettings The raw Android enabled settings keyed by user ID.
 */
internal data class PackageRecord(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val hasVerifiedSystemIdentity: Boolean,
    val hasSystemCopy: Boolean?,
    val users: Map<Int, PackageState>,
    val enabledSettings: Map<Int, Int>,
    /** The permission details read during an interactive scan. */
    val permissions: List<ComponentPermission>? = null,
)

/** Distinguishes confirmed absence from permission, transport, or format failures. */
internal sealed interface PackageReading {
    data class Found(val record: PackageRecord) : PackageReading
    data object Missing : PackageReading

    /** The reason an exact package lookup did not produce a usable answer. */
    data class Unavailable(val issue: PackageScanIssue) : PackageReading
}

/** Indicates why the user spaces needed to start a scan could not be determined. */
internal class UserListScanException(val issue: PackageScanIssue) : Exception()

/**
 * Queries catalogued packages and applies changes to eligible entries through the selected connection.
 *
 * @property executor The connection used for every read and write within the operation.
 */
internal class PackageCommands(
    private val executor: PackageExecutor,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Resolves the requested directory entries and their states in each visible Android user. */
    suspend fun scan(callerUser: Int, entries: Collection<PackageEntry>, permissionManager: PackageManager?): ScanSnapshot {
        val users = listUsers()
        if (users.none { it.id == callerUser }) throw UserListScanException(PackageScanIssue.USER_STATE_MISSING)
        val packages = entries.map { entry ->
            currentCoroutineContext().ensureActive()
            when (val reading = read(entry.packageName, permissionManager)) {
                is PackageReading.Found -> {
                    val states = reading.record.users + users.associate {
                        it.id to (reading.record.users[it.id] ?: PackageState.UNKNOWN)
                    }
                    DetectedPackage(
                        entry = entry,
                        versionName = reading.record.versionName,
                        versionCode = reading.record.versionCode,
                        systemPath = reading.record.codePath,
                        users = states,
                        hasVerifiedSystemIdentity = reading.record.hasVerifiedSystemIdentity,
                        hasSystemCopy = reading.record.hasSystemCopy,
                        permissions = reading.record.permissions,
                        scanIssues = states.filterValues { it == PackageState.UNKNOWN }
                            .mapValues { PackageScanIssue.USER_STATE_MISSING },
                        userDisabledIds = reading.record.enabledSettings.filter { (id, enabled) ->
                            states[id] == PackageState.DISABLED &&
                                enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        }.keys,
                    )
                }
                PackageReading.Missing -> DetectedPackage(
                    entry = entry,
                    versionName = null,
                    versionCode = null,
                    systemPath = null,
                    users = users.associate { it.id to PackageState.NOT_FOUND },
                    hasVerifiedSystemIdentity = false,
                )
                is PackageReading.Unavailable -> DetectedPackage(
                    entry = entry,
                    versionName = null,
                    versionCode = null,
                    systemPath = null,
                    users = users.associate { it.id to PackageState.UNKNOWN },
                    hasVerifiedSystemIdentity = false,
                    scanIssues = users.associate { it.id to reading.issue },
                )
            }
        }
        return ScanSnapshot(callerUser, users, packages)
    }

    /** Returns the package-manager users visible to the service. */
    private fun listUsers(): List<AndroidUser> {
        val result = query({ throw UserListScanException(it) }) {
            executor.execute(listOf("list", "users"))
        }
        result.queryIssue()?.let { throw UserListScanException(it) }
        val users = result.output.lineSequence().mapNotNull { line ->
            val match = listedUser.matchEntire(line) ?: return@mapNotNull null
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val name = match.groupValues[2].ifBlank { id.toString() }
            AndroidUser(id, name)
        }.distinctBy(AndroidUser::id).toList()
        if (users.isEmpty()) throw UserListScanException(PackageScanIssue.UNRECOGNIZED_RESPONSE)
        return users
    }

    /** Reads the exact package directly because some ROMs omit it from package lists. */
    fun read(packageName: String, permissionManager: PackageManager? = null): PackageReading {
        if (!executor.prefersShellDump) {
            return readDump(packageName, permissionManager) { executor.dump(packageName) }
        }
        val direct = readDump(packageName, permissionManager) { executor.execute(listOf("dump", packageName)) }
        if (direct !is PackageReading.Unavailable) return direct
        val fallback = readDump(packageName, permissionManager) { executor.dump(packageName) }
        if (fallback !is PackageReading.Unavailable) return fallback
        return when {
            direct.issue == PackageScanIssue.PERMISSION_DENIED -> direct
            fallback.issue == PackageScanIssue.UNRECOGNIZED_RESPONSE -> direct
            else -> fallback
        }
    }

    /** Converts transport failures to a reason before parsing the package response. */
    private inline fun readDump(
        packageName: String,
        permissionManager: PackageManager?,
        request: () -> PackageShellResult,
    ): PackageReading {
        val result = query({ return PackageReading.Unavailable(it) }, request)
        return parseDump(packageName, result, permissionManager)
    }

    /** Classifies failures from Android without swallowing coroutine cancellation. */
    private inline fun query(
        fail: (PackageScanIssue) -> Nothing,
        request: () -> PackageShellResult,
    ): PackageShellResult = try {
        request()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SecurityException) {
        fail(PackageScanIssue.PERMISSION_DENIED)
    } catch (_: Exception) {
        fail(PackageScanIssue.QUERY_FAILED)
    }

    /** Detects access denials and failed commands even if Binder itself completed. */
    private fun PackageShellResult.queryIssue(): PackageScanIssue? {
        val denied = output.lineSequence().any { line ->
            val text = line.trimStart()
            val message = if (text.startsWith("Error:", ignoreCase = true)) text.drop(6).trimStart() else text
            message.startsWith("Permission Denial", ignoreCase = true) ||
                message.startsWith("Permission denied", ignoreCase = true) ||
                message.startsWith("Security exception:", ignoreCase = true) ||
                message.startsWith("SecurityException", ignoreCase = true) ||
                message.startsWith("java.lang.SecurityException", ignoreCase = true)
        }
        return when {
            denied -> PackageScanIssue.PERMISSION_DENIED
            exitCode != 0 -> PackageScanIssue.QUERY_FAILED
            else -> null
        }
    }

    /**
     * Checks [canSubmit] before changing one user and verifies the resulting package state.
     *
     * [requireVerifiedSystemIdentity] enforces the package identity check for background cleanup.
     * [captureDenialLog] keeps the system response for rejected interactive uninstalls.
     * [disableOnUninstallFailure] permits one ordinary package disable if an uninstall leaves the package installed.
     * [deleteThroughAppProcess] retries one uninstall directly through app_process when the
     * command layer fails. The original system error still describes a failed retry.
     */
    suspend fun change(
        target: ChangeTarget,
        action: PackageAction,
        requireVerifiedSystemIdentity: Boolean,
        captureDenialLog: Boolean,
        disableOnUninstallFailure: Boolean,
        canSubmit: suspend () -> Boolean = { true },
        deleteThroughAppProcess: ((String, Int) -> PackageFallbackResult)? = null,
    ): ChangeOutcome {
        val packageName = target.packageName
        val reading = read(packageName)
        if (action == PackageAction.UNINSTALL && reading == PackageReading.Missing) {
            return ChangeOutcome(target, true, null)
        }
        val record = (reading as? PackageReading.Found)?.record
            ?: return ChangeOutcome(target, false, ChangeFailure.IdentityUnverified)
        if (requireVerifiedSystemIdentity && !record.hasVerifiedSystemIdentity) {
            return ChangeOutcome(target, false, ChangeFailure.SystemIdentityNotVerified)
        }
        val state = record.users[target.userId]
        if (state == null || state == PackageState.UNKNOWN) {
            return ChangeOutcome(target, false, ChangeFailure.SpaceStateUnverified)
        }
        val reenable = action == PackageAction.RESTORE && state == PackageState.DISABLED
        if (reenable && record.enabledSettings[target.userId] != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            return ChangeOutcome(target, false, ChangeFailure.DisabledBySystem)
        }
        if (!reenable && reading.matches(target, action)) return ChangeOutcome(target, true, null)
        if (!reenable && !state.permits(action)) {
            val failure = if (action == PackageAction.DISABLE) {
                ChangeFailure.NotEnabledInSpace
            } else {
                ChangeFailure.NoRestorableCopy
            }
            return ChangeOutcome(target, false, failure)
        }

        val userId = target.userId.toString()
        val command = when (action) {
            PackageAction.UNINSTALL -> buildList {
                addAll(listOf("uninstall", "--user", userId))
                record.versionCode?.let { addAll(listOf("--versionCode", it.toString())) }
                add(packageName)
            }
            PackageAction.DISABLE -> target.disableCommand()
            PackageAction.RESTORE -> if (reenable) {
                listOf("enable", "--user", userId, packageName)
            } else {
                listOf("install-existing", "--user", userId, "--wait", packageName)
            }
        }
        if (!canSubmit()) return ChangeOutcome(target, false, null)
        val startedAt = System.currentTimeMillis()
        val attempt = executeChange(command)
        // A rejected Binder reply can arrive after the service applied the command.
        val afterRequest = read(packageName)
        val post = readUserState(target, afterRequest)
        val completed = if (reenable) {
            post == PackageState.INSTALLED &&
                afterRequest.enabledFor(target.userId) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            afterRequest.matches(target, action)
        }
        if (completed) {
            return ChangeOutcome(target, true, null)
        }

        val fallback = if (deleteThroughAppProcess != null && action == PackageAction.UNINSTALL &&
            post != null && post != PackageState.UNKNOWN && canSubmit()
        ) {
            currentCoroutineContext().ensureActive()
            deleteThroughAppProcess(packageName, target.userId)
        } else {
            null
        }
        if (fallback?.submitted == true && awaitUninstall(target)) {
            return ChangeOutcome(target, true, null)
        }

        var disableAttempt: Result<PackageShellResult>? = null
        var afterDisable: PackageState? = null
        if (disableOnUninstallFailure && action == PackageAction.UNINSTALL) {
            val current = read(packageName)
            val currentState = readUserState(target, current)
            if (current.matches(target, action)) return ChangeOutcome(target, true, null)
            val enabled = current.enabledFor(target.userId)
            if (currentState == PackageState.INSTALLED &&
                (enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                    enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) &&
                canSubmit()
            ) {
                currentCoroutineContext().ensureActive()
                disableAttempt = executeChange(target.disableCommand())
                val updated = read(packageName)
                afterDisable = readUserState(target, updated)
                if (updated.matches(target, action)) return ChangeOutcome(target, true, null)
                if (updated.matches(target, PackageAction.DISABLE)) {
                    return ChangeOutcome(target, false, null, disabledInstead = true)
                }
            }
        }

        val result = attempt.getOrNull()
        val failure = attempt.exceptionOrNull()
        if (failure != null && failure !is SecurityException && disableAttempt == null) throw failure
        val rejected = failure is SecurityException ||
            (result != null && (result.exitCode > 0 || result.systemErrorLine() != null))
        val denialLog = if (captureDenialLog && (rejected || disableAttempt != null)) {
            PackageDiagnostics.uninstallDenial(
                executor, target, record, state, post, startedAt, attempt, fallback, disableAttempt, afterDisable,
            )
        } else {
            null
        }
        val failureReason = when {
            result != null -> failureReason(result, post)
            failure is SecurityException -> ChangeFailure.SpaceOperationDenied
            else -> ChangeFailure.StateUnverified
        }
        return ChangeOutcome(target, false, failureReason, denialLog)
    }

    /** Keeps a package-service failure available for verification and diagnostics. */
    private fun executeChange(command: List<String>): Result<PackageShellResult> = try {
        Result.success(executor.execute(command))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    /** Waits until a deletion submitted through app_process becomes visible to package dumps. */
    private suspend fun awaitUninstall(target: ChangeTarget): Boolean {
        val deadline = clock() + RETRY_VERIFY_TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            if (read(target.packageName).matches(target, PackageAction.UNINSTALL)) return true
            if (clock() >= deadline) return false
            delay(RETRY_VERIFY_INTERVAL_MS)
        }
    }

    /** Explains a failed change while preserving the system's own error text. */
    private fun failureReason(result: PackageShellResult, state: PackageState?): ChangeFailure {
        if (state == null || state == PackageState.UNKNOWN) {
            return ChangeFailure.PostChangeStateUnverified
        }
        val systemError = result.systemErrorLine()?.take(160)
        return when {
            systemError != null -> ChangeFailure.SystemError(systemError)
            result.exitCode != 0 -> ChangeFailure.OperationDenied
            else -> ChangeFailure.StateUnchanged
        }
    }

    /** Finds an explicit error reported by the package command. */
    private fun PackageShellResult.systemErrorLine(): String? = output.lineSequence()
        .map(String::trim)
        .firstOrNull {
            it.startsWith("Failure", ignoreCase = true) ||
                it.startsWith("Error", ignoreCase = true) ||
                it.startsWith("SecurityException", ignoreCase = true) ||
            it.startsWith("java.lang.SecurityException", ignoreCase = true) ||
            it.startsWith("DELETE_FAILED_", ignoreCase = true) ||
                it.startsWith("Permission Denial", ignoreCase = true)
        }

    /** Returns the exact package manager enable setting observed for this user. */
    private fun PackageReading.enabledFor(userId: Int): Int? =
        (this as? PackageReading.Found)?.record?.enabledSettings?.get(userId)

    /** Builds a disable request for the exact package and user in the confirmation. */
    private fun ChangeTarget.disableCommand(): List<String> =
        listOf("disable-user", "--user", userId.toString(), packageName)

    /** Resolves a target's installation state, including when its whole package record is gone. */
    private fun readUserState(
        target: ChangeTarget,
        reading: PackageReading,
    ): PackageState? = when (reading) {
        is PackageReading.Found -> reading.record.users[target.userId]
        PackageReading.Missing -> PackageState.NOT_FOUND
        is PackageReading.Unavailable -> null
    }

    /** Verifies the resulting state, distinguishing a user freeze from system-disabled states. */
    private fun PackageReading.matches(target: ChangeTarget, action: PackageAction): Boolean {
        val state = readUserState(target, this)
        return when (action) {
            PackageAction.UNINSTALL -> state == PackageState.RESTORABLE || state == PackageState.NOT_FOUND
            PackageAction.DISABLE -> state == PackageState.DISABLED &&
                enabledFor(target.userId) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            PackageAction.RESTORE -> state == PackageState.INSTALLED || state == PackageState.DISABLED
        }
    }

    private fun parseDump(
        packageName: String,
        result: PackageShellResult,
        permissionManager: PackageManager?,
    ): PackageReading {
        val issue = result.queryIssue()
        if (issue == PackageScanIssue.PERMISSION_DENIED) return PackageReading.Unavailable(issue)
        val lines = result.output.lines()
        val section = lines.indexOfFirst { it.trimEnd() == "Packages:" }
        if (section >= 0) {
            val packages = lines.drop(section + 1)
                .takeWhile { it.isBlank() || it.first().isWhitespace() }
            val start = packages.indexOfFirst { packageHeader.matchEntire(it)?.groupValues?.get(1) == packageName }
            if (start >= 0) {
                if (issue != null) return PackageReading.Unavailable(issue)
                val block = packages.drop(start + 1).takeWhile { !packageHeader.matches(it) }
                val record = parseRecord(block)
                val permissions = permissionManager?.let { readComponentPermissions(packageName, record, block, lines, it) }
                return PackageReading.Found(record.copy(permissions = permissions))
            }
        }
        val missing = lines.any {
            it.trim().equals("Unable to find package: " + packageName, ignoreCase = true)
        }
        return if (missing) {
            PackageReading.Missing
        } else {
            PackageReading.Unavailable(issue ?: PackageScanIssue.UNRECOGNIZED_RESPONSE)
        }
    }

    private fun parseRecord(lines: List<String>): PackageRecord {
        val fields = lines.map(String::trim)
        val flagFields = fields.filter { it.startsWith("pkgFlags=[") || it.startsWith("flags=[") }
        val flags = flagFields
            .flatMap { it.substringAfter('[').substringBefore(']').trim().split(Regex("""\s+""")) }
            .toSet()
        val isSystem = "SYSTEM" in flags || "UPDATED_SYSTEM_APP" in flags
        val appId = fields.firstOrNull { it.startsWith("appId=") || it.startsWith("userId=") }
            ?.substringAfter('=')?.toIntOrNull()
        val codePath = fields.firstOrNull { it.startsWith("codePath=") }
            ?.substringAfter('=')?.takeUnless { it.isBlank() || it == "null" }
        val enabledSettings = mutableMapOf<Int, Int>()
        val users = lines.mapNotNull { line ->
            val match = userHeader.matchEntire(line) ?: return@mapNotNull null
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val data = match.groupValues[2]
            val enabled = enabledFlag.find(data)?.groupValues?.get(1)?.toIntOrNull()
            if (enabled != null) enabledSettings[id] = enabled
            val state = when (installedFlag.find(data)?.groupValues?.get(1)) {
                "true" -> when (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> PackageState.DISABLED
                    else -> PackageState.INSTALLED
                }
                "false" -> PackageState.NOT_FOUND
                else -> PackageState.UNKNOWN
            }
            id to state
        }.toMap()
        // Package settings can still report a code path after the APK is gone.
        val hasApk = when (fields.firstOrNull { it.startsWith("pkg=") }?.substringAfter('=')) {
            "null" -> false
            null, "" -> isSystem || codePath != null || users.values.any { it.permits(PackageAction.UNINSTALL) }
            else -> true
        }
        return PackageRecord(
            versionName = fields.firstOrNull { it.startsWith("versionName=") }
                ?.substringAfter('=')?.takeUnless { it == "null" },
            versionCode = fields.firstNotNullOfOrNull {
                versionCode.find(it)?.groupValues?.get(1)?.toLongOrNull()
            },
            codePath = codePath,
            hasVerifiedSystemIdentity = isSystem && appId != null && appId >= 10_000,
            hasSystemCopy = isSystem.takeIf { flagFields.isNotEmpty() },
            enabledSettings = enabledSettings,
            users = if (hasApk) {
                users.mapValues { (_, state) -> if (state == PackageState.NOT_FOUND) PackageState.RESTORABLE else state }
            } else {
                users
            },
        )
    }

    private companion object {
        val packageHeader = Regex("""^ {2}Package \[([^\]]+)](?: \([^)]*\))?:.*$""")
        val userHeader = Regex("""^\s+User (\d+):\s+(.*)$""")
        val listedUser = Regex("""^\s*UserInfo\{(\d+):(.*):[a-fA-F0-9]+\}.*$""")
        val installedFlag = Regex("""\binstalled=(true|false)\b""")
        val enabledFlag = Regex("""\benabled=(\d+)\b""")
        val versionCode = Regex("""^versionCode=(\d+)\b""")

        /** Total time allowed for an app_process deletion to appear in the package dump. */
        const val RETRY_VERIFY_TIMEOUT_MS = 3_000L

        /** Delay between package dumps while waiting for an app_process deletion. */
        const val RETRY_VERIFY_INTERVAL_MS = 150L
    }
}
