package dev.xd.bluetrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ble.GatewayStatus
import dev.xd.bluetrack.ui.Route
import dev.xd.bluetrack.ui.hub.HubHeader
import dev.xd.bluetrack.ui.hub.SectionLabel
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Settings route. Mirrors canvas `SettingsScreen`
 * (`docs/design/v1/settings.jsx`).
 *
 * Six groups in canvas order:
 *
 *  - **Identity** — visible-as name, trusted-host shortcut, raw
 *    fingerprint, show-QR + forget actions.
 *  - **Connectivity** — FG-service state, auto-connect toggle,
 *    HID profile / BLE advertiser / multi-adv availability.
 *  - **Permissions** — BT nearby + notifications grants, link to
 *    system permissions screen.
 *  - **Diagnostics & Activity** — deep links to those two
 *    routes + export shortcut.
 *  - **Appearance** — Tweaks panel placeholder + reduce-motion
 *    + aurora-on-low-battery toggles (UI-only for now).
 *  - **About** — version, commit, identity storage hint,
 *    source-code link, "what is Bluetrack?".
 *
 * Wired to [GatewayStatus] for everything the gateway tracks
 * today; the rest are surface-level placeholders until backing
 * DataStore + permissions plumbing land.
 */
@Composable
fun SettingsScreen(
    status: GatewayStatus,
    tweaks: TweaksState,
    onTweakChange: (TweaksState) -> Unit,
    onNavigate: (Route) -> Unit,
    onForgetHost: () -> Unit,
    versionName: String,
    versionCode: Int,
    /**
     * Runtime BT nearby permission grant. `null` = activity has
     * not plumbed `ContextCompat.checkSelfPermission(...)` through
     * yet; the row renders as "Unknown" instead of guessing from
     * adapter state (which would mislead a user who has granted
     * the permission but disabled the BT radio).
     */
    nearbyPermissionGranted: Boolean? = null,
    /**
     * Runtime `POST_NOTIFICATIONS` grant (API 33+). `null` when
     * not plumbed; rendered as "Unknown".
     */
    notificationsPermissionGranted: Boolean? = null,
    commitShort: String? = null,
    modifier: Modifier = Modifier,
) {
    val compat = status.compatibility
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        HubHeader(title = "Settings")
        SettingsGroup(title = "IDENTITY") {
            SettingsRow(
                label = "Visible as",
                value = "Bluetrack Pro Engine",
                mono = true,
                hint = "Search for this name in macOS · Windows BT settings",
            )
            val trusted = status.trustedHostFingerprint
            SettingsRow(
                label = "Trusted host",
                value = status.host ?: trusted?.let { "fingerprint pinned" } ?: "—",
                accent = trusted != null,
                kind = SettingsRowKind.Chev,
            )
            if (trusted != null) {
                SettingsRow(
                    label = "Fingerprint",
                    value = trusted,
                    mono = true,
                )
            }
            SettingsRow(label = "Show identity QR", kind = SettingsRowKind.Chev)
            SettingsRow(
                label = "Forget host…",
                kind = SettingsRowKind.Chev,
                onClick = if (trusted != null) onForgetHost else null,
            )
        }
        SettingsGroup(title = "CONNECTIVITY") {
            SettingsRow(
                label = "Foreground service",
                value = if (compat.bluetoothEnabled) "Running" else "Off",
                accent = compat.bluetoothEnabled,
            )
            SettingsRow(
                label = "Auto-connect to bonded",
                value = "On",
                hint = "Computer-class hosts only · audio + accessories skipped",
            )
            SettingsRow(label = "HID profile", value = compat.hidProfile)
            SettingsRow(
                label = "BLE advertiser",
                value = compat.bleAdvertiserAvailable.availabilityLabel(),
            )
            SettingsRow(
                label = "Multi advertisement",
                value = compat.multipleAdvertisementSupported.availabilityLabel(),
            )
            SettingsRow(label = "Scan mode", value = compat.scanMode, mono = true)
        }
        SettingsGroup(title = "PERMISSIONS") {
            // Drive directly from runtime grant state, not from
            // adapter power. The two are separate (a user can
            // grant the permission and still toggle Bluetooth off
            // — that should not show as `Required` here).
            SettingsRow(
                label = "Bluetooth nearby",
                value = nearbyPermissionGranted.grantLabel(),
                accent = nearbyPermissionGranted == true,
            )
            SettingsRow(
                label = "Notifications",
                value = notificationsPermissionGranted.grantLabel(),
                accent = notificationsPermissionGranted == true,
                kind = SettingsRowKind.Chev,
            )
            SettingsRow(label = "Manage all permissions", kind = SettingsRowKind.Chev)
        }
        SettingsGroup(title = "DIAGNOSTICS & ACTIVITY") {
            SettingsRow(
                label = "Open Diagnostics",
                kind = SettingsRowKind.Chev,
                onClick = { onNavigate(Route.Diagnostics) },
            )
            SettingsRow(
                label = "Open Activity log",
                kind = SettingsRowKind.Chev,
                onClick = { onNavigate(Route.Activity) },
            )
            SettingsRow(label = "Export session log", kind = SettingsRowKind.Chev)
        }
        SettingsGroup(title = "APPEARANCE") {
            SettingsToggleRow(
                label = "Glass surfaces",
                hint = "Aurora layer + tinted cards. Off renders flat.",
                checked = tweaks.glassEnabled,
                onCheckedChange = { onTweakChange(tweaks.copy(glassEnabled = it)) },
            )
            SettingsToggleRow(
                label = "Reduce motion",
                hint = "Freezes aurora drift + breath rings. Static halos stay visible.",
                checked = tweaks.motionReduced,
                onCheckedChange = { onTweakChange(tweaks.copy(motionReduced = it)) },
            )
            SettingsToggleRow(
                label = "Aurora on low battery",
                hint = "Keep the aurora animating below 20% charge.",
                checked = tweaks.auroraOnLowBattery,
                onCheckedChange = { onTweakChange(tweaks.copy(auroraOnLowBattery = it)) },
            )
            SettingsSliderRow(
                label = "Neon strength",
                hint = "Halo intensity on the dock indicator + status hero.",
                value = tweaks.neonStrength,
                onValueChange = { onTweakChange(tweaks.copy(neonStrength = it)) },
            )
        }
        SettingsGroup(title = "ABOUT") {
            SettingsRow(
                label = "Version",
                value = "$versionName (build $versionCode)",
                mono = true,
            )
            commitShort?.let {
                SettingsRow(label = "Commit", value = it, mono = true)
            }
            SettingsRow(
                label = "Identity storage",
                value = "SharedPreferences · host_identity_v1",
                mono = true,
                hint = "App-private, never leaves the device",
            )
            SettingsRow(label = "Source code", kind = SettingsRowKind.Ext)
            SettingsRow(label = "What is Bluetrack?", kind = SettingsRowKind.Chev)
        }
        // Bottom breathing room so the dock never overlaps the last row.
        Box(modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = BluetrackTokens.Sp6),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
    ) {
        SectionLabel(label = title)
        val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .btGlass(strong = false, shape = shape),
        ) {
            // Canvas's hairline-between-row look is approximated by
            // each row's natural 13 dp vertical padding; the eye
            // reads the implied separation. Real dividers can come
            // later if the group composable accepts a list rather
            // than a slot.
            content()
        }
    }
}

private fun Boolean?.availabilityLabel(): String = when (this) {
    true -> "Available"
    false -> "Not supported"
    null -> "Unknown"
}

private fun Boolean?.grantLabel(): String = when (this) {
    true -> "Granted"
    false -> "Required"
    null -> "Unknown"
}
