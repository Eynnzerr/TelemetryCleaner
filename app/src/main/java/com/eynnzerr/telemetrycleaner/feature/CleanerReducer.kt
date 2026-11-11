package com.eynnzerr.telemetrycleaner.feature

import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.ChangeOutcome
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.CleanerPreferences
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.data.ShizukuStatus

/** Events in the cleaner's serialized message loop. */
internal sealed interface CleanerMessage {
    /** Interaction dispatched from the screen. */
    data class Intent(val value: CleanerIntent) : CleanerMessage

    /** Connection status reported by Shizuku. */
    data class ConnectionChanged(val status: ShizukuStatus) : CleanerMessage

    /** The scan with [id] completed; [preferences] defines the current operation scope. */
    data class ScanFinished(
        val id: Long,
        val snapshot: ScanSnapshot,
        val preferences: CleanerPreferences,
    ) : CleanerMessage

    /** The scan with [id] failed for the given [error]. */
    data class ScanFailed(val id: Long, val error: CleanerError) : CleanerMessage

    /** A background cleanup may have changed one or more package states. */
    data object AutomaticChanges : CleanerMessage

    /** The operation with [id] completed with the provided target results. */
    data class ChangeFinished(
        val id: Long,
        val action: PackageAction,
        val outcomes: List<ChangeOutcome>,
        val requestFailed: Boolean,
    ) : CleanerMessage
}

/** Side effects requested by the reducer and executed by the store. */
internal sealed interface CleanerEffect {
    /** Rechecks the availability of Shizuku. */
    data object RefreshConnection : CleanerEffect

    /** Requests authorization from Shizuku. */
    data object RequestPermission : CleanerEffect

    /** Queries current package and user state. */
    data class Scan(val id: Long) : CleanerEffect

    /** Runs the confirmed package operation. */
    data class Change(val id: Long, val confirmation: CleanerPanel.Confirmation) : CleanerEffect
}

/**
 * The state and effects produced by processing one message.
 *
 * @property scopeToSave Explicit space selection to persist before publishing the new state.
 */
internal data class CleanerTransition(
    val state: CleanerState,
    val effects: List<CleanerEffect> = emptyList(),
    val scopeToSave: Set<Long>? = null,
)

/** Computes screen transitions without accessing Shizuku or performing I/O. */
internal object CleanerReducer {
    /** Reduces one user intent or work result against the latest [state]. */
    fun reduce(state: CleanerState, message: CleanerMessage): CleanerTransition = when (message) {
        is CleanerMessage.Intent -> reduceIntent(state, message.value)
        is CleanerMessage.ConnectionChanged -> connectionChanged(state, message.status)
        is CleanerMessage.ScanFinished -> scanFinished(state, message)
        is CleanerMessage.ScanFailed -> CleanerTransition(
            if (state.isCurrentScan(message.id)) state.copy(activity = CleanerActivity.IDLE, error = message.error)
            else state,
        )
        CleanerMessage.AutomaticChanges -> {
            if (state.connection == ShizukuStatus.READY && state.activity != CleanerActivity.CHANGING) {
                startScan(state)
            } else {
                CleanerTransition(state)
            }
        }
        is CleanerMessage.ChangeFinished -> changeFinished(state, message)
    }

    /** Translates user actions into a new state and any required work. */
    private fun reduceIntent(state: CleanerState, intent: CleanerIntent): CleanerTransition =
        when (intent) {
            CleanerIntent.Refresh -> refresh(state)
            CleanerIntent.RequestPermission -> CleanerTransition(
                state,
                if (state.connection == ShizukuStatus.PERMISSION_REQUIRED) {
                    listOf(CleanerEffect.RequestPermission)
                } else {
                    emptyList()
                },
            )
            is CleanerIntent.ToggleSpace -> toggleSpace(state, intent.userId)
            is CleanerIntent.TogglePackage -> togglePackage(state, intent.packageName)
            CleanerIntent.ToggleAllPackages -> toggleAllPackages(state)
            is CleanerIntent.OpenDetails -> openDetails(state, intent.packageName)
            is CleanerIntent.RequestAction -> requestAction(state, intent)
            CleanerIntent.ConfirmAction -> confirmAction(state)
            CleanerIntent.DismissPanel -> CleanerTransition(state.copy(panel = null))
        }

    /** Rechecks the service and refreshes an already connected screen. */
    private fun refresh(state: CleanerState): CleanerTransition {
        if (state.connection != ShizukuStatus.READY) {
            return CleanerTransition(state, listOf(CleanerEffect.RefreshConnection))
        }
        if (state.activity != CleanerActivity.IDLE) return CleanerTransition(state)
        val transition = startScan(state)
        return transition.copy(effects = listOf(CleanerEffect.RefreshConnection) + transition.effects)
    }

    /** Invalidates scans and unsubmitted confirmations from an earlier connection. */
    private fun connectionChanged(state: CleanerState, status: ShizukuStatus): CleanerTransition {
        if (state.connection == status) return CleanerTransition(state)
        val connectedState = state.copy(connection = status)
        if (status == ShizukuStatus.READY) {
            return if (state.activity == CleanerActivity.CHANGING) {
                CleanerTransition(connectedState)
            } else {
                startScan(connectedState)
            }
        }

        val withoutConfirmation = connectedState.copy(panel = connectedState.panel.withoutConfirmation())
        if (state.activity != CleanerActivity.SCANNING) return CleanerTransition(withoutConfirmation)
        return CleanerTransition(
            withoutConfirmation.copy(activity = CleanerActivity.IDLE, workId = state.workId + 1),
        )
    }

    /** Starts a scan and closes any action preview based on the previous scan. */
    private fun startScan(state: CleanerState): CleanerTransition {
        val id = state.workId + 1
        return CleanerTransition(
            state.copy(
                activity = CleanerActivity.SCANNING,
                panel = state.panel.withoutConfirmation(),
                error = null,
                workId = id,
            ),
            listOf(CleanerEffect.Scan(id)),
        )
    }

    /** Replaces observed state and reconciles the user's selection. */
    private fun scanFinished(
        state: CleanerState,
        message: CleanerMessage.ScanFinished,
    ): CleanerTransition {
        if (!state.isCurrentScan(message.id)) return CleanerTransition(state)

        val snapshot = message.snapshot
        val panel = state.panel
        val updated = state.copy(
            snapshot = snapshot,
            selectedUserIds = message.preferences.selectedUsers(snapshot),
            activity = CleanerActivity.IDLE,
            panel = if (
                panel is CleanerPanel.Details &&
                snapshot.packages.none { it.entry.packageName == panel.packageName }
            ) {
                null
            } else {
                panel
            },
            error = null,
        )
        return CleanerTransition(updated.retainEligiblePackages())
    }

    /** Checks that the reported scan still matches the in-flight request. */
    private fun CleanerState.isCurrentScan(id: Long): Boolean =
        activity == CleanerActivity.SCANNING && workId == id

    /** Accepts a selection change only for a user known in the current scan. */
    private fun toggleSpace(state: CleanerState, userId: Int): CleanerTransition {
        val users = state.snapshot?.users.orEmpty()
        val user = users.firstOrNull { it.id == userId } ?: return CleanerTransition(state)
        if (state.activity != CleanerActivity.IDLE ||
            (user.serialNumber == null && userId !in state.selectedUserIds)
        ) {
            return CleanerTransition(state)
        }
        val checkedIds = state.selectedUserIds.toggled(userId)
        val selectedUsers = users.filter { it.id in checkedIds && it.serialNumber != null }
        return CleanerTransition(
            state.copy(
                selectedUserIds = selectedUsers.mapTo(mutableSetOf(), AndroidUser::id),
                panel = state.panel.withoutConfirmation(),
            ).retainEligiblePackages(),
            scopeToSave = selectedUsers.mapNotNull(AndroidUser::serialNumber).toSet(),
        )
    }

    /** Adds or removes a package from the batch selection. */
    private fun togglePackage(state: CleanerState, packageName: String): CleanerTransition {
        if (state.panel != null || !state.canSelectPackage(packageName)) return CleanerTransition(state)
        return CleanerTransition(
            state.copy(selectedPackageNames = state.selectedPackageNames.toggled(packageName)),
        )
    }

    /** Updates all eligible checkboxes together using the current scan and space selection. */
    private fun toggleAllPackages(state: CleanerState): CleanerTransition {
        val eligible = state.selectablePackageNames()
        if (state.panel != null || eligible.isEmpty()) return CleanerTransition(state)
        val selected = if (state.selectedPackageNames.containsAll(eligible)) emptySet() else eligible
        return CleanerTransition(state.copy(selectedPackageNames = selected))
    }

    /** Shows details for a package identified by the scan. */
    private fun openDetails(state: CleanerState, packageName: String): CleanerTransition =
        if (state.snapshot?.packages?.any { it.entry.packageName == packageName } == true) {
            CleanerTransition(state.copy(panel = CleanerPanel.Details(packageName)))
        } else {
            CleanerTransition(state)
        }

    /** Resolves an action preview to the affected package and user pairs. */
    private fun requestAction(
        state: CleanerState,
        intent: CleanerIntent.RequestAction,
    ): CleanerTransition {
        val targets = state.targetsFor(intent.action, intent.packageName)
        if (targets.isEmpty()) return CleanerTransition(state)
        return CleanerTransition(
            state.copy(panel = CleanerPanel.Confirmation(intent.action, targets)),
        )
    }

    /** Executes only the action the user just reviewed. */
    private fun confirmAction(state: CleanerState): CleanerTransition {
        val confirmation = state.panel as? CleanerPanel.Confirmation ?: return CleanerTransition(state)
        if (!state.readyForActions) return CleanerTransition(state.copy(panel = null))
        val id = state.workId + 1
        return CleanerTransition(
            state.copy(
                panel = null,
                activity = CleanerActivity.CHANGING,
                snapshot = state.snapshot?.invalidate(confirmation.targets),
                selectedPackageNames = emptySet(),
                workId = id,
            ),
            listOf(CleanerEffect.Change(id, confirmation)),
        )
    }

    /** Presents per-target outcomes and starts a post-operation scan when possible. */
    private fun changeFinished(
        state: CleanerState,
        message: CleanerMessage.ChangeFinished,
    ): CleanerTransition {
        if (state.activity != CleanerActivity.CHANGING || state.workId != message.id) {
            return CleanerTransition(state)
        }
        val finished = state.copy(
            panel = CleanerPanel.Results(message.action, message.outcomes),
            activity = CleanerActivity.IDLE,
            error = if (message.requestFailed) CleanerError.ACTION_FAILED else null,
        )
        return if (state.connection == ShizukuStatus.READY) {
            startScan(finished)
        } else {
            CleanerTransition(finished)
        }
    }

    /** Retains only selections that can still be managed in the selected spaces. */
    private fun CleanerState.retainEligiblePackages(): CleanerState =
        copy(selectedPackageNames = selectedPackageNames.filterTo(mutableSetOf()) { canSelectPackage(it) })

    /** Keeps unaffected observations while marking changed states for verification. */
    private fun ScanSnapshot.invalidate(targets: List<ChangeTarget>): ScanSnapshot {
        val usersByPackage = targets.groupBy(ChangeTarget::packageName, ChangeTarget::userId)
        return copy(
            packages = packages.map { detected ->
                val affectedUsers = usersByPackage[detected.entry.packageName].orEmpty()
                if (affectedUsers.isEmpty()) {
                    detected
                } else {
                    detected.copy(users = detected.users + affectedUsers.associateWith { PackageState.UNKNOWN })
                }
            },
        )
    }

    /** Invalidates an action preview while leaving read-only sheets available. */
    private fun CleanerPanel?.withoutConfirmation(): CleanerPanel? =
        if (this is CleanerPanel.Confirmation) null else this

    /** Returns a new set with [value] added or removed. */
    private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value
}
