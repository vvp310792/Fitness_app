package com.fitnessapp.summary.ui

import com.fitnessapp.summary.ui.components.trendHasGap
import com.fitnessapp.summary.ui.components.BarState
import com.fitnessapp.summary.ui.components.barState
import com.fitnessapp.summary.ui.components.BarDatum
import org.junit.Assert.*
import org.junit.Test

class ChartPresentationTest {
    @Test fun noFalseLineAcrossMissingDays() {
        assertFalse(trendHasGap(100,101,1))
        assertTrue(trendHasGap(100,102,1))
        assertFalse(trendHasGap(100,107,7))
        assertTrue(trendHasGap(100,108,7))
        assertTrue(trendHasGap(100,130,21))
    }
    @Test fun measurementMissingAndFutureHaveSeparateStates() {
        assertEquals(BarState.MEASURED,barState(false,true))
        assertEquals(BarState.MISSING,barState(false,false))
        assertEquals(BarState.FUTURE,barState(true,false))
        assertEquals(BarState.FUTURE,barState(true,true))
        assertEquals(BarState.MEASURED,BarDatum("Пн",0).state)
    }
}
