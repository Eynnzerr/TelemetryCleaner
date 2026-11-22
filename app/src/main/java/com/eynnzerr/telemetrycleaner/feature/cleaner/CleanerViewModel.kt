package com.eynnzerr.telemetrycleaner.feature.cleaner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eynnzerr.telemetrycleaner.data.ChangeOutcome
import com.eynnzerr.telemetrycleaner.data.CleanerRepository
import com.eynnzerr.telemetrycleaner.data.CustomPackageChange
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageScanIssue
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.packagemanager.UserListScanException
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Owns the cleaner's message queue and runs effects produced by its pure reducer. */
internal class CleanerViewModel(
    private val repository: CleanerRepository,
    private val settings: CleanerSettings,
) : ViewModel() {
    /** Serializes intents, connection changes, and responses from running effects. */
    private val messages = Channel<CleanerMessage>(Channel.UNLIMITED)

    /** Delivers one-shot failure notices without storing transient toasts in screen state. */
    private val eventChannel = Channel<CleanerUiEvent>(Channel.BUFFERED)

    /** One-shot UI events emitted by the cleaner store. */
    val events: Flow<CleanerUiEvent> = eventChannel.receiveAsFlow()

    /** Holds the single state reduced by the message loop. */
    private val mutableState = MutableStateFlow(CleanerState())

    /** Screen state in the order in which messages have been reduced. */
    val state: StateFlow<CleanerState> = mutableState.asStateFlow()

    /** Cancels superseded scans before they queue more package queries. */
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            for (message in messages) {
                val transition = CleanerReducer.reduce(mutableState.value, message)
                val scope = transition.scopeToSave
                if (scope != null && !settings.setSelectedUsers(scope)) continue
                mutableState.value = transition.state
                transition.effects.forEach(::execute)
            }
        }
        viewModelScope.launch {
            repository.connection.collect { connection ->
                messages.send(CleanerMessage.ConnectionChanged(connection))
            }
        }
        viewModelScope.launch {
            repository.automaticChanges.collect {
                messages.send(CleanerMessage.PackagesChanged)
            }
        }
        viewModelScope.launch {
            repository.customPackageChanges.collect { change ->
                val message = when (change) {
                    is CustomPackageChange.Added -> CleanerMessage.PackagesChanged
                    is CustomPackageChange.Removed -> CleanerMessage.CustomPackageRemoved(change.packageName)
                }
                messages.send(message)
            }
        }
    }

    /** Queues an interaction for the reducer. */
    fun dispatch(intent: CleanerIntent) {
        messages.trySend(CleanerMessage.Intent(intent))
    }

    /** Removes a custom package from the directory and reports storage failures as an event. */
    fun removeCustomPackage(packageName: String) {
        viewModelScope.launch {
            if (!repository.setCustomPackage(packageName, included = false)) {
                eventChannel.send(CleanerUiEvent.SaveFailed)
            }
        }
    }

    /** Schedules an effect and routes its result back into the message queue. */
    private fun execute(effect: CleanerEffect) {
        if (effect is CleanerEffect.Scan) scanJob?.cancel()
        val job = viewModelScope.launch {
            when (effect) {
                CleanerEffect.RefreshConnection -> checkConnection { repository.refreshConnection() }
                CleanerEffect.RequestPermission -> checkConnection(repository::requestPermission)
                is CleanerEffect.SelectBackend -> selectBackend(effect.backend)
                is CleanerEffect.Scan -> scan(effect.id)
                is CleanerEffect.Change -> change(effect)
            }
        }
        if (effect is CleanerEffect.Scan) scanJob = job
    }

    /** Runs a connection interaction and reports failures for the selected backend. */
    private suspend fun checkConnection(action: suspend () -> Unit) {
        try {
            action()
            if (mutableState.value.connection.status == PrivilegeStatus.ERROR) {
                // A recovered call may leave the repository's observable status unchanged.
                messages.send(CleanerMessage.ConnectionChanged(repository.connection.value))
            }
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            val connection = repository.connection.value.copy(status = PrivilegeStatus.ERROR)
            messages.send(CleanerMessage.ConnectionChanged(connection))
        }
    }

    /** Saves the user's backend choice and reports the resulting connection state. */
    private suspend fun selectBackend(backend: PrivilegeBackend) {
        val saved = try {
            repository.selectBackend(backend)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            false
        }
        messages.send(CleanerMessage.BackendSelectionFinished(saved, repository.connection.value))
    }

    /** Reads package states without blocking the message loop. */
    private suspend fun scan(id: Long) {
        val message = try {
            CleanerMessage.ScanFinished(id, repository.scan(), settings.readPreferences())
        } catch (failure: UserListScanException) {
            currentCoroutineContext().ensureActive()
            val error = when (failure.issue) {
                PackageScanIssue.QUERY_FAILED -> CleanerError.SCAN_SPACE_QUERY_FAILED
                PackageScanIssue.PERMISSION_DENIED -> CleanerError.SCAN_SPACE_PERMISSION_DENIED
                PackageScanIssue.UNRECOGNIZED_RESPONSE -> CleanerError.SCAN_SPACE_RESPONSE_INVALID
                PackageScanIssue.USER_STATE_MISSING -> CleanerError.SCAN_CURRENT_SPACE_MISSING
            }
            CleanerMessage.ScanFailed(id, error)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            CleanerMessage.ScanFailed(id, CleanerError.SCAN_FAILED)
        }
        messages.send(message)
    }

    /** Performs the confirmed mutation and reports each target's result. */
    private suspend fun change(effect: CleanerEffect.Change) {
        val (action, targets) = effect.confirmation
        val message = try {
            val disableOnUninstallFailure = action == PackageAction.UNINSTALL &&
                settings.readPreferences().disableOnUninstallFailure
            val outcomes = repository.change(targets, action, disableOnUninstallFailure, expectedBackend = effect.backend)
            CleanerMessage.ChangeFinished(effect.id, action, outcomes, requestFailed = false)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            val outcomes = targets.map { target -> ChangeOutcome(target, success = false, failure = null) }
            CleanerMessage.ChangeFinished(effect.id, action, outcomes, requestFailed = true)
        }
        messages.send(message)
    }

    /** Releases the screen's message queue. */
    override fun onCleared() {
        messages.close()
        eventChannel.close()
    }
}
