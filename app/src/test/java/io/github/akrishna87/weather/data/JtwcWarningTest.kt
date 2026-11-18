package io.github.akrishna87.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Joint Typhoon Warning Center's plain-text warnings, as parsed into storms. */
class JtwcWarningTest {

    /** A real JTWC warning: Cyclone Akvan (05B) over the Bay of Bengal, 17 Nov 2026. */
    private val akvan = """
        WTIO31 PGTW 170900
        MSGID/GENADMIN/JOINT TYPHOON WRNCEN PEARL HARBOR HI//
        SUBJ/TROPICAL CYCLONE 05B (AKVAN) WARNING NR 003//
        RMKS/
        1. TROPICAL CYCLONE 05B (AKVAN) WARNING NR 003
           01 ACTIVE TROPICAL CYCLONE IN NORTHIO
           MAX SUSTAINED WINDS BASED ON ONE-MINUTE AVERAGE
           WIND RADII VALID OVER OPEN WATER ONLY
            ---
           WARNING POSITION:
           170600Z --- NEAR 12.0N 87.3E
             MOVEMENT PAST SIX HOURS - 020 DEGREES AT 03 KTS
             POSITION ACCURATE TO WITHIN 060 NM
             POSITION BASED ON CENTER LOCATED BY SATELLITE
           PRESENT WIND DISTRIBUTION:
           MAX SUSTAINED WINDS - 040 KT, GUSTS 050 KT
           WIND RADII VALID OVER OPEN WATER ONLY
           RADIUS OF 034 KT WINDS - 000 NM NORTHEAST QUADRANT
                                  000 NM SOUTHEAST QUADRANT
                                  100 NM SOUTHWEST QUADRANT
                                  160 NM NORTHWEST QUADRANT
           REPEAT POSIT: 12.0N 87.3E
            ---
           FORECASTS:
           12 HRS, VALID AT:
           171800Z --- 12.5N 86.8E
           MAX SUSTAINED WINDS - 040 KT, GUSTS 050 KT
           VECTOR TO 24 HR POSIT: 310 DEG/ 05 KTS
            ---
           24 HRS, VALID AT:
           180600Z --- 13.2N 86.0E
           MAX SUSTAINED WINDS - 045 KT, GUSTS 055 KT
            ---
           72 HRS, VALID AT:
           200600Z --- 15.6N 81.7E
           MAX SUSTAINED WINDS - 060 KT, GUSTS 075 KT
            ---
        FORECAST TRACK AND INTENSITY (KT) ARE THE INITIAL FORECAST
        POSITION AND INTENSITY FOLLOWED BY THE 12/24/36/48/72 HR
        FORECAST POINTS VALID AT THE SAME WARNED TIME.
        //
        NNNN
    """.trimIndent()

    private val akvanUrl = "https://www.metoc.navy.mil/jtwc/products/io0526web.txt"

    /** Parses [text] and fails the test if it doesn't read as a storm. */
    private fun parse(text: String = akvan, url: String = akvanUrl): Storm {
        val s = StormFeeds.parseJtwcWarning(text, url)
        assertNotNull("the warning should parse into a storm", s)
        return s!!
    }

    @Test
    fun readsNamePositionWindAndMovement() {
        val s = parse()
        assertEquals("Akvan", s.name)
        assertEquals(12.0, s.lat, 1e-9)
        assertEquals(87.3, s.lon, 1e-9)
        assertEquals(74.08, s.windKmh!!, 0.01) // 40 knots
        assertEquals(20.0, s.movingTowardDeg!!, 1e-9)
        assertEquals(5.556, s.movingKmh!!, 0.01) // 3 knots
        assertEquals("jtwc-io0526", s.key)
        assertEquals("Tropical Storm", s.kind) // 74 km/h is below cyclone strength
        assertEquals(listOf("JTWC"), s.sources)
    }

    @Test
    fun forecastPositionsBecomeTheTrack() {
        val s = parse()
        val track = s.track!!
        assertEquals(4, track.points.size) // now + 12h + 24h + 72h
        val now = track.points.first()
        assertTrue(!now.forecast)
        assertNull(now.label)
        val later = track.points.last()
        assertTrue(later.forecast)
        assertEquals("+72h", later.label)
        assertEquals(15.6, later.lat, 1e-9)
        assertEquals(81.7, later.lon, 1e-9)
        assertEquals(1, track.lines.size)
        assertEquals(LatLon(12.0, 87.3), track.lines[0].first())
    }

    @Test
    fun hemisphereSignsAndSouthernStorms() {
        val southern = akvan
            .replace("SUBJ/TROPICAL CYCLONE 05B (AKVAN)", "SUBJ/TROPICAL CYCLONE 03S (XAVIER)")
            .replace("NEAR 12.0N 87.3E", "NEAR 8.9S 162.6W")
            .replace("REPEAT POSIT: 12.0N 87.3E", "REPEAT POSIT: 8.9S 162.6W")
            .replace(
                "12 HRS, VALID AT:\n   171800Z --- 12.5N 86.8E",
                "12 HRS, VALID AT:\n   171800Z --- 8.2S 163.9W")
        val s = parse(southern, "https://www.metoc.navy.mil/jtwc/products/sh0326web.txt")
        assertEquals("Xavier", s.name)
        assertEquals(-8.9, s.lat, 1e-9)
        assertEquals(-162.6, s.lon, 1e-9)
        assertEquals(-8.2, s.track!!.points[1].lat, 1e-9)
        assertEquals(-163.9, s.track!!.points[1].lon, 1e-9)
    }

    @Test
    fun unnamedOrUnreadableWarningsAreSkipped() {
        assertNull(StormFeeds.parseJtwcWarning("WTIO31 PGTW 170900\nnothing here", akvanUrl))
        val unnamed = """
            WTIO31 PGTW 170900
            SUBJ/TROPICAL DEPRESSION 05B WARNING NR 003//
            WARNING POSITION:
            170600Z --- NEAR 12.0N 87.3E
        """.trimIndent()
        assertNull(StormFeeds.parseJtwcWarning(unnamed, akvanUrl))
    }
}
