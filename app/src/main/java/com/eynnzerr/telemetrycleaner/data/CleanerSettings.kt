package com.eynnzerr.telemetrycleaner.data

import android.util.Log
import androidx.annotation.MainThread
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Color palettes the user can apply to the app. */
enum class ThemePalette { SYSTEM, TEAL, BLUE, PURPLE, AMBER, GREEN, CYAN, ROSE, ORANGE, SLATE }

/** The cleanup cadence for users who have not chosen an interval. */
internal const val DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES = 30

/**
 * Device-local settings shared by the cleaner and foreground service.
 *
 * @property autoCleanupEnabled Whether the user opted into background removal.
 * @property selectedUserSerials Explicitly selected profile serials, or null for the app's current space.
 * @property themePalette The saved color palette for the app.
 * @property autoCleanupIntervalMinutes The number of minutes between automatic checks.
 * @property disableOnUninstallFailure Whether a failed manual uninstall may fall back to disabling the package.
 */
internal data class CleanerPreferences(
    val autoCleanupEnabled: Boolean,
    val selectedUserSerials: Set<Long>?,
    val themePalette: ThemePalette = ThemePalette.SYSTEM,
    val autoCleanupIntervalMinutes: Int = DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES,
    val disableOnUninstallFailure: Boolean = true,
) {
    /** Defaults to the current user only until the user explicitly picks a scope. */
    fun selectedUsers(snapshot: ScanSnapshot): Set<Int> =
        snapshot.users.filter { includes(it, snapshot.currentUserId) }.mapTo(mutableSetOf(), AndroidUser::id)

    /** Matches persisted spaces by serial so a recycled Android user ID does not inherit an opt-in. */
    fun includes(user: AndroidUser, currentUserId: Int): Boolean {
        val serials = selectedUserSerials ?: return user.id == currentUserId
        return user.serialNumber?.let { it in serials } == true
    }
}

/** The foreground service's current availability and most recent check state. */
internal enum class AutoCleanupStatus {
    OFF, STARTING, WAITING_FOR_SHIZUKU, CHECKING, MONITORING, NO_SPACES, CHECK_FAILED, START_FAILED
}

/** Persists cleaner settings and exposes the live service status. */
internal class CleanerSettings(private val store: DataStore<Preferences>) {
    /** Makes a cleanup submission wait for any in-flight settings write. */
    private val settingsAccess = Mutex()

    /** Emits changes to cleaner settings independently of cleanup history. */
    val preferences: Flow<CleanerPreferences> = store.data
        .map { saved ->
            CleanerPreferences(
                autoCleanupEnabled = saved[AUTO_CLEANUP_ENABLED] ?: false,
                selectedUserSerials = saved[SELECTED_USER_SERIALS]?.mapNotNull(String::toLongOrNull)?.toSet(),
                themePalette = ThemePalette.entries.firstOrNull { it.name == saved[THEME_PALETTE] }
                    ?: ThemePalette.SYSTEM,
                autoCleanupIntervalMinutes = saved[AUTO_CLEANUP_INTERVAL_MINUTES]?.takeIf { it > 0 }
                    ?: DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES,
                disableOnUninstallFailure = saved[DISABLE_ON_UNINSTALL_FAILURE] ?: true,
            )
        }
        .catch { failure ->
            if (failure !is IOException) throw failure
            Log.w(TAG, "Unable to read cleaner settings", failure)
            emit(
                CleanerPreferences(
                    autoCleanupEnabled = false,
                    selectedUserSerials = emptySet(),
                    disableOnUninstallFailure = false,
                ),
            )
        }
        .distinctUntilChanged()

    /** Holds the current status for settings UI and service launch coordination. */
    private val mutableAutoCleanupStatus = MutableStateFlow(AutoCleanupStatus.OFF)

    /** Reports runtime status independently from the persisted switch. */
    val autoCleanupStatus: StateFlow<AutoCleanupStatus> = mutableAutoCleanupStatus.asStateFlow()

    /** Reads the saved settings after any in-flight settings write. */
    suspend fun readPreferences(): CleanerPreferences = settingsAccess.withLock { preferences.first() }

    /** Saves the user's choice and reports whether the write succeeded. */
    suspend fun setAutoCleanupEnabled(enabled: Boolean): Boolean =
        save { it[AUTO_CLEANUP_ENABLED] = enabled }

    /** Saves the preference for disabling a package after a failed manual uninstall. */
    suspend fun setDisableOnUninstallFailure(enabled: Boolean): Boolean =
        save { it[DISABLE_ON_UNINSTALL_FAILURE] = enabled }

    /** Saves a positive interval in whole minutes when automatic cleanup is enabled. */
    suspend fun setAutoCleanupInterval(minutes: Int): Boolean {
        if (minutes <= 0) return false
        var accepted = false
        val saved = save { preferences ->
            if (preferences[AUTO_CLEANUP_ENABLED] == true) {
                preferences[AUTO_CLEANUP_INTERVAL_MINUTES] = minutes
                accepted = true
            }
        }
        return saved && accepted
    }

    /** Saves an explicit set of spaces, including the choice to select none. */
    suspend fun setSelectedUsers(serials: Set<Long>): Boolean =
        save { it[SELECTED_USER_SERIALS] = serials.mapTo(mutableSetOf(), Long::toString) }

    /** Saves the color palette and reports whether the write succeeded. */
    suspend fun setThemePalette(palette: ThemePalette): Boolean =
        save { it[THEME_PALETTE] = palette.name }

    /** Publishes the current service status to the settings screen. */
    @MainThread
    fun setAutoCleanupStatus(status: AutoCleanupStatus) {
        mutableAutoCleanupStatus.value = status
    }

    /** Writes one settings change before allowing the next cleanup submission to read it. */
    private suspend fun save(change: (MutablePreferences) -> Unit): Boolean = settingsAccess.withLock {
        try {
            store.edit { change(it) }
            true
        } catch (failure: IOException) {
            Log.w(TAG, "Unable to save cleaner settings", failure)
            false
        }
    }

    private companion object {
        /** Identifies settings persistence errors in the Android log. */
        const val TAG = "CleanerSettings"

        /** The user's opt-in to automatic removal. */
        val AUTO_CLEANUP_ENABLED = booleanPreferencesKey("auto_cleanup_enabled")

        /** Whether to try disabling a package when manual uninstall fails. */
        val DISABLE_ON_UNINSTALL_FAILURE = booleanPreferencesKey("disable_on_uninstall_failure")

        /** The user's automatic check interval in minutes. */
        val AUTO_CLEANUP_INTERVAL_MINUTES = intPreferencesKey("auto_cleanup_interval_minutes")

        /** Serial numbers of explicitly selected spaces. */
        val SELECTED_USER_SERIALS = stringSetPreferencesKey("selected_user_serials")

        /** The name of the selected color palette. */
        val THEME_PALETTE = stringPreferencesKey("theme_palette")
    }
}
