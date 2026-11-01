package dev.troupe.android.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test fun doublesAndCaps() {
        val b = Backoff(1.seconds, 4.seconds, jitter = 0.0)
        assertEquals(listOf(1, 2, 4, 4).map { it.seconds }, List(4) { b.next() })
    }

    @Test fun startsAtHalfASecondAndCapsAtThirtySeconds() {
        val b = Backoff(jitter = 0.0)
        assertEquals(listOf(500, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000).map { it.milliseconds }, List(8) { b.next() })
    }

    @Test fun resetStartsOver() {
        val b = Backoff(jitter = 0.0)
        repeat(5) { b.next() }
        b.reset()
        assertEquals(500.milliseconds, b.next())
    }

    @Test fun jitterStaysWithinTwentyPercentAndNeverPassesTheCap() {
        val b = Backoff(random = Random(7))
        repeat(200) { b.reset(); val d = b.next(); assertTrue("$d", d in 400.milliseconds..600.milliseconds) }
        val delays = List(20) { b.next() }.drop(10)
        assertTrue("$delays", delays.all { it in 24.seconds..30.seconds })
        assertTrue("jitter varies the delay: $delays", delays.toSet().size > 1)
    }
}
