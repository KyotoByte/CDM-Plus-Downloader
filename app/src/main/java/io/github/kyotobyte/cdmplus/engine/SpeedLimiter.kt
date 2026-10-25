package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.delay

/** Global token-bucket style limiter shared by every connection. 0 = unlimited. */
class SpeedLimiter(@Volatile var bytesPerSecond: Long = 0) {
    private var windowStart = System.nanoTime()
    private var used = 0L

    suspend fun acquire(n: Int) {
        val bps = bytesPerSecond
        if (bps <= 0) return
        val waitMs: Long
        synchronized(this) {
            val now = System.nanoTime()
            if (now - windowStart > 1_000_000_000L) {
                windowStart = now
                used = 0
            }
            used += n
            val allowedNs = used * 1_000_000_000L / bps
            waitMs = (allowedNs - (now - windowStart)) / 1_000_000L
        }
        if (waitMs > 0) delay(waitMs)
    }
}
