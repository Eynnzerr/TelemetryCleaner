package com.eynnzerr.telemetrycleaner.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.eynnzerr.telemetrycleaner.MainActivity
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AutoCleanupCheck
import com.eynnzerr.telemetrycleaner.data.AutoCleanupCheckStatus
import com.eynnzerr.telemetrycleaner.data.AutoCleanupHistoryStore
import com.eynnzerr.telemetrycleaner.data.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.CleanerRepository
import com.eynnzerr.telemetrycleaner.data.CleanerSettings
import com.eynnzerr.telemetrycleaner.data.ShizukuStatus
import com.eynnzerr.telemetrycleaner.ui.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject

/** Periodically removes eligible carrier packages while automatic cleanup is enabled. */
class AutoCleanupService : Service() {
    /** Provides the saved opt-in, selected spaces, and status for the settings screen. */
    private val settings: CleanerSettings by inject()

    /** Shares persisted check and removal results with the history screen. */
    private val history: AutoCleanupHistoryStore by inject()

    /** Lazily creates service-owned Shizuku listeners after foreground promotion. */
    private val repository = inject<CleanerRepository>()

    /** Owns work and notification updates for this service instance. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Ensures repeated start requests share one monitor. */
    private var monitorJob: Job? = null

    /** The service is controlled by starts and stops from its own app. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Promotes the service before opening Shizuku and honors the notification stop action. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                if (settings.setAutoCleanupEnabled(false)) {
                    settings.setAutoCleanupStatus(AutoCleanupStatus.OFF)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        if (monitorJob?.isActive == true) {
            updateNotification(settings.autoCleanupStatus.value)
            return START_STICKY
        }

        settings.setAutoCleanupStatus(AutoCleanupStatus.STARTING)
        createNotificationChannel()
        try {
            // Android 10–13 have no special-use foreground service type.
            @Suppress("DEPRECATION")
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(AutoCleanupStatus.STARTING), serviceType)
        } catch (_: IllegalStateException) {
            return stopAfterStartFailure()
        } catch (_: SecurityException) {
            return stopAfterStartFailure()
        }

        monitorJob = serviceScope.launch {
            if (settings.readPreferences().autoCleanupEnabled) {
                monitor()
            } else {
                settings.setAutoCleanupStatus(AutoCleanupStatus.OFF)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /** Releases the Shizuku listeners and clears the foreground notification. */
    override fun onDestroy() {
        serviceScope.cancel()
        if (repository.isInitialized()) repository.value.close()
        if (settings.autoCleanupStatus.value != AutoCleanupStatus.OFF) {
            settings.setAutoCleanupStatus(AutoCleanupStatus.START_FAILED)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Restarts the timer when the user changes scope or Shizuku becomes ready. */
    private suspend fun monitor() {
        val cleaner = repository.value
        val cleanupPreferences = settings.preferences.distinctUntilChangedBy {
            it.autoCleanupEnabled to it.selectedUserSerials
        }
        combine(cleanupPreferences, cleaner.connection) { preferences, connection ->
            preferences to connection
        }.collectLatest { (preferences, connection) ->
            if (!preferences.autoCleanupEnabled) {
                settings.setAutoCleanupStatus(AutoCleanupStatus.OFF)
                stopSelf()
                return@collectLatest
            }
            while (currentCoroutineContext().isActive) {
                if (connection == ShizukuStatus.READY) {
                    checkPackages(cleaner)
                } else {
                    publish(AutoCleanupStatus.WAITING_FOR_SHIZUKU)
                }
                if (!awaitNextCheck()) return@collectLatest
                cleaner.refreshConnection()
                if (cleaner.connection.value != connection) return@collectLatest
            }
        }
    }

    /** Applies interval edits from the end of the last check and returns true when the next check is due. */
    private suspend fun awaitNextCheck(): Boolean {
        val completedAt = SystemClock.elapsedRealtime()
        while (true) {
            val preferences = settings.readPreferences()
            if (!preferences.autoCleanupEnabled) return false
            val intervalMinutes = preferences.autoCleanupIntervalMinutes
            val remaining = intervalMinutes * 60_000L - (SystemClock.elapsedRealtime() - completedAt)
            if (remaining <= 0) return true

            // Recalculate from the same completion time after a timer or preference change.
            withTimeoutOrNull(remaining) {
                settings.preferences.first {
                    !it.autoCleanupEnabled || it.autoCleanupIntervalMinutes != intervalMinutes
                }
            }
        }
    }

    /** Runs one attempt and reports whether scanning or a target change failed. */
    private suspend fun checkPackages(cleaner: CleanerRepository) {
        if (settings.readPreferences().selectedUserSerials?.isEmpty() == true) {
            publish(AutoCleanupStatus.NO_SPACES)
            return
        }
        publish(AutoCleanupStatus.CHECKING)
        val resultStatus = try {
            val result = cleaner.autoCleanup(settings, history)
            currentCoroutineContext().ensureActive()
            when {
                !result.hasSpaces -> AutoCleanupCheckStatus.NO_SPACES
                result.hasUnverifiedPackages || result.outcomes.any { !it.success } -> AutoCleanupCheckStatus.INCOMPLETE
                else -> AutoCleanupCheckStatus.COMPLETE
            }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                history.recordCheck(AutoCleanupCheck(System.currentTimeMillis(), AutoCleanupCheckStatus.INTERRUPTED))
            }
            throw cancellation
        } catch (_: Exception) {
            AutoCleanupCheckStatus.INCOMPLETE
        }
        withContext(NonCancellable) {
            history.recordCheck(AutoCleanupCheck(System.currentTimeMillis(), resultStatus))
        }
        publish(
            when (resultStatus) {
                AutoCleanupCheckStatus.NO_SPACES -> AutoCleanupStatus.NO_SPACES
                AutoCleanupCheckStatus.COMPLETE -> AutoCleanupStatus.MONITORING
                AutoCleanupCheckStatus.INCOMPLETE, AutoCleanupCheckStatus.INTERRUPTED -> AutoCleanupStatus.CHECK_FAILED
            },
        )
    }

    /** Creates the low-importance channel used for the service's persistent notification. */
    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.auto_cleanup_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** Shows the runtime status and gives the user a direct way to stop cleanup. */
    private fun notification(status: AutoCleanupStatus): Notification {
        val openApp = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val stop = Intent(this, AutoCleanupService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.auto_cleanup_notification_title))
            .setContentText(getString(status.label()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(this, 0, openApp, flags))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.auto_cleanup_stop),
                PendingIntent.getService(this, 0, stop, flags),
            )
            .build()
    }

    /** Keeps settings and the visible notification in sync with the monitor. */
    private suspend fun publish(status: AutoCleanupStatus) {
        if (settings.autoCleanupStatus.value == status || !settings.readPreferences().autoCleanupEnabled) return
        settings.setAutoCleanupStatus(status)
        updateNotification(status)
    }

    /** Posts the latest service status when the app can show drawer notifications. */
    private fun updateNotification(status: AutoCleanupStatus) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        }
    }

    /** Leaves the opt-in saved so the app can retry when Android allows a start. */
    private fun stopAfterStartFailure(): Int {
        settings.setAutoCleanupStatus(AutoCleanupStatus.START_FAILED)
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        /** Identifies the notification's explicit opt-out action. */
        private const val ACTION_STOP = "com.eynnzerr.telemetrycleaner.STOP_AUTO_CLEANUP"

        /** Identifies the user's notification preferences for automatic cleanup. */
        private const val CHANNEL_ID = "auto_cleanup"

        /** Replaces the same notification throughout the service's lifetime. */
        private const val NOTIFICATION_ID = 1

        /** Starts the monitor when opted in, or refreshes its notification after permission is granted. */
        @MainThread
        internal suspend fun startIfEnabled(
            context: Context,
            settings: CleanerSettings,
            refreshNotification: Boolean = false,
        ) {
            if (!settings.readPreferences().autoCleanupEnabled) return
            val needsStart = when (settings.autoCleanupStatus.value) {
                AutoCleanupStatus.OFF, AutoCleanupStatus.STARTING, AutoCleanupStatus.START_FAILED -> true
                else -> false
            }
            if (!needsStart && !refreshNotification) return
            if (needsStart) settings.setAutoCleanupStatus(AutoCleanupStatus.STARTING)
            try {
                context.startForegroundService(Intent(context, AutoCleanupService::class.java))
            } catch (_: IllegalStateException) {
                if (needsStart) settings.setAutoCleanupStatus(AutoCleanupStatus.START_FAILED)
            } catch (_: SecurityException) {
                if (needsStart) settings.setAutoCleanupStatus(AutoCleanupStatus.START_FAILED)
            }
        }
    }
}
