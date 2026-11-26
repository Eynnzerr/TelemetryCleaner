package com.eynnzerr.telemetrycleaner.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eynnzerr.telemetrycleaner.data.DhizukuStatus
import com.eynnzerr.telemetrycleaner.data.ReleaseCheckResult
import com.eynnzerr.telemetrycleaner.data.checkLatestRelease
import com.eynnzerr.telemetrycleaner.data.packagemanager.DhizukuController
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerPreferences
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import com.eynnzerr.telemetrycleaner.data.preferences.DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES
import com.eynnzerr.telemetrycleaner.data.preferences.ThemePalette
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable state backing the Settings screen and theme selection. */
internal data class SettingsUiState(
    val preferences: CleanerPreferences? = null,
    val autoCleanupStatus: AutoCleanupStatus = AutoCleanupStatus.OFF,
    val dhizukuStatus: DhizukuStatus = DhizukuStatus.MISSING,
    val checkingForUpdate: Boolean = false,
    val releaseResult: ReleaseCheckResult? = null,
) {
    val themePalette: ThemePalette
        get() = preferences?.themePalette ?: ThemePalette.SYSTEM

    val customPackageNames: Set<String>
        get() = preferences?.customPackageNames.orEmpty()

    val autoCleanupEnabled: Boolean?
        get() = preferences?.autoCleanupEnabled

    val autoCleanupIntervalMinutes: Int
        get() = preferences?.autoCleanupIntervalMinutes ?: DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES

    val dhizukuFallbackEnabled: Boolean?
        get() = preferences?.dhizukuFallbackEnabled
}

/** Platform side effects requested by [SettingsViewModel] that require an Activity context. */
internal sealed interface SettingsEvent {
    data object SaveFailed : SettingsEvent
    data object StopAutoCleanupService : SettingsEvent
}

/**
 * Owns settings mutations, release update checks, and foreground service toggle coordination
 * across configuration changes.
 */
internal class SettingsViewModel(
    private val settings: CleanerSettings,
    private val dhizuku: DhizukuController? = null,
) : ViewModel() {
    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)

    /**
     * Emits one-shot settings events to the active foreground collector.
     *
     * Background auto-cleanup save failures stay in [CleanerSettings.pendingAutoCleanupSaveFailure]
     * until collected in foreground, so a later successful persistence write clears any unshown failure before resume.
     */
    val events: Flow<SettingsEvent> = merge(
        eventChannel.receiveAsFlow(),
        settings.pendingAutoCleanupSaveFailure.mapNotNull { pending ->
            if (pending && settings.consumeAutoCleanupSaveFailure()) {
                SettingsEvent.SaveFailed
            } else {
                null
            }
        },
    )

    private val checkingForUpdate = MutableStateFlow(false)
    private val releaseResult = MutableStateFlow<ReleaseCheckResult?>(null)
    private val dhizukuStatusFlow: StateFlow<DhizukuStatus> =
        dhizuku?.status ?: MutableStateFlow(DhizukuStatus.MISSING)

    val state: StateFlow<SettingsUiState> = combine(
        settings.preferences,
        settings.autoCleanupStatus,
        dhizukuStatusFlow,
        checkingForUpdate,
        releaseResult,
    ) { preferences, status, dhizukuStatus, checking, release ->
        SettingsUiState(
            preferences = preferences,
            autoCleanupStatus = status,
            dhizukuStatus = dhizukuStatus,
            checkingForUpdate = checking,
            releaseResult = release,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            autoCleanupStatus = settings.autoCleanupStatus.value,
            dhizukuStatus = dhizukuStatusFlow.value,
        ),
    )

    /** Enables or disables the optional Dhizuku enhancement channel in Shizuku mode. */
    fun setDhizukuFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!settings.setDhizukuFallbackEnabled(enabled)) {
                eventChannel.send(SettingsEvent.SaveFailed)
                return@launch
            }
            if (enabled) {
                val currentStatus = dhizuku?.refreshStatus()
                if (currentStatus == DhizukuStatus.PERMISSION_REQUIRED) {
                    dhizuku?.requestPermission()
                }
            }
        }
    }

    /** Refreshes Dhizuku availability and authorization status without prompting. */
    fun refreshDhizukuStatus() {
        viewModelScope.launch {
            dhizuku?.refreshStatus()
        }
    }

    /** Triggers Dhizuku's permission request dialog when an active server awaits approval. */
    fun requestDhizukuPermission() {
        viewModelScope.launch {
            dhizuku?.requestPermission()
        }
    }

    /** Saves the interval used for subsequent automatic cleanup checks. */
    fun setAutoCleanupInterval(minutes: Int) {
        viewModelScope.launch {
            if (!settings.setAutoCleanupInterval(minutes)) {
                eventChannel.send(SettingsEvent.SaveFailed)
            }
        }
    }

    /** Saves the selected accent palette for all screens. */
    fun setThemePalette(palette: ThemePalette) {
        viewModelScope.launch {
            if (!settings.setThemePalette(palette)) {
                eventChannel.send(SettingsEvent.SaveFailed)
            }
        }
    }

    /** Persists opting out of automatic cleanup and requests stopping the foreground service. */
    fun disableAutoCleanup() {
        viewModelScope.launch {
            if (!settings.setAutoCleanupEnabled(false)) {
                eventChannel.send(SettingsEvent.SaveFailed)
                return@launch
            }
            settings.setAutoCleanupStatus(AutoCleanupStatus.OFF)
            eventChannel.send(SettingsEvent.StopAutoCleanupService)
        }
    }

    /** Queries GitHub Releases once at a time and retains the result across configuration changes. */
    fun checkForUpdate(repository: String, currentVersion: String) {
        if (checkingForUpdate.value) return
        checkingForUpdate.value = true
        viewModelScope.launch {
            try {
                releaseResult.value = checkLatestRelease(repository, currentVersion)
            } finally {
                checkingForUpdate.value = false
            }
        }
    }

    /** Clears the release-check dialog once dismissed or acted upon. */
    fun dismissReleaseDialog() {
        releaseResult.value = null
    }

    override fun onCleared() {
        eventChannel.close()
    }
}
