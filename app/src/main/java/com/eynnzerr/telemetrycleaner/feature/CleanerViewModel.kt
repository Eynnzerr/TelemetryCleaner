package com.eynnzerr.telemetrycleaner.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eynnzerr.telemetrycleaner.data.ChangeOutcome
import com.eynnzerr.telemetrycleaner.data.CleanerRepository
import com.eynnzerr.telemetrycleaner.data.CleanerSettings
import com.eynnzerr.telemetrycleaner.data.ShizukuStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns the cleaner's message queue and runs effects produced by its pure reducer. */
internal class CleanerViewModel(
    private val repository: CleanerRepository,
    private val settings: CleanerSettings,
) : ViewModel() {
    /** Serializes intents, connection changes, and responses from running effects. */
    private val messages = Channel<CleanerMessage>(Channel.UNLIMITED)

    /** Holds the single state reduced by the message loop. */
    private val mutableState = MutableStateFlow(CleanerState())

    /** Screen state in the order in which messages have been reduced. */
    val state: StateFlow<CleanerState> = mutableState.asStateFlow()

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
            repository.connection.collect { status ->
                messages.send(CleanerMessage.ConnectionChanged(status))
            }
        }
        viewModelScope.launch {
            repository.automaticChanges.collect {
                messages.send(CleanerMessage.AutomaticChanges)
            }
        }
    }

    /** Queues an interaction for the reducer. */
    fun dispatch(intent: CleanerIntent) {
        messages.trySend(CleanerMessage.Intent(intent))
    }

    /** Schedules an effect and routes its result back into the message queue. */
    private fun execute(effect: CleanerEffect) {
        viewModelScope.launch {
            when (effect) {
                CleanerEffect.RefreshConnection -> checkConnection(repository::refreshConnection)
                CleanerEffect.RequestPermission -> checkConnection(repository::requestPermission)
                is CleanerEffect.Scan -> scan(effect.id)
                is CleanerEffect.Change -> change(effect)
            }
        }
    }

    /** Runs a Shizuku connection interaction on the main dispatcher. */
    private suspend fun checkConnection(action: () -> Unit) {
        try {
            action()
            if (mutableState.value.connection == ShizukuStatus.ERROR) {
                // A recovered call may leave the repository's observable status unchanged.
                messages.send(CleanerMessage.ConnectionChanged(repository.connection.value))
            }
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            messages.send(CleanerMessage.ConnectionChanged(ShizukuStatus.ERROR))
        }
    }

    /** Reads package states without blocking the message loop. */
    private suspend fun scan(id: Long) {
        val snapshot = try {
            repository.scan()
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
        messages.send(CleanerMessage.ScanFinished(id, snapshot, settings.readPreferences()))
    }

    /** Performs the confirmed mutation and reports each target's result. */
    private suspend fun change(effect: CleanerEffect.Change) {
        val (action, targets) = effect.confirmation
        val message = try {
            val outcomes = repository.change(targets, action)
            CleanerMessage.ChangeFinished(effect.id, action, outcomes, requestFailed = false)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            val outcomes = targets.map { target -> ChangeOutcome(target, success = false, message = null) }
            CleanerMessage.ChangeFinished(effect.id, action, outcomes, requestFailed = true)
        }
        messages.send(message)
    }

    /** Releases the repository's Shizuku listeners with the screen store. */
    override fun onCleared() {
        messages.close()
        repository.close()
    }
}
