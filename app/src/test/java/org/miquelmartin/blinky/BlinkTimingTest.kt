package org.miquelmartin.blinky

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkTimingTest {
    @Test fun randomizedStartPeriodsAndDurationsStayWithinTheApprovedBounds() {
        val random = Random(42)
        val timings = List(1_000) { nextBlinkTiming(random) }
        assertTrue(timings.all { it.periodMillis in 8_000..12_000 && it.durationMillis in 800..1_200 })
        assertTrue(timings.map { it.periodMillis }.distinct().size > 100)
        assertTrue(timings.map { it.durationMillis }.distinct().size > 100)
    }

    @Test fun slowCloseHoldAndOpenAreSynchronizedWithoutFlashing() {
        assertEquals(0f, blinkClosure(0f), 0.0001f)
        assertEquals(0.5f, blinkClosure(0.21f), 0.0001f)
        assertEquals(1f, blinkClosure(0.42f), 0.0001f)
        assertEquals(1f, blinkClosure(0.5f), 0.0001f)
        assertEquals(1f, blinkClosure(0.58f), 0.0001f)
        assertEquals(0.5f, blinkClosure(0.79f), 0.0001f)
        assertEquals(0f, blinkClosure(1f), 0.0001f)
    }
}
