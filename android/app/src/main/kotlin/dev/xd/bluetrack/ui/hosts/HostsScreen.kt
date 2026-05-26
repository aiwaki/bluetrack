package dev.xd.bluetrack.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ble.BluetoothHostKind
import dev.xd.bluetrack.ble.GatewayStatus
import dev.xd.bluetrack.ui.hub.HubHeader
import dev.xd.bluetrack.ui.hub.SectionLabel
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Hosts route. Lists bonded BLE devices from
 * [GatewayStatus.compatibility.bondedDevices], reads the real
 * Bluetooth Class of Device classification the gateway emits in
 * `compatibility.hostKinds`, and renders them in two sections
 * (Computers / Accessories). Matches canvas `HostsScreen`
 * (`docs/design/v1/hosts.jsx`).
 *
 * The route is rendered inside the shared `ScreenShell` so the
 * dock + aurora stay the same as Hub. An [InfoSheet]-style
 * bottom panel surfaces compat caveats (e.g. iOS HID
 * restriction) when the user taps the warn icon next to a row.
 */
@Composable
fun HostsScreen(
    status: GatewayStatus,
    onConnectHost: (String) -> Unit,
    onDisconnectHost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val entries = remember(status) { buildEntries(status) }
    val computers = entries.filter { it.klass == HostClass.Computer }
    val accessories = entries.filter { it.klass != HostClass.Computer }
    var caveat by remember { mutableStateOf<String?>(null) }
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
        ) {
            HubHeader(title = "Hosts")
            if (entries.isEmpty()) {
                Box(modifier = dev.xd.bluetrack.ui.rememberStaggerModifier(index = 0)) {
                    EmptyState()
                }
            } else {
                Column(
                    modifier = Modifier
                        .padding(horizontal = BluetrackTokens.Sp6)
                        .then(dev.xd.bluetrack.ui.rememberStaggerModifier(index = 0)),
                    verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
                ) {
                    SectionLabel(label = "Computers · ${computers.size}")
                    computers.forEach { host ->
                        HostRow(
                            host = host,
                            onConnect = { onConnectHost(host.name) },
                            onDisconnect = { onDisconnectHost(host.name) },
                            onShowCaveat = { caveat = it },
                            modifier = Modifier.padding(bottom = BluetrackTokens.Sp1),
                        )
                    }
                }
                if (accessories.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = BluetrackTokens.Sp6)
                            .then(dev.xd.bluetrack.ui.rememberStaggerModifier(index = 1)),
                        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
                    ) {
                        SectionLabel(
                            label = "Accessories · ${accessories.size}",
                            action = {
                                Text(
                                    text = "AUTO-IGNORED",
                                    color = palette.fg3,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.8.sp,
                                )
                            },
                        )
                        accessories.forEach { host ->
                            HostRow(
                                host = host,
                                onConnect = {},
                                onDisconnect = {},
                                onShowCaveat = { caveat = it },
                                modifier = Modifier.padding(bottom = BluetrackTokens.Sp1),
                            )
                        }
                    }
                }
                CompatibilityNote(
                    modifier = Modifier
                        .padding(
                            horizontal = BluetrackTokens.Sp6,
                            vertical = BluetrackTokens.Sp2,
                        )
                        .then(dev.xd.bluetrack.ui.rememberStaggerModifier(index = 2)),
                )
            }
        }
        caveat?.let { key ->
            CaveatSheet(
                caveat = key,
                onDismiss = { caveat = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    val palette = BluetrackTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = BluetrackTokens.Sp6, end = BluetrackTokens.Sp6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        Text(
            text = "No paired computers yet",
            color = palette.fg0,
            fontSize = 18.sp,
        )
        Text(
            text = "Pair a Mac, PC or Android tablet to start. " +
                "Audio devices and accessories are ignored automatically.",
            color = palette.fg2,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CompatibilityNote(modifier: Modifier = Modifier) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .padding(BluetrackTokens.Sp3),
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = "ⓘ", color = palette.fg2, fontSize = 14.sp)
        Text(
            text = "Bluetrack only acts as a HID Device, never a host. " +
                "Headphones, mice and keyboards stay in this list for transparency " +
                "but are skipped during auto-connect.",
            color = palette.fg2,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun CaveatSheet(
    caveat: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val info = CAVEATS[caveat] ?: return
    val sheetShape = RoundedCornerShape(
        topStart = BluetrackTokens.RadiusLg,
        topEnd = BluetrackTokens.RadiusLg,
        bottomEnd = 0.dp,
        bottomStart = 0.dp,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(BluetrackTokens.Sp3),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .btGlass(strong = true, shape = sheetShape)
                .padding(BluetrackTokens.Sp5),
            verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
        ) {
            Text(text = info.title, color = palette.fg0, fontSize = 18.sp)
            Text(text = info.body, color = palette.fg1, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BluetrackTokens.Sp3)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.mint)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "GOT IT",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                )
            }
        }
    }
}

private data class CaveatInfo(
    val title: String,
    val body: String,
)

private val CAVEATS = mapOf(
    "multi-adv" to CaveatInfo(
        title = "Multiple advertising not supported",
        body = "This phone's BLE chip can only run one advertisement at a time. " +
            "Bluetrack will pause the discoverability beacon while the HID link is active. " +
            "Pairing a second host while connected requires disconnecting first.",
    ),
    "ios-hid" to CaveatInfo(
        title = "iOS doesn't accept HID over BLE",
        body = "iPhones and iPads can pair with the phone but won't open a HID Device session — " +
            "Apple restricts this profile to MFi accessories. Use a Mac, PC or Android tablet as the host.",
    ),
    "hid-unavail" to CaveatInfo(
        title = "HID profile not available",
        body = "Some Android builds (especially low-end and AOSP-derived ROMs) ship without the " +
            "HID Device service. Bluetrack falls back to a notice; the touchpad and gamepad " +
            "won't work on this phone.",
    ),
    "adv-unavail" to CaveatInfo(
        title = "BLE advertiser missing",
        body = "The chipset can scan and connect but cannot advertise. Pair from the host side " +
            "instead — Bluetrack will be reachable by name once you initiate the bond.",
    ),
)

private fun buildEntries(status: GatewayStatus): List<HostEntry> {
    val active = status.host?.lowercase()
    val compat = status.compatibility
    // Gateway-wide caveats (fallback when a host has no
    // per-device caveat from the BluetoothClass classifier).
    val gatewayCaveat: String? = when {
        compat.hidProfile != "Available" && compat.hidProfile != "Unknown" -> "hid-unavail"
        compat.bleAdvertiserAvailable == false -> "adv-unavail"
        else -> null
    }
    return compat.bondedDevices.mapIndexed { i, name ->
        // Prefer the real `BluetoothClass`-derived kind from the
        // gateway snapshot; fall back to `Unknown` if the gateway
        // could not classify (rare — uncategorised device, no
        // BluetoothClass field on the bonded record).
        val realKind = compat.hostKinds[name] ?: BluetoothHostKind.Unknown
        val klass = realKind.toUiClass()
        val state = when {
            active != null && name.lowercase() == active -> HostState.Active
            realKind == BluetoothHostKind.Phone -> HostState.Incompatible
            klass == HostClass.Computer -> HostState.Available
            klass == HostClass.Unknown -> HostState.Incompatible
            else -> HostState.Ignored
        }
        val reason = when (klass) {
            HostClass.Audio -> "Audio profile · not a HID host"
            HostClass.Pointing -> "Pointer device · cannot be a HID host"
            HostClass.Keyboard -> "Keyboard device · cannot be a HID host"
            else -> null
        }
        // Caveat priority: gateway-wide blockers (`hid-unavail`,
        // `adv-unavail`) win over per-host warnings because they
        // mean nothing will work on this phone regardless of
        // which host you try. Only fall back to the per-host
        // caveat (`ios-hid`, `multi-adv`) when the gateway is
        // healthy. Codex review on PR #56 caught the reverse
        // ordering that hid the severe blocker behind a milder
        // multi-adv warning on computers.
        val perHost = compat.hostCaveats[name].orEmpty()
        val caveat: String? = when {
            klass == HostClass.Computer && gatewayCaveat != null -> gatewayCaveat
            perHost.isNotEmpty() -> perHost.first()
            else -> null
        }
        HostEntry(
            id = "$i:$name",
            name = name,
            os = null,
            klass = klass,
            state = state,
            fingerprint = null,
            caveat = caveat,
            reason = reason,
        )
    }
}

/**
 * Bridge from the gateway's real `BluetoothHostKind` enum to the
 * UI's local `HostClass`. We keep the UI enum because it carries
 * a slightly different vocabulary (no `Phone`; `Unknown` doubles
 * as "uncategorised + incompatible"). The mapping below is the
 * single place those vocabularies converge.
 */
private fun BluetoothHostKind.toUiClass(): HostClass = when (this) {
    BluetoothHostKind.Computer -> HostClass.Computer
    BluetoothHostKind.Audio -> HostClass.Audio
    BluetoothHostKind.Pointing -> HostClass.Pointing
    BluetoothHostKind.Keyboard -> HostClass.Keyboard
    BluetoothHostKind.Phone -> HostClass.Unknown
    BluetoothHostKind.Unknown -> HostClass.Unknown
}
