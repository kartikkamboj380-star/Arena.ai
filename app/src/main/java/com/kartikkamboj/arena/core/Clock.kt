package com.kartikkamboj.arena.core

/**
 * Injectable time source. Everything that reasons about session age or "last active" stamps
 * reads the clock through this interface, which keeps the session state machine testable on the
 * JVM without Robolectric.
 */
interface Clock {
    fun nowMillis(): Long
}

val SystemClock: Clock = object : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Mutable clock for tests. */
class FakeClock(start: Long = 0L) : Clock {
    var now: Long = start
        private set

    override fun nowMillis(): Long = now

    fun advanceMillis(delta: Long) {
        now += delta
    }

    fun set(value: Long) {
        now = value
    }
}

/** Human readable relative age, e.g. "just now", "14 min ago", "3 h ago", "2 d ago". */
fun relativeAge(epochMillis: Long, now: Long): String {
    if (epochMillis <= 0L) return "never"
    val delta = (now - epochMillis).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 60L * 24L -> "${minutes / 60L} h ago"
        minutes < 60L * 24L * 30L -> "${minutes / (60L * 24L)} d ago"
        else -> "${minutes / (60L * 24L * 30L)} mo ago"
    }
}
