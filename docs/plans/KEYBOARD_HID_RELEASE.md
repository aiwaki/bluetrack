# Keyboard HID + Multi-Touch Gestures + Horizontal Scroll + Battery — Forced Re-Pair Release

Bundled forced-re-pair release. Every change in this document
requires the HID host (macOS / Windows / Linux) to drop its cached
report map and re-pair Bluetrack. Per `CLAUDE.md` and
`.claude/rules/00-project.md` we bundle all forced-re-pair work
into one release so the user does not pay the re-pair cost twice
in a row.

## What ships in this release

1. **Keyboard HID report** — third report ID inside the composite
   descriptor.
2. **Mouse descriptor expansion** — AC Pan (horizontal wheel)
   byte added to the mouse report.
3. **Battery Service (GATT BAS)** — standalone GATT service alongside
   the HID feedback service so hosts can show the phone's battery
   level next to the Bluetrack device in their Bluetooth menu.
4. **Mac trackpad gesture parity** — pinch zoom, 3-finger
   swipes, 4-finger pinch/spread, two-finger horizontal scroll.

CHANGELOG.md MUST flag this release as forced-re-pair.

## 1. HID composite descriptor changes

Today `BleHidGateway.kt` ships a composite descriptor with
**Mouse (report ID 1) + Gamepad (report ID 2)**. The keyboard
report adds a third application collection at **report ID 3**.

### 1a. Mouse descriptor — add AC Pan byte

Current mouse report layout: `[buttons:1, X:1, Y:1, wheel:1]` = 4
bytes. New layout: `[buttons:1, X:1, Y:1, wheel:1, AC Pan:1]` =
5 bytes. AC Pan is Consumer page usage `0x0238`. macOS and
Windows both interpret it as horizontal wheel.

Descriptor delta in `BleHidGateway.mouseDesc`:

```
Before tail (... wheel only):
  0x09, 0x38,        // Usage (Wheel, Generic Desktop)
  0x15, 0x81,
  0x25, 0x7F,
  0x75, 0x08,
  0x95, 0x03,        // X + Y + Wheel = 3 bytes
  0x81, 0x06,
  0xC0, 0xC0

After (wheel + AC Pan):
  0x09, 0x38,        // Usage (Wheel, Generic Desktop) — vertical
  0x15, 0x81,
  0x25, 0x7F,
  0x75, 0x08,
  0x95, 0x03,        // X + Y + Wheel (vertical) = 3 bytes
  0x81, 0x06,
  0x05, 0x0C,        // Usage Page (Consumer)
  0x0A, 0x38, 0x02,  // Usage (AC Pan) — horizontal wheel
  0x15, 0x81,
  0x25, 0x7F,
  0x75, 0x08,
  0x95, 0x01,        // 1 byte
  0x81, 0x06,
  0xC0, 0xC0
```

Mouse report bytes after the change:

```
[0] buttons (bit 0 left, bit 1 right, bit 2 middle)
[1] X delta  (-127..127)
[2] Y delta  (-127..127)
[3] wheel vertical  (-127..127)
[4] wheel horizontal — AC Pan (-127..127)   ← NEW
```

`TranslationEngine.processWheel(dy, send)` becomes
`processWheel(dy, dx, send)` so the input pacer can route a
two-axis scroll without a second report. Per-emit cap stays
`MAX_WHEEL_PER_EMIT = 1` per axis. Buffer cap
`MAX_WHEEL_PER_POLL = 8` stays.

### 1b. Keyboard HID — new application collection, report ID 3

Boot-protocol-compatible keyboard report so OS-level shortcut
hooks (macOS event tap, Windows low-level keyboard hook) see the
events the same way they see a hardware Bluetooth keyboard.

Report layout (8 bytes, classic boot keyboard):

```
[0] modifier byte (LCTRL=0x01, LSHIFT=0x02, LALT=0x04, LGUI=0x08,
                   RCTRL=0x10, RSHIFT=0x20, RALT=0x40, RGUI=0x80)
[1] reserved (0x00)
[2] keycode 1   (HID Usage page 0x07)
[3] keycode 2
[4] keycode 3
[5] keycode 4
[6] keycode 5
[7] keycode 6
```

Descriptor:

```
0x05, 0x01,        // Usage Page (Generic Desktop)
0x09, 0x06,        // Usage (Keyboard)
0xA1, 0x01,        // Collection (Application)
0x85, 0x03,        // Report ID 3
0x05, 0x07,        // Usage Page (Keyboard/Keypad)
0x19, 0xE0,        // Usage Min (LCtrl)
0x29, 0xE7,        // Usage Max (RGUI)
0x15, 0x00,
0x25, 0x01,
0x75, 0x01,
0x95, 0x08,        // 8 modifier bits
0x81, 0x02,
0x95, 0x01,
0x75, 0x08,
0x81, 0x01,        // 1 reserved byte
0x95, 0x06,
0x75, 0x08,
0x15, 0x00,
0x25, 0x65,
0x05, 0x07,
0x19, 0x00,
0x29, 0x65,
0x81, 0x00,        // 6 keycodes
0xC0
```

### 1c. Composite assembly

`BleHidGateway` concatenates `mouseDesc + gamepadDesc +
keyboardDesc` (in that order, matching the existing convention
where the input pacer's `HidMode` enum picks the right report ID
per emit).

## 2. Battery Service (GATT BAS)

Standalone GATT service alongside the feedback service. Hosts
read it once on connect to populate the battery icon next to
"Bluetrack" in their Bluetooth menu.

- Service UUID: `0x180F` (Battery Service)
- Characteristic: `0x2A19` (Battery Level), `READ + NOTIFY`,
  uint8 percentage 0..100.
- Source: Android `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`.
- Update cadence: on charging-state change or every 60 s,
  whichever comes first.
- No security gating — battery is public. The encrypted feedback
  service uses its own UUID with the existing pairing pin
  semantics.

`BleHidGateway` adds the BAS service when it opens the GATT
server (already opens for the feedback service today).

## 3. TranslationEngine: keyboard API

Add a parallel emit path for keyboard reports, mirroring the
mouse path:

```kotlin
private val keyboardReport = ByteArray(8)
private var keyboardModifiers = 0
private val keycodesPressed = LinkedHashSet<Int>() // max 6

fun processKeyDown(modifier: Int, keycode: Int, send: (ByteArray) -> Unit) {
    keyboardModifiers = keyboardModifiers or modifier
    if (keycodesPressed.size < 6 && keycodesPressed.add(keycode)) emitKeyboard(send)
}

fun processKeyUp(modifier: Int, keycode: Int, send: (ByteArray) -> Unit) {
    keyboardModifiers = keyboardModifiers and modifier.inv()
    keycodesPressed.remove(keycode)
    emitKeyboard(send)
}

private fun emitKeyboard(send: (ByteArray) -> Unit) {
    keyboardReport[0] = (keyboardModifiers and 0xFF).toByte()
    keyboardReport[1] = 0
    var i = 2
    for (kc in keycodesPressed) {
        keyboardReport[i++] = kc.toByte()
        if (i >= 8) break
    }
    while (i < 8) keyboardReport[i++] = 0
    send(keyboardReport)
}

fun tapKey(modifier: Int, keycode: Int, send: (ByteArray) -> Unit) {
    processKeyDown(modifier, keycode, send)
    processKeyUp(modifier, keycode, send)
}
```

HID keycodes used by the gestures below:

```
KC_F3   = 0x3C   (macOS Mission Control via System Prefs default)
KC_F4   = 0x3D   (Launchpad)
KC_F11  = 0x44   (Show Desktop)
KC_LEFT = 0x50
KC_RIGHT= 0x4F
KC_UP   = 0x52
KC_DOWN = 0x51
KC_EQUAL= 0x2E   ("=" — pair with LGUI for Cmd+= (zoom in))
KC_MINUS= 0x2D   ("-" — pair with LGUI for Cmd+- (zoom out))
```

## 4. Touchpad gesture handlers

Today `MainActivity.kt` handles 1-finger drag, 1-finger tap,
2-finger tap (right click), 2-finger drag (vertical scroll),
1-finger hold (drag start). Add:

### 4a. 2-finger horizontal scroll

Already implicit once `processWheel(dy, dx)` lands. The existing
2-finger scroll branch tracks `avgY()`; add `avgX()` symmetrically
and emit dx through the new AC Pan byte.

```kotlin
fun avgX(): Float = if (ev.pointerCount >= 2) (ev.getX(0) + ev.getX(1)) * 0.5f else ev.x
```

The active-scroll ticker and the fling job both gain an `xVelocity`
counterpart. Keep the same velocity model and cap (`FLING_MIN_VELOCITY`).
Direction-aware EMA reset applies per axis.

### 4b. Pinch zoom — 2-finger spread/pinch

Mac convention: pinch out = zoom in (Cmd+=), pinch in = zoom out
(Cmd+-). Fires once per "notch" of pinch distance to mimic the
discrete-step behaviour macOS applies to non-trackpad pinch input
(real trackpads send continuous zoom magnification; we can't
fake that without Magic Trackpad HID class which we are
explicitly not pretending to be).

Detection:
- Two pointers down.
- Compute initial distance D0 between them.
- On MOVE, compute current distance D.
- Emit one zoom keystroke per `PINCH_NOTCH_PX = 36 dp` of `|D - D_anchor|`
  change since last emit. Anchor resets after each emit.

```kotlin
if (gestureMode == PINCH) {
    val delta = currentDist - pinchAnchorDist
    val notch = (delta / pinchNotchPx).toInt()
    if (notch != 0) {
        repeat(abs(notch)) {
            val key = if (notch > 0) KC_EQUAL else KC_MINUS
            engine.tapKey(MOD_LGUI, key, send)
        }
        pinchAnchorDist += notch * pinchNotchPx
    }
}
```

Pinch vs scroll disambiguation: latch on first MOVE after second
finger lands. If `|distance change|` > `|avg Y change|` × 1.5,
latch PINCH; else SCROLL. Once latched, sticky until ACTION_UP.

### 4c. 3-finger swipe

Mac default mapping (System Settings → Trackpad → More Gestures):
- 3-finger swipe up = Mission Control (Ctrl+Up by default, but
  the trackpad gesture is a custom Quartz event that no HID
  shortcut maps to; closest workable is **F3** which macOS sends
  via the dedicated Mission Control key on Magic Keyboard).
- 3-finger swipe down = App Exposé (Ctrl+Down arrow by default).
- 3-finger swipe left/right = switch between full-screen apps
  (Ctrl+Left / Ctrl+Right).

Detection: latch on first MOVE with `pointerCount >= 3`. Pick
dominant axis by initial delta. Emit on swipe threshold
`SWIPE_THRESHOLD_PX = 80 dp`. One emit per gesture (latched, no
repeat).

```kotlin
when {
    abs(dx) > abs(dy) && abs(dx) > swipeThresholdPx -> {
        val key = if (dx > 0) KC_RIGHT else KC_LEFT
        engine.tapKey(MOD_LCTRL, key, send)
    }
    abs(dy) > swipeThresholdPx -> {
        val key = if (dy < 0) KC_F3 else KC_DOWN
        val mod = if (dy < 0) 0 else MOD_LCTRL
        engine.tapKey(mod, key, send)
    }
}
```

### 4d. 4-finger pinch/spread

- 4-finger pinch in = Launchpad (F4 keystroke or Cmd+Space which
  is Spotlight; F4 is the Apple-defined Launchpad key).
- 4-finger spread out = Show Desktop (F11).

Same notch detection as 2-finger pinch but threshold is wider
(`FOUR_FINGER_NOTCH_PX = 80 dp`) and emit is one-shot per
gesture, not per notch — Launchpad / Show Desktop are toggles.

### 4e. Finger-count map summary

```
1 finger drag        → cursor motion           (existing)
1 finger tap         → left click              (existing)
1 finger hold + drag → text select drag        (existing)
2 finger tap         → right click             (existing)
2 finger drag V      → vertical scroll         (existing, smoothed)
2 finger drag H      → horizontal scroll       (NEW, AC Pan byte)
2 finger pinch       → Cmd+= / Cmd+- zoom      (NEW, keyboard HID)
3 finger swipe ←/→   → Ctrl+Left/Right desktop (NEW, keyboard HID)
3 finger swipe ↑     → F3 Mission Control      (NEW, keyboard HID)
3 finger swipe ↓     → Ctrl+Down App Exposé    (NEW, keyboard HID)
4 finger pinch in    → F4 Launchpad            (NEW, keyboard HID)
4 finger spread out  → F11 Show Desktop        (NEW, keyboard HID)
```

## 5. Tests

- `TranslationEngineTest` — new `keyboardReport()` assertions:
  modifier-only, modifier + single key, key release, six-key
  rollover.
- `HidOutputBufferTest` — mouse report grew from 4 to 5 bytes;
  poll/enqueue tests update offsets.
- Golden vector — `host/test-vectors/feedback_v1.json` does not
  change (handshake / feedback path untouched). New fixture
  `mouse_report_v2.json` and `keyboard_report_v1.json` for
  cross-platform byte-equal checks if we extend the host CLI.

## 6. Host re-pair playbook (CHANGELOG entry)

CHANGELOG.md MUST include the following block (mirror existing
forced-re-pair entries):

> **Forced re-pair release.** The HID descriptor grew a new
> Keyboard report and the Mouse report gained a horizontal-wheel
> byte. Cached host descriptors no longer match the device; macOS
> / Windows / Linux must forget Bluetrack and pair again. To
> avoid forcing users through two re-pairings in a row this
> bundle also lands GATT BAS so a future battery descriptor edit
> does not require its own re-pair.

Host steps (paste into CHANGELOG):

- macOS: System Settings → Bluetooth → "Bluetrack" → ⓘ → Forget.
- Windows 11: Settings → Bluetooth & devices → Bluetrack → Remove.
- Linux (bluetoothctl): `remove <MAC>` then `scan on` → `pair <MAC>`.

## 7. Out-of-scope (next bundle, NOT this release)

- Voice → keyboard HID dictation (`docs/IDEAS.md` A1) — depends
  on this release; ship in a follow-up that does not require
  re-pair.
- Magic Trackpad multitouch HID class — would let us emit native
  continuous zoom magnification and Mission Control gesture
  events instead of faked modifier keystrokes, but requires
  emulating a different HID class than the current generic
  mouse + keyboard composite, plus the macOS HID multitouch
  protocol blob. Park.
- "Force click" — macOS Force Touch is a private pressure event
  the HID layer does not expose. Skip.

## 8. Shipping order inside the release branch

Atomic commits, in order:

1. `BleHidGateway`: descriptor concat, keyboard report ID 3 wiring,
   BAS service registration.
2. `TranslationEngine`: keyboard API + horizontal-wheel byte.
3. `HidOutputBuffer`: 5-byte mouse frame + 8-byte keyboard frame.
4. `MainViewModel`: input pacer routes for new emit paths.
5. `MainActivity` touchpad gesture handler: pinch / 3-finger /
   4-finger / horizontal scroll branches.
6. `TranslationEngineTest` + `HidOutputBufferTest` updates.
7. CHANGELOG.md forced-re-pair entry.
8. README + UI hint copy update if any gesture is surfaced to
   the user (likely keep the hint overlay's "Use it like a
   MacBook trackpad" tagline — every new gesture maps to the
   user's existing Mac muscle memory, so no extra teaching
   surface needed).

## 9. Risk register

| Risk | Mitigation |
|------|------------|
| macOS does not honour F3 / F4 as Mission Control / Launchpad if the user remapped them | Document the dependency on default macOS keyboard shortcuts in the CHANGELOG; emit a Bluetrack settings toggle to switch to Ctrl+Up alternative |
| Cmd+= / Cmd+- zooms in some apps but pages in others (browser zoom vs in-app zoom) | Same as Mac trackpad — accept platform behaviour |
| AC Pan byte rejected by older Linux kernels (<5.4) | Test on Ubuntu LTS; fall back to vertical-only wheel on those hosts via a feature negotiation in the HID Information descriptor if needed |
| 4-finger gestures fire accidentally during palm rest | Reject any frame where `pointerCount` jumped from <2 to ≥4 in <80 ms (no transition through 3) and the finger spread is below `PINCH_NOTCH_PX × 4` — looks like a palm slap, not an intentional gesture |
| Pinch latched but user wanted scroll | `latched` only sticks until ACTION_UP; release re-arms detection. Disambiguation threshold (1.5× ratio) gives scroll precedence on near-pure-vertical movement |
