package org.doorcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun increasesDelayAndCapsAtThirtySeconds() {
        assertEquals(2_000L, ReconnectPolicy.delayMillis(0))
        assertEquals(4_000L, ReconnectPolicy.delayMillis(1))
        assertEquals(8_000L, ReconnectPolicy.delayMillis(2))
        assertEquals(16_000L, ReconnectPolicy.delayMillis(3))
        assertEquals(30_000L, ReconnectPolicy.delayMillis(4))
        assertEquals(30_000L, ReconnectPolicy.delayMillis(50))
    }

    @Test
    fun attemptAndResetStayInValidRange() {
        assertEquals(1, ReconnectPolicy.nextAttempt(0))
        assertEquals(4, ReconnectPolicy.nextAttempt(3))
        assertEquals(4, ReconnectPolicy.nextAttempt(4))
        assertEquals(0, ReconnectPolicy.reset())
    }
}

