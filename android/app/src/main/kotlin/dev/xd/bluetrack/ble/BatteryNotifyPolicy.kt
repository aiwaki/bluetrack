package dev.xd.bluetrack.ble

/**
 * Pure cadence decision for the GATT Battery Service NOTIFY.
 *
 * Kept out of [BleHidGateway] so the rule is JVM-unit-testable
 * without Android battery / GATT framework mocks. The gateway owns
 * the `last*` state and the side effect (`notifyCharacteristicChanged`);
 * this object only answers "should we push an update now?".
 *
 * Notify when the level moved, the charging state flipped, or at
 * least [minIntervalMs] elapsed since the last notify — whichever
 * comes first. On the first reading the sentinels (`lastLevel = -1`,
 * `lastCharging = null`) always trip the first two clauses, so the
 * initial state is always pushed.
 */
internal object BatteryNotifyPolicy {
    fun shouldNotify(
        lastLevel: Int,
        newLevel: Int,
        lastCharging: Boolean?,
        newCharging: Boolean,
        lastNotifyAtMs: Long,
        nowMs: Long,
        minIntervalMs: Long,
    ): Boolean {
        if (newLevel != lastLevel) return true
        if (newCharging != lastCharging) return true
        return nowMs - lastNotifyAtMs >= minIntervalMs
    }
}
