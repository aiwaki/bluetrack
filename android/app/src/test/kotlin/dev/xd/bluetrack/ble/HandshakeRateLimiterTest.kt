package dev.xd.bluetrack.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeRateLimiterTest {

    @Test
    fun firstNAttemptsFitInCapacity() {
        val limiter = HandshakeRateLimiter(capacity = 4, tokensPerSecond = 4.0)
        var t = 1_000L
        // Default capacity 4 → 4 immediate hits accept, 5th rejects.
        for (i in 0 until 4) {
            assertTrue("attempt $i must accept", limiter.tryAcquire("AA:BB", t))
        }
        assertFalse("attempt 4 must reject", limiter.tryAcquire("AA:BB", t))
    }

    @Test
    fun bucketRefillsAtSpecifiedRate() {
        val limiter = HandshakeRateLimiter(capacity = 4, tokensPerSecond = 4.0)
        var t = 1_000L
        // Drain.
        repeat(4) { limiter.tryAcquire("AA:BB", t) }
        assertFalse(limiter.tryAcquire("AA:BB", t))
        // 250 ms later: one new token.
        t += 250
        assertTrue(limiter.tryAcquire("AA:BB", t))
        assertFalse(limiter.tryAcquire("AA:BB", t))
        // Another 1000 ms: 4 more tokens (capped at capacity).
        t += 1_000
        for (i in 0 until 4) {
            assertTrue("post-refill attempt $i must accept", limiter.tryAcquire("AA:BB", t))
        }
        assertFalse(limiter.tryAcquire("AA:BB", t))
    }

    @Test
    fun bucketCapsAtCapacity() {
        val limiter = HandshakeRateLimiter(capacity = 4, tokensPerSecond = 4.0)
        // First touch at t=1000 with no prior history → starts full at 4.
        assertTrue(limiter.tryAcquire("AA:BB", 1_000L))  // 3 left
        // Wait 100 seconds. Refill should cap at 4, not balloon to 400.
        for (i in 0 until 4) {
            assertTrue("post-cap attempt $i must accept", limiter.tryAcquire("AA:BB", 101_000L + i))
        }
        assertFalse(limiter.tryAcquire("AA:BB", 101_000L))
    }

    @Test
    fun perPeerBucketsAreIsolated() {
        val limiter = HandshakeRateLimiter(capacity = 2, tokensPerSecond = 1.0)
        val t = 1_000L
        assertTrue(limiter.tryAcquire("AA:BB", t))
        assertTrue(limiter.tryAcquire("AA:BB", t))
        assertFalse(limiter.tryAcquire("AA:BB", t))
        // Different peer has its own bucket.
        assertTrue(limiter.tryAcquire("CC:DD", t))
        assertTrue(limiter.tryAcquire("CC:DD", t))
        assertFalse(limiter.tryAcquire("CC:DD", t))
    }

    @Test
    fun lruEvictionKeepsMapBounded() {
        val limiter = HandshakeRateLimiter(
            capacity = 1,
            tokensPerSecond = 1.0,
            maxTrackedPeers = 3,
        )
        var t = 1_000L
        // Touch 3 peers; map fills.
        limiter.tryAcquire("A", t); t += 1
        limiter.tryAcquire("B", t); t += 1
        limiter.tryAcquire("C", t); t += 1
        assertEquals(3, limiter.trackedPeerCount())
        // Touch A again to bump it to most-recent.
        limiter.tryAcquire("A", t); t += 1
        // Add D — eldest (B) must be evicted.
        limiter.tryAcquire("D", t); t += 1
        assertEquals(3, limiter.trackedPeerCount())
        // Drain D.
        assertFalse(limiter.tryAcquire("D", t))
        // Touch B again — its bucket was evicted, so it starts fresh
        // (full capacity) and accepts.
        assertTrue(limiter.tryAcquire("B", t))
        // C may have been evicted depending on touch order. The
        // important property is map size <= cap.
        assertTrue(limiter.trackedPeerCount() <= 3)
    }

    @Test
    fun releaseDropsTrackedPeer() {
        val limiter = HandshakeRateLimiter(capacity = 1, tokensPerSecond = 1.0)
        val t = 1_000L
        limiter.tryAcquire("AA:BB", t)
        assertEquals(1, limiter.trackedPeerCount())
        limiter.release("AA:BB")
        assertEquals(0, limiter.trackedPeerCount())
    }

    @Test
    fun negativeElapsedTimeDoesNotPoisonBucket() {
        val limiter = HandshakeRateLimiter(capacity = 2, tokensPerSecond = 1.0)
        // Some Android wake/sleep transitions can produce a clock
        // that briefly steps backward when SystemClock.elapsedRealtime
        // is read across cores. coerceAtLeast(0) keeps refills sane.
        assertTrue(limiter.tryAcquire("AA:BB", 5_000L))
        assertTrue(limiter.tryAcquire("AA:BB", 4_500L))  // backwards
        assertFalse(limiter.tryAcquire("AA:BB", 4_500L))
    }

    @Test
    fun sustainedFloodIsThrottledToConfiguredRate() {
        val limiter = HandshakeRateLimiter(capacity = 4, tokensPerSecond = 4.0)
        // Simulate 10 seconds of attacker spamming 100 writes per second.
        // Total writes attempted: 1000.
        var accepted = 0
        var t = 1_000L
        for (i in 0 until 1_000) {
            if (limiter.tryAcquire("FLOODER", t)) accepted += 1
            t += 10  // 100 writes/s
        }
        // Burst of 4 + 4 tokens/s × 10 s ≈ 4 + 40 = 44. The actual
        // count fluctuates by ±1 depending on rounding, but stays
        // far below the 1000 attempted.
        assertTrue("accepted=$accepted should be in burst+steady range", accepted in 40..50)
    }
}
