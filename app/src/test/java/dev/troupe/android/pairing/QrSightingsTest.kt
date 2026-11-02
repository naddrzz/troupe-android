package dev.troupe.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class QrSightingsTest {
    @Test fun aCodeInViewIsHandedOverOnce() {
        val sightings = QrSightings()
        assertEquals(listOf("a", null, null, "b"), listOf("a", "a", "a", "b").map(sightings::next))
    }

    @Test fun theSameCodeCountsAgainAfterLeavingTheView() {
        val sightings = QrSightings()
        assertEquals(listOf("a", null, null, "a"), listOf("a", "a", null, "a").map(sightings::next))
    }
}
