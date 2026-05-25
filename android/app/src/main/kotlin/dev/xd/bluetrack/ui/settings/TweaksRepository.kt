package dev.xd.bluetrack.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Backing store for the runtime UI tweaks the Appearance group on
 * the Settings route exposes. Replaces the previous "Auto" / "Off"
 * placeholder rows with real persisted state.
 *
 * Schema (4 fields, all immutable-ish — defaults match the
 * canvas's `data-motion="full" data-glass="on"`):
 *
 *  - `motionReduced: Boolean`   — when true, ScreenShell freezes
 *    aurora drift and skips the heartbeat animation. Defaults to
 *    `false`.
 *  - `glassEnabled: Boolean`    — false strips the aurora layer
 *    and renders the dock + cards on a flat surface. Defaults to
 *    `true`.
 *  - `auroraOnLowBattery: Boolean` — UI-only; whether the aurora
 *    layer keeps animating when the device reports a low-battery
 *    state. Defaults to `false` (the canvas default is "off" to
 *    save power on the user's daily-driver). Real Battery
 *    Manager wiring lives in the activity, not this repo.
 *  - `neonStrength: Float`      — 0..1 multiplier on the
 *    `bluetrackGlow` halo + dock active indicator. Defaults to
 *    `1.0`. Below ~0.2 the glow effectively disappears.
 *
 * Kept separate from the `host_identity_v1` SharedPreferences
 * store on purpose — that prefs file is locked to a specific
 * schema by the Ed25519 identity migration path and is read by
 * the BLE handshake on every session start. Mixing user-facing
 * tweaks into it would force a migration on every UI change.
 */
class TweaksRepository(
    private val context: Context,
) {
    private val ds: DataStore<Preferences>
        get() = context.tweaksDataStore

    val motionReduced: Flow<Boolean> = ds.data.map { it[KEY_MOTION_REDUCED] ?: false }
    val glassEnabled: Flow<Boolean> = ds.data.map { it[KEY_GLASS_ENABLED] ?: true }
    val auroraOnLowBattery: Flow<Boolean> = ds.data.map { it[KEY_AURORA_LOW_BAT] ?: false }
    val neonStrength: Flow<Float> = ds.data.map { it[KEY_NEON_STRENGTH] ?: 1f }

    /**
     * Multiplier on the touchpad gesture's baseline gain. The
     * touchpad gesture math applies acceleration + edge boost on
     * top of a fixed `0.42` baseline; this slider scales that
     * baseline so users with small phones (less finger travel) or
     * a preference for snappy cursors can shift the whole curve.
     * Range `0.5..2.0`, default `1.0` (no change).
     */
    val touchpadSensitivity: Flow<Float> = ds.data.map { it[KEY_TOUCHPAD_SENSITIVITY] ?: 1f }

    /**
     * Whether the user has dismissed the first-time touchpad
     * gesture hint overlay. The overlay covers the Hub touchpad
     * the first time it is opened and explains the four gestures
     * (1-finger move, 1-tap click, 2-tap right click, 2-finger
     * scroll). Persisted so reinstalls / "Clear data" re-trigger
     * the flow naturally.
     */
    val touchpadHintsDismissed: Flow<Boolean> = ds.data.map { it[KEY_TOUCHPAD_HINTS_DISMISSED] ?: false }

    /**
     * Whether the gateway auto-connects bonded computer-class
     * hosts. Default `true` matches the original "calm autopilot"
     * design — the user just needs the phone paired and the
     * Mac/PC online for the link to come back. Settings exposes
     * this as a toggle so users who run multiple phones / hosts
     * can opt into a fully manual `tap to CONNECT` flow.
     */
    val autoConnectEnabled: Flow<Boolean> = ds.data.map { it[KEY_AUTO_CONNECT] ?: true }

    /**
     * True after the user has dismissed the first-run Welcome
     * screen. The shell hides Welcome and proceeds to the dock
     * once this flips. Persisted so reinstalls do not re-trigger
     * the flow if data was retained, while a fresh install (or
     * "Clear data" from system settings) shows it again.
     */
    val onboarded: Flow<Boolean> = ds.data.map { it[KEY_ONBOARDED] ?: false }

    /**
     * Theme mode preference. One of `SYSTEM` / `LIGHT` / `DARK`.
     * `SYSTEM` resolves at composition time via
     * `isSystemInDarkTheme()` so the app follows the OS toggle.
     * Default `SYSTEM` matches the platform expectation — the
     * very first launch picks up whatever the user already runs
     * the rest of their phone in.
     */
    val themeMode: Flow<String> = ds.data.map { it[KEY_THEME_MODE] ?: "SYSTEM" }

    suspend fun setMotionReduced(value: Boolean) {
        ds.edit { it[KEY_MOTION_REDUCED] = value }
    }

    suspend fun setGlassEnabled(value: Boolean) {
        ds.edit { it[KEY_GLASS_ENABLED] = value }
    }

    suspend fun setAuroraOnLowBattery(value: Boolean) {
        ds.edit { it[KEY_AURORA_LOW_BAT] = value }
    }

    suspend fun setNeonStrength(value: Float) {
        ds.edit { it[KEY_NEON_STRENGTH] = value.coerceIn(0f, 1f) }
    }

    suspend fun setTouchpadSensitivity(value: Float) {
        ds.edit { it[KEY_TOUCHPAD_SENSITIVITY] = value.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setTouchpadHintsDismissed(value: Boolean) {
        ds.edit { it[KEY_TOUCHPAD_HINTS_DISMISSED] = value }
    }

    suspend fun setAutoConnectEnabled(value: Boolean) {
        ds.edit { it[KEY_AUTO_CONNECT] = value }
    }

    suspend fun setOnboarded(value: Boolean) {
        ds.edit { it[KEY_ONBOARDED] = value }
    }

    suspend fun setThemeMode(value: String) {
        ds.edit { it[KEY_THEME_MODE] = value }
    }

    /**
     * Persist all tweak keys in a single atomic DataStore
     * transaction. Used by the Settings route so a fast slider
     * drag (or any rapid sequence of changes) cannot interleave
     * stale snapshots across coroutines and revert newer values.
     * Codex review on PR #53 flagged the per-key sequence as a
     * race; this is the fix.
     */
    suspend fun setAll(state: TweaksState) {
        ds.edit {
            it[KEY_MOTION_REDUCED] = state.motionReduced
            it[KEY_GLASS_ENABLED] = state.glassEnabled
            it[KEY_AURORA_LOW_BAT] = state.auroraOnLowBattery
            it[KEY_NEON_STRENGTH] = state.neonStrength.coerceIn(0f, 1f)
            it[KEY_AUTO_CONNECT] = state.autoConnectEnabled
            it[KEY_TOUCHPAD_SENSITIVITY] = state.touchpadSensitivity.coerceIn(0.5f, 2.0f)
        }
    }

    private companion object {
        val KEY_MOTION_REDUCED = booleanPreferencesKey("motion_reduced")
        val KEY_GLASS_ENABLED = booleanPreferencesKey("glass_enabled")
        val KEY_AURORA_LOW_BAT = booleanPreferencesKey("aurora_on_low_battery")
        val KEY_NEON_STRENGTH = floatPreferencesKey("neon_strength")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect_enabled")
        val KEY_TOUCHPAD_SENSITIVITY = floatPreferencesKey("touchpad_sensitivity")
        val KEY_TOUCHPAD_HINTS_DISMISSED = booleanPreferencesKey("touchpad_hints_dismissed")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        val KEY_THEME_MODE = androidx.datastore.preferences.core
            .stringPreferencesKey("theme_mode")
    }
}

private val Context.tweaksDataStore: DataStore<Preferences> by preferencesDataStore(name = "bluetrack_tweaks_v1")

/**
 * Snapshot of the four tweak values. Bundled together so the
 * activity passes a single `TweaksState` down the shell instead
 * of plumbing four separate flows.
 */
data class TweaksState(
    val motionReduced: Boolean = false,
    val glassEnabled: Boolean = true,
    val auroraOnLowBattery: Boolean = false,
    val neonStrength: Float = 1f,
    val autoConnectEnabled: Boolean = true,
    val touchpadSensitivity: Float = 1f,
) {
    companion object {
        val Default = TweaksState()
    }
}
