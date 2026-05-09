# Bluetrack — what exists / what's missing

Hand-off to Claude Design. Plain description of the current Android app
and the gaps the new UI should fill. No engineering details.

## What Bluetrack is

Native Android app that turns the phone into a Bluetooth HID device for
a PC: it advertises mouse and gamepad reports over Bluetooth so the
phone shows up like a real input device. A separate BLE side channel
accepts encrypted "feedback" packets back from the host (calibration /
correction). The phone is the device, the computer is the host.

Target feel: automatic and calm. No manual buttons in normal flow,
clear state, smooth input, honest about hardware limits.

## What exists today (single screen)

The whole app is one screen. From top to bottom:

- **Header.** App name and a one-line headline status ("HID ready",
  "HID profile unavailable", "Bluetooth off", etc.).
- **Metric tiles row.** Three numbers — `Reports` (HID frames sent to
  the host), `Feedback` (encrypted packets received), and an age
  ("3s ago") under each.
- **Touchpad surface.** Big interactive area. Drag your finger here to
  send mouse motion. Already smooth — fractional deltas, predictive
  filler for short touch gaps, governor that backs off when the
  Bluetooth send stalls. **This is a hardware-tested baseline; do not
  regress its smoothness.** Currently the canvas demo's polyline +
  liquid-drop visual lives here in the new design.
- **Mode toggle.** Switches the touchpad between MOUSE and GAMEPAD
  output. The new design promotes this to the 3D toggle hero.
- **System panel.** Five status rows + sometimes two extras:
  - `BT` — Bluetooth ready/off.
  - `HID` — current HID Device profile state.
  - `Pair` — discoverable / paired / cancelled.
  - `BLE` — feedback service state.
  - `Pin` — 6-digit pairing pin shown when the BLE GATT server is
    open. The user reads this off the phone and types it on the host
    CLI; without it the host's encrypted packets fail to decrypt.
  - `Trust` — short fingerprint (16 hex chars) of the long-term
    Ed25519 host that the phone has TOFU-pinned. Shows
    "first run" before any host has been pinned. Has a `Forget`
    button next to it that wipes the pinned host so the next
    handshake re-pairs.
- **Activity timeline.** Last few gateway events ("HID host connected",
  "Rejected feedback packet", etc.) with relative timestamps.
- **Permission rationale + system prompts.** First launch, the app
  asks for Bluetooth nearby-device permission and the foreground-
  service notification. Standard Android system dialogs — the app
  cannot bypass them.

That's it. No tabs, no settings, no separate gamepad screen, no host
list, no diagnostics page.

## What's missing (gaps for the redesign to fill)

These are absent or weak in the current build, in priority order:

1. **No navigation.** Single screen, no docks, no tabs, no routing.
   Everything piles onto the one panel.
2. **No dedicated Gamepad surface.** Mode toggles to gamepad mode but
   the input area stays portrait and shaped like a touchpad. There is
   no landscape sticks/buttons UI.
3. **No host list.** The app silently auto-connects to a "best HID
   host" candidate, but the user cannot see which hosts are bonded,
   which is currently selected, or override the choice.
4. **No diagnostics surface.** Counters and per-frame latency exist
   internally but are only available through `adb logcat`. There is
   no in-app place for `Reports per second / Feedback per second /
   Rejections / Last latency / Replay-window state / Pin validity`.
5. **No settings.** No theme, no accent, no glow strength, no glass
   on/off, no persistence of user choices. The Tweaks panel from
   Claude Design's canvas should land here.
6. **No first-run welcome / pairing walkthrough.** The user is dropped
   straight onto the live screen. Permission requests come up
   transactionally without context.
7. **Touchpad visuals are minimal.** The motion pipeline is excellent
   but the surface itself is featureless — no liquid-drop, no fading
   trail, no breath animation. Claude Design's canvas already proposes
   the polyline + drop visuals; that's the target.
8. **System rows are flat text.** The neon-glow status, multi-layer
   accent, and Apple-glass background in Claude Design's canvas are
   absent in the current build — it's plain dark backgrounds with
   small white labels.
9. **Trust state has no visible "why".** When the phone rejects a
   handshake from an untrusted host, an error blinks once in the
   timeline. There is no calm, persistent "Different host attempted
   to connect" surface, no obvious path to "Forget host" beyond the
   small button next to the fingerprint.
10. **No identity / fingerprint comparison flow.** Today the user
    eyeballs the host's CLI banner against the phone's `Trust` row
    manually. There is no QR / out-of-band exchange UI yet.

## State the UI knows about

The existing engine surfaces all of this — feel free to reference any
of it when designing screens:

- Bluetooth: enabled, advertiser available, multiple-advertisement
  supported, scan mode, list of bonded devices (names).
- HID: current mode (mouse / gamepad), profile state, current host
  (if connected), reports sent, last-report age.
- BLE feedback: service state, packets received, packets rejected,
  last-feedback age, current 6-digit pin (when GATT server open),
  trusted host fingerprint (when pinned).
- Activity log: last ~24 events, each with timestamp, source, message.
- Compatibility snapshot: HID profile availability label, scan-mode
  label, BLE advertiser availability — useful for surfacing "this
  device cannot do HID" cleanly instead of a silent failure.

## Hardware caveats the design must respect

- Android shows system dialogs for Bluetooth enable, discoverability,
  pairing, and the foreground-service notification. The app cannot
  bypass them. Designs should anticipate these as transient overlays
  rather than design around them.
- HID Device profile support is firmware-dependent. On some phones
  it is simply unavailable; the UI should make that visible without
  shouting "error".
- Foreground-service notification is permanent while the HID
  keep-alive is running. The notification itself is system-styled.
- Forced landscape only makes sense for the Gamepad surface. The rest
  of the app should stay portrait-friendly.

## Out of scope for this redesign

- Capturing host snapshots / compatibility matrix screens — that is
  a developer / community workflow, not a user-facing one.
- Replacing the input pipeline (motion, predictor, governor). The
  redesign reskins the touchpad; the math behind it stays.
- Changing the BLE protocol. The pin and trust flows are fixed; the
  redesign decides how they are surfaced, not how they work.
