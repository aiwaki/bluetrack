package dev.xd.bluetrack.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class LifetimeCountersTest {

    @Test
    fun inMemoryStoreRoundTripsSnapshot() {
        val store = InMemoryLifetimeCountersStore()
        assertEquals(LifetimeCountersSnapshot(), store.read())
        val s = LifetimeCountersSnapshot(reports = 12, feedback = 3, rejections = 7)
        store.write(s)
        assertEquals(s, store.read())
    }

    @Test
    fun inMemoryStoreResetClearsState() {
        val store = InMemoryLifetimeCountersStore(
            LifetimeCountersSnapshot(reports = 1, feedback = 2, rejections = 3),
        )
        store.reset()
        assertEquals(LifetimeCountersSnapshot(), store.read())
    }

    @Test
    fun accumulatorLoadsPersistedSnapshot() {
        val store = InMemoryLifetimeCountersStore(
            LifetimeCountersSnapshot(reports = 5, feedback = 6, rejections = 7),
        )
        val acc = LifetimeCountersAccumulator(store)
        acc.load()
        assertEquals(LifetimeCountersSnapshot(5, 6, 7), acc.current())
    }

    @Test
    fun reportsBatchedThenFlushedAfterFlushThreshold() {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store, flushEveryN = 10)
        // Below threshold: in-memory advances, store stays at 0.
        repeat(9) { acc.addReports(1L) }
        assertEquals(9L, acc.current().reports)
        assertEquals(0L, store.read().reports)

        // 10th increment crosses threshold: store catches up.
        acc.addReports(1L)
        assertEquals(10L, acc.current().reports)
        assertEquals(10L, store.read().reports)
    }

    @Test
    fun feedbackUsesSameThresholdAsReports() {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store, flushEveryN = 5)
        repeat(4) { acc.addFeedback(1L) }
        assertEquals(0L, store.read().feedback)
        acc.addFeedback(1L)
        assertEquals(5L, store.read().feedback)
    }

    @Test
    fun rejectionsAlwaysFlushSynchronously() {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store, flushEveryN = 1_000_000)
        // Even with a huge threshold, a single rejection persists.
        acc.addRejections(1L)
        assertEquals(1L, store.read().rejections)
        acc.addRejections(2L)
        assertEquals(3L, store.read().rejections)
    }

    @Test
    fun rejectionFlushAlsoPushesQueuedReportDelta() {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store, flushEveryN = 1_000)
        acc.addReports(40L)        // below threshold, not flushed
        acc.addFeedback(20L)       // below threshold, not flushed
        acc.addRejections(1L)      // forces flush; reports + feedback land too
        assertEquals(LifetimeCountersSnapshot(40, 20, 1), store.read())
    }

    @Test
    fun manualFlushPersistsCurrentSnapshot() {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store, flushEveryN = 1_000)
        acc.addReports(7L)
        acc.addFeedback(3L)
        // Below threshold; nothing in store yet.
        assertEquals(LifetimeCountersSnapshot(), store.read())
        acc.flush()
        assertEquals(LifetimeCountersSnapshot(7, 3, 0), store.read())
    }

    @Test
    fun resetClearsBothInMemoryAndStore() {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store, flushEveryN = 1)
        acc.addReports(10L)
        acc.addFeedback(5L)
        acc.addRejections(2L)
        assertEquals(LifetimeCountersSnapshot(10, 5, 2), store.read())
        acc.reset()
        assertEquals(LifetimeCountersSnapshot(), acc.current())
        assertEquals(LifetimeCountersSnapshot(), store.read())
    }

    @Test
    fun loadAfterPersistencePreservesCounters() {
        val store = InMemoryLifetimeCountersStore()
        val acc1 = LifetimeCountersAccumulator(store, flushEveryN = 1)
        acc1.addReports(123L)
        acc1.addFeedback(45L)
        acc1.addRejections(6L)
        // Simulate process kill + restart: fresh accumulator, same store.
        val acc2 = LifetimeCountersAccumulator(store)
        acc2.load()
        assertEquals(LifetimeCountersSnapshot(123, 45, 6), acc2.current())
    }

    @Test
    fun zeroDeltaIsNoOp() {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store, flushEveryN = 1)
        acc.addReports(0L)
        acc.addFeedback(0L)
        acc.addRejections(0L)
        // No persistence, no in-memory change.
        assertEquals(LifetimeCountersSnapshot(), acc.current())
        assertEquals(LifetimeCountersSnapshot(), store.read())
    }

    @Test
    fun negativeDeltaThrowsAndLeavesStateUntouched() {
        val store = InMemoryLifetimeCountersStore()
        val acc = LifetimeCountersAccumulator(store, flushEveryN = 1)
        acc.addReports(5L)
        try {
            acc.addReports(-1L)
            assertEquals("should have thrown", true, false)
        } catch (_: IllegalArgumentException) {
            // ok
        }
        assertEquals(5L, acc.current().reports)
    }
}
