package dev.troupe.android.automations.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the ViewModel keeps one controller per project: retained ones always, at most two idle ones besides. */
class RetainedCacheTest {
    private class Value(val key: String)

    private val created = mutableListOf<Value>()
    private val disposed = mutableListOf<Value>()
    private val cache = RetainedCache(maxIdle = 2, create = { key: String -> Value(key).also { created += it } }, dispose = { disposed += it })

    /** What a screen does: takes the value while composing, retains it once shown, releases it when it leaves. */
    private fun show(key: String): Value = cache.get(key).also { cache.retain(key) }

    @Test fun retainedValuesAreNeverEvicted() {
        val kept = show("p1")
        listOf("p2", "p3", "p4", "p5").forEach {
            show(it)
            cache.release(it)
        }

        assertTrue("a retained value was disposed", kept !in disposed)
        assertSame(kept, cache.get("p1"))
    }

    @Test fun aThirdIdleValueEvictsTheLeastRecentlyReleased() {
        val p1 = show("p1")
        val p2 = show("p2")
        val p3 = show("p3")
        cache.release("p2")
        cache.release("p1")
        assertEquals(emptyList<Value>(), disposed)

        cache.release("p3")

        assertEquals(listOf(p2), disposed)
        assertSame(p1, cache.get("p1"))
        assertSame(p3, cache.get("p3"))
    }

    @Test fun retainingAnIdleValueAgainReusesIt() {
        val p1 = show("p1")
        cache.release("p1")

        assertSame(p1, show("p1"))
        // Retained again, it is no longer idle: two more idle values do not evict it.
        listOf("p2", "p3", "p4").forEach {
            show(it)
            cache.release(it)
        }
        assertTrue("a re-retained value was disposed", p1 !in disposed)
        assertEquals(1, created.count { it.key == "p1" })
    }

    @Test fun evictionDisposesTheValueAndItsKeyIsRebuiltOnNextUse() {
        val p1 = show("p1")
        cache.release("p1")
        listOf("p2", "p3").forEach {
            show(it)
            cache.release(it)
        }
        assertEquals(listOf(p1), disposed)

        val again = show("p1")

        assertNotSame(p1, again)
        assertEquals(2, created.count { it.key == "p1" })
    }

    @Test fun aValueStillOnScreenTwiceStaysUntilBothRelease() {
        val p1 = show("p1")
        cache.retain("p1")
        cache.release("p1")
        listOf("p2", "p3", "p4").forEach {
            show(it)
            cache.release(it)
        }

        assertTrue("released by one of two screens and disposed", p1 !in disposed)
    }

    @Test fun takingAnIdleValueProtectsItUntilItIsRetained() {
        val p1 = show("p1")
        cache.release("p1")
        show("p2")
        show("p3")

        // The screen composing p1 has taken it but not retained it yet when two others leave.
        cache.get("p1")
        cache.release("p2")
        cache.release("p3")

        assertTrue("a value being shown was disposed", p1 !in disposed)
    }

    @Test fun clearDisposesEverything() {
        val p1 = show("p1")
        val p2 = show("p2")
        cache.release("p2")

        cache.clear()

        assertEquals(setOf(p1, p2), disposed.toSet())
    }
}
