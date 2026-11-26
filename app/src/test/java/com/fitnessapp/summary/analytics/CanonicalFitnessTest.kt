package com.fitnessapp.summary.analytics

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CanonicalFitnessTest {
    private val day = LocalDate.of(2026, 11, 26)
    private val start = 1_700_000_000_000L
    private fun run(src: FitnessSource, id: String, offset: Long = 0, distance: Double? = 5000.0, duration: Long? = 1800) =
        FitnessSession(src, id, FitnessSport.RUN, start + offset, duration, distance)
    private fun daily(src: FitnessSource, id: String, date: LocalDate, v: Double?, complete: Boolean = true,
                      q: ObservationQuality = ObservationQuality.MEASURED, origin: FitnessSource? = null) =
        DailyObservation(src, id, date, DailyMetric.STEPS, v, q, complete, origin)

    @Test fun threeMatchingCopiesBecomeOneEpisode() {
        val g = run(FitnessSource.GARMIN, "g")
        val s = run(FitnessSource.STRAVA, "s", 2000)
        val h = run(FitnessSource.HEALTH_CONNECT, "h", 1000)
        val result = CanonicalFitness.episodes(listOf(s,h,g)).single()
        assertEquals(g, result.primary)
        assertEquals(3, result.observations.size)
        assertFalse(result.ambiguous)
        assertEquals(listOf(result), CanonicalFitness.episodes(listOf(g,s,h)))
    }

    @Test fun differentSportAtSameTimeIsNotADuplicate() {
        val g = run(FitnessSource.GARMIN, "g")
        val s = run(FitnessSource.STRAVA, "s").copy(sport = FitnessSport.CYCLE)
        assertEquals(2, CanonicalFitness.episodes(listOf(g,s)).size)
    }

    @Test fun ambiguousTwoToOnePreservesAllRows() {
        val g1 = run(FitnessSource.GARMIN, "g1")
        val g2 = run(FitnessSource.GARMIN, "g2", 60_000)
        val s = run(FitnessSource.STRAVA, "s", 30_000)
        val result = CanonicalFitness.episodes(listOf(g1,g2,s))
        assertEquals(3, result.size)
        assertTrue(result.all { it.ambiguous })
    }

    @Test fun transitiveChainDoesNotMerge() {
        val a = run(FitnessSource.GARMIN, "a")
        val b = run(FitnessSource.STRAVA, "b", 120_000)
        val c = run(FitnessSource.HEALTH_CONNECT, "c", 240_000)
        val result = CanonicalFitness.episodes(listOf(a,b,c))
        assertEquals(3, result.size)
        assertTrue(result.all { it.ambiguous })
    }

    @Test fun missingDistanceOrConflictingDistanceIsNotAutoMerged() {
        val g = run(FitnessSource.GARMIN, "g")
        for (d in listOf(null, 9000.0)) {
            val rows = CanonicalFitness.episodes(listOf(g, run(FitnessSource.STRAVA, "s", distance=d)))
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.ambiguous })
        }
    }

    @Test fun separateShortSessionsAreNotMerged() {
        val g = run(FitnessSource.GARMIN, "g", duration=60)
        val s = run(FitnessSource.STRAVA, "s", 120_000, duration=60)
        assertEquals(2, CanonicalFitness.episodes(listOf(g,s)).size)
        assertTrue(CanonicalFitness.episodes(listOf(g,s)).none { it.ambiguous })
    }

    @Test fun strengthKeepsWatchAndGymLog() {
        val g = run(FitnessSource.GARMIN, "g").copy(sport=FitnessSport.STRENGTH, distanceMeters=null)
        val s = run(FitnessSource.GYMUP, "s", 20_000).copy(sport=FitnessSport.STRENGTH, distanceMeters=null)
        assertEquals(2, CanonicalFitness.episodes(listOf(g,s)).single().observations.size)
    }

    @Test fun verifiedIdentityWithConflictingTimestampIsFlagged() {
        val g = run(FitnessSource.GARMIN, "g").copy(upstreamId="garmin:shared")
        val s = run(FitnessSource.STRAVA, "s", 86_400_000).copy(upstreamId="garmin:shared")
        assertTrue(CanonicalFitness.episodes(listOf(g,s)).all { it.ambiguous })
    }

    @Test fun verifiedIdentityDoesNotOverrideConflictingMeasurements() {
        val a = run(FitnessSource.GARMIN,"a").copy(upstreamId="garmin:shared")
        val b = run(FitnessSource.STRAVA,"b",distance=9000.0).copy(upstreamId="garmin:shared")
        val result=CanonicalFitness.episodes(listOf(a,b))
        assertEquals(2,result.size)
        assertTrue(result.all { it.ambiguous && it.possibleDuplicateKeys.size==1 })
    }

    @Test fun verifiedIdentityCanFillMissingEvidence() {
        val a=run(FitnessSource.GARMIN,"a").copy(upstreamId="garmin:shared")
        val b=run(FitnessSource.STRAVA,"b",distance=null,duration=null).copy(upstreamId="garmin:shared")
        assertEquals(2,CanonicalFitness.episodes(listOf(a,b)).single().observations.size)
    }

    @Test fun missingZeroAndAlternativesRemainDifferent() {
        val empty = daily(FitnessSource.GARMIN, "g", day, null)
        val zero = daily(FitnessSource.HEALTH_CONNECT, "h", day, 0.0)
        assertNull(CanonicalFitness.chooseDaily(listOf(empty)).selected)
        assertEquals(zero, CanonicalFitness.chooseDaily(listOf(empty,zero)).selected)
        val count = daily(FitnessSource.GARMIN, "g", day, 42.0)
        val choice = CanonicalFitness.chooseDaily(listOf(count,zero))
        assertEquals(count, choice.selected)
        assertTrue(choice.disagreement)
        assertEquals(2, choice.observations.size)
    }

    @Test fun qualityAndCompletenessPrecedeSourcePreference() {
        val incomplete = daily(FitnessSource.GARMIN, "g", day, 500.0, false)
        val measured = daily(FitnessSource.GOOGLE_HEALTH, "h", day, 700.0)
        assertEquals(measured, CanonicalFitness.chooseDaily(listOf(incomplete,measured)).selected)
        assertEquals(measured, CanonicalFitness.chooseDaily(listOf(incomplete.copy(complete=true, quality=ObservationQuality.ESTIMATED),measured)).selected)
    }

    @Test fun transportedScaleObservationRetainsOriginalPriority() {
        val zepp = daily(FitnessSource.GOOGLE_HEALTH, "z", day, 75.0, origin=FitnessSource.ZEPP).copy(metric=DailyMetric.WEIGHT_KG)
        val g = daily(FitnessSource.GARMIN, "g", day, 77.0).copy(metric=DailyMetric.WEIGHT_KG)
        assertEquals(zepp, CanonicalFitness.chooseDaily(listOf(g,zepp)).selected)
    }

    @Test fun referenceCountsDaysNotRecordsAndExcludesToday() {
        val rows = (1..14).map { i -> daily(FitnessSource.GARMIN, "g$i", day.minusDays(i.toLong()), i.toDouble()) }
        val extras = listOf(rows.first().copy(source=FitnessSource.GOOGLE_HEALTH,nativeId="copy"),
            daily(FitnessSource.GARMIN, "today", day, 99999.0),
            daily(FitnessSource.GARMIN, "old", day.minusDays(29), 99999.0),
            daily(FitnessSource.GARMIN, "partial", day.minusDays(15), 1000.0, false))
        val ref = CanonicalFitness.reference(day,DailyMetric.STEPS,rows+extras)!!
        assertEquals(14,ref.validDays)
        assertEquals(7.5,ref.median,0.0)
        assertNull(CanonicalFitness.reference(day,DailyMetric.STEPS,rows.take(13)+extras))
    }

    @Test fun completedWeeksExcludeCurrentDay() {
        val monday=LocalDate.of(2026,11,23)
        assertEquals(0,CanonicalFitness.completedWeekDays(monday,monday))
        assertEquals(3,CanonicalFitness.completedWeekDays(monday,day))
        assertEquals(7,CanonicalFitness.completedWeekDays(monday,day.plusWeeks(1)))
    }
}
