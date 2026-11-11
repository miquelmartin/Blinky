package org.miquelmartin.blinky

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GhostExpressionsTest {
    @Test fun randomizedStartPeriodsAndDurationsStayWithinTheApprovedBounds() {
        val random = Random(42)
        val timings = List(1_000) { nextExpressionTiming(GhostExpression.BLINK, random) }
        assertTrue(timings.all { it.periodMillis in 5_000..10_000 && it.durationMillis in 1_000..1_300 })
        assertTrue(timings.map { it.periodMillis }.distinct().size > 100)
        assertTrue(timings.map { it.durationMillis }.distinct().size > 100)
    }

    @Test fun everyExpressionHasBoundedTimingAndSelectionNeverImmediatelyRepeats() {
        val random = Random(21)
        var previous: GhostExpression? = null
        val counts = GhostExpression.entries.associateWith { 0 }.toMutableMap()
        repeat(30_000) {
            val selected = nextExpression(previous, random)
            assertNotEquals(previous, selected)
            val timing = nextExpressionTiming(selected, random)
            assertTrue(timing.periodMillis in 5_000..10_000)
            assertTrue(timing.durationMillis in selected.durationRange)
            assertTrue(timing.durationMillis < timing.periodMillis)
            counts[selected] = counts.getValue(selected) + 1
            previous = selected
        }
        assertTrue(counts.values.all { it > 0 })
        assertTrue(counts.getValue(GhostExpression.BLINK) > counts.getValue(GhostExpression.CROSS_EYED) * 2)
        assertTrue(counts.getValue(GhostExpression.CURIOUS_GLANCE) > counts.getValue(GhostExpression.EYE_ROLL) * 2)
    }

    @Test fun weightedTicketsExactlyCoverAllEligibleExpressions() {
        for (previous in listOf(null) + GhostExpression.entries) {
            val eligible = GhostExpression.entries.filter { it != previous }
            val choices = (0 until eligible.sumOf { it.weight }).map { ticket ->
                nextExpression(previous, object : Random() {
                    override fun nextBits(bitCount: Int) = error("Unexpected random source")
                    override fun nextInt(until: Int): Int {
                        assertEquals(eligible.sumOf { it.weight }, until)
                        return ticket
                    }
                })
            }
            for (expression in GhostExpression.entries) {
                assertEquals(if (expression == previous) 0 else expression.weight, choices.count { it == expression })
            }
        }
    }

    @Test fun onlyWholeBlinkExpressionsToggleTheStickyWordmarkExactlyOnce() {
        val state = GhostExpressionState()
        assertTrue(state.wordmarkVisible)
        for (expression in listOf(GhostExpression.BLINK) + GhostExpression.entries.filter { !it.togglesWordmark }) {
            state.start(expression, true)
            assertFalse(state.wordmarkVisible)
            if (!expression.togglesWordmark) {
                for (step in 0..100) assertEquals(0f, state.frame(step / 100f).wordmarkAmount, 0f)
            }
            state.finish()
            assertEquals(0f, state.frame(0f).wordmarkAmount, 0f)
        }
        state.start(GhostExpression.DOUBLE_BLINK, false)
        assertTrue(state.wordmarkVisible)
        for (step in 0..100) {
            state.frame(step / 100f)
            assertTrue(state.wordmarkVisible)
        }
        assertEquals(1f, state.frame(0.5f).wordmarkAmount, 0.0001f)
        assertEquals(1f, state.frame(0.75f).wordmarkAmount, 0.0001f)
        state.finish()
        state.start(GhostExpression.BLINK, false)
        assertEquals(1f, state.frame(0f).wordmarkAmount, 0f)
        assertEquals(0f, state.frame(0.5f).wordmarkAmount, 0f)
        state.finish()
        assertFalse(state.wordmarkVisible)
    }

    @Test fun interruptionKeepsCommittedToggleAndEveryExpressionReturnsNeutralEyes() {
        for (expression in GhostExpression.entries) {
            val state = GhostExpressionState()
            state.start(expression, false)
            val end = state.frame(1f)
            assertEquals(0f, end.leftClosure, 0.0001f)
            assertEquals(0f, end.rightClosure, 0.0001f)
            assertEquals(0f, end.leftPupilX, 0.0001f)
            assertEquals(0f, end.rightPupilX, 0.0001f)
            assertEquals(0f, end.pupilY, 0.0001f)
            assertEquals(1f, end.pupilScale, 0.0001f)
            state.finish()
            assertEquals(!expression.togglesWordmark, state.wordmarkVisible)
            assertEquals(ExpressionFrame(wordmarkAmount = if (state.wordmarkVisible) 1f else 0f), state.frame(0.5f))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun overlappingExpressionsAreRejected() {
        GhostExpressionState().apply {
            start(GhostExpression.WINK, true)
            start(GhostExpression.EYE_ROLL, false)
        }
    }

    @Test fun eyesFollowTheSelectedExpressionAndWinkSide() {
        fun frame(expression: GhostExpression, left: Boolean = true, time: Float = 0.5f) =
            GhostExpressionState().apply { start(expression, left) }.frame(time)
        assertEquals(1f, frame(GhostExpression.WINK).leftClosure, 0f)
        assertEquals(0f, frame(GhostExpression.WINK).rightClosure, 0f)
        assertEquals(1f, frame(GhostExpression.WINK, false).rightClosure, 0f)
        assertTrue(frame(GhostExpression.CROSS_EYED).leftPupilX > 0f)
        assertTrue(frame(GhostExpression.CROSS_EYED).rightPupilX < 0f)
        assertEquals(-6f, frame(GhostExpression.EYE_ROLL).pupilY, 0.0001f)
        assertTrue(frame(GhostExpression.CURIOUS_GLANCE).leftPupilX < 0f)
        assertTrue(frame(GhostExpression.CURIOUS_GLANCE, false).leftPupilX > 0f)
        assertTrue(frame(GhostExpression.SHIFTY_EYES, time = 0.25f).leftPupilX < 0f)
        assertTrue(frame(GhostExpression.SHIFTY_EYES, time = 0.65f).leftPupilX > 0f)
        assertEquals(0.5f, frame(GhostExpression.SLEEPY_LIDS).leftClosure, 0f)
        assertEquals(1f, frame(GhostExpression.DOUBLE_BLINK, time = 0.25f).leftClosure, 0f)
        assertEquals(0f, frame(GhostExpression.DOUBLE_BLINK, time = 0.5f).leftClosure, 0f)
        assertEquals(1f, frame(GhostExpression.DOUBLE_BLINK, time = 0.75f).leftClosure, 0f)
    }

    @Test fun slowCloseHoldAndOpenAreSynchronizedWithoutFlashing() {
        assertEquals(0f, blinkClosure(0f), 0.0001f)
        assertEquals(0.5f, blinkClosure(0.16f), 0.0001f)
        assertEquals(1f, blinkClosure(0.32f), 0.0001f)
        assertEquals(1f, blinkClosure(0.5f), 0.0001f)
        assertEquals(1f, blinkClosure(0.68f), 0.0001f)
        assertEquals(0.5f, blinkClosure(0.84f), 0.0001f)
        assertEquals(0f, blinkClosure(1f), 0.0001f)
    }

    @Test fun sleepyHoldAndClosedBlinkAreLongEnoughToSee() {
        val sleepy = GhostExpressionState().apply { start(GhostExpression.SLEEPY_LIDS, true) }
        for (step in 18..82) {
            val frame = sleepy.frame(step / 100f)
            assertEquals(0.5f, frame.leftClosure, 0f)
            assertEquals(0.5f, frame.rightClosure, 0f)
        }
        assertTrue(GhostExpression.SLEEPY_LIDS.durationRange.first * 0.64 >= 1_920)
        for (step in 32..68) assertEquals(1f, blinkClosure(step / 100f), 0f)
        assertTrue(GhostExpression.BLINK.durationRange.first * 0.36 >= 360)
        assertTrue(GhostExpression.DOUBLE_BLINK.durationRange.first / 2 * 0.36 >= 288)
    }

    @Test fun thirteenExpressionsHaveDistinctMotionAndReturnToNeutralWithoutChangingOtherState() {
        assertEquals(13, GhostExpression.entries.size)
        val signatures = GhostExpression.entries.map { expression ->
            val state = GhostExpressionState().apply { start(expression, true) }
            val frames = (0..100).map { state.frame(it / 100f).copy(wordmarkAmount = 1f) }
            assertTrue("Every expression needs visible motion", frames.any { it != frames.first() })
            state.finish()
            assertEquals(ExpressionFrame(wordmarkAmount = if (expression.togglesWordmark) 0f else 1f), state.frame(0f))
            frames
        }
        assertEquals(13, signatures.distinct().size)
    }
}
