package dev.xd.bluetrack.ble

/**
 * Per-peer token-bucket rate limiter for BLE handshake writes.
 *
 * The cryptographic verify path on the peripheral spends ~1 ms per
 * handshake (Ed25519 verify + X25519 derive + HKDF + GATT response).
 * A misbehaving central in BT range can spam the handshake
 * characteristic and force the GATT thread to chew through that
 * crypto on every garbage write, also flooding the activity timeline
 * with rejections.
 *
 * Mitigation: rate-limit handshake writes per peer address. Each
 * peer gets a bucket with [capacity] tokens that refills at
 * [tokensPerSecond]. A write that finds the bucket empty is dropped
 * before any crypto runs. The defaults (4 capacity, 4 tokens/s) mean
 * a legitimate host (which writes one handshake per session, with
 * the occasional retry on a dropped connection) is never rate-limited
 * in practice while a flooder is throttled to a sustained 4 writes/s
 * — slow enough that the timeline stays usable.
 *
 * Buckets are kept per peer-address string. To bound memory under a
 * misbehaving advertiser cycling through random addresses, the map
 * is capped at [maxTrackedPeers]; the least-recently-touched bucket
 * is evicted when a new peer would push the map over the cap.
 *
 * Thread safety: external callers are expected to call from the
 * GATT server callback thread, which is single-threaded. The class
 * is `synchronized` defensively so a future move to a different
 * dispatcher is safe.
 */
class HandshakeRateLimiter(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val tokensPerSecond: Double = DEFAULT_TOKENS_PER_SECOND,
    private val maxTrackedPeers: Int = DEFAULT_MAX_TRACKED_PEERS,
) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(tokensPerSecond > 0.0) { "tokensPerSecond must be > 0" }
        require(maxTrackedPeers > 0) { "maxTrackedPeers must be > 0" }
    }

    private data class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
        var lastTouchMs: Long,
    )

    // LinkedHashMap preserves insertion order; we re-insert on every
    // touch so the eldest entry is the least-recently-touched peer.
    private val buckets = java.util.LinkedHashMap<String, Bucket>(16, 0.75f, false)

    /**
     * Returns true and consumes one token if [peerAddress] is under
     * its rate limit at [nowMs]; false (no token consumed) otherwise.
     */
    @Synchronized
    fun tryAcquire(
        peerAddress: String,
        nowMs: Long,
    ): Boolean {
        val bucket = touch(peerAddress, nowMs)
        // Refill since last visit.
        val elapsedSec = (nowMs - bucket.lastRefillMs).coerceAtLeast(0L) / 1_000.0
        bucket.tokens =
            (bucket.tokens + elapsedSec * tokensPerSecond)
                .coerceAtMost(capacity.toDouble())
        bucket.lastRefillMs = nowMs
        bucket.lastTouchMs = nowMs

        return if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            true
        } else {
            false
        }
    }

    /** Forget [peerAddress] — used when a device disconnects. */
    @Synchronized
    fun release(peerAddress: String) {
        buckets.remove(peerAddress)
    }

    /** Test-only: number of peers currently tracked. */
    @Synchronized
    internal fun trackedPeerCount(): Int = buckets.size

    private fun touch(
        peerAddress: String,
        nowMs: Long,
    ): Bucket {
        val existing = buckets.remove(peerAddress)
        val bucket =
            existing ?: Bucket(
                tokens = capacity.toDouble(),
                lastRefillMs = nowMs,
                lastTouchMs = nowMs,
            )
        // Evict oldest if we're about to exceed the cap.
        while (buckets.size >= maxTrackedPeers) {
            val iter = buckets.entries.iterator()
            if (!iter.hasNext()) break
            iter.next()
            iter.remove()
        }
        buckets[peerAddress] = bucket
        return bucket
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 4
        const val DEFAULT_TOKENS_PER_SECOND: Double = 4.0
        const val DEFAULT_MAX_TRACKED_PEERS: Int = 64
    }
}
