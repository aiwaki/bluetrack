package dev.xd.bluetrack.ble

/**
 * Throttled accumulator over a [LifetimeCountersPolicy].
 *
 * `BleHidGateway` increments at HID-report rates (potentially 200/s),
 * which would be wasteful to push to SharedPreferences on every
 * event. The accumulator keeps counters in memory and writes through
 * to the underlying store only every [flushEveryN] increments — except
 * for rejection events, which are rare and important enough to flush
 * immediately so a process kill cannot lose them.
 *
 * The accumulator is thread-safe via plain `synchronized`. Construct
 * one per gateway lifetime; call [load] once on startup, then the
 * `add*` / [flush] / [reset] methods from any thread.
 */
class LifetimeCountersAccumulator(
    private val store: LifetimeCountersPolicy,
    private val flushEveryN: Int = DEFAULT_FLUSH_EVERY_N,
) {
    init {
        require(flushEveryN > 0) { "flushEveryN must be > 0" }
    }

    private var snapshot = LifetimeCountersSnapshot()
    private var unflushedDelta = 0L

    /** Read the persisted snapshot into memory. Call once on startup. */
    @Synchronized
    fun load() {
        snapshot = store.read()
        unflushedDelta = 0L
    }

    /** Current in-memory snapshot. */
    @Synchronized
    fun current(): LifetimeCountersSnapshot = snapshot

    /** Increment the report counter by [n] (≥ 0). May persist. */
    @Synchronized
    fun addReports(n: Long) {
        require(n >= 0) { "n must be >= 0" }
        if (n == 0L) return
        snapshot = snapshot.copy(reports = snapshot.reports + n)
        accumulateAndMaybeFlush(n)
    }

    /** Increment the feedback counter by [n] (≥ 0). May persist. */
    @Synchronized
    fun addFeedback(n: Long) {
        require(n >= 0) { "n must be >= 0" }
        if (n == 0L) return
        snapshot = snapshot.copy(feedback = snapshot.feedback + n)
        accumulateAndMaybeFlush(n)
    }

    /**
     * Increment the rejection counter by [n] (≥ 0). Always persists
     * synchronously — rejections are rare and informative; never
     * lose a count to a process kill.
     *
     * Prefer [addRejection] when the cause is known so the
     * Diagnostics route can surface a real per-category
     * breakdown. This bare-int form stays for back-compat with
     * any call site that genuinely cannot attribute the
     * rejection (none today, but the API surface is permissive).
     */
    @Synchronized
    fun addRejections(n: Long) {
        require(n >= 0) { "n must be >= 0" }
        if (n == 0L) return
        snapshot = snapshot.copy(rejections = snapshot.rejections + n)
        store.write(snapshot)
        unflushedDelta = 0L
    }

    /**
     * Increment both the total rejection counter and the
     * per-cause bucket for [cause]. Always persists synchronously
     * — same guarantee as [addRejections].
     *
     * Each cause is tracked independently in
     * `snapshot.rejectionsByCause`; the sum across buckets is
     * always equal to `snapshot.rejections` (we update both in
     * the same critical section).
     */
    @Synchronized
    fun addRejection(cause: RejectionCause) {
        val updatedByCause = snapshot.rejectionsByCause.toMutableMap().also { map ->
            map[cause] = (map[cause] ?: 0L) + 1L
        }
        snapshot = snapshot.copy(
            rejections = snapshot.rejections + 1L,
            rejectionsByCause = updatedByCause,
        )
        store.write(snapshot)
        unflushedDelta = 0L
    }

    /** Force-persist the current snapshot. Call on shutdown. */
    @Synchronized
    fun flush() {
        store.write(snapshot)
        unflushedDelta = 0L
    }

    /** Reset both in-memory and persisted state to zero. */
    @Synchronized
    fun reset() {
        snapshot = LifetimeCountersSnapshot()
        store.reset()
        unflushedDelta = 0L
    }

    private fun accumulateAndMaybeFlush(delta: Long) {
        unflushedDelta += delta
        if (unflushedDelta >= flushEveryN) {
            store.write(snapshot)
            unflushedDelta = 0L
        }
    }

    companion object {
        const val DEFAULT_FLUSH_EVERY_N: Int = 50
    }
}
