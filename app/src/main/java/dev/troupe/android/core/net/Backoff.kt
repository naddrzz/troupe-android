package dev.troupe.android.core.net

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Exponential reconnect delays: [base], doubled per attempt up to [max], each varied by ±[jitter]. */
class Backoff(
    private val base: Duration = 500.milliseconds,
    private val max: Duration = 30.seconds,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    fun next(): Duration {
        val exponential = if (attempt >= 30) max else minOf(base * (1L shl attempt).toDouble(), max)
        attempt++
        val varied = exponential * (1 + jitter * (random.nextDouble() * 2 - 1))
        return minOf(varied, max)
    }

    fun reset() {
        attempt = 0
    }
}
