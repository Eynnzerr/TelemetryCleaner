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
import com.eynnzerr.telemetrycleaner.data.CleanerRepository
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupCheck
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupCheckStatus
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupHistoryStore
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import com.eynnzerr.telemetrycleaner.data.preferences.label
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

/**
 * Serializes overlapping start, enable, and stop commands so only the latest request decides
 * whether monitoring runs, the service stops, or an enable save failure is reported.
 */
internal class AutoCleanupStartCoordinator(
    private val scope: CoroutineScope,
    private val settings: CleanerSettings,
    private val onStartMonitoring: suspend () -> Unit,
    private val onStopService: (startId: Int) -> Unit,
) {
    private var monitorJob: Job? = null
    private var requestJob: Job? = null
    private var latestGeneration: Long = 0L
    private var latestStartId: Int = 0

    val isMonitoring: Boolean
        get() = monitorJob?.isActive == true

    /** Reconciles monitoring and service lifecycle with the resolved preference for the latest generation. */
    private fun reconcile(shouldMonitor: Boolean, generation: Long) {
        if (latestGeneration != generation) return
        if (shouldMonitor) {
            if (monitorJob?.isActive != true) {
                monitorJob = scope.launch { onStartMonitoring() }
            }
        } else {
            monitorJob?.cancel()
            monitorJob = null
            settings.setAutoCleanupStatus(AutoCleanupStatus.OFF)
            onStopService(latestStartId)
        }
    }

    /** Handles an explicit ACTION_STOP command, superseding any pending start or enable request. */
    fun onStopRequest(startId: Int) {
        latestStartId = startId
        val generation = ++latestGeneration
        val previous = requestJob
        requestJob = scope.launch {
            previous?.join()
            if (latestGeneration != generation) return@launch
            val saved = settings.setAutoCleanupEnabled(false)
            if (latestGeneration != generation) return@launch
            val shouldMonitor = if (saved) {
                false
            } else {
                val stillEnabled = settings.readPreferences().autoCleanupEnabled
                if (latestGeneration != generation) return@launch
                if (stillEnabled) settings.reportAutoCleanupSaveFailure()
                stillEnabled
            }
            reconcile(shouldMonitor, generation)
        }
    }

    /**
     * Handles a normal start or explicit ACTION_ENABLE command.
     * Coalesced refreshes still advance [latestStartId] so subsequent stops match Android's latest delivered startId.
     */
    fun onStartRequest(enabling: Boolean, startId: Int) {
        latestStartId = startId
        if (!enabling) {
            if (monitorJob?.isActive == true || requestJob?.isActive == true) {
                return
            }
        }
        val generation = ++latestGeneration
        val previous = requestJob
        requestJob = scope.launch {
            previous?.join()
            if (latestGeneration != generation) return@launch
            val shouldMonitor = if (enabling) {
                val saved = settings.setAutoCleanupEnabled(true)
                if (latestGeneration != generation) return@launch
                if (saved) {
                    true
                } else {
                    val alreadyEnabled = settings.readPreferences().autoCleanupEnabled
                    if (latestGeneration != generation) return@launch
                    if (!alreadyEnabled) settings.reportAutoCleanupSaveFailure()
                    alreadyEnabled
                }
            } else {
                settings.readPreferences().autoCleanupEnabled
            }
            reconcile(shouldMonitor, generation)
        }
    }

    /** Stops the service with the latest delivered startId when the monitor observes persisted opt-out. */
    fun onMonitorObservedDisabled() {
        if (requestJob?.isActive == true) return
        settings.setAutoCleanupStatus(AutoCleanupStatus.OFF)
        onStopService(latestStartId)
    }
}

/** Periodically removes eligible carrier packages while automatic cleanup is enabled. */
class AutoCleanupService : Service() {
    /** Provides the saved opt-in, selected spaces, and status for the settings screen. */
    private val settings: CleanerSettings by inject()

    /** Shares persisted check and removal results with the history screen. */
    private val history: AutoCleanupHistoryStore by inject()

    /** Accesses the shared connection after foreground promotion. */
    private val repository: CleanerRepository by inject()

    /** Owns work and notification updates for this service instance. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tracks whether this service instance has already promoted to foreground. */
    private var promotedToForeground: Boolean = false

    /** Coordinates overlapping start, enable, and stop commands so one monitor owns execution. */
    private val coordinator: AutoCleanupStartCoordinator by lazy {
        AutoCleanupStartCoordinator(
            scope = serviceScope,
            settings = settings,
            onStartMonitoring = ::monitor,
            onStopService = ::stopSelf,
        )
    }

    /** The service is controlled by starts and stops from its own app. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Promotes the service before accessing the backend and honors the notification stop action. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            coordinator.onStopRequest(startId)
            return START_NOT_STICKY
        }
        val enabling = intent?.action == ACTION_ENABLE
        if (!promotedToForeground) {
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
                promotedToForeground = true
            } catch (_: IllegalStateException) {
                return stopAfterStartFailure()
            } catch (_: SecurityException) {
                return stopAfterStartFailure()
            }
        } else {
            if (enabling && settings.autoCleanupStatus.value == AutoCleanupStatus.OFF) {
                settings.setAutoCleanupStatus(AutoCleanupStatus.STARTING)
            }
            updateNotification(settings.autoCleanupStatus.value)
        }

        coordinator.onStartRequest(enabling = enabling, startId = startId)
        return START_STICKY
    }

    /** Cancels monitoring and clears the foreground notification. */
    override fun onDestroy() {
        serviceScope.cancel()
        promotedToForeground = false
        if (settings.autoCleanupStatus.value != AutoCleanupStatus.OFF) {
            settings.setAutoCleanupStatus(AutoCleanupStatus.START_FAILED)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Restarts the timer when the scope or backend changes. */
    private suspend fun monitor() {
        val cleaner = repository
        val cleanupPreferences = settings.preferences.distinctUntilChangedBy {
            it.autoCleanupEnabled to it.selectedUserSerials
        }
        combine(cleanupPreferences, cleaner.connection) { preferences, connection ->
            preferences to connection
        }.collectLatest { (preferences, connection) ->
            if (!preferences.autoCleanupEnabled) {
                coordinator.onMonitorObservedDisabled()
                return@collectLatest
            }
            while (currentCoroutineContext().isActive) {
                if (connection.status == PrivilegeStatus.READY) {
                    checkPackages(cleaner, connection.backend)
                } else {
                    publish(
                        if (connection.backend == PrivilegeBackend.ROOT) AutoCleanupStatus.WAITING_FOR_ROOT
                        else AutoCleanupStatus.WAITING_FOR_SHIZUKU,
                    )
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
    private suspend fun checkPackages(cleaner: CleanerRepository, backend: PrivilegeBackend) {
        if (settings.readPreferences().selectedUserSerials?.isEmpty() == true) {
            publish(AutoCleanupStatus.NO_SPACES)
            return
        }
        publish(AutoCleanupStatus.CHECKING)
        val resultStatus = try {
            val result = cleaner.autoCleanup(history, backend)
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
        /** Identifies a foreground opt-in start that must persist enabled state inside the service. */
        private const val ACTION_ENABLE = "com.eynnzerr.telemetrycleaner.ENABLE_AUTO_CLEANUP"

        /** Identifies the notification's explicit opt-out action. */
        private const val ACTION_STOP = "com.eynnzerr.telemetrycleaner.STOP_AUTO_CLEANUP"

        /** Identifies the user's notification preferences for automatic cleanup. */
        private const val CHANNEL_ID = "auto_cleanup"

        /** Replaces the same notification throughout the service's lifetime. */
        private const val NOTIFICATION_ID = 1

        /**
         * Immediately starts the foreground service from a foreground user confirmation so leaving
         * the Activity while preferences persist cannot drop the service startup.
         */
        @MainThread
        internal fun enableAndStart(
            context: Context,
            settings: CleanerSettings,
        ): Boolean {
            settings.setAutoCleanupStatus(AutoCleanupStatus.STARTING)
            return try {
                context.startForegroundService(
                    Intent(context, AutoCleanupService::class.java).setAction(ACTION_ENABLE),
                )
                true
            } catch (_: IllegalStateException) {
                settings.setAutoCleanupStatus(AutoCleanupStatus.START_FAILED)
                false
            } catch (_: SecurityException) {
                settings.setAutoCleanupStatus(AutoCleanupStatus.START_FAILED)
                false
            }
        }

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
