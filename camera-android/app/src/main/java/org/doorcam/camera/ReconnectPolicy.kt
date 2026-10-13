package org.doorcam.camera

object ReconnectPolicy {
    private val seconds = listOf(2L, 4L, 8L, 16L, 30L)
    fun delayMillis(attempt: Int): Long = seconds[attempt.coerceAtLeast(0).coerceAtMost(seconds.lastIndex)] * 1_000
    fun nextAttempt(attempt: Int): Int = (attempt + 1).coerceAtMost(seconds.lastIndex)
    fun reset(): Int = 0
}

