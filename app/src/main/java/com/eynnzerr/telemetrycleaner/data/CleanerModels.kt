package com.eynnzerr.telemetrycleaner.data

import androidx.annotation.StringRes

/** The app's ability to use Shizuku for package operations. */
enum class ShizukuStatus {
    MISSING, STOPPED, PERMISSION_REQUIRED, CONNECTING, READY, ERROR
}

/** The carriers served by the preinstalled collection components. */
enum class Carrier { UNICOM, TELECOM, MOBILE }

/**
 * An exact, documented package identity in the bundled directory.
 *
 * @property packageName The Android package name used for exact lookup.
 * @property displayName The known name of the component.
 * @property carriers The associated carriers, or an empty set while attribution is pending.
 * @property notes Localized version or compatibility caveat, when available.
 */
data class PackageEntry(
    val packageName: String,
    val displayName: String,
    val carriers: Set<Carrier>,
    @get:StringRes val notes: Int? = null,
)

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
)

/**
 * A fresh scan scoped to the calling application's user and any visible profiles.
 *
 * @property currentUserId The user containing the calling instance of this app.
 * @property users The users observed by the privileged service.
 * @property packages The carrier packages and their per-user state.
 */
data class ScanSnapshot(
    val currentUserId: Int,
    val users: List<AndroidUser>,
    val packages: List<DetectedPackage>,
) {
    /** Resolves actionable package and user pairs, checking system identity when required. */
    internal fun targetsFor(
        action: PackageAction,
        selectedUserIds: Set<Int>,
        packageNames: Set<String>? = null,
        requireVerifiedSystemIdentity: Boolean = true,
    ): List<ChangeTarget> = packages
        .filter { packageNames == null || it.entry.packageName in packageNames }
        .filter { !requireVerifiedSystemIdentity || it.hasVerifiedSystemIdentity }
        .flatMap { detected ->
            selectedUserIds.filter { userId -> detected.users[userId].permits(action) }
                .map { userId -> ChangeTarget(detected.entry.packageName, userId) }
        }
}

/** The supported per-user mutations. */
enum class PackageAction { UNINSTALL, RESTORE }

/** Whether this observed package state is a valid starting point for the action. */
internal fun PackageState?.permits(action: PackageAction): Boolean = when (action) {
    PackageAction.UNINSTALL -> this == PackageState.INSTALLED || this == PackageState.DISABLED
    PackageAction.RESTORE -> this == PackageState.RESTORABLE
}

/**
 * A single exact package and Android user in a confirmed operation.
 *
 * @property packageName The exact package name selected from the directory.
 * @property userId The explicit Android user ID to change.
 */
data class ChangeTarget(val packageName: String, val userId: Int)

/**
 * The verified result of attempting one mutation.
 *
 * @property target The exact package and user requested by the caller.
 * @property success Whether Android reports the requested resulting state.
 * @property message The reason an operation failed or could not be confirmed.
 * @property denialLog Diagnostics from a manual uninstall rejected by the package service.
 */
data class ChangeOutcome(
    val target: ChangeTarget,
    val success: Boolean,
    val message: String?,
    val denialLog: String? = null,
)
