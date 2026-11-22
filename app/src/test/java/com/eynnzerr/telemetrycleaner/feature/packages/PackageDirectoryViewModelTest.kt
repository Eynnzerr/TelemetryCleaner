package com.eynnzerr.telemetrycleaner.feature.packages

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.eynnzerr.telemetrycleaner.data.CustomPackageChange
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeConnection
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.commitCustomPackageChange
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
class PackageDirectoryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
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
    fun commitCustomPackageChange_cancellingWhileWaitingForMutexDoesNotPersistOrEmit() = runTest(testDispatcher) {
        val mutex = Mutex()
        mutex.lock()
        var persisted = false
        val emitted = mutableListOf<CustomPackageChange>()
        var cancelled = false

        val job = launch {
            try {
                commitCustomPackageChange(
                    mutex = mutex,
                    packageName = "com.example.pkg_a",
                    included = true,
                    persist = { _, _ ->
                        persisted = true
                        true
                    },
                    emitChange = { emitted += it },
                )
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        mutex.unlock()
        advanceUntilIdle()

        assertTrue(cancelled)
        assertFalse(persisted)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun submitAdd_cancelledDuringNonCancellableSaveEmitsChangeWithoutClosingReopenedDialog() = runTest(testDispatcher) {
        val settings = CleanerSettings(InMemoryPreferencesDataStore(), logWarning = { _, _ -> })
        val connection = MutableStateFlow(PrivilegeConnection(PrivilegeBackend.SHIZUKU, PrivilegeStatus.READY))
        val mutex = Mutex()
        val persistStarted = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val emittedChanges = mutableListOf<CustomPackageChange>()

        val viewModel = PackageDirectoryViewModel(
            connectionFlow = connection,
            lookupPackage = { pkg -> PackageEntry(pkg, pkg, emptySet()) },
            setCustomPackage = { pkg, included ->
                commitCustomPackageChange(
                    mutex = mutex,
                    packageName = pkg,
                    included = included,
                    persist = { name, inc ->
                        persistStarted.complete(Unit)
                        releasePersist.await()
                        settings.setCustomPackage(name, inc)
                    },
                    emitChange = { emittedChanges += it },
                )
            },
            settings = settings,
        )

        val stateCollector = launch { viewModel.state.collect {} }

        // Start adding package A and pause inside the NonCancellable persistence step.
        viewModel.openAddDialog()
        viewModel.onInputChange("com.example.pkg_a")
        viewModel.submitAdd()
        advanceUntilIdle()
        assertTrue(persistStarted.isCompleted)

        // User cancels the dialog, reopens it, and begins typing package B while A's save is finishing.
        viewModel.dismissAddDialog()
        viewModel.openAddDialog()
        viewModel.onInputChange("com.example.pkg_b")
        advanceUntilIdle()

        // Complete A's persistence; A's event must still be emitted, while B's open dialog stays intact.
        releasePersist.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(CustomPackageChange.Added("com.example.pkg_a")), emittedChanges)
        assertTrue(viewModel.state.value.addDialogOpen)
        assertEquals("com.example.pkg_b", viewModel.state.value.input)
        assertEquals(AddCustomPackageStatus.Idle, viewModel.state.value.status)

        stateCollector.cancel()
    }
}
