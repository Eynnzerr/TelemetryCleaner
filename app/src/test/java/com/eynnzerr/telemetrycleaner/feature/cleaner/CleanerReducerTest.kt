package com.eynnzerr.telemetrycleaner.feature.cleaner

import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.ChangeOutcome
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeConnection
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanerReducerTest {
    private val owner = AndroidUser(id = 0, name = "Owner", serialNumber = 100L)
    private val clone = AndroidUser(id = 10, name = "system_clone", serialNumber = 200L)
    private val unknownSerialUser = AndroidUser(id = 11, name = "Work", serialNumber = null)

    private val bundledEntry = PackageDirectory.variants.getValue("com.oppo.ctautoregist")
    private val customEntry = PackageEntry("com.example.custom", "Custom App", emptySet())

    private val readyShizuku = PrivilegeConnection(PrivilegeBackend.SHIZUKU, PrivilegeStatus.READY)
    private val readyRoot = PrivilegeConnection(PrivilegeBackend.ROOT, PrivilegeStatus.READY)

    private fun sampleSnapshot(
        bundledState: Map<Int, PackageState> = mapOf(0 to PackageState.INSTALLED, 10 to PackageState.RESTORABLE),
        customState: Map<Int, PackageState>? = mapOf(0 to PackageState.INSTALLED, 10 to PackageState.NOT_FOUND),
        users: List<AndroidUser> = listOf(owner, clone),
    ): ScanSnapshot {
        val packages = buildList {
            add(
                DetectedPackage(
                    entry = bundledEntry,
                    versionName = "1.0",
                    versionCode = 1L,
                    systemPath = "/system_ext/app/CTAutoRegist",
                    users = bundledState,
                    hasVerifiedSystemIdentity = true,
                ),
            )
            if (customState != null) {
                add(
                    DetectedPackage(
                        entry = customEntry,
                        versionName = "2.0",
                        versionCode = 2L,
                        systemPath = "/data/app/Custom",
                        users = customState,
                        hasVerifiedSystemIdentity = false,
                    ),
                )
            }
        }
        return ScanSnapshot(currentUserId = 0, users = users, packages = packages)
    }

    private fun idleReadyState(
        snapshot: ScanSnapshot = sampleSnapshot(),
        selectedUserIds: Set<Int> = setOf(0),
        selectedPackageNames: Set<String> = emptySet(),
        panel: CleanerPanel? = null,
    ): CleanerState = CleanerState(
        connection = readyShizuku,
        snapshot = snapshot,
        selectedUserIds = selectedUserIds,
        selectedPackageNames = selectedPackageNames,
        activity = CleanerActivity.IDLE,
        panel = panel,
        workId = 5L,
    )

    @Test
    fun connectionReady_startsScanWithIncrementedWorkId() {
        val initial = CleanerState(
            connection = PrivilegeConnection(PrivilegeBackend.SHIZUKU, PrivilegeStatus.CONNECTING),
            workId = 1L,
        )
        val transition = CleanerReducer.reduce(initial, CleanerMessage.ConnectionChanged(readyShizuku))

        assertEquals(CleanerActivity.SCANNING, transition.state.activity)
        assertEquals(2L, transition.state.workId)
        assertEquals(listOf(CleanerEffect.Scan(2L)), transition.effects)
    }

    @Test
    fun switchingBackend_clearsSnapshotAndSelections() {
        val state = idleReadyState(selectedPackageNames = setOf(bundledEntry.packageName))
        val transition = CleanerReducer.reduce(state, CleanerMessage.ConnectionChanged(readyRoot))

        assertEquals(PrivilegeBackend.ROOT, transition.state.connection.backend)
        assertNull(transition.state.snapshot)
        assertTrue(transition.state.selectedUserIds.isEmpty())
        assertTrue(transition.state.selectedPackageNames.isEmpty())
        assertEquals(CleanerActivity.SCANNING, transition.state.activity)
    }

    @Test
    fun staleScanFinishedAndScanFailed_areIgnored() {
        val scanning = idleReadyState().copy(activity = CleanerActivity.SCANNING, workId = 10L)
        val prefs = CleanerPreferences(autoCleanupEnabled = false, selectedUserSerials = null)

        val staleFinish = CleanerReducer.reduce(
            scanning,
            CleanerMessage.ScanFinished(id = 9L, snapshot = sampleSnapshot(), preferences = prefs),
        )
        assertEquals(scanning, staleFinish.state)

        val staleFail = CleanerReducer.reduce(
            scanning,
            CleanerMessage.ScanFailed(id = 9L, error = CleanerError.SCAN_FAILED),
        )
        assertEquals(scanning, staleFail.state)
    }

    @Test
    fun scanFinished_dropsCustomPackagesRemovedWhileScanWasInFlight() {
        val scanning = idleReadyState(snapshot = sampleSnapshot()).copy(activity = CleanerActivity.SCANNING, workId = 6L)
        val prefs = CleanerPreferences(
            autoCleanupEnabled = false,
            selectedUserSerials = null,
            customPackageNames = emptySet(), // customEntry was removed while scan was running
        )

        val transition = CleanerReducer.reduce(
            scanning,
            CleanerMessage.ScanFinished(id = 6L, snapshot = sampleSnapshot(), preferences = prefs),
        )

        assertEquals(CleanerActivity.IDLE, transition.state.activity)
        assertEquals(listOf(bundledEntry.packageName), transition.state.snapshot?.packages?.map { it.entry.packageName })
    }

    @Test
    fun setFreezeOnly_togglesUninstallAndDisableButIgnoresRestore() {
        val targets = listOf(ChangeTarget(bundledEntry.packageName, 0))
        val uninstallState = idleReadyState(panel = CleanerPanel.Confirmation(PackageAction.UNINSTALL, targets))

        val frozen = CleanerReducer.reduce(uninstallState, CleanerMessage.Intent(CleanerIntent.SetFreezeOnly(true)))
        assertEquals(CleanerPanel.Confirmation(PackageAction.DISABLE, targets), frozen.state.panel)

        val reverted = CleanerReducer.reduce(frozen.state, CleanerMessage.Intent(CleanerIntent.SetFreezeOnly(false)))
        assertEquals(CleanerPanel.Confirmation(PackageAction.UNINSTALL, targets), reverted.state.panel)

        val restoreState = idleReadyState(panel = CleanerPanel.Confirmation(PackageAction.RESTORE, targets))
        val unchanged = CleanerReducer.reduce(restoreState, CleanerMessage.Intent(CleanerIntent.SetFreezeOnly(true)))
        assertEquals(CleanerPanel.Confirmation(PackageAction.RESTORE, targets), unchanged.state.panel)
    }

    @Test
    fun confirmAction_invalidatesAffectedTargetsAndRunsChangeEffect() {
        val targets = listOf(ChangeTarget(bundledEntry.packageName, 0))
        val state = idleReadyState(
            selectedPackageNames = setOf(bundledEntry.packageName),
            panel = CleanerPanel.Confirmation(PackageAction.UNINSTALL, targets),
        )

        val transition = CleanerReducer.reduce(state, CleanerMessage.Intent(CleanerIntent.ConfirmAction))

        assertEquals(CleanerActivity.CHANGING, transition.state.activity)
        assertNull(transition.state.panel)
        assertTrue(transition.state.selectedPackageNames.isEmpty())
        val updatedBundled = transition.state.snapshot!!.packages.first { it.entry.packageName == bundledEntry.packageName }
        assertEquals(PackageState.UNKNOWN, updatedBundled.users[0])
        assertEquals(PackageState.RESTORABLE, updatedBundled.users[10]) // unaffected user preserved
        assertEquals(
            listOf(
                CleanerEffect.Change(
                    id = 6L,
                    confirmation = CleanerPanel.Confirmation(PackageAction.UNINSTALL, targets),
                    backend = PrivilegeBackend.SHIZUKU,
                ),
            ),
            transition.effects,
        )
    }

    @Test
    fun changeFinished_showsResultsAndStartsVerificationScan() {
        val target = ChangeTarget(bundledEntry.packageName, 0)
        val changing = idleReadyState().copy(activity = CleanerActivity.CHANGING, workId = 6L)
        val outcomes = listOf(ChangeOutcome(target, success = true))

        val transition = CleanerReducer.reduce(
            changing,
            CleanerMessage.ChangeFinished(id = 6L, action = PackageAction.UNINSTALL, outcomes = outcomes, requestFailed = false),
        )

        assertEquals(CleanerPanel.Results(PackageAction.UNINSTALL, outcomes), transition.state.panel)
        assertEquals(CleanerActivity.SCANNING, transition.state.activity)
        assertEquals(7L, transition.state.workId)
        assertEquals(listOf(CleanerEffect.Scan(7L)), transition.effects)
    }

    @Test
    fun customPackageRemoved_clearsSnapshotSelectionAndMatchingPanelsOnly() {
        val withDetails = idleReadyState(
            selectedPackageNames = setOf(bundledEntry.packageName, customEntry.packageName),
            panel = CleanerPanel.Details(customEntry.packageName),
        )
        val removed = CleanerReducer.reduce(withDetails, CleanerMessage.CustomPackageRemoved(customEntry.packageName))

        assertEquals(listOf(bundledEntry.packageName), removed.state.snapshot?.packages?.map { it.entry.packageName })
        assertEquals(setOf(bundledEntry.packageName), removed.state.selectedPackageNames)
        assertNull(removed.state.panel)

        // Unrelated Details panel should stay open
        val withOtherDetails = idleReadyState(panel = CleanerPanel.Details(bundledEntry.packageName))
        val keptPanel = CleanerReducer.reduce(withOtherDetails, CleanerMessage.CustomPackageRemoved(customEntry.packageName))
        assertEquals(CleanerPanel.Details(bundledEntry.packageName), keptPanel.state.panel)
    }

    @Test
    fun toggleSpace_persistsSerialNumbersAndRejectsMissingSerial() {
        val snapshot = sampleSnapshot(users = listOf(owner, clone, unknownSerialUser))
        val state = idleReadyState(snapshot = snapshot, selectedUserIds = setOf(0))

        // Adding clone succeeds and produces scopeToSave
        val addedClone = CleanerReducer.reduce(state, CleanerMessage.Intent(CleanerIntent.ToggleSpace(10)))
        assertEquals(setOf(0, 10), addedClone.state.selectedUserIds)
        assertEquals(setOf(100L, 200L), addedClone.scopeToSave)

        // Adding user without serial number is ignored
        val ignored = CleanerReducer.reduce(state, CleanerMessage.Intent(CleanerIntent.ToggleSpace(11)))
        assertEquals(state, ignored.state)
        assertNull(ignored.scopeToSave)
    }

    @Test
    fun toggleAllPackages_selectsAllEligibleThenClears() {
        val state = idleReadyState()
        val selectAll = CleanerReducer.reduce(state, CleanerMessage.Intent(CleanerIntent.ToggleAllPackages))
        assertEquals(setOf(bundledEntry.packageName, customEntry.packageName), selectAll.state.selectedPackageNames)

        val clearAll = CleanerReducer.reduce(selectAll.state, CleanerMessage.Intent(CleanerIntent.ToggleAllPackages))
        assertTrue(clearAll.state.selectedPackageNames.isEmpty())
    }

    @Test
    fun toggleSpace_retainsOnlyEligibleSelectedPackagesAcrossLargeSelection() {
        val packages = (0 until 200).map { index ->
            val onlyOwner = index % 2 == 0
            DetectedPackage(
                entry = PackageEntry("com.example.pkg$index", "Pkg $index", emptySet()),
                versionName = "1.0",
                versionCode = 1L,
                systemPath = "/data/app/Pkg$index",
                users = if (onlyOwner) {
                    mapOf(0 to PackageState.INSTALLED, 10 to PackageState.NOT_FOUND)
                } else {
                    mapOf(0 to PackageState.NOT_FOUND, 10 to PackageState.INSTALLED)
                },
                hasVerifiedSystemIdentity = false,
            )
        }
        val snapshot = ScanSnapshot(currentUserId = 0, users = listOf(owner, clone), packages = packages)
        val allNames = packages.mapTo(mutableSetOf()) { it.entry.packageName }
        val state = idleReadyState(
            snapshot = snapshot,
            selectedUserIds = setOf(0, 10),
            selectedPackageNames = allNames,
        )

        val cloneOnly = CleanerReducer.reduce(state, CleanerMessage.Intent(CleanerIntent.ToggleSpace(0)))
        val expectedClonePackages = (0 until 200)
            .filter { it % 2 != 0 }
            .mapTo(mutableSetOf()) { "com.example.pkg$it" }
        assertEquals(setOf(10), cloneOnly.state.selectedUserIds)
        assertEquals(expectedClonePackages, cloneOnly.state.selectedPackageNames)
        assertEquals(expectedClonePackages, cloneOnly.state.selectablePackageNames())
    }

    @Test
    fun backendSelectionFailed_setsErrorAndDoesNotScan() {
        val switching = idleReadyState().copy(activity = CleanerActivity.SWITCHING_BACKEND)
        val transition = CleanerReducer.reduce(
            switching,
            CleanerMessage.BackendSelectionFinished(saved = false, connection = readyShizuku),
        )

        assertEquals(CleanerActivity.IDLE, transition.state.activity)
        assertEquals(CleanerError.BACKEND_SELECTION_FAILED, transition.state.error)
        assertTrue(transition.effects.isEmpty())
    }
}
