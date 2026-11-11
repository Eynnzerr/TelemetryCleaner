package com.eynnzerr.telemetrycleaner.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The locally recorded activity of automatic cleanup.
 *
 * @property lastCheck The most recent check, if the service has recorded one.
 * @property removals Individual removal outcomes, newest first.
 */
internal data class AutoCleanupHistory(
    val lastCheck: AutoCleanupCheck? = null,
    val removals: List<AutoCleanupRemoval> = emptyList(),
)

/**
 * The recorded result of an automatic check.
 *
 * @property timeMillis When the service finished the check, as epoch milliseconds.
 * @property status How the check ended.
 */
internal data class AutoCleanupCheck(val timeMillis: Long, val status: AutoCleanupCheckStatus)

/** The result of one automatic check across the selected Android spaces. */
internal enum class AutoCleanupCheckStatus {
    COMPLETE, INCOMPLETE, NO_SPACES, INTERRUPTED
}

/**
 * The recorded result of one automatic removal.
 *
 * @property timeMillis When the removal result was recorded, as epoch milliseconds.
 * @property packageName The exact package targeted by the service.
 * @property space The Android user ID and display name at the time of removal.
 * @property success Whether the service verified that the target was removed.
 */
internal data class AutoCleanupRemoval(
    val timeMillis: Long,
    val packageName: String,
    val space: AndroidUser,
    val success: Boolean,
)

/** Saves automatic check and removal history locally for the current Android user. */
internal class AutoCleanupHistoryStore(private val store: DataStore<Preferences>) {
    /** History available after process restart and as new events are recorded. */
    val history: Flow<AutoCleanupHistory> = store.data
        .map { preferences ->
            AutoCleanupHistory(
                lastCheck = readLastCheck(preferences[LAST_CHECK]),
                removals = readRemovals(preferences[REMOVALS]),
            )
        }
        .catch { failure ->
            if (failure !is IOException) throw failure
            Log.w(TAG, "Unable to read automatic cleanup history", failure)
            emit(AutoCleanupHistory())
        }
        .distinctUntilChanged()

    /** Replaces the last check while retaining all recent removal outcomes. */
    suspend fun recordCheck(check: AutoCleanupCheck) {
        val encoded = JSONObject()
            .put("time", check.timeMillis)
            .put("status", check.status.name)
        try {
            store.edit { preferences -> preferences[LAST_CHECK] = encoded.toString() }
        } catch (failure: IOException) {
            Log.w(TAG, "Unable to save automatic cleanup check", failure)
        }
    }

    /** Adds a removal outcome and keeps at most the 50 most recent outcomes. */
    suspend fun recordRemoval(removal: AutoCleanupRemoval) {
        try {
            store.edit { preferences ->
                val removals = (listOf(removal) + readRemovals(preferences[REMOVALS])).take(MAX_REMOVALS)
                val encoded = JSONArray()
                removals.forEach {
                    encoded.put(
                        JSONObject()
                            .put("time", it.timeMillis)
                            .put("package", it.packageName)
                            .put("spaceId", it.space.id)
                            .put("spaceName", it.space.name)
                            .put("success", it.success),
                    )
                }
                preferences[REMOVALS] = encoded.toString()
            }
        } catch (failure: IOException) {
            Log.w(TAG, "Unable to save automatic cleanup removal", failure)
        }
    }

    /** Reads the independently stored check, or returns null if it is unavailable. */
    private fun readLastCheck(stored: String?): AutoCleanupCheck? {
        if (stored == null) return null
        return try {
            val encoded = JSONObject(stored)
            val status = AutoCleanupCheckStatus.entries.firstOrNull { it.name == encoded.optString("status") }
                ?: return null
            AutoCleanupCheck(encoded.getLong("time"), status)
        } catch (_: JSONException) {
            null
        }
    }

    /** Restores available removal rows and skips incomplete entries. */
    private fun readRemovals(stored: String?): List<AutoCleanupRemoval> {
        if (stored == null) return emptyList()
        return try {
            val encoded = JSONArray(stored)
            (0 until encoded.length()).asSequence()
                .mapNotNull(encoded::optJSONObject)
                .mapNotNull(::readRemoval)
                .take(MAX_REMOVALS)
                .toList()
        } catch (_: JSONException) {
            emptyList()
        }
    }

    /** Decodes an individual outcome without affecting the other saved rows. */
    private fun readRemoval(encoded: JSONObject): AutoCleanupRemoval? = try {
        AutoCleanupRemoval(
            timeMillis = encoded.getLong("time"),
            packageName = encoded.getString("package"),
            space = AndroidUser(
                id = encoded.getInt("spaceId"),
                name = encoded.getString("spaceName"),
            ),
            success = encoded.getBoolean("success"),
        )
    } catch (_: JSONException) {
        null
    }

    private companion object {
        /** Identifies history persistence errors in the Android log. */
        const val TAG = "AutoCleanupHistory"

        /** Stores the latest automatic check as a JSON object. */
        val LAST_CHECK = stringPreferencesKey("auto_cleanup_last_check")

        /** Stores recent removal results as a JSON array. */
        val REMOVALS = stringPreferencesKey("auto_cleanup_removals")

        /** The on-device retention limit for individual removal outcomes. */
        const val MAX_REMOVALS = 50
    }
}
