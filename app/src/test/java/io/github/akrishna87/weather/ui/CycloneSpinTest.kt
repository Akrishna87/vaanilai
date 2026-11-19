package io.github.akrishna87.weather.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CycloneSpinTest {
    @Test
    fun rotatesInOppositeDirectionsInEachHemisphere() {
        assertEquals(-Math.PI / 2, cycloneSpinPhase(900L, cycloneSpinDirection(15.0)), 1e-9)
        assertEquals(Math.PI / 2, cycloneSpinPhase(900L, cycloneSpinDirection(-15.0)), 1e-9)
    }

    @Test
    fun loopsAndUsesOriginalOrientationAtZero() {
        for (latitude in listOf(15.0, -15.0)) {
            val direction = cycloneSpinDirection(latitude)
            assertEquals(0.0, cycloneSpinPhase(0L, direction), 1e-9)
            assertEquals(cycloneSpinPhase(900L, direction), cycloneSpinPhase(4_500L, direction), 1e-9)
        }
    }
}
