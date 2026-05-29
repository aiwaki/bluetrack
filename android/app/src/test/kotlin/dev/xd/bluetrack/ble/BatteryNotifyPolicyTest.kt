package dev.xd.bluetrack.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryNotifyPolicyTest {
    private val interval = 60_000L

    private fun decide(
        lastLevel: Int,
        newLevel: Int,
        lastCharging: Boolean?,
        newCharging: Boolean,
        lastNotifyAtMs: Long,
        nowMs: Long,
    ) = BatteryNotifyPolicy.shouldNotify(
        lastLevel = lastLevel,
        newLevel = newLevel,
        lastCharging = lastCharging,
        newCharging = newCharging,
        lastNotifyAtMs = lastNotifyAtMs,
        nowMs = nowMs,
        minIntervalMs = interval,
    )

    @Test
    fun firstReadingAlwaysNotifies() {
        // Sentinels (level -1, charging null) trip the change clauses.
        assertTrue(decide(-1, 87, null, false, 0L, 0L))
    }

    @Test
    fun levelChangeNotifiesImmediately() {
        assertTrue(decide(87, 86, false, false, 1_000L, 1_500L))
    }

    @Test
    fun chargingFlipNotifiesImmediately() {
        assertTrue(decide(87, 87, false, true, 1_000L, 1_500L))
    }

    @Test
    fun noChangeWithinIntervalDoesNotNotify() {
        assertFalse(decide(87, 87, false, false, 1_000L, 1_000L + interval - 1))
    }

    @Test
    fun noChangeAfterIntervalNotifies() {
        assertTrue(decide(87, 87, false, false, 1_000L, 1_000L + interval))
    }
}
