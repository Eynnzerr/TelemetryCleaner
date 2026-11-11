package com.eynnzerr.telemetrycleaner.feature

import com.eynnzerr.telemetrycleaner.data.ChangeOutcome
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.data.ShizukuStatus

/** Work in progress on the cleaner screen. */
enum class CleanerActivity { IDLE, SCANNING, CHANGING }

/** A screen-level failure that can be described with localized UI copy. */
enum class CleanerError { SCAN_FAILED, ACTION_FAILED }

/** The currently presented sheet and the data needed to render it. */
sealed interface CleanerPanel {
    /** Shows the evidence and installation status for [packageName]. */
    data class Details(val packageName: String) : CleanerPanel

    /** Captures the exact [targets] for a pending [action]. */
    data class Confirmation(
        val action: PackageAction,
        val targets: List<ChangeTarget>,
    ) : CleanerPanel

    /** Shows independently verified [outcomes] for the requested [action]. */
    data class Results(
        val action: PackageAction,
        val outcomes: List<ChangeOutcome>,
    ) : CleanerPanel
}

/**
 * Immutable screen state produced by the cleaner reducer.
 *
 * @property connection Most recent Shizuku connection and authorization status.
 * @property snapshot Last observed package states. In-flight changes invalidate affected states.
 * @property selectedUserIds User spaces included in the next operation.
 * @property selectedPackageNames Packages checked for batch operations.
 * @property activity Work currently in progress.
 * @property panel Active sheet, if any.
 * @property error Last screen-level failure, if any.
 * @property workId Associates scan and operation responses with the request that started them.
 */
data class CleanerState(
    val connection: ShizukuStatus = ShizukuStatus.CONNECTING,
    val snapshot: ScanSnapshot? = null,
    val selectedUserIds: Set<Int> = emptySet(),
    val selectedPackageNames: Set<String> = emptySet(),
    val activity: CleanerActivity = CleanerActivity.IDLE,
    val panel: CleanerPanel? = null,
    val error: CleanerError? = null,
    internal val workId: Long = 0,
) {
    /** Whether the current snapshot can be used to offer package operations. */
    val readyForActions: Boolean
        get() = connection == ShizukuStatus.READY &&
            activity == CleanerActivity.IDLE &&
            snapshot != null &&
            error == null

    /**
     * Returns the eligible packages and spaces for [action]. A [packageName] selects a single
     * package. Without one, the selection on the main screen determines the packages.
     */
    fun targetsFor(action: PackageAction, packageName: String? = null): List<ChangeTarget> {
        if (!readyForActions) return emptyList()
        val packageNames = if (packageName == null) selectedPackageNames else setOf(packageName)
        return snapshot?.targetsFor(action, selectedUserIds, packageNames).orEmpty()
    }

    /** Whether [packageName] can be selected for a batch action in any chosen space. */
    fun canSelectPackage(packageName: String): Boolean =
        PackageAction.entries.any { action -> targetsFor(action, packageName).isNotEmpty() }

    /** Returns the operator packages with an available action in the selected spaces. */
    fun selectablePackageNames(): Set<String> =
        snapshot?.packages.orEmpty()
            .filter { it.entry.carriers.isNotEmpty() && canSelectPackage(it.entry.packageName) }
            .mapTo(mutableSetOf()) { it.entry.packageName }
}

/** User interactions accepted by the cleaner store. */
sealed interface CleanerIntent {
    /** Rechecks Shizuku and starts a fresh scan when connected. */
    data object Refresh : CleanerIntent

    /** Opens the Shizuku authorization prompt. */
    data object RequestPermission : CleanerIntent

    /** Toggles the inclusion of the space identified by [userId]. */
    data class ToggleSpace(val userId: Int) : CleanerIntent

    /** Toggles [packageName] in the batch selection. */
    data class TogglePackage(val packageName: String) : CleanerIntent

    /** Selects every eligible operator package, or clears the selection if all are checked. */
    data object ToggleAllPackages : CleanerIntent

    /** Opens the details of [packageName]. */
    data class OpenDetails(val packageName: String) : CleanerIntent

    /** Previews [action] for [packageName], or the checked packages when it is null. */
    data class RequestAction(
        val action: PackageAction,
        val packageName: String? = null,
    ) : CleanerIntent

    /** Submits the targets shown in the confirmation sheet. */
    data object ConfirmAction : CleanerIntent

    /** Closes the current sheet. */
    data object DismissPanel : CleanerIntent
}
