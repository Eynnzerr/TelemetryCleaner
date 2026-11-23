package com.eynnzerr.telemetrycleaner.ui.cleaner

import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeConnection
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerError
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingStatusVisibilityTest {

    private val owner = AndroidUser(id = 0, name = "Owner", serialNumber = 0)
    private val pkgA = PackageEntry("com.example.a", "CompA", setOf(Carrier.UNICOM))
    private val pkgB = PackageEntry("com.example.b", "CompB", setOf(Carrier.TELECOM))

    private fun cleanerState(
        pkgAState: PackageState = PackageState.INSTALLED,
        pkgBState: PackageState = PackageState.INSTALLED,
        selectedUserIds: Set<Int> = setOf(owner.id),
        activity: CleanerActivity = CleanerActivity.IDLE,
        error: CleanerError? = null,
        status: PrivilegeStatus = PrivilegeStatus.READY,
    ): CleanerState {
        val packages = listOf(
            DetectedPackage(
                entry = pkgA,
                versionName = "1.0",
                versionCode = 1L,
                systemPath = "/system/app/A/A.apk",
                users = mapOf(owner.id to pkgAState),
                userDisabledIds = emptySet(),
                hasVerifiedSystemIdentity = true,
                hasSystemCopy = true,
            ),
            DetectedPackage(
                entry = pkgB,
                versionName = "1.0",
                versionCode = 1L,
                systemPath = "/system/app/B/B.apk",
                users = mapOf(owner.id to pkgBState),
                userDisabledIds = emptySet(),
                hasVerifiedSystemIdentity = true,
                hasSystemCopy = true,
            ),
        )
        return CleanerState(
            connection = PrivilegeConnection(PrivilegeBackend.SHIZUKU, status),
            snapshot = ScanSnapshot(owner.id, listOf(owner), packages),
            selectedUserIds = selectedUserIds,
            activity = activity,
            error = error,
        )
    }

    @Test
    fun neverShowsForNominalInstalledCountStates() {
        val twoInstalled = cleanerState(PackageState.INSTALLED, PackageState.INSTALLED)
        val zeroInstalled = cleanerState(PackageState.RESTORABLE, PackageState.RESTORABLE)

        assertFalse(twoInstalled.shouldShowFloatingStatusPill(isSummaryVisible = false))
        assertFalse(twoInstalled.shouldShowFloatingStatusPill(isSummaryVisible = true))
        assertFalse(zeroInstalled.shouldShowFloatingStatusPill(isSummaryVisible = false))
        assertFalse(zeroInstalled.shouldShowFloatingStatusPill(isSummaryVisible = true))
    }

    @Test
    fun hidesWhenSummaryVisibleAndReappearsWhenScrolledAwayAgain() {
        val scanning = cleanerState(activity = CleanerActivity.SCANNING)
        val changing = cleanerState(activity = CleanerActivity.CHANGING)
        val disconnected = cleanerState(status = PrivilegeStatus.STOPPED)

        listOf(scanning, changing, disconnected).forEach { state ->
            // Scrolled past summary card -> show capsule
            assertTrue(state.shouldShowFloatingStatusPill(isSummaryVisible = false))
            // Scroll back to summary card -> hide capsule
            assertFalse(state.shouldShowFloatingStatusPill(isSummaryVisible = true))
            // Scroll down again while still in this state -> show capsule again
            assertTrue(state.shouldShowFloatingStatusPill(isSummaryVisible = false))
        }
    }

    @Test
    fun hidesOnceBusyOperationReturnsToNominalInstalledState() {
        val changing = cleanerState(activity = CleanerActivity.CHANGING)
        val done = cleanerState(PackageState.RESTORABLE, PackageState.INSTALLED, activity = CleanerActivity.IDLE)

        assertTrue(changing.shouldShowFloatingStatusPill(isSummaryVisible = false))
        assertFalse(done.shouldShowFloatingStatusPill(isSummaryVisible = false))
    }

    @Test
    fun showsForErrorEmptyScopeAndIncompleteZeroScanWhenOffscreen() {
        val error = cleanerState(error = CleanerError.ACTION_FAILED)
        val noSpaces = cleanerState(selectedUserIds = emptySet())
        val incompleteZero = cleanerState(PackageState.UNKNOWN, PackageState.RESTORABLE)
        val connecting = cleanerState(status = PrivilegeStatus.CONNECTING)

        listOf(error, noSpaces, incompleteZero, connecting).forEach { state ->
            assertTrue(state.shouldShowFloatingStatusPill(isSummaryVisible = false))
            assertFalse(state.shouldShowFloatingStatusPill(isSummaryVisible = true))
        }
    }

    @Test
    fun showsForPartialScanFailureEvenWhenInstalledCountIsPositive() {
        val partialFailure = cleanerState(PackageState.INSTALLED, PackageState.UNKNOWN)

        assertTrue(partialFailure.shouldShowFloatingStatusPill(isSummaryVisible = false))
        assertFalse(partialFailure.shouldShowFloatingStatusPill(isSummaryVisible = true))
    }
}
