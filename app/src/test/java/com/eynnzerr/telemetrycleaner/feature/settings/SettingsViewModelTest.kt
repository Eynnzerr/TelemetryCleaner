package com.eynnzerr.telemetrycleaner.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.eynnzerr.telemetrycleaner.data.DhizukuStatus
import com.eynnzerr.telemetrycleaner.data.packagemanager.DhizukuController
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import com.eynnzerr.telemetrycleaner.service.AutoCleanupStartCoordinator
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class ControllablePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
        var failReads: Boolean = false,
        private val onUpdate: suspend (attempt: Int) -> Boolean = { true },
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private var updateAttempts = 0
        override val data: Flow<Preferences> = flow {
            if (failReads) throw IOException("Simulated disk read failure")
            state.collect { emit(it) }
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val attempt = ++updateAttempts
            if (!onUpdate(attempt)) throw IOException("Simulated disk write failure on attempt $attempt")
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun overlappingEnableRequests_firstSuspendedAndFailsSecondSucceeds_runsSingleMonitorWithNoStaleError() =
        runTest(testDispatcher) {
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            val store = ControllablePreferencesDataStore { attempt ->
                if (attempt == 1) {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                    false
                } else {
                    true
                }
            }
            val settings = CleanerSettings(store, logWarning = { _, _ -> })
            val viewModel = SettingsViewModel(settings)
            val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)

            var monitorStarts = 0
            val stoppedStartIds = mutableListOf<Int>()
            val coordinator = AutoCleanupStartCoordinator(
                scope = serviceScope,
                settings = settings,
                onStartMonitoring = {
                    monitorStarts += 1
                    awaitCancellation()
                },
                onStopService = { stoppedStartIds += it },
            )

            try {
                // 1. Enable A starts writing and suspends.
                coordinator.onStartRequest(enabling = true, startId = 1)
                advanceUntilIdle()
                assertTrue(firstWriteStarted.isCompleted)
                assertFalse(coordinator.isMonitoring)

                // 2. Enable B arrives while A is still writing.
                coordinator.onStartRequest(enabling = true, startId = 2)
                advanceUntilIdle()

                // 3. A fails, then B writes and succeeds.
                releaseFirstWrite.complete(Unit)
                advanceUntilIdle()

                assertTrue(settings.readPreferences().autoCleanupEnabled)
                assertEquals(1, monitorStarts)
                assertTrue(coordinator.isMonitoring)
                assertTrue(stoppedStartIds.isEmpty())

                // 4. Activity returns to foreground and begins collecting events: no stale SaveFailed is shown.
                val collectedEvents = mutableListOf<SettingsEvent>()
                val collectJob = launch { viewModel.events.collect(collectedEvents::add) }
                advanceUntilIdle()
                assertTrue(collectedEvents.isEmpty())
                collectJob.cancel()
            } finally {
                serviceScope.cancel()
            }
        }

    @Test
    fun backgroundEnableFailureSupersededBySuccessBeforeResume_clearsPendingErrorBeforeForegroundCollect() =
        runTest(testDispatcher) {
            var shouldFail = true
            val store = ControllablePreferencesDataStore { !shouldFail }
            val settings = CleanerSettings(store, logWarning = { _, _ -> })
            val viewModel = SettingsViewModel(settings)
            val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)

            var monitorStarts = 0
            val stoppedStartIds = mutableListOf<Int>()
            val coordinator = AutoCleanupStartCoordinator(
                scope = serviceScope,
                settings = settings,
                onStartMonitoring = {
                    monitorStarts += 1
                    awaitCancellation()
                },
                onStopService = { stoppedStartIds += it },
            )

            try {
                // Activity is backgrounded (not collecting events). A fails completely.
                coordinator.onStartRequest(enabling = true, startId = 1)
                advanceUntilIdle()
                assertEquals(listOf(1), stoppedStartIds)
                assertEquals(AutoCleanupStatus.OFF, settings.autoCleanupStatus.value)
                assertTrue(settings.pendingAutoCleanupSaveFailure.value)

                // While still backgrounded, B succeeds.
                shouldFail = false
                coordinator.onStartRequest(enabling = true, startId = 2)
                advanceUntilIdle()
                assertTrue(settings.readPreferences().autoCleanupEnabled)
                assertEquals(1, monitorStarts)
                assertTrue(coordinator.isMonitoring)
                assertFalse(settings.pendingAutoCleanupSaveFailure.value)

                // Activity returns to foreground: must not see A's superseded SaveFailed toast.
                val collectedEvents = mutableListOf<SettingsEvent>()
                val collectJob = launch { viewModel.events.collect(collectedEvents::add) }
                advanceUntilIdle()
                assertTrue(collectedEvents.isEmpty())
                collectJob.cancel()
            } finally {
                serviceScope.cancel()
            }
        }

    @Test
    fun singleEnableSaveFailure_surfacesSaveFailedOnceOnForegroundResumeAndStopsWithStartId() =
        runTest(testDispatcher) {
            val store = ControllablePreferencesDataStore { false }
            val settings = CleanerSettings(store, logWarning = { _, _ -> })
            val viewModel = SettingsViewModel(settings)
            val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)

            val stoppedStartIds = mutableListOf<Int>()
            val coordinator = AutoCleanupStartCoordinator(
                scope = serviceScope,
                settings = settings,
                onStartMonitoring = { awaitCancellation() },
                onStopService = { stoppedStartIds += it },
            )

            try {
                coordinator.onStartRequest(enabling = true, startId = 7)
                advanceUntilIdle()
                assertEquals(listOf(7), stoppedStartIds)
                assertEquals(AutoCleanupStatus.OFF, settings.autoCleanupStatus.value)

                // Foreground resumes: sees SaveFailed once.
                val firstResumeEvents = mutableListOf<SettingsEvent>()
                val firstCollector = launch { viewModel.events.collect(firstResumeEvents::add) }
                advanceUntilIdle()
                assertEquals(listOf(SettingsEvent.SaveFailed), firstResumeEvents)
                firstCollector.cancel()

                // Subsequent foreground resume must not re-deliver the already-shown failure.
                val secondResumeEvents = mutableListOf<SettingsEvent>()
                val secondCollector = launch { viewModel.events.collect(secondResumeEvents::add) }
                advanceUntilIdle()
                assertTrue(secondResumeEvents.isEmpty())
                secondCollector.cancel()
            } finally {
                serviceScope.cancel()
            }
        }

    @Test
    fun recoveryStartReadingDisabled_stopsQuietlyWithoutSaveFailed() = runTest(testDispatcher) {
        val store = ControllablePreferencesDataStore { true }
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val viewModel = SettingsViewModel(settings)
        val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val collectedEvents = mutableListOf<SettingsEvent>()
        val collectJob = launch { viewModel.events.collect(collectedEvents::add) }

        var monitorStarts = 0
        val stoppedStartIds = mutableListOf<Int>()
        val coordinator = AutoCleanupStartCoordinator(
            scope = serviceScope,
            settings = settings,
            onStartMonitoring = {
                monitorStarts += 1
                awaitCancellation()
            },
            onStopService = { stoppedStartIds += it },
        )

        try {
            coordinator.onStartRequest(enabling = false, startId = 3)
            advanceUntilIdle()

            assertEquals(0, monitorStarts)
            assertEquals(listOf(3), stoppedStartIds)
            assertEquals(AutoCleanupStatus.OFF, settings.autoCleanupStatus.value)
            assertTrue(collectedEvents.isEmpty())
        } finally {
            collectJob.cancel()
            serviceScope.cancel()
        }
    }

    @Test
    fun consecutiveForegroundEnableFailures_eachEmitSaveFailed() = runTest(testDispatcher) {
        val store = ControllablePreferencesDataStore { false }
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val viewModel = SettingsViewModel(settings)
        val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val collectedEvents = mutableListOf<SettingsEvent>()
        val collectJob = launch { viewModel.events.collect(collectedEvents::add) }

        val coordinator = AutoCleanupStartCoordinator(
            scope = serviceScope,
            settings = settings,
            onStartMonitoring = { awaitCancellation() },
            onStopService = {},
        )

        try {
            coordinator.onStartRequest(enabling = true, startId = 1)
            advanceUntilIdle()
            coordinator.onStartRequest(enabling = true, startId = 2)
            advanceUntilIdle()

            assertEquals(listOf(SettingsEvent.SaveFailed, SettingsEvent.SaveFailed), collectedEvents)
        } finally {
            collectJob.cancel()
            serviceScope.cancel()
        }
    }

    @Test
    fun enableSucceedsThenQueuedStopFails_recoversSingleMonitorAndReportsSaveFailed() = runTest(testDispatcher) {
        val enableWriteStarted = CompletableDeferred<Unit>()
        val releaseEnableWrite = CompletableDeferred<Unit>()
        val store = ControllablePreferencesDataStore { attempt ->
            if (attempt == 1) {
                enableWriteStarted.complete(Unit)
                releaseEnableWrite.await()
                true
            } else {
                false
            }
        }
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val viewModel = SettingsViewModel(settings)
        val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)

        var monitorStarts = 0
        val stoppedStartIds = mutableListOf<Int>()
        val coordinator = AutoCleanupStartCoordinator(
            scope = serviceScope,
            settings = settings,
            onStartMonitoring = {
                monitorStarts += 1
                awaitCancellation()
            },
            onStopService = { stoppedStartIds += it },
        )

        try {
            // 1. Enable A starts writing and suspends.
            coordinator.onStartRequest(enabling = true, startId = 1)
            advanceUntilIdle()
            assertTrue(enableWriteStarted.isCompleted)
            assertFalse(coordinator.isMonitoring)

            // 2. Notification stop B arrives before A finishes writing.
            coordinator.onStopRequest(startId = 2)
            advanceUntilIdle()

            // 3. A persists true, then B fails to persist false -> monitor must be reconciled and SaveFailed reported.
            releaseEnableWrite.complete(Unit)
            advanceUntilIdle()

            assertTrue(settings.readPreferences().autoCleanupEnabled)
            assertEquals(1, monitorStarts)
            assertTrue(coordinator.isMonitoring)
            assertTrue(stoppedStartIds.isEmpty())

            val collectedEvents = mutableListOf<SettingsEvent>()
            val collectJob = launch { viewModel.events.collect(collectedEvents::add) }
            advanceUntilIdle()
            assertEquals(listOf(SettingsEvent.SaveFailed), collectedEvents)
            collectJob.cancel()
        } finally {
            serviceScope.cancel()
        }
    }

    @Test
    fun stopWhileMonitoringWithCoalescedRefresh_cancelsMonitorAndStopsWithLatestStartId() = runTest(testDispatcher) {
        val stopWriteStarted = CompletableDeferred<Unit>()
        val releaseStopWrite = CompletableDeferred<Unit>()
        val store = ControllablePreferencesDataStore { attempt ->
            if (attempt == 2) {
                stopWriteStarted.complete(Unit)
                releaseStopWrite.await()
            }
            true
        }
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)

        var monitorStarts = 0
        val stoppedStartIds = mutableListOf<Int>()
        val coordinator = AutoCleanupStartCoordinator(
            scope = serviceScope,
            settings = settings,
            onStartMonitoring = {
                monitorStarts += 1
                awaitCancellation()
            },
            onStopService = { stoppedStartIds += it },
        )

        try {
            // 1. Enable succeeds and monitor is running (startId = 1).
            coordinator.onStartRequest(enabling = true, startId = 1)
            advanceUntilIdle()
            assertEquals(1, monitorStarts)
            assertTrue(coordinator.isMonitoring)

            // 2. Notification stop arrives (startId = 2) and suspends while persisting false.
            coordinator.onStopRequest(startId = 2)
            advanceUntilIdle()
            assertTrue(stopWriteStarted.isCompleted)

            // 3. Coalesced refresh arrives (startId = 3) while stop write is in flight.
            coordinator.onStartRequest(enabling = false, startId = 3)
            advanceUntilIdle()

            // 4. Stop write completes -> monitor is cancelled, preference is false, and stopSelf uses startId = 3.
            releaseStopWrite.complete(Unit)
            advanceUntilIdle()

            assertFalse(settings.readPreferences().autoCleanupEnabled)
            assertFalse(coordinator.isMonitoring)
            assertEquals(AutoCleanupStatus.OFF, settings.autoCleanupStatus.value)
            assertEquals(listOf(3), stoppedStartIds)
        } finally {
            serviceScope.cancel()
        }
    }

    @Test
    fun stopWriteAndSubsequentReadBothFail_stopsMonitorWithLatestStartIdAndReportsSaveFailed() =
        runTest(testDispatcher) {
            var failWrites = false
            val store = ControllablePreferencesDataStore { !failWrites }
            val settings = CleanerSettings(store, logWarning = { _, _ -> })
            val viewModel = SettingsViewModel(settings)
            val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)

            val stoppedStartIds = mutableListOf<Int>()
            val coordinator = AutoCleanupStartCoordinator(
                scope = serviceScope,
                settings = settings,
                onStartMonitoring = { awaitCancellation() },
                onStopService = { stoppedStartIds += it },
            )

            try {
                // 1. Enable succeeds and monitoring is active.
                coordinator.onStartRequest(enabling = true, startId = 1)
                advanceUntilIdle()
                assertTrue(settings.readPreferences().autoCleanupEnabled)
                assertTrue(coordinator.isMonitoring)

                // 2. Storage becomes unreadable and unwritable; user taps notification stop (startId = 2).
                failWrites = true
                store.failReads = true
                coordinator.onStopRequest(startId = 2)
                advanceUntilIdle()

                assertFalse(coordinator.isMonitoring)
                assertEquals(AutoCleanupStatus.OFF, settings.autoCleanupStatus.value)
                assertEquals(listOf(2), stoppedStartIds)

                val collectedEvents = mutableListOf<SettingsEvent>()
                val collectJob = launch { viewModel.events.collect(collectedEvents::add) }
                advanceUntilIdle()
                assertEquals(listOf(SettingsEvent.SaveFailed), collectedEvents)
                collectJob.cancel()
            } finally {
                serviceScope.cancel()
            }
        }

    @Test
    fun setDhizukuFallbackEnabled_persistsAndRequestsPermissionWhenRequired() = runTest(testDispatcher) {
        val store = ControllablePreferencesDataStore()
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val statusFlow = MutableStateFlow(DhizukuStatus.INACTIVE)
        var refreshCount = 0
        var requestCount = 0
        val fakeDhizuku = object : DhizukuController {
            override val status = statusFlow
            override suspend fun refreshStatus(): DhizukuStatus {
                refreshCount += 1
                statusFlow.value = DhizukuStatus.PERMISSION_REQUIRED
                return statusFlow.value
            }

            override suspend fun requestPermission(): DhizukuStatus {
                requestCount += 1
                statusFlow.value = DhizukuStatus.READY
                return statusFlow.value
            }
        }
        val viewModel = SettingsViewModel(settings, fakeDhizuku)

        viewModel.setDhizukuFallbackEnabled(true)
        advanceUntilIdle()

        assertTrue(settings.readPreferences().dhizukuFallbackEnabled)
        assertEquals(1, refreshCount)
        assertEquals(1, requestCount)
        assertEquals(DhizukuStatus.READY, fakeDhizuku.status.value)

        viewModel.setDhizukuFallbackEnabled(false)
        advanceUntilIdle()

        assertFalse(settings.readPreferences().dhizukuFallbackEnabled)
        assertEquals(1, refreshCount)
        assertEquals(1, requestCount)
    }
}
