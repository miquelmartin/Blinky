package org.miquelmartin.blinky

import kotlin.math.cos
import kotlin.random.Random

enum class GhostExpression(val weight: Int, val durationRange: LongRange) {
    BLINK(6, 800L..1_200L),
    WINK(3, 900L..1_200L),
    CROSS_EYED(1, 1_300L..1_700L),
    EYE_ROLL(1, 1_400L..1_800L),
    CURIOUS_GLANCE(5, 1_600L..2_000L),
    SHIFTY_EYES(2, 1_500L..1_800L),
    SLEEPY_LIDS(2, 1_600L..2_000L),
    DOUBLE_BLINK(2, 1_200L..1_500L);

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
    val wordmarkAmount: Float = 1f
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
            if (wasVisible) 1f - smooth((shutterTime / 0.42f).coerceAtMost(1f))
            else smooth(((shutterTime - 0.58f) / 0.42f).coerceAtLeast(0f))
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
            GhostExpression.SLEEPY_LIDS -> neutral.copy(leftClosure = held * 0.55f, rightClosure = held * 0.55f)
        }
    }
}

private fun smooth(time: Float) = ((1 - cos(Math.PI * time.coerceIn(0f, 1f))) / 2).toFloat()

fun blinkClosure(fraction: Float): Float {
    val time = fraction.coerceIn(0f, 1f)
    return when {
        time <= 0.42f -> ((1 - cos(Math.PI * time / 0.42)) / 2).toFloat()
        time <= 0.58f -> 1f
        else -> ((1 + cos(Math.PI * (time - 0.58) / 0.42)) / 2).toFloat()
    }
}
