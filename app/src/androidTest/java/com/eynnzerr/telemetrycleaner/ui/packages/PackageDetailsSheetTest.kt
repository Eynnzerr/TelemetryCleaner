package com.eynnzerr.telemetrycleaner.ui.packages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.ComponentPermission
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PermissionProtection
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.ui.components.CleanerModalSheet
import com.eynnzerr.telemetrycleaner.ui.components.SHEET_DRAG_HANDLE_TAG
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies gesture ownership between the permissions list, horizontal tab pager, and modal sheet:
 * pulling down at the top of the permissions list must not dismiss the sheet, horizontal swipes
 * switch tabs, and dragging the top drag handle dismisses the sheet.
 */
@RunWith(AndroidJUnit4::class)
class PackageDetailsSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleSnapshot(): ScanSnapshot {
        val owner = AndroidUser(id = 0, name = "Owner", serialNumber = 0L)
        val entry = PackageEntry("com.oppo.ctautoregist", "CTAutoRegist", setOf(Carrier.TELECOM))
        val permissions = List(20) { index ->
            ComponentPermission(
                name = "android.permission.TEST_PERMISSION_$index",
                label = "Test Permission $index",
                protection = PermissionProtection.DANGEROUS,
                grants = mapOf(owner.id to true),
            )
        }
        val detected = DetectedPackage(
            entry = entry,
            versionName = "16.0.0",
            versionCode = 31L,
            systemPath = "/product/priv-app/CTAutoRegist/CTAutoRegist.apk",
            users = mapOf(owner.id to PackageState.INSTALLED),
            hasVerifiedSystemIdentity = true,
            hasSystemCopy = true,
            permissions = permissions,
        )
        return ScanSnapshot(owner.id, listOf(owner), listOf(detected))
    }

    @Test
    fun permissionsTopPullStaysOpen_horizontalSwipeSwitchesTab_dragHandleDismisses() {
        var sheetOpen by mutableStateOf(true)
        val snapshot = sampleSnapshot()

        composeRule.setContent {
            TelemetryCleanerTheme(dynamicColor = false) {
                if (sheetOpen) {
                    CleanerModalSheet(onDismiss = { sheetOpen = false }) {
                        PackageDetailsSheetContent(
                            packageName = "com.oppo.ctautoregist",
                            snapshot = snapshot,
                            onDismiss = { sheetOpen = false },
                            showPermissionsInitially = true,
                        )
                    }
                }
            }
        }

        // 1. Pull down at the top of the permissions list -> sheet must stay expanded.
        composeRule.onNodeWithText("android.permission.TEST_PERMISSION_0").assertIsDisplayed()
        composeRule.onNodeWithText("android.permission.TEST_PERMISSION_0").performTouchInput {
            swipeDown(startY = centerY, endY = bottom + 600f, durationMillis = 150)
        }
        composeRule.waitForIdle()
        assertTrue("Pulling down inside permissions list should not dismiss sheet", sheetOpen)

        // 2. Swipe horizontally from Permissions (page 1) to Overview (page 0).
        composeRule.onNodeWithText("android.permission.TEST_PERMISSION_0").performTouchInput {
            swipeRight(startX = left + 20f, endX = right - 20f)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("com.oppo.ctautoregist").assertIsDisplayed()
        assertTrue(sheetOpen)

        // 3. Drag the sheet drag handle downward -> sheet dismisses (locale-independent tag).
        composeRule.onNodeWithTag(SHEET_DRAG_HANDLE_TAG, useUnmergedTree = true)
            .performTouchInput {
                swipeDown(
                    startY = center.y,
                    endY = center.y + 900f,
                    durationMillis = 180,
                )
            }
        composeRule.waitForIdle()
        assertFalse("Dragging the handle down should dismiss the sheet", sheetOpen)
    }
}
