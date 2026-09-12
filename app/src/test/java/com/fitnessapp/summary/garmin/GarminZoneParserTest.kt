package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.analytics.HeartRateZone
import com.fitnessapp.summary.analytics.ZoneBoundaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one Garmin payload in this project whose field names could not be verified against a
 * reference implementation - neither garth nor python-garminconnect carries a fixture or a
 * dataclass for `biometric-service/heartRateZones`, and no account was available to call
 * it from.
 *
 * That is precisely why the parser matches structurally instead of by name, and why this
 * test exists: it pins the parser against several shapes the response could plausibly
 * take, so "we guessed the key wrong" cannot come back as a screen of confident zeros. If
 * the real response turns out to be a shape none of these cover, the raw response is kept
 * on the row and shown in the Я tab, and a line gets added here.
 */
class GarminZoneParserTest {

    @Test
    fun `flat camelCase floors, the most likely shape`() {
        val zones = GarminZoneParser.parse(
            """
            [{"sport":"DEFAULT","zone1Floor":93,"zone2Floor":112,"zone3Floor":130,
              "zone4Floor":149,"zone5Floor":167,"maxHeartRateUsed":186,"restingHrUsed":48,
              "heartRateZoneCalculationMethod":"PERCENT_MAX_HR"}]
            """.trimIndent()
        )
        val row = zones.single()
        assertEquals("DEFAULT", row.sport)
        assertEquals(listOf(93, 112, 130, 149, 167), row.floors)
        assertEquals(186, row.maxHeartRateUsed)
        assertEquals(48, row.restingHeartRateUsed)
        assertEquals("PERCENT_MAX_HR", row.method)
        assertTrue(row.isUsable)
    }

    /** Garmin sends bpm as floats in several other endpoints, so it may here too. */
    @Test
    fun `floors given as floats are rounded, not dropped`() {
        val row = GarminZoneParser.parse(
            """[{"sport":"RUNNING","zone1Floor":93.0,"zone2Floor":111.6,"zone3Floor":130.0,
                "zone4Floor":148.8,"zone5Floor":167.4,"maxHeartRate":186.0}]"""
        ).single()
        assertEquals(listOf(93, 112, 130, 149, 167), row.floors)
        assertTrue(row.isUsable)
    }

    /** snake_case is just as plausible, and the structural match must not care. */
    @Test
    fun `snake_case names parse the same`() {
        val row = GarminZoneParser.parse(
            """[{"sport":"CYCLING","zone_1_floor":90,"zone_2_floor":110,"zone_3_floor":128,
                "zone_4_floor":146,"zone_5_floor":165,"max_heart_rate_used":183}]"""
        ).single()
        assertEquals(listOf(90, 110, 128, 146, 165), row.floors)
        assertEquals(183, row.maxHeartRateUsed)
    }

    /** The other plausible shape entirely: zones as a nested array of objects. */
    @Test
    fun `nested per-zone objects parse`() {
        val zones = GarminZoneParser.parse(
            """
            [{"sport":"DEFAULT","maxHeartRateUsed":190,
              "zones":[{"zoneNumber":1,"floor":95},{"zoneNumber":2,"floor":114},
                       {"zoneNumber":3,"floor":133},{"zoneNumber":4,"floor":152},
                       {"zoneNumber":5,"floor":171}]}]
            """.trimIndent()
        )
        assertEquals(listOf(95, 114, 133, 152, 171), zones.single().floors)
        assertEquals(190, zones.single().maxHeartRateUsed)
    }

    /** Five zone objects in order and no explicit number - position is enough. */
    @Test
    fun `nested zones without a number fall back to position`() {
        val row = GarminZoneParser.parse(
            """[{"sport":"DEFAULT","heartRateZones":[{"lowBoundary":95},{"lowBoundary":114},
                {"lowBoundary":133},{"lowBoundary":152},{"lowBoundary":171}]}]"""
        ).single()
        assertEquals(listOf(95, 114, 133, 152, 171), row.floors)
    }

    /** A zone described by both bounds: the floor is the lower of the two. */
    @Test
    fun `when a zone carries both bounds the floor wins`() {
        val row = GarminZoneParser.parse(
            """[{"sport":"DEFAULT","zone1Floor":93,"zone1Ceiling":111,"zone2Floor":112,
                "zone2Ceiling":129,"zone3Floor":130,"zone4Floor":149,"zone5Floor":167}]"""
        ).single()
        assertEquals(listOf(93, 112, 130, 149, 167), row.floors)
    }

    /** Garmin returns one entry per sport profile; all of them must survive. */
    @Test
    fun `every sport profile becomes its own row`() {
        val zones = GarminZoneParser.parse(
            """[{"sport":"DEFAULT","zone1Floor":93,"zone2Floor":112,"zone3Floor":130,"zone4Floor":149,"zone5Floor":167},
                {"sport":"RUNNING","zone1Floor":95,"zone2Floor":115,"zone3Floor":134,"zone4Floor":153,"zone5Floor":172}]"""
        )
        assertEquals(2, zones.size)
        assertEquals(setOf("DEFAULT", "RUNNING"), zones.map { it.sport }.toSet())
    }

    /**
     * The failure that has to stay visible. A shape the parser doesn't understand must not
     * produce a usable-looking row - and the raw response must survive, because it is the
     * only thing that says what the real field names are.
     */
    @Test
    fun `an unrecognised shape is not usable but keeps the raw response`() {
        val row = GarminZoneParser.parse("""[{"sport":"DEFAULT","somethingElse":{"a":1}}]""").single()
        assertFalse(row.isUsable)
        assertTrue(row.rawJson.contains("somethingElse"))
        assertNull(ZoneBoundaries.fromGarmin(row))
    }

    /** Half-parsed floors are worse than none: they would file every session into one band. */
    @Test
    fun `a partially parsed row is rejected rather than used`() {
        val row = GarminZoneParser.parse(
            """[{"sport":"DEFAULT","zone1Floor":93,"zone2Floor":112,"zone3Floor":130}]"""
        ).single()
        assertFalse(row.isUsable)
        assertNull(ZoneBoundaries.fromGarmin(row))
    }

    /** Floors that don't ascend are a misread, not a zone ladder. */
    @Test
    fun `non-ascending floors are rejected`() {
        val row = GarminZoneParser.parse(
            """[{"sport":"DEFAULT","zone1Floor":93,"zone2Floor":112,"zone3Floor":100,
                "zone4Floor":149,"zone5Floor":167}]"""
        ).single()
        assertFalse(row.isUsable)
    }

    /** An error envelope must not land as an empty "Garmin has no zones" row. */
    @Test
    fun `an object with neither sport nor floors is dropped`() {
        assertTrue(GarminZoneParser.parse("""[{"message":"unauthorized","code":401}]""").isEmpty())
        assertTrue(GarminZoneParser.parse("").isEmpty())
        assertTrue(GarminZoneParser.parse("not json at all").isEmpty())
    }

    /** A bare object rather than an array is accepted - one fewer way to come back empty. */
    @Test
    fun `a single object response is accepted`() {
        val row = GarminZoneParser.parse(
            """{"sport":"DEFAULT","zone1Floor":93,"zone2Floor":112,"zone3Floor":130,
               "zone4Floor":149,"zone5Floor":167}"""
        ).single()
        assertTrue(row.isUsable)
    }

    /** Values that aren't heart rates (an id, a timestamp) must not be read as one. */
    @Test
    fun `implausible numbers are not taken as heart rates`() {
        val row = GarminZoneParser.parse(
            """[{"sport":"DEFAULT","userProfilePk":123456789,"zone1Floor":93,"zone2Floor":112,
                "zone3Floor":130,"zone4Floor":149,"zone5Floor":167,"maxHeartRateUsed":186}]"""
        ).single()
        assertEquals(186, row.maxHeartRateUsed)
        assertTrue(row.isUsable)
    }

    /** The whole point: Garmin's floors, once parsed, are what classifies a session. */
    @Test
    fun `parsed Garmin floors drive the zone of a session`() {
        val row = GarminZoneParser.parse(
            """[{"sport":"DEFAULT","zone1Floor":95,"zone2Floor":114,"zone3Floor":133,
                "zone4Floor":152,"zone5Floor":171,"maxHeartRateUsed":190}]"""
        ).single()
        val bounds = ZoneBoundaries.fromGarmin(row)
        assertNotNull(bounds)
        assertEquals(HeartRateZone.Z1, HeartRateZone.of(80, bounds!!))
        assertEquals(HeartRateZone.Z2, HeartRateZone.of(114, bounds))
        assertEquals(HeartRateZone.Z2, HeartRateZone.of(132, bounds))
        assertEquals(HeartRateZone.Z3, HeartRateZone.of(133, bounds))
        assertEquals(HeartRateZone.Z5, HeartRateZone.of(171, bounds))
    }

    /** Field names for the log when nothing parses - names only, never the numbers. */
    @Test
    fun `key names are reported for the log`() {
        val names = GarminZoneParser.keyNames("""[{"sport":"DEFAULT","mysteryField":1}]""")
        assertEquals(setOf("sport", "mysteryField"), names.toSet())
    }
}

/**
 * Real time in zones, `activity-service/{id}/hrTimeInZones`.
 *
 * This is the payload that makes the intensity card correct rather than approximately
 * correct: without it a 45-minute run averaging 125 with a peak of 167 was filed whole
 * into zone 1, and a week that really did hold Z2 and Z3 work came out as "100 % Z1" - a
 * wrong answer the user caught on their own training. Its field names are as unverified as
 * the ladder's, so the same structural treatment and the same pinning applies.
 */
class GarminTimeInZonesParserTest {

    @Test
    fun `the expected shape parses`() {
        val secs = GarminZoneParser.parseTimeInZones(
            """[{"zoneNumber":1,"secsInZone":1800.0,"zoneLowBoundary":131},
                {"zoneNumber":2,"secsInZone":720.0,"zoneLowBoundary":145},
                {"zoneNumber":3,"secsInZone":180.0,"zoneLowBoundary":160},
                {"zoneNumber":4,"secsInZone":0.0,"zoneLowBoundary":175},
                {"zoneNumber":5,"secsInZone":0.0,"zoneLowBoundary":190}]"""
        )
        assertEquals(listOf(1800, 720, 180, 0, 0), secs)
    }

    /**
     * The boundary is a heart rate, not a duration. Reading `zoneLowBoundary` as seconds
     * would fill every zone with a plausible-looking number and never fail.
     */
    @Test
    fun `a zone boundary is never mistaken for seconds`() {
        val secs = GarminZoneParser.parseTimeInZones(
            """[{"zoneNumber":1,"zoneLowBoundary":131,"secsInZone":600},
                {"zoneNumber":2,"zoneLowBoundary":145,"secsInZone":300},
                {"zoneNumber":3,"zoneLowBoundary":160,"secsInZone":0},
                {"zoneNumber":4,"zoneLowBoundary":175,"secsInZone":0},
                {"zoneNumber":5,"zoneLowBoundary":190,"secsInZone":0}]"""
        )
        assertEquals(listOf(600, 300, 0, 0, 0), secs)
    }

    /** Garmin sends these as floats elsewhere; an int-only read would zero them silently. */
    @Test
    fun `float seconds are rounded, not dropped`() {
        val secs = GarminZoneParser.parseTimeInZones(
            """[{"zoneNumber":1,"secsInZone":1799.6},{"zoneNumber":2,"secsInZone":0.4},
                {"zoneNumber":3,"secsInZone":0},{"zoneNumber":4,"secsInZone":0},
                {"zoneNumber":5,"secsInZone":0}]"""
        )
        assertEquals(listOf(1800, 0, 0, 0, 0), secs)
    }

    /** Five entries in order carry their own numbering implicitly. */
    @Test
    fun `five entries without a number fall back to position`() {
        val secs = GarminZoneParser.parseTimeInZones(
            """[{"timeInZone":100},{"timeInZone":200},{"timeInZone":300},
                {"timeInZone":400},{"timeInZone":500}]"""
        )
        assertEquals(listOf(100, 200, 300, 400, 500), secs)
    }

    /** An array wrapped in an object is still an array. */
    @Test
    fun `a wrapped array parses`() {
        val secs = GarminZoneParser.parseTimeInZones(
            """{"timeInZones":[{"zoneNumber":1,"secsInZone":60},{"zoneNumber":2,"secsInZone":30},
                {"zoneNumber":3,"secsInZone":0},{"zoneNumber":4,"secsInZone":0},
                {"zoneNumber":5,"secsInZone":0}]}"""
        )
        assertEquals(listOf(60, 30, 0, 0, 0), secs)
    }

    /** A session Garmin has no breakdown for must be null, never five zeros presented as data. */
    @Test
    fun `nothing usable is null`() {
        assertNull(GarminZoneParser.parseTimeInZones("[]"))
        assertNull(GarminZoneParser.parseTimeInZones(""))
        assertNull(GarminZoneParser.parseTimeInZones("""[{"zoneNumber":1,"secsInZone":0}]"""))
        assertNull(GarminZoneParser.parseTimeInZones("""{"message":"not found"}"""))
    }
}
