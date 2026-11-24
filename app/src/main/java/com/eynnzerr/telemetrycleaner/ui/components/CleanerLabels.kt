package com.eynnzerr.telemetrycleaner.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.ChangeFailure
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageFamily
import com.eynnzerr.telemetrycleaner.data.PackageScanIssue
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeConnection
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus

/** Returns the connection label for the active privilege backend. */
@StringRes
internal fun PrivilegeConnection.statusLabel(): Int = when (backend) {
    PrivilegeBackend.SHIZUKU -> when (status) {
        PrivilegeStatus.MISSING -> R.string.shizuku_not_installed
        PrivilegeStatus.STOPPED -> R.string.shizuku_disconnected
        PrivilegeStatus.PERMISSION_REQUIRED -> R.string.shizuku_unauthorized
        PrivilegeStatus.CONNECTING -> R.string.shizuku_connecting
        PrivilegeStatus.READY -> R.string.shizuku_connected
        PrivilegeStatus.ERROR -> R.string.shizuku_error
    }
    PrivilegeBackend.ROOT -> when (status) {
        PrivilegeStatus.MISSING -> R.string.root_missing
        PrivilegeStatus.STOPPED -> R.string.root_disconnected
        PrivilegeStatus.PERMISSION_REQUIRED -> R.string.root_unauthorized
        PrivilegeStatus.CONNECTING -> R.string.root_connecting
        PrivilegeStatus.READY -> R.string.root_connected
        PrivilegeStatus.ERROR -> R.string.root_error
    }
}

/** Localizes ColorOS's built-in clone label while preserving custom user names. */
@Composable
internal fun AndroidUser.displayName(): String =
    when {
        name == "system_clone" -> stringResource(R.string.system_clone)
        id == 0 && (name == "机主" || name == "Owner") -> stringResource(R.string.main_space)
        else -> name
    }

/** Returns the localized carrier name shared by scan results and the package directory. */
@StringRes
internal fun Carrier.label(): Int = when (this) {
    Carrier.UNICOM -> R.string.carrier_unicom
    Carrier.TELECOM -> R.string.carrier_telecom
    Carrier.MOBILE -> R.string.carrier_mobile
}

/** Explains an unresolved installation state using the reason reported for that space. */
@StringRes
internal fun DetectedPackage.installationStatusLabel(userId: Int): Int = when (users[userId]) {
    PackageState.INSTALLED -> R.string.status_installed
    PackageState.DISABLED -> {
        if (userId in userDisabledIds) R.string.status_disabled else R.string.status_disabled_by_system
    }
    PackageState.SUSPENDED -> R.string.status_suspended
    PackageState.RESTORABLE -> R.string.status_restorable
    PackageState.NOT_FOUND -> R.string.status_not_found
    PackageState.UNKNOWN, null -> when (scanIssues[userId]) {
        PackageScanIssue.QUERY_FAILED -> R.string.scan_package_query_failed
        PackageScanIssue.PERMISSION_DENIED -> R.string.scan_package_permission_denied
        PackageScanIssue.UNRECOGNIZED_RESPONSE -> R.string.scan_package_response_invalid
        PackageScanIssue.USER_STATE_MISSING -> R.string.scan_package_space_state_missing
        null -> R.string.status_installation_recheck
    }
}

/** Resolves the localized label for a group in the bundled catalogue. */
@StringRes
internal fun PackageFamily.label(): Int = when (this) {
    PackageFamily.CROSS_VENDOR -> R.string.package_family_cross_vendor
    PackageFamily.COLOR_OS -> R.string.package_family_color_os
    PackageFamily.XIAOMI -> R.string.package_family_xiaomi
    PackageFamily.VIVO -> R.string.package_family_vivo
    PackageFamily.HUAWEI -> R.string.package_family_huawei
    PackageFamily.HONOR -> R.string.package_family_honor
    PackageFamily.FLYME -> R.string.package_family_flyme
    PackageFamily.NUBIA -> R.string.package_family_nubia
    PackageFamily.SAMSUNG -> R.string.package_family_samsung
    PackageFamily.MOTOROLA -> R.string.package_family_motorola
    PackageFamily.ZTE -> R.string.package_family_zte
    PackageFamily.BROWSER -> R.string.package_family_browser
}

/** Resolves a structured change failure into localized UI copy or preserves raw system output. */
@Composable
internal fun ChangeFailure.displayMessage(): String = when (this) {
    is ChangeFailure.SystemError -> detail
    ChangeFailure.NotManaged -> stringResource(R.string.change_error_not_managed)
    ChangeFailure.ProtectedSystemPackage -> stringResource(R.string.change_error_protected_system_package)
    ChangeFailure.ShizukuDisconnected -> stringResource(R.string.change_error_shizuku_disconnected)
    ChangeFailure.SpaceOperationDenied -> stringResource(R.string.change_error_space_operation_denied)
    ChangeFailure.StateUnverified -> stringResource(R.string.change_error_state_unverified)
    ChangeFailure.IdentityUnverified -> stringResource(R.string.change_error_identity_unverified)
    ChangeFailure.SystemIdentityNotVerified -> stringResource(R.string.change_error_system_identity_not_verified)
    ChangeFailure.SpaceStateUnverified -> stringResource(R.string.change_error_space_state_unverified)
    ChangeFailure.DisabledBySystem -> stringResource(R.string.change_error_disabled_by_system)
    ChangeFailure.NotEnabledInSpace -> stringResource(R.string.change_error_not_enabled_in_space)
    ChangeFailure.NoRestorableCopy -> stringResource(R.string.change_error_no_restorable_copy)
    ChangeFailure.PostChangeStateUnverified -> stringResource(R.string.change_error_post_change_state_unverified)
    ChangeFailure.OperationDenied -> stringResource(R.string.change_error_operation_denied)
    ChangeFailure.StateUnchanged -> stringResource(R.string.change_error_state_unchanged)
}
