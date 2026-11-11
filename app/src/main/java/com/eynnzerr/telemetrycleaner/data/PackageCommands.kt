package com.eynnzerr.telemetrycleaner.data

import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The state of one active package-manager record, excluding old system versions. */
internal data class PackageRecord(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val isSystem: Boolean,
    val isCoreUid: Boolean,
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

    /** Resolves the requested catalogue variants and their states in each visible Android user. */
    suspend fun scan(callerUser: Int, packageNames: Set<String>, permissionManager: PackageManager?): ScanSnapshot {
        val users = listUsers()
        if (users.none { it.id == callerUser }) throw UserListScanException(PackageScanIssue.USER_STATE_MISSING)
        val packages = packageNames.map { packageName ->
            currentCoroutineContext().ensureActive()
            val entry = PackageDirectory.variants.getValue(packageName)
            when (val reading = read(packageName, permissionManager)) {
                is PackageReading.Found -> {
                    val states = users.associate { it.id to (reading.record.users[it.id] ?: PackageState.UNKNOWN) }
                    DetectedPackage(
                        entry = entry,
                        versionName = reading.record.versionName,
                        versionCode = reading.record.versionCode,
                        systemPath = reading.record.codePath,
                        users = states,
                        canManage = canManage(reading.record),
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
                    canManage = false,
                )
                is PackageReading.Unavailable -> DetectedPackage(
                    entry = entry,
                    versionName = null,
                    versionCode = null,
                    systemPath = null,
                    users = users.associate { it.id to PackageState.UNKNOWN },
                    canManage = false,
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

    /** Verifies the active record of a catalogued package, including after OTA. */
    private fun canManage(record: PackageRecord): Boolean = record.isSystem && !record.isCoreUid

    /** Checks [canSubmit] before changing one user and verifies the resulting package state. */
    suspend fun change(
        target: ChangeTarget,
        action: PackageAction,
        canSubmit: suspend () -> Boolean = { true },
    ): ChangeOutcome {
        val packageName = target.packageName
        if (packageName !in PackageDirectory.variants) {
            return ChangeOutcome(target, false, "该组件不在内置目录中。")
        }
        val reading = read(packageName) as? PackageReading.Found
            ?: return ChangeOutcome(target, false, "无法确认组件身份。请刷新后重试。")
        val record = reading.record
        if (!canManage(record)) {
            return ChangeOutcome(target, false, "该包的系统身份未通过核验。")
        }
        val state = record.users[target.userId]
        if (state == null || state == PackageState.UNKNOWN) {
            return ChangeOutcome(target, false, "无法确认该空间的组件状态。")
        }
        if (matches(state, action)) return ChangeOutcome(target, true, null)

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
        val result = PackageBinder.execute(command)
        val post = (read(packageName) as? PackageReading.Found)?.record?.users?.get(target.userId)
        if (post != null && matches(post, action)) return ChangeOutcome(target, true, null)

        val reason = when {
            post == null || post == PackageState.UNKNOWN -> "无法确认操作后的状态。请刷新后再试。"
            result.exitCode != 0 -> result.output.lineSequence().firstOrNull {
                it.startsWith("Failure", ignoreCase = true) ||
                    it.startsWith("Error", ignoreCase = true) ||
                    it.startsWith("SecurityException", ignoreCase = true)
            }?.take(160) ?: "系统拒绝了此操作。"
            else -> "系统返回后，组件状态仍未改变。"
        }
        return ChangeOutcome(target, false, reason)
    }

    private fun matches(state: PackageState, action: PackageAction): Boolean =
        when (action) {
            PackageAction.UNINSTALL -> state == PackageState.RESTORABLE
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
        val flags = fields.filter { it.startsWith("pkgFlags=[") || it.startsWith("flags=[") }
            .flatMap { it.substringAfter('[').substringBefore(']').trim().split(Regex("""\s+""")) }
            .toSet()
        val isSystem = "SYSTEM" in flags || "UPDATED_SYSTEM_APP" in flags
        val appId = fields.firstOrNull { it.startsWith("appId=") || it.startsWith("userId=") }
            ?.substringAfter('=')?.toIntOrNull()
        val users = lines.mapNotNull { line ->
            val match = userHeader.matchEntire(line) ?: return@mapNotNull null
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val data = match.groupValues[2]
            val state = when (installedFlag.find(data)?.groupValues?.get(1)) {
                "true" -> when (enabledFlag.find(data)?.groupValues?.get(1)) {
                    "2", "3", "4" -> PackageState.DISABLED
                    else -> PackageState.INSTALLED
                }
                "false" -> if (isSystem) PackageState.RESTORABLE else PackageState.NOT_FOUND
                else -> PackageState.UNKNOWN
            }
            id to state
        }.toMap()
        return PackageRecord(
            versionName = fields.firstOrNull { it.startsWith("versionName=") }
                ?.substringAfter('=')?.takeUnless { it == "null" },
            versionCode = fields.firstNotNullOfOrNull {
                versionCode.find(it)?.groupValues?.get(1)?.toLongOrNull()
            },
            codePath = fields.firstOrNull { it.startsWith("codePath=") }?.substringAfter('='),
            isSystem = isSystem,
            isCoreUid = appId == null || appId < 10_000,
            users = users,
        )
    }
}
