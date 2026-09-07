package com.fitnessapp.summary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light scheme - deep teal/cyan. Deliberately a different family from the sibling
// habits app's emerald, so the two are never confused on a home screen.
val PrimaryLight = Color(0xFF0E7490)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFB6E8F5)
val OnPrimaryContainerLight = Color(0xFF00202B)
val SecondaryLight = Color(0xFF4A6270)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFCDE6F2)
val OnSecondaryContainerLight = Color(0xFF061F29)
val BackgroundLight = Color(0xFFF5FAFC)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFDCEBF1)
val OnSurfaceLight = Color(0xFF171C1F)
val OnSurfaceVariantLight = Color(0xFF3F484D)
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)

// Dark scheme
val PrimaryDark = Color(0xFF7DD3E8)
val OnPrimaryDark = Color(0xFF003544)
val PrimaryContainerDark = Color(0xFF004D61)
val OnPrimaryContainerDark = Color(0xFFB6E8F5)
val SecondaryDark = Color(0xFFB1CBD9)
val OnSecondaryDark = Color(0xFF1C343F)
val SecondaryContainerDark = Color(0xFF334A56)
val OnSecondaryContainerDark = Color(0xFFCDE6F2)
val BackgroundDark = Color(0xFF0F1417)
val SurfaceDark = Color(0xFF161C1F)
val SurfaceVariantDark = Color(0xFF3F484D)
val OnSurfaceDark = Color(0xFFDFE3E6)
val OnSurfaceVariantDark = Color(0xFFBFC8CD)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)

/**
 * Colours for the data itself, kept apart from the Material scheme above.
 *
 * One colour per metric family, used everywhere that metric appears - the same amber
 * means "steps" on a day tile, in the week bar chart and on a workout row - so the
 * charts can be read without consulting a legend each time.
 *
 * There are two full sets rather than one set reused on both backgrounds. Dark mode
 * gets its *own* steps, not an automatic flip: the light-mode indigo used for deep
 * sleep sits at 1.75:1 against the dark surface, which is effectively invisible.
 * Both sets were checked to clear 3:1 contrast against their own surface rather than
 * being eyeballed.
 */
data class MetricPalette(
    val steps: Color,
    val distance: Color,
    val calories: Color,
    val heart: Color,
    val sleep: Color,
    val workout: Color,
    val sleepDeep: Color,
    val sleepRem: Color,
    val sleepLight: Color,
    val sleepAwake: Color,
    // Garmin's own scores (garmin/ package). One hue per score family, same rule as
    // above: readiness is violet everywhere it appears, stress is orange everywhere.
    val stress: Color,
    val bodyBattery: Color,
    val readiness: Color,
    val hrv: Color,
    val training: Color,
    val weight: Color,
    // Stress zones, Garmin's four bands. Like the sleep stages these are only ever drawn
    // as labelled rows with the duration in text, so the colour reinforces, never carries.
    val stressRest: Color,
    val stressLow: Color,
    val stressMedium: Color,
    val stressHigh: Color
)

private val LightMetrics = MetricPalette(
    steps = Color(0xFFD97706),
    distance = Color(0xFF059669),
    calories = Color(0xFFDC2626),
    heart = Color(0xFFDB2777),
    sleep = Color(0xFF4F46E5),
    workout = Color(0xFF0E7490),
    // Ordered dark -> light with sleep depth, so the ramp itself reads as "deeper".
    // Identity never rests on these alone: every stage row carries its own name and
    // duration in text, which is also what covers the lightest step sitting a shade
    // under the 3:1 contrast line.
    sleepDeep = Color(0xFF3730A3),
    sleepRem = Color(0xFF4F46E5),
    sleepLight = Color(0xFF818CF8),
    sleepAwake = Color(0xFFDC2626),
    // All checked >= 3:1 against #FFFFFF (3.1-7.1).
    stress = Color(0xFFEA580C),
    bodyBattery = Color(0xFF65A30D),
    readiness = Color(0xFF7C3AED),
    hrv = Color(0xFF0284C7),
    training = Color(0xFF9333EA),
    weight = Color(0xFF6D28D9),
    stressRest = Color(0xFF0891B2),
    stressLow = Color(0xFFA16207),
    stressMedium = Color(0xFFEA580C),
    stressHigh = Color(0xFFDC2626)
)

private val DarkMetrics = MetricPalette(
    steps = Color(0xFFFBBF24),
    distance = Color(0xFF34D399),
    calories = Color(0xFFF87171),
    heart = Color(0xFFF472B6),
    sleep = Color(0xFF818CF8),
    workout = Color(0xFF22D3EE),
    sleepDeep = Color(0xFF818CF8),
    sleepRem = Color(0xFFA5B4FC),
    sleepLight = Color(0xFFC7D2FE),
    sleepAwake = Color(0xFFF87171),
    // All checked >= 6:1 against #161C1F.
    stress = Color(0xFFFB923C),
    bodyBattery = Color(0xFFA3E635),
    readiness = Color(0xFFA78BFA),
    hrv = Color(0xFF38BDF8),
    training = Color(0xFFC084FC),
    weight = Color(0xFFC4B5FD),
    stressRest = Color(0xFF22D3EE),
    stressLow = Color(0xFFFACC15),
    stressMedium = Color(0xFFFB923C),
    stressHigh = Color(0xFFF87171)
)

@Composable
fun metricPalette(): MetricPalette = if (isSystemInDarkTheme()) DarkMetrics else LightMetrics
