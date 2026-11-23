package com.eynnzerr.telemetrycleaner.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupHistory
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupHistoryStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Exposes persisted automatic cleanup check and removal history to the history screen. */
internal class AutoCleanupHistoryViewModel(
    historyStore: AutoCleanupHistoryStore,
) : ViewModel() {
    val history: StateFlow<AutoCleanupHistory> = historyStore.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AutoCleanupHistory(),
    )
}
