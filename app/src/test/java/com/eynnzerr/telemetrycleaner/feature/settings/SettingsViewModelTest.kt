package com.eynnzerr.telemetrycleaner.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import com.eynnzerr.telemetrycleaner.service.AutoCleanupService
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class FakePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
        var failWrites: Boolean = false,
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("Simulated disk write failure")
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
    fun resolveAutoCleanupEnablement_whenEnableSaveFails_emitsSaveFailedToSettingsViewModel() = runTest(testDispatcher) {
        val store = FakePreferencesDataStore(failWrites = true)
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val viewModel = SettingsViewModel(settings)
        val collectedEvents = mutableListOf<SettingsEvent>()
        val collectJob = launch { viewModel.events.collect(collectedEvents::add) }

        val enabled = AutoCleanupService.resolveAutoCleanupEnablement(settings, enabling = true)
        advanceUntilIdle()

        assertFalse(enabled)
        assertEquals(listOf(SettingsEvent.SaveFailed), collectedEvents)
        collectJob.cancel()
    }

    @Test
    fun persistAutoCleanupEnable_whenActiveMonitorEnableSaveFails_emitsSaveFailedToSettingsViewModel() = runTest(testDispatcher) {
        val store = FakePreferencesDataStore(failWrites = true)
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val viewModel = SettingsViewModel(settings)
        val collectedEvents = mutableListOf<SettingsEvent>()
        val collectJob = launch { viewModel.events.collect(collectedEvents::add) }

        val saved = AutoCleanupService.persistAutoCleanupEnable(settings)
        advanceUntilIdle()

        assertFalse(saved)
        assertEquals(listOf(SettingsEvent.SaveFailed), collectedEvents)
        collectJob.cancel()
    }

    @Test
    fun resolveAutoCleanupEnablement_whenRecoveryReadsDisabled_stopsQuietlyWithoutSaveFailed() = runTest(testDispatcher) {
        val store = FakePreferencesDataStore(failWrites = false)
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val viewModel = SettingsViewModel(settings)
        val collectedEvents = mutableListOf<SettingsEvent>()
        val collectJob = launch { viewModel.events.collect(collectedEvents::add) }

        val enabled = AutoCleanupService.resolveAutoCleanupEnablement(settings, enabling = false)
        advanceUntilIdle()

        assertFalse(enabled)
        assertTrue(collectedEvents.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun resolveAutoCleanupEnablement_whenEnableSaveSucceeds_persistsEnabledWithoutErrors() = runTest(testDispatcher) {
        val store = FakePreferencesDataStore(failWrites = false)
        val settings = CleanerSettings(store, logWarning = { _, _ -> })
        val viewModel = SettingsViewModel(settings)
        val collectedEvents = mutableListOf<SettingsEvent>()
        val collectJob = launch { viewModel.events.collect(collectedEvents::add) }

        val enabled = AutoCleanupService.resolveAutoCleanupEnablement(settings, enabling = true)
        advanceUntilIdle()

        assertTrue(enabled)
        assertTrue(settings.readPreferences().autoCleanupEnabled)
        assertTrue(collectedEvents.isEmpty())
        collectJob.cancel()
    }
}
