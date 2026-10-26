package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.data.GarminSyncMark
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Write-then-read contract of the per-day sync marks. Marks used to be stamped with the
 * history walk's version while runWindow read with LOGIC_VERSION - no mark was ever found,
 * and the recent sync re-asked 14 days on every open until the network gave out.
 */
class GarminDayMarkTest {

    @Test
    fun `a written day mark is found by the version runWindow reads with`() {
        GarminSyncMark.DAY_SECTIONS.forEach { section ->
            assertEquals(
                GarminSyncManager.DAY_MARK_READ_VERSION,
                GarminSyncManager.dayMark(20750, section, hasData = true).logicVersion
            )
        }
    }
}
