package com.eynnzerr.telemetrycleaner.data

/** The user's choice of how to authorize package operations. */
enum class PrivilegeBackend { SHIZUKU, ROOT }

/** The availability of the selected package operation backend. */
enum class PrivilegeStatus {
    MISSING, STOPPED, PERMISSION_REQUIRED, CONNECTING, READY, ERROR
}

/**
 * The backend selected for both manual operations and automatic cleanup.
 *
 * @property backend The user's selected authorization method.
 * @property status The current availability of that method.
 */
data class PrivilegeConnection(val backend: PrivilegeBackend, val status: PrivilegeStatus)

/** The carriers served by the preinstalled collection components. */
enum class Carrier { UNICOM, TELECOM, MOBILE }

/**
 * A bundled or user-selected package identity in the cleaner's directory.
 *
 * @property packageName The Android package name used for exact lookup.
 * @property displayName The known component name or the device's application label when available.
 * @property carriers The associated carriers, if known and applicable.
 */
data class PackageEntry(
    val packageName: String,
    val displayName: String,
    val carriers: Set<Carrier>,
)

/** Matches Android package identifiers with at least two dot-separated segments. */
private val qualifiedPackageName = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+""")

/** Whether [name] can be passed as an exact package target to the package service. */
internal fun isExactPackageName(name: String): Boolean = name == "android" || qualifiedPackageName.matches(name)

/**
 * A user or profile visible to Android's package manager.
 *
 * @property id The numeric Android user ID.
 * @property name The name provided by Android.
 * @property serialNumber The stable profile identity, when available.
 */
data class AndroidUser(val id: Int, val name: String, val serialNumber: Long? = null)

/** The package manager's observed state for one Android user. */
enum class PackageState {
    INSTALLED,
    DISABLED,
    RESTORABLE,
    NOT_FOUND,
    UNKNOWN,
}

/** Explains why the package manager could not confirm a scan result. */
enum class PackageScanIssue { QUERY_FAILED, PERMISSION_DENIED, UNRECOGNIZED_RESPONSE, USER_STATE_MISSING }

/** Groups requested permissions by their protection level on this device. */
enum class PermissionProtection { DANGEROUS, PRIVILEGED, SIGNATURE, OTHER, UNKNOWN }

/**
 * A permission Android records as requested by a component.
 *
 * @property name The exact permission identifier.
 * @property label The localized platform label, when available.
 * @property protection The protection level reported by the platform.
 * @property grants Explicit grant records for installed spaces, keyed by user ID.
 */
data class ComponentPermission(
    val name: String,
    val label: String?,
    val protection: PermissionProtection,
    val grants: Map<Int, Boolean>,
)

/**
 * The active package and its state in each visible user.
 *
 * @property entry The exact identity being checked.
 * @property versionName The installed firmware or update version reported by Android.
 * @property versionCode The version used to guard a mutation against a concurrent update.
 * @property systemPath The active APK code path, including a staged system update if applicable.
 * @property users The observed installation state keyed by user ID.
 * @property hasVerifiedSystemIdentity Whether Android reports a system package with an application-range ID.
 * @property hasSystemCopy Whether the package flags indicate a system copy, or null if flags are unavailable.
 * @property permissions Requested permissions, or null if declarations could not be read.
 * @property scanIssues Reasons the scanner could not confirm the state, keyed by user ID.
 * @property userDisabledIds Spaces whose package is explicitly disabled by the user and can be re-enabled.
 */
data class DetectedPackage(
    val entry: PackageEntry,
    val versionName: String?,
    val versionCode: Long?,
    val systemPath: String?,
    val users: Map<Int, PackageState>,
    val hasVerifiedSystemIdentity: Boolean,
    val hasSystemCopy: Boolean? = null,
    val permissions: List<ComponentPermission>? = null,
    val scanIssues: Map<Int, PackageScanIssue> = emptyMap(),
    val userDisabledIds: Set<Int> = emptySet(),
) {
    /** Identifies components with enough package data to display an interactive row. */
    fun hasVisibleRecord(): Boolean = systemPath != null || users.values.any {
        it == PackageState.INSTALLED || it == PackageState.DISABLED || it == PackageState.RESTORABLE
    }

    /**
     * Whether uninstalling this package in [targetUserIds] removes the last APK copy on the device
     * (applies to non-system apps with no surviving copy in any other or disabled space).
     */
    fun losesApkOnUninstall(targetUserIds: Set<Int>): Boolean =
        hasSystemCopy == false && users.none { (userId, status) ->
            status == PackageState.UNKNOWN ||
                status == PackageState.DISABLED ||
                (userId !in targetUserIds && status == PackageState.INSTALLED)
        }

    /** Resolves actionable spaces for this single package without scanning the whole snapshot. */
    internal fun targetsFor(
        action: PackageAction,
        selectedUserIds: Set<Int>,
        usersById: Map<Int, AndroidUser> = emptyMap(),
        currentUserId: Int? = null,
        requireVerifiedSystemIdentity: Boolean = true,
        includeDisabledForUninstall: Boolean = false,
    ): List<ChangeTarget> {
        if (requireVerifiedSystemIdentity && !hasVerifiedSystemIdentity) return emptyList()
        return selectedUserIds.filter { userId ->
            val state = users[userId]
            when (action) {
                PackageAction.UNINSTALL -> state.permits(action) &&
                    (includeDisabledForUninstall || state != PackageState.DISABLED)
                PackageAction.DISABLE -> state.permits(action)
                PackageAction.RESTORE -> state.permits(action) ||
                    (state == PackageState.DISABLED && userId in userDisabledIds)
            }
        }.map { userId ->
            val serial = if (currentUserId == null || userId != currentUserId) {
                usersById[userId]?.serialNumber
            } else {
                null
            }
            ChangeTarget(entry.packageName, userId, serial)
        }
    }
}

/**
 * A fresh scan scoped to the calling application's user and any visible profiles.
 *
 * @property currentUserId The user containing the calling instance of this app.
 * @property users The users observed by the privileged service.
 * @property packages The catalogued packages and their per-user state.
 */
data class ScanSnapshot(
    val currentUserId: Int,
    val users: List<AndroidUser>,
    val packages: List<DetectedPackage>,
) {
    /** Resolves actionable package and user pairs, with optional identity and uninstall-state checks. */
    internal fun targetsFor(
        action: PackageAction,
        selectedUserIds: Set<Int>,
        packageNames: Set<String>? = null,
        requireVerifiedSystemIdentity: Boolean = true,
        includeDisabledForUninstall: Boolean = false,
    ): List<ChangeTarget> {
        val usersById = users.associateBy(AndroidUser::id)
        return packages
            .filter { packageNames == null || it.entry.packageName in packageNames }
            .flatMap { detected ->
                detected.targetsFor(
                    action = action,
                    selectedUserIds = selectedUserIds,
                    usersById = usersById,
                    currentUserId = currentUserId,
                    requireVerifiedSystemIdentity = requireVerifiedSystemIdentity,
                    includeDisabledForUninstall = includeDisabledForUninstall,
                )
            }
    }
}

/** The supported per-user mutations. */
enum class PackageAction { UNINSTALL, DISABLE, RESTORE }

/** Whether this observed package state is a valid starting point for the action. */
internal fun PackageState?.permits(action: PackageAction): Boolean = when (action) {
    PackageAction.UNINSTALL -> this == PackageState.INSTALLED || this == PackageState.DISABLED
    PackageAction.DISABLE -> this == PackageState.INSTALLED
    PackageAction.RESTORE -> this == PackageState.RESTORABLE
}

/**
 * A single exact package and Android user in a confirmed operation.
 *
 * @property packageName The exact package name selected from the directory.
 * @property userId The explicit Android user ID to change.
 * @property userSerialNumber The profile serial captured during scan to prevent recycled-user mismatches.
 */
data class ChangeTarget(
    val packageName: String,
    val userId: Int,
    val userSerialNumber: Long? = null,
)

/** Structured reason why a requested package change failed or could not be confirmed. */
sealed interface ChangeFailure {
    /** The requested package is no longer in the bundled or custom directory. */
    data object NotManaged : ChangeFailure

    /** The requested package is a protected core system or recovery package. */
    data object ProtectedSystemPackage : ChangeFailure

    /** Shizuku disconnected before the operation could be submitted. */
    data object ShizukuDisconnected : ChangeFailure

    /** Android rejected the operation for the requested user space. */
    data object SpaceOperationDenied : ChangeFailure

    /** Package state could not be read reliably; user should refresh and retry. */
    data object StateUnverified : ChangeFailure

    /** Package record could not be confirmed before mutating it. */
    data object IdentityUnverified : ChangeFailure

    /** Automatic cleanup refused to touch a package without verified system identity. */
    data object SystemIdentityNotVerified : ChangeFailure

    /** Target user space state could not be confirmed in the package dump. */
    data object SpaceStateUnverified : ChangeFailure

    /** The package was disabled by the system rather than the user and cannot be re-enabled. */
    data object DisabledBySystem : ChangeFailure

    /** Disable was requested for a package that is not currently enabled in this space. */
    data object NotEnabledInSpace : ChangeFailure

    /** Restore was requested when no restorable APK copy exists on the device. */
    data object NoRestorableCopy : ChangeFailure

    /** Post-operation package state could not be confirmed. */
    data object PostChangeStateUnverified : ChangeFailure

    /** The package service returned a non-zero exit code without a recognized error line. */
    data object OperationDenied : ChangeFailure

    /** The command returned without an error, but the package state did not change. */
    data object StateUnchanged : ChangeFailure

    /** Raw error text returned directly by Android's package service. */
    data class SystemError(val detail: String) : ChangeFailure
}

/**
 * The verified result of attempting one mutation.
 *
 * @property target The exact package and user requested by the caller.
 * @property success Whether Android reports the requested resulting state.
 * @property failure The structured reason an operation failed or could not be confirmed.
 * @property denialLog Diagnostics from a manual uninstall rejected by the package service.
 * @property disabledInstead Whether the fallback left the package installed and explicitly disabled.
 */
data class ChangeOutcome(
    val target: ChangeTarget,
    val success: Boolean,
    val failure: ChangeFailure? = null,
    val denialLog: String? = null,
    val disabledInstead: Boolean = false,
)
