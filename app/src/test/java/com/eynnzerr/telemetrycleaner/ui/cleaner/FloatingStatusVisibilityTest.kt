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

    private fun readyState(
        installedCount: Int,
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
                users = mapOf(owner.id to if (installedCount >= 1) PackageState.INSTALLED else PackageState.RESTORABLE),
                userDisabledIds = emptySet(),
                hasVerifiedSystemIdentity = true,
                hasSystemCopy = true,
            ),
            DetectedPackage(
                entry = pkgB,
                versionName = "1.0",
                versionCode = 1L,
                systemPath = "/system/app/B/B.apk",
                users = mapOf(owner.id to if (installedCount >= 2) PackageState.INSTALLED else PackageState.RESTORABLE),
                userDisabledIds = emptySet(),
                hasVerifiedSystemIdentity = true,
                hasSystemCopy = true,
            ),
        )
        return CleanerState(
            connection = PrivilegeConnection(PrivilegeBackend.SHIZUKU, status),
            snapshot = ScanSnapshot(owner.id, listOf(owner), packages),
            selectedUserIds = setOf(owner.id),
            activity = activity,
            error = error,
        )
    }

    @Test
    fun staysHiddenWhenScrollingDownWithoutStatusChange() {
        val initial = readyState(installedCount = 2).summaryStatusKey()
        var state = FloatingStatusVisibilityState(visible = false, lastSeenStatus = initial)

        state = state.reduce(currentStatus = initial, isSummaryVisible = false)

        assertFalse(state.visible)
    }

    @Test
    fun staysHiddenWhenStatusChangesWhileSummaryIsVisible() {
        val initial = readyState(installedCount = 2).summaryStatusKey()
        val updated = readyState(installedCount = 1).summaryStatusKey()
        var state = FloatingStatusVisibilityState(visible = false, lastSeenStatus = initial)

        state = state.reduce(currentStatus = updated, isSummaryVisible = true)
        assertFalse(state.visible)

        // Scrolling down after seeing the updated card should still stay hidden.
        state = state.reduce(currentStatus = updated, isSummaryVisible = false)
        assertFalse(state.visible)
    }

    @Test
    fun appearsOnOffscreenStatusChangeAndDismissesOnceSummaryReturns() {
        val initial = readyState(installedCount = 2).summaryStatusKey()
        val changing = readyState(installedCount = 2, activity = CleanerActivity.CHANGING).summaryStatusKey()
        val afterUninstall = readyState(installedCount = 1).summaryStatusKey()
        var state = FloatingStatusVisibilityState(visible = false, lastSeenStatus = initial)

        // Scroll past summary card without status change.
        state = state.reduce(currentStatus = initial, isSummaryVisible = false)
        assertFalse(state.visible)

        // Uninstall begins while summary is off-screen -> capsule appears.
        state = state.reduce(currentStatus = changing, isSummaryVisible = false)
        assertTrue(state.visible)

        // Uninstall finishes with new installed count -> capsule stays visible with updated state.
        state = state.reduce(currentStatus = afterUninstall, isSummaryVisible = false)
        assertTrue(state.visible)

        // Scroll back up so summary card becomes visible -> capsule immediately dismisses.
        state = state.reduce(currentStatus = afterUninstall, isSummaryVisible = true)
        assertFalse(state.visible)

        // Scrolling down again without further status change keeps it hidden.
        state = state.reduce(currentStatus = afterUninstall, isSummaryVisible = false)
        assertFalse(state.visible)
    }

    @Test
    fun appearsWhenOffscreenRefreshFinishesWithUnchangedCount() {
        val initial = readyState(installedCount = 2).summaryStatusKey()
        val scanning = readyState(installedCount = 2, activity = CleanerActivity.SCANNING).summaryStatusKey()
        var state = FloatingStatusVisibilityState(visible = false, lastSeenStatus = initial)

        state = state.reduce(currentStatus = initial, isSummaryVisible = false)
        assertFalse(state.visible)

        state = state.reduce(currentStatus = scanning, isSummaryVisible = false)
        assertTrue(state.visible)

        state = state.reduce(currentStatus = initial, isSummaryVisible = false)
        assertTrue(state.visible)
    }

    @Test
    fun appearsOnOffscreenDisconnectOrError() {
        val initial = readyState(installedCount = 2).summaryStatusKey()
        val disconnected = readyState(installedCount = 2, status = PrivilegeStatus.STOPPED).summaryStatusKey()
        val failed = readyState(installedCount = 2, error = CleanerError.SCAN_FAILED).summaryStatusKey()

        var state = FloatingStatusVisibilityState(visible = false, lastSeenStatus = initial)
        state = state.reduce(currentStatus = disconnected, isSummaryVisible = false)
        assertTrue(state.visible)

        state = state.reduce(currentStatus = disconnected, isSummaryVisible = true)
        assertFalse(state.visible)

        state = state.reduce(currentStatus = failed, isSummaryVisible = false)
        assertTrue(state.visible)
    }
}
