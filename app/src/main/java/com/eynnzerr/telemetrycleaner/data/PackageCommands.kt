package com.eynnzerr.telemetrycleaner.data

import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The state of one active package-manager record, excluding old system versions.
 *
 * @property hasVerifiedSystemIdentity Whether the record identifies a system package with an application-range ID.
 * @property hasSystemCopy Whether the package flags indicate a system copy, or null if flags are unavailable.
 */
internal data class PackageRecord(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val hasVerifiedSystemIdentity: Boolean,
    val hasSystemCopy: Boolean?,
    val users: Map<Int, PackageState>,
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

/** Queries and changes only catalogued carrier packages through the Android shell. */
internal object PackageCommands {
    private val packageHeader = Regex("""^ {2}Package \[([^\]]+)](?: \([^)]*\))?:.*$""")
    private val userHeader = Regex("""^\s+User (\d+):\s+(.*)$""")
    private val listedUser = Regex("""^\s*UserInfo\{(\d+):(.*):[a-fA-F0-9]+\}.*$""")
    private val installedFlag = Regex("""\binstalled=(true|false)\b""")
    private val enabledFlag = Regex("""\benabled=(\d+)\b""")
    private val versionCode = Regex("""^versionCode=(\d+)\b""")

    /** Total time allowed for an app_process deletion to appear in the package dump. */
    private const val RETRY_VERIFY_TIMEOUT_MS = 3_000L

    /** Delay between package dumps while waiting for an app_process deletion. */
    private const val RETRY_VERIFY_INTERVAL_MS = 150L

    /** Resolves the requested catalogue variants and their states in each visible Android user. */
    suspend fun scan(callerUser: Int, packageNames: Set<String>, permissionManager: PackageManager?): ScanSnapshot {
        val users = listUsers()
        if (users.none { it.id == callerUser }) throw UserListScanException(PackageScanIssue.USER_STATE_MISSING)
        val packages = packageNames.map { packageName ->
            currentCoroutineContext().ensureActive()
            val entry = PackageDirectory.variants.getValue(packageName)
            when (val reading = read(packageName, permissionManager)) {
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
            PackageBinder.execute(listOf("list", "users"))
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
    private fun read(packageName: String, permissionManager: PackageManager? = null): PackageReading {
        val direct = readDump(packageName, permissionManager) { PackageBinder.execute(listOf("dump", packageName)) }
        if (direct !is PackageReading.Unavailable) return direct
        val fallback = readDump(packageName, permissionManager) { PackageBinder.dump(packageName) }
        if (fallback !is PackageReading.Unavailable) return fallback
        return when {
            direct.issue == PackageScanIssue.PERMISSION_DENIED -> direct
            fallback.issue == PackageScanIssue.UNRECOGNIZED_RESPONSE -> direct
            else -> fallback
        }
    }

    /** Converts Binder transport failures to a reason before parsing the package response. */
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
     * [deleteThroughAppProcess] retries one uninstall directly through app_process when the
     * command layer fails. The original system error still describes a failed retry.
     */
    suspend fun change(
        target: ChangeTarget,
        action: PackageAction,
        requireVerifiedSystemIdentity: Boolean,
        canSubmit: suspend () -> Boolean = { true },
        deleteThroughAppProcess: (String, Int) -> Boolean = { _, _ -> false },
    ): ChangeOutcome {
        val packageName = target.packageName
        if (packageName !in PackageDirectory.variants) {
            return ChangeOutcome(target, false, "该组件不在内置目录中。")
        }
        val reading = read(packageName)
        if (action == PackageAction.UNINSTALL && reading == PackageReading.Missing) {
            return ChangeOutcome(target, true, null)
        }
        val record = (reading as? PackageReading.Found)?.record
            ?: return ChangeOutcome(target, false, "无法确认组件身份。请刷新后重试。")
        if (requireVerifiedSystemIdentity && !record.hasVerifiedSystemIdentity) {
            return ChangeOutcome(target, false, "该包的系统身份未通过核验。")
        }
        val state = record.users[target.userId]
        if (state == null || state == PackageState.UNKNOWN) {
            return ChangeOutcome(target, false, "无法确认该空间的组件状态。")
        }
        if (matches(state, action)) return ChangeOutcome(target, true, null)
        if (!state.permits(action)) {
            return ChangeOutcome(target, false, "设备上没有可用于恢复的组件副本。")
        }

        val userId = target.userId.toString()
        val command = when (action) {
            PackageAction.UNINSTALL -> buildList {
                addAll(listOf("uninstall", "--user", userId))
                record.versionCode?.let { addAll(listOf("--versionCode", it.toString())) }
                add(packageName)
            }
            PackageAction.RESTORE ->
                listOf("install-existing", "--user", userId, "--wait", packageName)
        }
        if (!canSubmit()) return ChangeOutcome(target, false, null)
        val result = try {
            PackageBinder.execute(command)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A rejected Binder reply can arrive after the service applied the command.
            val post = readUserState(target)
            if (matches(post, action)) return ChangeOutcome(target, true, null)
            if (post != null && post != PackageState.UNKNOWN &&
                retryUninstall(target, action, deleteThroughAppProcess)
            ) {
                return ChangeOutcome(target, true, null)
            }
            throw failure
        }
        val post = readUserState(target)
        if (matches(post, action)) return ChangeOutcome(target, true, null)

        // ROMs can reject system package removal at the command layer. Retry once through the
        // hidden package manager interface, then keep showing the original system error.
        if (post != null && post != PackageState.UNKNOWN &&
            retryUninstall(target, action, deleteThroughAppProcess)
        ) {
            return ChangeOutcome(target, true, null)
        }
        return ChangeOutcome(target, false, failureReason(result, post))
    }

    /** Retries an uninstall through app_process and waits for the package dump to reflect it. */
    private suspend fun retryUninstall(
        target: ChangeTarget,
        action: PackageAction,
        deleteThroughAppProcess: (String, Int) -> Boolean,
    ): Boolean {
        if (action != PackageAction.UNINSTALL) return false
        if (!deleteThroughAppProcess(target.packageName, target.userId)) return false
        return awaitUninstall(target)
    }

    /** Waits until a deletion submitted through app_process becomes visible to package dumps. */
    private suspend fun awaitUninstall(target: ChangeTarget): Boolean {
        val deadline = SystemClock.elapsedRealtime() + RETRY_VERIFY_TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            if (matches(readUserState(target), PackageAction.UNINSTALL)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(RETRY_VERIFY_INTERVAL_MS)
        }
    }

    /** Explains a failed change while preserving the system's own error text. */
    private fun failureReason(result: PackageShellResult, state: PackageState?): String {
        if (state == null || state == PackageState.UNKNOWN) {
            return "无法确认操作后的状态。请刷新后再试。"
        }
        val systemError = result.output.lineSequence()
            .map(String::trim)
            .firstOrNull {
                it.startsWith("Failure", ignoreCase = true) ||
                    it.startsWith("Error", ignoreCase = true) ||
                    it.startsWith("SecurityException", ignoreCase = true)
            }?.take(160)
        return when {
            result.exitCode != 0 -> systemError ?: "系统拒绝了此操作。"
            systemError != null -> systemError
            else -> "系统返回后，组件状态仍未改变。"
        }
    }

    /** Resolves a target's installation state, including when its whole package record is gone. */
    private fun readUserState(target: ChangeTarget): PackageState? = when (val reading = read(target.packageName)) {
        is PackageReading.Found -> reading.record.users[target.userId]
        PackageReading.Missing -> PackageState.NOT_FOUND
        is PackageReading.Unavailable -> null
    }

    private fun matches(state: PackageState?, action: PackageAction): Boolean =
        when (action) {
            PackageAction.UNINSTALL -> state == PackageState.RESTORABLE || state == PackageState.NOT_FOUND
            PackageAction.RESTORE -> state == PackageState.INSTALLED || state == PackageState.DISABLED
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
        val users = lines.mapNotNull { line ->
            val match = userHeader.matchEntire(line) ?: return@mapNotNull null
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val data = match.groupValues[2]
            val state = when (installedFlag.find(data)?.groupValues?.get(1)) {
                "true" -> when (enabledFlag.find(data)?.groupValues?.get(1)) {
                    "2", "3", "4" -> PackageState.DISABLED
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
            users = if (hasApk) {
                users.mapValues { (_, state) -> if (state == PackageState.NOT_FOUND) PackageState.RESTORABLE else state }
            } else {
                users
            },
        )
    }
}
