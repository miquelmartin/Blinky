package org.miquelmartin.blinky

import kotlin.math.cos
import kotlin.random.Random

data class BlinkTiming(val periodMillis: Long, val durationMillis: Long)

fun nextBlinkTiming(random: Random = Random.Default) =
    BlinkTiming(random.nextLong(8_000, 12_001), random.nextLong(800, 1_201))

fun blinkClosure(fraction: Float): Float {
    val time = fraction.coerceIn(0f, 1f)
    return when {
        time <= 0.42f -> ((1 - cos(Math.PI * time / 0.42)) / 2).toFloat()
        time <= 0.58f -> 1f
        else -> ((1 + cos(Math.PI * (time - 0.58) / 0.42)) / 2).toFloat()
    }
}
