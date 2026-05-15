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

    override fun read(): LifetimeCountersSnapshot = LifetimeCountersSnapshot(
        reports = prefs.getLong(KEY_REPORTS, 0L),
        feedback = prefs.getLong(KEY_FEEDBACK, 0L),
        rejections = prefs.getLong(KEY_REJECTIONS, 0L),
    )

    override fun write(snapshot: LifetimeCountersSnapshot) {
        prefs
            .edit()
            .putLong(KEY_REPORTS, snapshot.reports)
            .putLong(KEY_FEEDBACK, snapshot.feedback)
            .putLong(KEY_REJECTIONS, snapshot.rejections)
            .apply()
    }

    override fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val KEY_REPORTS = "lifetime_reports"
        private const val KEY_FEEDBACK = "lifetime_feedback"
        private const val KEY_REJECTIONS = "lifetime_rejections"
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
