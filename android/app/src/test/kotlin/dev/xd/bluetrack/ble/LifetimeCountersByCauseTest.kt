package dev.xd.bluetrack.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the per-cause rejection counter plumbing added
 * in step 9b. Verifies that `addRejection(cause)` increments
 * both the total and the typed bucket atomically, that the
 * in-memory snapshot matches the persisted snapshot after each
 * write, and that `reset()` clears everything.
 */
class LifetimeCountersByCauseTest {
    private fun fresh(): Pair<InMemoryLifetimeCountersStore, LifetimeCountersAccumulator> {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store).also { it.load() }
        return store to acc
    }

    @Test
    fun typedRejectionIncrementsBothBucketAndTotal() {
        val (store, acc) = fresh()
        acc.addRejection(RejectionCause.Gcm)
        val snap = acc.current()
        assertEquals(1L, snap.rejections)
        assertEquals(1L, snap.rejectionsByCause[RejectionCause.Gcm])
        // Persisted snapshot mirrors the in-memory one.
        assertEquals(snap, store.read())
    }

    @Test
    fun multipleCausesEachLandInOwnBucket() {
        val (_, acc) = fresh()
        acc.addRejection(RejectionCause.Gcm)
        acc.addRejection(RejectionCause.Gcm)
        acc.addRejection(RejectionCause.Replay)
        acc.addRejection(RejectionCause.Untrusted)
        acc.addRejection(RejectionCause.RateLimit)
        val snap = acc.current()
        assertEquals(5L, snap.rejections)
        assertEquals(2L, snap.rejectionsByCause[RejectionCause.Gcm])
        assertEquals(1L, snap.rejectionsByCause[RejectionCause.Replay])
        assertEquals(1L, snap.rejectionsByCause[RejectionCause.Untrusted])
        assertEquals(1L, snap.rejectionsByCause[RejectionCause.RateLimit])
        // Causes we never emitted remain absent (we filter zeros
        // when reading, so the map only carries non-zero buckets).
        assertEquals(null, snap.rejectionsByCause[RejectionCause.Size])
    }

    @Test
    fun untypedAddRejectionsKeepsLegacyAggregateBehaviour() {
        val (_, acc) = fresh()
        acc.addRejections(3L)
        val snap = acc.current()
        assertEquals(3L, snap.rejections)
        // Untyped path does not populate the per-cause map —
        // callers that want the breakdown must use addRejection.
        assertEquals(emptyMap<RejectionCause, Long>(), snap.rejectionsByCause)
    }

    @Test
    fun resetClearsAllBuckets() {
        val (store, acc) = fresh()
        acc.addRejection(RejectionCause.Gcm)
        acc.addRejection(RejectionCause.Replay)
        acc.reset()
        val snap = acc.current()
        assertEquals(0L, snap.rejections)
        assertEquals(emptyMap<RejectionCause, Long>(), snap.rejectionsByCause)
        assertEquals(snap, store.read())
    }

    @Test
    fun loadRestoresPersistedBuckets() {
        val store = InMemoryLifetimeCountersStore(
            initial = LifetimeCountersSnapshot(
                reports = 10L,
                feedback = 2L,
                rejections = 3L,
                rejectionsByCause = mapOf(
                    RejectionCause.Gcm to 2L,
                    RejectionCause.Replay to 1L,
                ),
            ),
        )
        val acc = LifetimeCountersAccumulator(store).also { it.load() }
        val snap = acc.current()
        assertEquals(10L, snap.reports)
        assertEquals(2L, snap.feedback)
        assertEquals(3L, snap.rejections)
        assertEquals(2L, snap.rejectionsByCause[RejectionCause.Gcm])
        assertEquals(1L, snap.rejectionsByCause[RejectionCause.Replay])
    }
}
