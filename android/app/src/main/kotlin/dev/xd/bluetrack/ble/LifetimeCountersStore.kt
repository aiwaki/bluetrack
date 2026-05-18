package dev.xd.bluetrack.ble

import android.content.Context

/**
 * Three running totals that survive a process kill: how many HID
 * reports the phone has emitted in its lifetime, how many encrypted
 * BLE feedback packets it has accepted, and how many rejection events
 * (bad pin, untrusted host, replay drop, malformed write, ...) it has
 * counted. Useful for spotting "this phone is silently rejecting
 * floods" weeks after the fact.
 */
data class LifetimeCountersSnapshot(
    val reports: Long = 0L,
    val feedback: Long = 0L,
    val rejections: Long = 0L,
    /**
     * Per-cause rejection breakdown — added in step 9b so the
     * Diagnostics route can replace its heuristic split with a
     * real histogram. `rejections` is still the sum across all
     * causes so existing call sites keep working.
     *
     * Persisted as one SharedPrefs key per [RejectionCause].
     * Missing causes default to `0L`, so older snapshots remain
     * forward-compatible — a build that does not yet emit a
     * particular cause just leaves that bucket at zero.
     */
    val rejectionsByCause: Map<RejectionCause, Long> = emptyMap(),
)

/**
 * Persistence policy for [LifetimeCountersSnapshot]. Decoupled from
 * the Android `Context` so JVM unit tests can drop in an in-memory
 * fake, mirroring the [TrustedHostPolicy] pattern.
 */
interface LifetimeCountersPolicy {
    fun read(): LifetimeCountersSnapshot
    fun write(snapshot: LifetimeCountersSnapshot)
    fun reset()
}

/**
 * SharedPreferences-backed [LifetimeCountersPolicy].
 *
 * Storage is plain SharedPreferences. The values are not secrets; the
 * OS already protects per-app SharedPreferences from other apps.
 */
class LifetimeCountersStore(
    context: Context,
) : LifetimeCountersPolicy {
    private val prefs =
        context
            .applicationContext
            .getSharedPreferences("bluetrack_lifetime", Context.MODE_PRIVATE)

    override fun read(): LifetimeCountersSnapshot {
        val byCause = RejectionCause.entries
            .associateWith { cause ->
                prefs.getLong(causeKey(cause), 0L)
            }.filterValues { it > 0L }
        return LifetimeCountersSnapshot(
            reports = prefs.getLong(KEY_REPORTS, 0L),
            feedback = prefs.getLong(KEY_FEEDBACK, 0L),
            rejections = prefs.getLong(KEY_REJECTIONS, 0L),
            rejectionsByCause = byCause,
        )
    }

    override fun write(snapshot: LifetimeCountersSnapshot) {
        val editor = prefs
            .edit()
            .putLong(KEY_REPORTS, snapshot.reports)
            .putLong(KEY_FEEDBACK, snapshot.feedback)
            .putLong(KEY_REJECTIONS, snapshot.rejections)
        RejectionCause.entries.forEach { cause ->
            val v = snapshot.rejectionsByCause[cause] ?: 0L
            editor.putLong(causeKey(cause), v)
        }
        editor.apply()
    }

    override fun reset() {
        prefs.edit().clear().apply()
    }

    private fun causeKey(cause: RejectionCause): String = "$KEY_REJECTIONS_PREFIX${cause.name.lowercase()}"

    private companion object {
        private const val KEY_REPORTS = "lifetime_reports"
        private const val KEY_FEEDBACK = "lifetime_feedback"
        private const val KEY_REJECTIONS = "lifetime_rejections"
        private const val KEY_REJECTIONS_PREFIX = "lifetime_rejections_"
    }
}

/**
 * In-memory [LifetimeCountersPolicy] for unit tests.
 */
class InMemoryLifetimeCountersStore(
    initial: LifetimeCountersSnapshot = LifetimeCountersSnapshot(),
) : LifetimeCountersPolicy {
    private var snapshot: LifetimeCountersSnapshot = initial

    override fun read(): LifetimeCountersSnapshot = snapshot

    override fun write(snapshot: LifetimeCountersSnapshot) {
        this.snapshot = snapshot
    }

    override fun reset() {
        snapshot = LifetimeCountersSnapshot()
    }
}
