package com.eynnzerr.telemetrycleaner.data.preferences

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
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
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
 * @property dhizukuFallbackEnabled Whether Shizuku mode may use an authorized Dhizuku Device Owner bridge as fallback.
 * @property privilegeBackend The authorization method to use for package operations.
 * @property customPackageNames Additional package names the user chose for manual management.
 */
internal data class CleanerPreferences(
    val autoCleanupEnabled: Boolean,
    val selectedUserSerials: Set<Long>?,
    val themePalette: ThemePalette = ThemePalette.SYSTEM,
    val autoCleanupIntervalMinutes: Int = DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES,
    val dhizukuFallbackEnabled: Boolean = false,
    val privilegeBackend: PrivilegeBackend = PrivilegeBackend.SHIZUKU,
    val customPackageNames: Set<String> = emptySet(),
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
    OFF, STARTING, WAITING_FOR_SHIZUKU, WAITING_FOR_ROOT, CHECKING, MONITORING, NO_SPACES, CHECK_FAILED, START_FAILED
}

/** Persists cleaner settings and exposes the live service status. */
internal class CleanerSettings(
    private val store: DataStore<Preferences>,
    private val logWarning: (String, Throwable) -> Unit = { message, failure -> Log.w(TAG, message, failure) },
) {
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
                dhizukuFallbackEnabled = saved[DHIZUKU_FALLBACK_ENABLED] ?: false,
                privilegeBackend = if (saved[PRIVILEGE_BACKEND] == PrivilegeBackend.ROOT.name) {
                    PrivilegeBackend.ROOT
                } else {
                    PrivilegeBackend.SHIZUKU
                },
                customPackageNames = saved[CUSTOM_PACKAGES].orEmpty() - PackageDirectory.variants.keys,
            )
        }
        .catch { failure ->
            if (failure !is IOException) throw failure
            logWarning("Unable to read cleaner settings", failure)
            emit(
                CleanerPreferences(
                    autoCleanupEnabled = false,
                    selectedUserSerials = emptySet(),
                ),
            )
        }
        .distinctUntilChanged()

    /** Holds the current status for settings UI and service launch coordination. */
    private val mutableAutoCleanupStatus = MutableStateFlow(AutoCleanupStatus.OFF)

    /** Reports runtime status independently from the persisted switch. */
    val autoCleanupStatus: StateFlow<AutoCleanupStatus> = mutableAutoCleanupStatus.asStateFlow()

    /** Holds an unacknowledged service auto-cleanup save failure until shown or superseded by success. */
    private val mutablePendingAutoCleanupSaveFailure = MutableStateFlow(false)

    /** True when persisting an explicit service enable or stop request failed and has not yet been shown or superseded. */
    val pendingAutoCleanupSaveFailure: StateFlow<Boolean> =
        mutablePendingAutoCleanupSaveFailure.asStateFlow()

    /** Reads the saved settings after any in-flight settings write. */
    suspend fun readPreferences(): CleanerPreferences = settingsAccess.withLock { preferences.first() }

    /** Reads the persisted auto-cleanup opt-in, or null if storage itself cannot be read. */
    suspend fun readPersistedAutoCleanupEnabled(): Boolean? = settingsAccess.withLock {
        try {
            store.data.first()[AUTO_CLEANUP_ENABLED] ?: false
        } catch (failure: IOException) {
            logWarning("Unable to read cleaner settings", failure)
            null
        }
    }

    /** Saves the user's choice and reports whether the write succeeded. */
    suspend fun setAutoCleanupEnabled(enabled: Boolean): Boolean =
        save { it[AUTO_CLEANUP_ENABLED] = enabled }.also { saved ->
            if (saved) clearAutoCleanupSaveFailure()
        }

    /** Saves whether Shizuku mode may fall back to Dhizuku for protected main-space packages. */
    suspend fun setDhizukuFallbackEnabled(enabled: Boolean): Boolean =
        save { it[DHIZUKU_FALLBACK_ENABLED] = enabled }

    /** Saves the backend selected by the user. */
    suspend fun setPrivilegeBackend(backend: PrivilegeBackend): Boolean =
        save { it[PRIVILEGE_BACKEND] = backend.name }

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

    /** Adds or removes one user-selected package without overwriting other directory changes. */
    suspend fun setCustomPackage(packageName: String, included: Boolean): Boolean = save {
        val names = it[CUSTOM_PACKAGES].orEmpty()
        it[CUSTOM_PACKAGES] = if (included) names + packageName else names - packageName
    }

    /** Saves the color palette and reports whether the write succeeded. */
    suspend fun setThemePalette(palette: ThemePalette): Boolean =
        save { it[THEME_PALETTE] = palette.name }

    /** Publishes the current service status to the settings screen. */
    @MainThread
    fun setAutoCleanupStatus(status: AutoCleanupStatus) {
        mutableAutoCleanupStatus.value = status
    }

    /** Records that persisting an explicit service enable or stop request failed. */
    fun reportAutoCleanupSaveFailure() {
        mutablePendingAutoCleanupSaveFailure.value = true
    }

    /** Atomically consumes a pending service save failure once delivered to the foreground UI. */
    fun consumeAutoCleanupSaveFailure(): Boolean =
        mutablePendingAutoCleanupSaveFailure.compareAndSet(expect = true, update = false)

    /** Clears any unshown service save failure after a newer persistence change succeeds. */
    fun clearAutoCleanupSaveFailure() {
        mutablePendingAutoCleanupSaveFailure.value = false
    }

    /** Writes one settings change before allowing the next cleanup submission to read it. */
    private suspend fun save(change: (MutablePreferences) -> Unit): Boolean = settingsAccess.withLock {
        try {
            store.edit { change(it) }
            true
        } catch (failure: IOException) {
            logWarning("Unable to save cleaner settings", failure)
            false
        }
    }

    private companion object {
        /** Identifies settings persistence errors in the Android log. */
        const val TAG = "CleanerSettings"

        /** The user's opt-in to automatic removal. */
        val AUTO_CLEANUP_ENABLED = booleanPreferencesKey("auto_cleanup_enabled")

        /** Whether Shizuku mode may fall back to Dhizuku for protected main-space packages. */
        val DHIZUKU_FALLBACK_ENABLED = booleanPreferencesKey("dhizuku_fallback_enabled")

        /** The selected authorization method. */
        val PRIVILEGE_BACKEND = stringPreferencesKey("privilege_backend")

        /** The user's automatic check interval in minutes. */
        val AUTO_CLEANUP_INTERVAL_MINUTES = intPreferencesKey("auto_cleanup_interval_minutes")

        /** Serial numbers of explicitly selected spaces. */
        val SELECTED_USER_SERIALS = stringSetPreferencesKey("selected_user_serials")

        /** User-added package names, stored independently from the built-in automatic cleanup list. */
        val CUSTOM_PACKAGES = stringSetPreferencesKey("custom_packages")

        /** The name of the selected color palette. */
        val THEME_PALETTE = stringPreferencesKey("theme_palette")
    }
}
