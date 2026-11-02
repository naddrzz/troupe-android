package dev.troupe.android.core.net

import org.junit.Assert.assertFalse
import org.junit.Test

class TransportPolicyTest {
    @Test fun nonCanonicalOctetsFailClosed() {
        listOf("١٠.٠.٠.١", "010.0.0.1", "10.00.0.1", "192.168.01.1").forEach { assertFalse(it, TransportPolicy.isCleartextAllowed(it)) }
    }
}
