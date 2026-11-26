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
    /** Whether dump fields positively confirm or deny that an APK still exists on disk, or null if ambiguous. */
    val hasVerifiedApk: Boolean? = null,
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

/** Outcome of checking whether a package exists in an actionable state on the device. */
internal sealed interface PackageLookupResult {
    data class Found(val record: PackageRecord) : PackageLookupResult
    data object Missing : PackageLookupResult
    data object Unavailable : PackageLookupResult
}

/**
 * Classifies a raw [PackageReading] for exact-package directory verification.
 * Rejects stale package headers whose users are all [PackageState.NOT_FOUND] (explicit `pkg=null`),
 * and reports [PackageLookupResult.Unavailable] when APK presence is ambiguous (e.g. bare `codePath`).
 */
internal fun PackageReading.toLookupResult(): PackageLookupResult = when (this) {
    PackageReading.Missing -> PackageLookupResult.Missing
    is PackageReading.Unavailable -> PackageLookupResult.Unavailable
    is PackageReading.Found -> when {
        record.users.values.any {
            it == PackageState.INSTALLED ||
                it == PackageState.DISABLED ||
                it == PackageState.HIDDEN ||
                it == PackageState.RESTORABLE
        } -> PackageLookupResult.Found(record)
        record.hasVerifiedApk == false &&
            record.users.isNotEmpty() &&
            record.users.values.all { it == PackageState.NOT_FOUND } ->
            PackageLookupResult.Missing
        else -> PackageLookupResult.Unavailable
    }
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
        val targetNames = entries.mapTo(mutableSetOf(), PackageEntry::packageName)
        val batchBlocks = if (targetNames.size > 1) {
            currentCoroutineContext().ensureActive()
            readBatchPackages(targetNames)
        } else {
            null
        }
        val packages = entries.map { entry ->
            currentCoroutineContext().ensureActive()
            val reading = readForScan(entry.packageName, batchBlocks, permissionManager)
            when (reading) {
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

    /**
     * Resolves one entry during a scan, using the single `dumpsys package p` snapshot to skip
     * absent packages and falling back to targeted single-package reads only for present packages
     * that need full permission and shared-user records.
     */
    private fun readForScan(
        packageName: String,
        batchBlocks: Map<String, List<String>>?,
        permissionManager: PackageManager?,
    ): PackageReading {
        if (batchBlocks == null) {
            return read(packageName, permissionManager)
        }
        val block = batchBlocks[packageName] ?: return PackageReading.Missing
        val batchRecord = parseRecord(block)
        if (permissionManager == null) {
            return PackageReading.Found(batchRecord)
        }
        val detailed = read(packageName, permissionManager)
        return if (detailed !is PackageReading.Unavailable) {
            detailed
        } else {
            PackageReading.Found(batchRecord)
        }
    }

    /**
     * Reads PMS's `Packages:` section once via `dumpsys package p` and extracts only the blocks
     * belonging to [targetPackages]. Returns null on any error or incomplete output so callers
     * safely fall back to per-package reads.
     */
    private fun readBatchPackages(targetPackages: Set<String>): Map<String, List<String>>? {
        val result = try {
            executor.dump("p")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        if (result.queryIssue() != null) return null

        val matched = mutableMapOf<String, MutableList<String>>()
        var inPackagesSection = false
        var totalPackages = 0
        var seenSystemFramework = false
        var currentBlock: MutableList<String>? = null

        for (line in result.output.lineSequence()) {
            if (!inPackagesSection) {
                if (line.trimEnd() == "Packages:") {
                    inPackagesSection = true
                }
                continue
            }
            if (isPostPackagesSectionHeader(line)) {
                break
            }
            val headerMatch = packageHeader.matchEntire(line)
            if (headerMatch != null) {
                totalPackages += 1
                val name = headerMatch.groupValues[1]
                if (name == SYSTEM_FRAMEWORK_PACKAGE) {
                    seenSystemFramework = true
                }
                currentBlock = if (name in targetPackages && name !in matched) {
                    mutableListOf<String>().also { matched[name] = it }
                } else {
                    null
                }
            } else {
                currentBlock?.add(line)
            }
        }
        return matched.takeIf { inPackagesSection && totalPackages > 0 && (matched.isNotEmpty() || seenSystemFramework) }
    }

    /** Reads the exact package directly from PMS because some ROMs omit it from package lists. */
    fun read(packageName: String, permissionManager: PackageManager? = null): PackageReading =
        readDump(packageName, permissionManager) { executor.dump(packageName) }

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
     * [allowDisabledForUninstall] controls whether an already-disabled or hidden package may still be uninstalled.
     * [deleteThroughAppProcess] retries one uninstall directly through app_process when the
     * command layer fails. The original system error still describes a failed retry.
     * [deleteThroughDhizuku] retries one uninstall through Dhizuku's Device Owner `PackageInstaller`
     * when shell-UID deletion is blocked.
     * [hideThroughDhizuku] falls back to `DevicePolicyManager.setApplicationHidden(..., true)` when
     * `disable-user` is blocked in Shizuku mode.
     * [unhideThroughDhizuku] restores a `HIDDEN` package through Dhizuku.
     * [installExistingThroughDhizuku] retries `RESTORABLE` restoration through DevicePolicyManager
     * when `cmd package install-existing` is blocked.
     * [allowShellUnhide] permits `cmd package unhide` (available in Root mode) to restore a `HIDDEN` package.
     */
    suspend fun change(
        target: ChangeTarget,
        action: PackageAction,
        requireVerifiedSystemIdentity: Boolean,
        captureDenialLog: Boolean,
        allowDisabledForUninstall: Boolean = false,
        canSubmit: suspend () -> Boolean = { true },
        submitDeniedFailure: ChangeFailure? = null,
        deleteThroughAppProcess: ((String, Int, Int) -> PackageFallbackResult)? = null,
        deleteThroughDhizuku: ((String, Int, Long, Boolean) -> PackageFallbackResult)? = null,
        hideThroughDhizuku: ((String, Int) -> PackageFallbackResult)? = null,
        unhideThroughDhizuku: ((String, Int) -> PackageFallbackResult)? = null,
        installExistingThroughDhizuku: ((String, Int) -> PackageFallbackResult)? = null,
        allowShellUnhide: Boolean = false,
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
        val unhide = action == PackageAction.RESTORE && state == PackageState.HIDDEN
        if (reenable && record.enabledSettings[target.userId] != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            return ChangeOutcome(target, false, ChangeFailure.DisabledBySystem)
        }
        if (action == PackageAction.UNINSTALL &&
            (state == PackageState.DISABLED || state == PackageState.HIDDEN) &&
            !allowDisabledForUninstall
        ) {
            return if (requireVerifiedSystemIdentity) {
                // Background cleanup skips already-frozen or hidden packages without wiping user data or recording a failure.
                ChangeOutcome(target, true, null)
            } else {
                ChangeOutcome(target, false, ChangeFailure.NotEnabledInSpace)
            }
        }
        if (action == PackageAction.UNINSTALL &&
            requireVerifiedSystemIdentity &&
            !allowDisabledForUninstall &&
            state == PackageState.INSTALLED &&
            !record.enabledSettings[target.userId].isExplicitlyEnabled()
        ) {
            return ChangeOutcome(target, false, ChangeFailure.SpaceStateUnverified)
        }
        if (!reenable && !unhide && reading.matches(target, action)) return ChangeOutcome(target, true, null)
        if (!reenable && !unhide && !state.permits(action)) {
            val failure = if (action == PackageAction.DISABLE) {
                ChangeFailure.NotEnabledInSpace
            } else {
                ChangeFailure.NoRestorableCopy
            }
            return ChangeOutcome(target, false, failure)
        }

        val userId = target.userId.toString()
        if (unhide) {
            return restoreHiddenPackage(
                target = target,
                userId = userId,
                canSubmit = canSubmit,
                submitDeniedFailure = submitDeniedFailure,
                unhideThroughDhizuku = unhideThroughDhizuku,
                allowShellUnhide = allowShellUnhide,
            )
        }

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
        currentCoroutineContext().ensureActive()
        if (!canSubmit()) return ChangeOutcome(target, false, submitDeniedFailure)
        currentCoroutineContext().ensureActive()
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

        var submitDeniedOnRetry = false
        val postCanUninstall = (post == PackageState.INSTALLED &&
            (!requireVerifiedSystemIdentity || allowDisabledForUninstall || afterRequest.enabledFor(target.userId).isExplicitlyEnabled())) ||
            (allowDisabledForUninstall && (post == PackageState.DISABLED || post == PackageState.HIDDEN))
        val fallbackVersionCode = record.versionCode?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
        val postVersionMatches = record.versionCode != null && afterRequest.versionCode() == record.versionCode
        val fallback = if (deleteThroughAppProcess != null && action == PackageAction.UNINSTALL &&
            postCanUninstall && postVersionMatches && fallbackVersionCode != null
        ) {
            if (canSubmit()) {
                currentCoroutineContext().ensureActive()
                deleteThroughAppProcess(packageName, target.userId, fallbackVersionCode)
            } else {
                submitDeniedOnRetry = true
                null
            }
        } else {
            null
        }
        if (fallback?.submitted == true && awaitUninstall(target)) {
            return ChangeOutcome(target, true, null)
        }

        var dhizukuUninstall: PackageFallbackResult? = null
        if (!submitDeniedOnRetry && deleteThroughDhizuku != null && action == PackageAction.UNINSTALL &&
            record.versionCode != null
        ) {
            val beforeDhizuku = if (fallback != null) read(packageName) else afterRequest
            if (beforeDhizuku.matches(target, PackageAction.UNINSTALL)) {
                return ChangeOutcome(target, true, null)
            }
            val beforeState = readUserState(target, beforeDhizuku)
            val beforeCanUninstall = (beforeState == PackageState.INSTALLED &&
                (!requireVerifiedSystemIdentity || allowDisabledForUninstall ||
                    beforeDhizuku.enabledFor(target.userId).isExplicitlyEnabled())) ||
                (allowDisabledForUninstall && (beforeState == PackageState.DISABLED || beforeState == PackageState.HIDDEN))
            val beforeVersionMatches = beforeDhizuku.versionCode() == record.versionCode
            if (beforeCanUninstall && beforeVersionMatches) {
                if (canSubmit()) {
                    currentCoroutineContext().ensureActive()
                    dhizukuUninstall = deleteThroughDhizuku(
                        packageName,
                        target.userId,
                        record.versionCode,
                        record.hasSystemCopy == true,
                    )
                    if (dhizukuUninstall.submitted && awaitUninstall(target)) {
                        return ChangeOutcome(target, true, null)
                    }
                } else {
                    submitDeniedOnRetry = true
                }
            }
        }

        var dhizukuHide: PackageFallbackResult? = null
        if (!submitDeniedOnRetry && action == PackageAction.DISABLE && hideThroughDhizuku != null) {
            val currentVersionMatches = record.versionCode != null && afterRequest.versionCode() == record.versionCode
            if (post == PackageState.INSTALLED &&
                currentVersionMatches &&
                afterRequest.enabledFor(target.userId).isExplicitlyEnabled()
            ) {
                if (canSubmit()) {
                    currentCoroutineContext().ensureActive()
                    dhizukuHide = hideThroughDhizuku(packageName, target.userId)
                    val updatedAfterHide = read(packageName)
                    if (readUserState(target, updatedAfterHide) == PackageState.HIDDEN) {
                        return ChangeOutcome(target, true, null, hiddenInstead = true)
                    }
                } else {
                    submitDeniedOnRetry = true
                }
            }
        }

        if (!submitDeniedOnRetry && action == PackageAction.RESTORE && !reenable &&
            installExistingThroughDhizuku != null && post == PackageState.RESTORABLE
        ) {
            if (canSubmit()) {
                currentCoroutineContext().ensureActive()
                val restoreFallback = installExistingThroughDhizuku(packageName, target.userId)
                if (restoreFallback.submitted) {
                    val updated = read(packageName)
                    if (updated.matches(target, PackageAction.RESTORE)) {
                        return ChangeOutcome(target, true, null)
                    }
                }
            } else {
                submitDeniedOnRetry = true
            }
        }

        val result = attempt.getOrNull()
        val failure = attempt.exceptionOrNull()
        if (failure != null && failure !is SecurityException &&
            dhizukuUninstall == null && dhizukuHide == null && !submitDeniedOnRetry
        ) {
            throw failure
        }
        val rejected = failure is SecurityException ||
            (result != null && (result.exitCode > 0 || result.systemErrorLine() != null))
        val denialLog = if (captureDenialLog &&
            (rejected || dhizukuUninstall != null || submitDeniedOnRetry)
        ) {
            PackageDiagnostics.uninstallDenial(
                executor = executor,
                target = target,
                record = record,
                before = state,
                after = post,
                startedAt = startedAt,
                attempt = attempt,
                fallback = fallback,
                dhizukuUninstall = dhizukuUninstall,
                submitDeniedOnRetry = submitDeniedOnRetry,
            )
        } else {
            null
        }
        val failureReason = when {
            submitDeniedOnRetry && submitDeniedFailure != null -> submitDeniedFailure
            result != null -> failureReason(result, post)
            failure is SecurityException -> ChangeFailure.SpaceOperationDenied
            else -> ChangeFailure.StateUnverified
        }
        return ChangeOutcome(target, false, failureReason, denialLog)
    }

    /** Restores a package marked `hidden=true` using Dhizuku (Device Owner) or Root (`cmd package unhide`). */
    private suspend fun restoreHiddenPackage(
        target: ChangeTarget,
        userId: String,
        canSubmit: suspend () -> Boolean,
        submitDeniedFailure: ChangeFailure?,
        unhideThroughDhizuku: ((String, Int) -> PackageFallbackResult)?,
        allowShellUnhide: Boolean,
    ): ChangeOutcome {
        if (unhideThroughDhizuku == null && !allowShellUnhide) {
            return ChangeOutcome(target, false, ChangeFailure.HiddenByDeviceOwner)
        }
        currentCoroutineContext().ensureActive()
        if (!canSubmit()) return ChangeOutcome(target, false, submitDeniedFailure)
        currentCoroutineContext().ensureActive()

        var shellAttempt: Result<PackageShellResult>? = null
        if (unhideThroughDhizuku != null) {
            unhideThroughDhizuku(target.packageName, target.userId)
        } else {
            shellAttempt = executeChange(listOf("unhide", "--user", userId, target.packageName))
        }

        var afterUnhide = read(target.packageName)
        var post = readUserState(target, afterUnhide)
        if (post == PackageState.HIDDEN && unhideThroughDhizuku != null && allowShellUnhide) {
            if (!canSubmit()) return ChangeOutcome(target, false, submitDeniedFailure)
            currentCoroutineContext().ensureActive()
            shellAttempt = executeChange(listOf("unhide", "--user", userId, target.packageName))
            afterUnhide = read(target.packageName)
            post = readUserState(target, afterUnhide)
        }

        if (post == PackageState.DISABLED &&
            afterUnhide.enabledFor(target.userId) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        ) {
            if (canSubmit()) {
                currentCoroutineContext().ensureActive()
                executeChange(listOf("enable", "--user", userId, target.packageName))
                afterUnhide = read(target.packageName)
                post = readUserState(target, afterUnhide)
            }
        }

        if (post == PackageState.INSTALLED ||
            (post == PackageState.DISABLED &&
                afterUnhide.enabledFor(target.userId) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        ) {
            return ChangeOutcome(target, true, null)
        }

        val shellResult = shellAttempt?.getOrNull()
        val shellFailure = shellAttempt?.exceptionOrNull()
        val failure = when {
            post == null || post == PackageState.UNKNOWN -> ChangeFailure.PostChangeStateUnverified
            shellResult != null -> failureReason(shellResult, post)
            shellFailure is SecurityException -> ChangeFailure.SpaceOperationDenied
            unhideThroughDhizuku != null && post == PackageState.HIDDEN -> ChangeFailure.HiddenByDeviceOwner
            else -> ChangeFailure.OperationDenied
        }
        return ChangeOutcome(target, false, failure)
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

    /** Whether Android explicitly reported an enabled (DEFAULT or ENABLED) component state. */
    private fun Int?.isExplicitlyEnabled(): Boolean =
        this == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            this == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

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

    /** Reads the raw version code from a successful dump reading, when present. */
    private fun PackageReading.versionCode(): Long? =
        (this as? PackageReading.Found)?.record?.versionCode

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
                .takeWhile { !isPostPackagesSectionHeader(it) }
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
            val hidden = hiddenFlag.find(data)?.groupValues?.get(1) == "true"
            if (enabled != null) enabledSettings[id] = enabled
            val state = when (installedFlag.find(data)?.groupValues?.get(1)) {
                "true" -> when {
                    hidden -> PackageState.HIDDEN
                    enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                        enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> PackageState.DISABLED
                    else -> PackageState.INSTALLED
                }
                "false" -> PackageState.NOT_FOUND
                else -> PackageState.UNKNOWN
            }
            id to state
        }.toMap()
        // Package settings can still report a code path after the APK is gone; bare codePath does not prove APK presence.
        val pkgValue = fields.firstOrNull { it.startsWith("pkg=") }?.substringAfter('=')
        val hasVerifiedApk: Boolean? = when {
            pkgValue == "null" -> false
            !pkgValue.isNullOrBlank() -> true
            isSystem || users.values.any { it.permits(PackageAction.UNINSTALL) } -> true
            else -> null
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
            hasVerifiedApk = hasVerifiedApk,
            users = if (hasVerifiedApk == true) {
                users.mapValues { (_, state) -> if (state == PackageState.NOT_FOUND) PackageState.RESTORABLE else state }
            } else {
                users
            },
        )
    }

    /**
     * Recognizes top-level PMS dump section boundaries that terminate `Packages:`.
     *
     * Avoids breaking on arbitrary column-0 lines because AOSP Android 12-15 omits indentation
     * before `<lib> overlay paths:` and `<lib> legacy overlay paths:` inside `User <id>:` blocks
     * (fixed in Android 16), and multiline fields or OEM lines can also start at column 0.
     */
    private fun isPostPackagesSectionHeader(line: String): Boolean {
        if (line.isBlank() || line.first().isWhitespace()) return false
        val heading = line.trimEnd()
        return heading in postPackagesSectionHeaders ||
            postPackagesSectionPrefixes.any { heading.startsWith(it) }
    }

    private companion object {
        const val SYSTEM_FRAMEWORK_PACKAGE = "android"

        val packageHeader = Regex("""^ {2}Package \[([^\]]+)](?: \([^)]*\))?:.*$""")
        val userHeader = Regex("""^\s+User (\d+):\s+(.*)$""")
        val listedUser = Regex("""^\s*UserInfo\{(\d+):(.*):[a-fA-F0-9]+\}.*$""")
        val installedFlag = Regex("""\binstalled=(true|false)\b""")
        val hiddenFlag = Regex("""\bhidden=(true|false)\b""")
        val enabledFlag = Regex("""\benabled=(\d+)\b""")
        val versionCode = Regex("""^versionCode=(\d+)\b""")

        val postPackagesSectionHeaders = setOf(
            "Renamed packages:",
            "Hidden system packages:",
            "Queries:",
            "Shared users:",
            "Package Changes:",
            "Frozen packages:",
            "Loaded volumes:",
            "Service permissions:",
            "Dexopt state:",
            "BgDexopt state:",
            "Compiler stats:",
            "Messages:",
            "Package warning messages:",
            "Active install sessions:",
            "Historical install sessions:",
            "Install sessions:",
            "Active staged sessions:",
            "Active APEX packages:",
            "Inactive APEX packages:",
            "Factory APEX packages:",
            "APEX session state:",
            "APEX packages:",
            "Per UID read timeouts:",
            "Snapshot statistics:",
            "Protected broadcast actions:",
            "Database versions:",
            "Known Packages:",
            "Verifiers:",
            "Domain Verifier:",
            "Features:",
            "Libraries:",
            "Shared libraries:",
            "Permissions:",
            "Registered ContentProviders:",
            "ContentProvider Authorities:",
            "Key Set Manager:",
            "Domain verification status:",
            "Activity Resolver Table:",
            "Receiver Resolver Table:",
            "Service Resolver Table:",
            "Provider Resolver Table:",
        )

        val postPackagesSectionPrefixes = listOf(
            "DUMP OF SERVICE ",
            "--------",
            "Preferred Activities",
            "Active APEX ",
            "Inactive APEX ",
            "Factory APEX ",
        )

        /** Total time allowed for an app_process deletion to appear in the package dump. */
        const val RETRY_VERIFY_TIMEOUT_MS = 3_000L

        /** Delay between package dumps while waiting for an app_process deletion. */
        const val RETRY_VERIFY_INTERVAL_MS = 150L
    }
}
