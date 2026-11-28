package com.fitnessapp.summary.ui

import com.fitnessapp.summary.ui.components.BarState
import com.fitnessapp.summary.ui.components.barState
import com.fitnessapp.summary.ui.components.BarDatum
import org.junit.Assert.*
import org.junit.Test

class ChartPresentationTest {
    @Test fun measurementMissingAndFutureHaveSeparateStates() {
        assertEquals(BarState.MEASURED,barState(false,true))
        assertEquals(BarState.MISSING,barState(false,false))
        assertEquals(BarState.FUTURE,barState(true,false))
        assertEquals(BarState.FUTURE,barState(true,true))
        assertEquals(BarState.MEASURED,BarDatum("Пн",0).state)
    }
}
