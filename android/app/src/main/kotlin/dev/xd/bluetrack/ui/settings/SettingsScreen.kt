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
import dev.xd.bluetrack.ui.hub.SectionLabel
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Settings route. Slimmed down to four groups that actually
 * carry signal: read-only Bluetooth state the user might care
 * about, permission shortcuts, a maintenance action, and the
 * About block. Identity / appearance / route-shortcut groups
 * were removed — Hub already surfaces the trusted host pin,
 * the dock covers Diagnostics and Activity, and the appearance
 * toggles drove cosmetic-only knobs that the design now keeps
 * at a fixed baseline.
 *
 *  - **Connection** — visible-as name + read-only adapter
 *    snapshot (FG service, BLE advertiser, multi-adv, scan
 *    mode). Auto-connect behaviour is implicit ("computer-class
 *    hosts only") and called out in the hint.
 *  - **Permissions** — BT nearby + notifications grants with
 *    clickable Manage shortcut. Tapping a row that is
 *    "Required" / unknown opens the matching system page.
 *  - **Maintenance** — single action: reset lifetime counters.
 *    Useful for new-device testing without reinstalling.
 *  - **About** — version, commit, source-code link.
 */
@Composable
fun SettingsScreen(
    status: GatewayStatus,
    versionName: String,
    versionCode: Int,
    /**
     * Runtime BT nearby permission grant. `null` = activity has
     * not plumbed `ContextCompat.checkSelfPermission(...)` through
     * yet; the row renders as "Unknown" instead of guessing from
     * adapter state.
     */
    nearbyPermissionGranted: Boolean? = null,
    /**
     * Runtime `POST_NOTIFICATIONS` grant (API 33+). `null` when
     * not plumbed.
     */
    notificationsPermissionGranted: Boolean? = null,
    commitShort: String? = null,
    autoConnectEnabled: Boolean = true,
    onAutoConnectChange: (Boolean) -> Unit = {},
    touchpadSensitivity: Float = 1f,
    onTouchpadSensitivityChange: (Float) -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenAppPermissions: () -> Unit = {},
    onOpenSourceCode: () -> Unit = {},
    onResetLifetimeCounters: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val compat = status.compatibility
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 100.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        Box(
            modifier = dev.xd.bluetrack.ui
                .rememberStaggerModifier(index = 0),
        ) {
            SettingsGroup(title = "CONNECTION") {
                // Read-only adapter / capability readouts (Visible as,
                // Foreground service, BLE advertiser, Multi
                // advertisement) moved to Diagnostics → System: they are
                // diagnostic state, not settings. CONNECTION now holds
                // only what the user can actually change.
                SettingsToggleRow(
                    label = "Auto-connect to bonded host",
                    hint = "Computer-class hosts only · audio + accessories are skipped",
                    checked = autoConnectEnabled,
                    onCheckedChange = onAutoConnectChange,
                )
            }
        }
        Box(
            modifier = dev.xd.bluetrack.ui
                .rememberStaggerModifier(index = 1),
        ) {
            SettingsGroup(title = "PERMISSIONS") {
                // Drive directly from runtime grant state — adapter
                // power is independent (a user can grant the
                // permission and still toggle BT off; that should
                // not say "Required" here).
                SettingsRow(
                    label = "Bluetooth nearby",
                    value = nearbyPermissionGranted.grantLabel(),
                    accent = nearbyPermissionGranted == true,
                    kind = SettingsRowKind.Chev,
                    onClick = onOpenAppPermissions,
                )
                SettingsRow(
                    label = "Notifications",
                    value = notificationsPermissionGranted.grantLabel(),
                    accent = notificationsPermissionGranted == true,
                    kind = SettingsRowKind.Chev,
                    onClick = onOpenNotificationSettings,
                )
                SettingsRow(
                    label = "Manage all permissions",
                    kind = SettingsRowKind.Chev,
                    onClick = onOpenAppPermissions,
                )
            }
        }
        Box(
            modifier = dev.xd.bluetrack.ui
                .rememberStaggerModifier(index = 2),
        ) {
            SettingsGroup(title = "INPUT") {
                // Touchpad sensitivity multiplier. The Hub touchpad
                // pipeline applies a fixed `0.42` baseline gain plus
                // velocity acceleration + edge boost; this slider
                // scales the baseline `×0.5..×2.0` so users with
                // small phones or a preference for a snappier cursor
                // can shift the whole curve without tweaking
                // acceleration directly. Mirror surface + external
                // mice are unaffected.
                SettingsSliderRow(
                    label = "Touchpad sensitivity",
                    hint = "Acceleration + edge boost still apply on top.",
                    value = touchpadSensitivity,
                    valueRange = 0.5f..2.0f,
                    valueLabel = { v -> "%.2fx".format(v) },
                    onValueChange = onTouchpadSensitivityChange,
                )
            }
        }
        Box(
            modifier = dev.xd.bluetrack.ui
                .rememberStaggerModifier(index = 4),
        ) {
            SettingsGroup(title = "MAINTENANCE") {
                // The only mutating action on the route. Lifetime
                // counters survive process kill (see
                // `LifetimeCountersAccumulator`); a manual reset is
                // useful when re-testing a fresh pairing or before
                // capturing a clean diagnostic snapshot.
                SettingsRow(
                    label = "Reset lifetime counters",
                    kind = SettingsRowKind.Chev,
                    onClick = onResetLifetimeCounters,
                    hint = "Clears report / feedback / rejection totals",
                )
            }
        }
        Box(
            modifier = dev.xd.bluetrack.ui
                .rememberStaggerModifier(index = 5),
        ) {
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
                    label = "Source code",
                    kind = SettingsRowKind.Ext,
                    onClick = onOpenSourceCode,
                )
            }
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

private fun Boolean?.grantLabel(): String = when (this) {
    true -> "Granted"
    false -> "Required"
    null -> "Unknown"
}
