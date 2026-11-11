package com.eynnzerr.telemetrycleaner.data

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo

/** Matches an exact permission identifier on a requested-permission line. */
private val permissionName = Regex("""[^\s:]+""")

/** Matches an explicit grant record, excluding flags and other dump fields. */
private val grantLine = Regex("""([^\s:]+):\s+granted=(true|false)(?:,.*)?""")

/** Matches the user suffix on a non-default install permission record. */
private val installUserSuffix = Regex(""", userId=\d+$""")

/** Identifies the shared permission state referenced by a package. */
private val sharedUserField = Regex("""sharedUser=SharedUserSetting\{\S+ ([^\s/]+)/\d+\}""")

/** Resolves declarations and explicit grant records from the active package-manager record. */
internal fun readComponentPermissions(
    packageName: String,
    record: PackageRecord,
    packageBlock: List<String>,
    dump: List<String>,
    manager: PackageManager,
): List<ComponentPermission>? {
    val names = packageBlock.section { it.trimEnd() == "    requested permissions:" }
        ?.filterNot(String::isBlank)?.map(String::trim)
        ?: manager.requestedPermissions(packageName, record.versionCode)
        ?: return null
    if (names.any { !permissionName.matches(it) }) return null

    val sharedName = packageBlock.firstNotNullOfOrNull { sharedUserField.matchEntire(it.trim())?.groupValues?.get(1) }
    val sharedBlock = sharedName?.let { name ->
        dump.section { it.trimEnd() == "Shared users:" }
            ?.section { it.startsWith("  SharedUser [$name]") }
    }.orEmpty()
    val grantsByUser = record.users.filterValues { it == PackageState.INSTALLED || it == PackageState.DISABLED }
        .mapValues { (userId, _) ->
            packageBlock.installGrants(userId) + sharedBlock.installGrants(userId) +
                packageBlock.userGrants(userId) + sharedBlock.userGrants(userId)
        }

    return names.distinct().map { name ->
        val info = manager.permissionInfo(name)
        ComponentPermission(
            name = name,
            label = info?.loadLabel(manager)?.toString()?.takeUnless { it.isBlank() || it == name },
            protection = info?.protectionCategory() ?: PermissionProtection.UNKNOWN,
            grants = grantsByUser.mapNotNull { (userId, grants) -> grants[name]?.let { userId to it } }.toMap(),
        )
    }
}

/** Falls back to the SDK if the dump omits the list, verifying the active version first. */
private fun PackageManager.requestedPermissions(packageName: String, version: Long?): List<String>? {
    if (version == null) return null
    return try {
        @Suppress("DEPRECATION")
        val info = getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES)
        info.takeIf { it.longVersionCode == version }?.let { it.requestedPermissions.orEmpty().toList() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/** Reads the device's permission definition, where visible to this app. */
private fun PackageManager.permissionInfo(name: String): PermissionInfo? = try {
    getPermissionInfo(name, 0)
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: SecurityException) {
    null
}

/** Groups runtime, privileged and signature permissions for the details screen. */
private fun PermissionInfo.protectionCategory(): PermissionProtection = when {
    protection == PermissionInfo.PROTECTION_DANGEROUS -> PermissionProtection.DANGEROUS
    protectionFlags and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0 -> PermissionProtection.PRIVILEGED
    protection == PermissionInfo.PROTECTION_SIGNATURE -> PermissionProtection.SIGNATURE
    else -> PermissionProtection.OTHER
}

/** Reads install permission records and applies any override for the selected user. */
private fun List<String>.installGrants(userId: Int): Map<String, Boolean> {
    val rows = section { it.trimEnd() == "    install permissions:" }.orEmpty().map(String::trimEnd)
    // Modern Android prints the system-user baseline and adds suffixed rows for differing profiles.
    return rows.filterNot(installUserSuffix::containsMatchIn).parseGrants() +
        rows.filter { it.endsWith(", userId=$userId") }.parseGrants()
}

/** Reads grants nested under the matching Android user, including per-user install records if present. */
private fun List<String>.userGrants(userId: Int): Map<String, Boolean> {
    val user = section { it.startsWith("    User $userId:") }.orEmpty()
    return user.section { it.trimEnd() == "      install permissions:" }.orEmpty().parseGrants() +
        user.section { it.trimEnd() == "      runtime permissions:" }.orEmpty().parseGrants()
}

/** Parses only explicit granted values from one permission block. */
private fun List<String>.parseGrants(): Map<String, Boolean> = mapNotNull { line ->
    val match = grantLine.matchEntire(line.trim()) ?: return@mapNotNull null
    match.groupValues[1] to (match.groupValues[2] == "true")
}.toMap()

/** Returns the indented content belonging to the first matching dump heading. */
private fun List<String>.section(matches: (String) -> Boolean): List<String>? {
    val start = indexOfFirst(matches)
    if (start < 0) return null
    val indent = this[start].indexOfFirst { !it.isWhitespace() }
    return drop(start + 1).takeWhile { it.isBlank() || it.indexOfFirst { c -> !c.isWhitespace() } > indent }
}
