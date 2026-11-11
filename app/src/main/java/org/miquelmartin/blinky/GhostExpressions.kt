package org.miquelmartin.blinky

import kotlin.math.cos
import kotlin.random.Random

enum class GhostExpression(val weight: Int, val durationRange: LongRange) {
    BLINK(6, 1_000L..1_300L),
    WINK(3, 1_000L..1_300L),
    CROSS_EYED(1, 1_300L..1_700L),
    EYE_ROLL(1, 1_400L..1_800L),
    CURIOUS_GLANCE(5, 1_600L..2_000L),
    SHIFTY_EYES(2, 1_500L..1_800L),
    SLEEPY_LIDS(2, 3_000L..3_400L),
    DOUBLE_BLINK(2, 1_600L..1_900L),
    WIDE_EYED(2, 1_500L..1_900L),
    SKEPTICAL(2, 1_800L..2_200L),
    PUPIL_BOB(1, 1_600L..2_000L),
    FIGURE_EIGHT(1, 2_000L..2_400L),
    PEEKABOO(1, 1_900L..2_300L);

    val togglesWordmark get() = this == BLINK || this == DOUBLE_BLINK
}

data class ExpressionTiming(val periodMillis: Long, val durationMillis: Long)

fun nextExpressionTiming(expression: GhostExpression, random: Random = Random.Default) =
    ExpressionTiming(
        random.nextLong(5_000, 10_001),
        random.nextLong(expression.durationRange.first, expression.durationRange.last + 1)
    )

fun nextExpression(previous: GhostExpression?, random: Random = Random.Default): GhostExpression {
    val eligible = GhostExpression.entries.filter { it != previous }
    var ticket = random.nextInt(eligible.sumOf { it.weight })
    for (expression in eligible) {
        ticket -= expression.weight
        if (ticket < 0) return expression
    }
    error("Expression weights must include a positive eligible weight.")
}

data class ExpressionFrame(
    val leftClosure: Float = 0f,
    val rightClosure: Float = 0f,
    val leftPupilX: Float = 0f,
    val rightPupilX: Float = 0f,
    val pupilY: Float = 0f,
    val wordmarkAmount: Float = 1f,
    val pupilScale: Float = 1f
)

class GhostExpressionState(
    var wordmarkVisible: Boolean = true,
    var previous: GhostExpression? = null
) {
    var current: GhostExpression? = null
        private set
    private var winkLeft = true
    private var wasVisible = true

    fun start(expression: GhostExpression, left: Boolean) {
        check(current == null) { "Expressions must not overlap." }
        current = expression
        previous = expression
        winkLeft = left
        wasVisible = wordmarkVisible
        if (expression.togglesWordmark) wordmarkVisible = !wordmarkVisible
    }

    fun finish() { current = null }

    fun frame(fraction: Float): ExpressionFrame {
        val expression = current ?: return ExpressionFrame(wordmarkAmount = if (wordmarkVisible) 1f else 0f)
        val time = fraction.coerceIn(0f, 1f)
        val blinkTime = if (expression == GhostExpression.DOUBLE_BLINK) (time * 2).let {
            if (it <= 1f) it else it - 1f
        } else time
        val closure = blinkClosure(blinkTime)
        val amount = if (!expression.togglesWordmark) {
            if (wordmarkVisible) 1f else 0f
        } else {
            val shutterTime = if (expression == GhostExpression.DOUBLE_BLINK) (time * 2).coerceAtMost(1f) else time
            if (wasVisible) 1f - smooth((shutterTime / 0.32f).coerceAtMost(1f))
            else smooth(((shutterTime - 0.68f) / 0.32f).coerceAtLeast(0f))
        }
        val held = when {
            time < 0.3f -> smooth(time / 0.3f)
            time < 0.65f -> 1f
            else -> 1f - smooth((time - 0.65f) / 0.35f)
        }
        val neutral = ExpressionFrame(wordmarkAmount = amount)
        return when (expression) {
            GhostExpression.BLINK, GhostExpression.DOUBLE_BLINK ->
                neutral.copy(leftClosure = closure, rightClosure = closure)
            GhostExpression.WINK -> neutral.copy(
                leftClosure = if (winkLeft) closure else 0f,
                rightClosure = if (winkLeft) 0f else closure
            )
            GhostExpression.CROSS_EYED -> neutral.copy(leftPupilX = held * 1.5f, rightPupilX = held * -9.5f)
            GhostExpression.EYE_ROLL -> {
                val arc = Math.PI * time
                neutral.copy(
                    leftPupilX = (kotlin.math.cos(arc) * kotlin.math.sin(arc) * 4).toFloat(),
                    rightPupilX = (kotlin.math.cos(arc) * kotlin.math.sin(arc) * 4).toFloat(),
                    pupilY = (-kotlin.math.sin(arc) * 6).toFloat()
                )
            }
            GhostExpression.CURIOUS_GLANCE -> neutral.copy(
                leftPupilX = held * if (winkLeft) -8f else 1.5f,
                rightPupilX = held * if (winkLeft) -8f else 1.5f
            )
            GhostExpression.SHIFTY_EYES -> {
                val look = when {
                    time < 0.25f -> -8f * smooth(time / 0.25f)
                    time < 0.55f -> -8f + 9.5f * smooth((time - 0.25f) / 0.3f)
                    time < 0.75f -> 1.5f
                    else -> 1.5f * (1f - smooth((time - 0.75f) / 0.25f))
                }
                neutral.copy(leftPupilX = look, rightPupilX = look)
            }
            GhostExpression.SLEEPY_LIDS -> {
                val sleepy = when {
                    time < 0.18f -> smooth(time / 0.18f)
                    time <= 0.82f -> 1f
                    else -> 1f - smooth((time - 0.82f) / 0.18f)
                }
                neutral.copy(leftClosure = sleepy * 0.5f, rightClosure = sleepy * 0.5f)
            }
            GhostExpression.WIDE_EYED -> neutral.copy(pupilScale = 1f - held * 0.45f)
            GhostExpression.SKEPTICAL -> neutral.copy(
                leftClosure = held * if (winkLeft) 0.68f else 0.12f,
                rightClosure = held * if (winkLeft) 0.12f else 0.68f,
                leftPupilX = -held * 4f, rightPupilX = -held * 4f
            )
            GhostExpression.PUPIL_BOB -> neutral.copy(
                pupilY = (-kotlin.math.sin(time * Math.PI * 4) * kotlin.math.sin(time * Math.PI) * 4).toFloat()
            )
            GhostExpression.FIGURE_EIGHT -> {
                val envelope = kotlin.math.sin(time * Math.PI)
                val x = (kotlin.math.sin(time * Math.PI * 2) * envelope * 4).toFloat()
                neutral.copy(
                    leftPupilX = x, rightPupilX = x,
                    pupilY = (kotlin.math.sin(time * Math.PI * 4) * envelope * 3).toFloat()
                )
            }
            GhostExpression.PEEKABOO -> neutral.copy(
                leftClosure = if (time <= 0.5f) blinkClosure(time * 2) * 0.8f else 0f,
                rightClosure = if (time > 0.5f) blinkClosure((time - 0.5f) * 2) * 0.8f else 0f
            )
        }
    }
}

private fun smooth(time: Float) = ((1 - cos(Math.PI * time.coerceIn(0f, 1f))) / 2).toFloat()

fun blinkClosure(fraction: Float): Float {
    val time = fraction.coerceIn(0f, 1f)
    return when {
        time <= 0.32f -> smooth(time / 0.32f)
        time <= 0.68f -> 1f
        else -> 1f - smooth((time - 0.68f) / 0.32f)
    }
}
