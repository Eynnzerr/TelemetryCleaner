package com.eynnzerr.telemetrycleaner.ui.packages

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PackageDetailsNestedScrollTest {

    @Test
    fun onPostScroll_consumesOnlyVerticalResidualScroll() {
        val available = Offset(x = 42f, y = 180f)
        val consumed = BlockVerticalSheetNestedScroll.onPostScroll(
            consumed = Offset.Zero,
            available = available,
            source = NestedScrollSource.UserInput,
        )
        assertEquals(Offset(x = 0f, y = 180f), consumed)
    }

    @Test
    fun onPostFling_consumesOnlyVerticalResidualVelocity() = runTest {
        val available = Velocity(x = -350f, y = 2400f)
        val consumed = BlockVerticalSheetNestedScroll.onPostFling(
            consumed = Velocity.Zero,
            available = available,
        )
        assertEquals(Velocity(x = 0f, y = 2400f), consumed)
    }
}
