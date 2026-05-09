# Bluetrack — blind spots + keyboard addition

Two halves. Read while Claude Design weekly limits reset.

1. **Blind spots audit.** Things the project does not address yet,
   organised by domain. Each entry is small; the goal is a checklist
   for the next planning pass.
2. **Keyboard HID.** Adding keyboard as a third HID mode alongside
   mouse and gamepad. Specs the descriptor, the engine path, the UI
   needs, and the surprises.

---

## 1. Blind spots audit

### 1A. Functional gaps (current build does not surface)

- **Mouse wheel.** Mouse descriptor includes the wheel byte but the
  app does not generate scroll deltas. Two-finger drag → wheel is the
  obvious gesture.
- **Right / middle click.** Mouse descriptor exposes 3 buttons; only
  drag motion is wired. Need an affordance — long-press, edge zone,
  or dedicated buttons under the touchpad.
- **HID host battery.** Some HID hosts read battery from the device.
  Phone battery → HID battery report descriptor would let macOS show
  "Bluetrack Pro Engine: 67 %". Optional but nice.
- **Latency surface.** Runtime knows `lastReportAtMs`,
  `lastFeedbackAtMs`, and the `BluetrackInput` log calculates pacer
  / queue / output latencies. Diagnostics shows totals but not
  per-frame jitter. Add a 60-second jitter histogram.
- **Multiple bonded hosts.** Auto-connect picks one; manual reroute
  is missing. v3 Hosts screen at least lists them, but does not let
  the user disconnect the active one without forgetting it.
- **Screen-off behaviour.** What happens when the phone screen
  sleeps with the foreground service running? Reports keep flowing?
  Touchpad input pauses? Today undefined; should be tested and
  documented.
- **Wake on motion.** Phone is asleep, mouse on the host moves —
  does the BLE feedback channel wake the receiver? Probably not in
  Doze. Document.

### 1B. Security / protocol gaps

- **No identity backup.** The Ed25519 host private key is in a
  single JSON file. Lose it (CLI machine reformat) → phone refuses
  every future handshake until "Forget host". Add an explicit
  export / import command in the CLI (`--export-identity` /
  `--import-identity`).
- **No scheduled identity rotation.** Manual `--reset-host-identity`
  only. For long-running deployments a yearly rotation would be
  hygienic.
- **Pin observability on screen.** Pin is plain text on the phone
  screen. Screenshots / screen-share leak it. Add: blur the pin
  except when explicitly tapped, with a "Reveal" button. Pin still
  rotates per session, so leakage cost is bounded but non-zero.
- **No version negotiation.** Adding any new field to the handshake
  is a breaking change. Reserve a 1-byte version prefix on the
  handshake characteristic now (or at least document that any
  future change forces a new service UUID).
- **No anti-DoS rate limit.** A misbehaving host can spam handshake
  writes; the peripheral validates each cryptographically. Add a
  soft rate limit on the GATT handler (e.g., reject more than 4
  handshake writes per second from a single device until reconnect).
- **BLE advertising privacy.** The feedback service UUID is fixed;
  anyone scanning sees "Bluetrack on this phone". Could be
  intentional, could be a fingerprinting vector. Document the
  decision.

### 1C. Testing gaps

- **No on-device instrumentation tests.** Everything is JVM unit
  tests. Touchpad smoothness, HID delivery, GATT lifecycle have to
  be verified by hand on real hardware. An emulator-based or
  connected-device test pass would catch regressions earlier.
- **No fuzz testing for the handshake parser.** Random 128-byte
  inputs through `FeedbackHandshakePayload.parse` + `installHandshake`
  to confirm it cannot crash, only return `MALFORMED` /
  `BAD_SIGNATURE`.
- **No cross-platform interop test.** Each platform tests itself.
  No fixture proves "Swift host build_packet → Android phone decrypt
  → callback fires" with byte-identical golden vectors. Add a JSON
  fixture file (handshake bytes, encrypted frames, expected dx/dy)
  consumed by all three test suites.
- **No CI test for Python sender.** `py_compile` only checks syntax.
  Add a real pytest pass that exercises `FeedbackSession` round-trip
  against the same golden vectors.
- **No release build smoke test.** CI only builds debug. Release
  build with R8 / ProGuard could strip BouncyCastle classes
  reflectively. Add an `assembleRelease` step + an instrumentation
  smoke that exercises the handshake.

### 1D. Operational / observability

- **No log export from phone.** Today the user reads `adb logcat`.
  The app could expose "Save last 5 minutes to file" and dump to
  shareable storage.
- **No telemetry / counters survive a process kill.** All counters
  reset on `MainActivity.onDestroy`. A simple persistent counter
  (lifetime reports, lifetime rejections) would tell us if a phone
  is acting up over weeks.
- **No error reports.** If the GATT add fails, only the timeline
  knows. No crashlytics, no aggregated reporting. Deliberate for
  privacy, but worth documenting.

### 1E. Build / distribution

- **No release signing config in the repo.** Release builds are
  unsigned today.
- **ProGuard rules.** Confirm BouncyCastle X25519 / Ed25519 +
  Ed25519 verify are kept under R8. Add explicit `-keep` rules.
- **No distribution plan.** No F-Droid metadata, no Play listing,
  no install instructions beyond the debug APK path. Decide before
  promising users this is "an app".
- **No automated release branch / changelog generation.** Each PR
  adds to `docs/CODEX_CONTEXT.md` informally. A `CHANGELOG.md` keyed
  to versionCode would help.

### 1F. UX / accessibility

- **No accessibility audit.** TalkBack labels, large-text scaling,
  high-contrast theme, motion-reduce (Tweaks has Reduce Motion but
  not "Reduce transparency" or "Bold text" parity).
- **No localisation.** Strings are hard-coded English. RU is the
  natural first second locale given the maintainer.
- **No tutorial / non-technical onboarding.** Welcome shows once,
  then the user is on their own. Non-technical users hit "PIN type
  on host CLI" and bounce.
- **No error recovery flows.** Pairing breaks mid-session: today
  the timeline shows it but there is no "Repair?" CTA.
- **No power user keyboard nav.** Hardware keyboard plugged into
  the phone (USB-C → USB-A) — does the app respond to keys? Not
  required, but a thought.

### 1G. Hardware / power

- **No Doze interaction tested.** Foreground service is supposed to
  exempt the app from Doze, but real-device tests would confirm.
- **No battery-drain numbers.** A long HID session at 5 ms cadence
  on a Pixel: how much per hour? Worth a measurement.
- **No thermal throttling rules.** What happens when the phone gets
  hot during a 3-hour gaming session? Reports back off?
- **No Android-version test matrix.** minSdk 29 in build.gradle.
  Has anyone actually run it on 29? Or is 33+ the realistic floor?

### 1H. Documentation gaps

- **No threat model document.** `CODEX_CONTEXT.md` lists security
  caveats but does not enumerate adversaries (passive sniffer,
  active in-range attacker, malicious paired host, attacker with
  shell on the host machine). Worth a one-page table.
- **No "supported phones / not supported" list.** Users will ask.
  Even a tracker issue is better than silence.
- **No migration notes between protocol versions.** The four PRs
  shipped to date were all breaking. A `docs/PROTOCOL.md` would
  give an upgrade path next time.

---

## 2. Keyboard HID — third HID mode

Adding keyboard alongside mouse and gamepad. Phone sends key
press / release events to the host as a Bluetooth HID keyboard.

### 2A. Why it matters

- Closes the "real input device" promise. Today the phone is a
  half-mouse / half-gamepad; a keyboard surface makes it a
  legitimate everyday peripheral.
- Lets the phone become a quick-typing tool when a real keyboard is
  not available (presentation remote, password entry, server
  control over BLE in a lab).
- Gives a clean home for media keys (volume, play/pause), which
  are technically a separate "Consumer Control" descriptor — see
  the descriptor section.

### 2B. HID descriptor

The current composite descriptor has report ID 1 (mouse) and report
ID 2 (gamepad). Add report ID 3 (keyboard) and optionally report
ID 4 (consumer control / media keys).

Standard 8-byte HID boot keyboard report:

```
[0]      modifiers (bitmask: LCtrl, LShift, LAlt, LGUI, RCtrl, RShift, RAlt, RGUI)
[1]      reserved (0x00)
[2..7]   up to 6 simultaneous HID Usage codes; 0x00 in unused slots
```

Consumer control 2-byte report (for media keys):

```
[0..1]   little-endian Usage from page 0x0C
         (0x00B5 = next track, 0x00B6 = prev track,
          0x00CD = play/pause, 0x00E2 = mute, 0x00E9 = vol+, 0x00EA = vol-)
```

Adding either report ID is a descriptor change → forget/re-pair on
the host (macOS, Windows). Document this loudly in the changelog
and in the Pair screen.

### 2C. Engine and gateway changes

- `engine/KeyboardReportFormat.kt` — encode/decode helpers for the
  8-byte boot keyboard report (and 2-byte consumer report if we
  ship it).
- `engine/TranslationEngine.kt` — new path that produces keyboard
  reports. The 6-key rollover constraint matters: track currently-
  pressed keys, push them into the report's 6 slots in press order.
- `engine/HidMode.kt` — extend the enum to `MOUSE`, `GAMEPAD`,
  `KEYBOARD`. Mode switching never calls `unregisterApp()`; the
  composite descriptor already exposes all three and we just route
  reports to the active path.
- `BleHidGateway.kt` — register the composite descriptor with the
  expanded report map; route mode changes; no other plumbing.
- `MainViewModel.kt` — add a `pressKey(usage:, down:)` API and a
  `pressModifier(mask:, down:)` API. The 8-byte report is built
  inside `TranslationEngine.keyboardReport()` from the held set.
- Latency: keyboard reports are short and rare. Reuse the existing
  `HidOutputBuffer` + `HidTransportGovernor`. No new pacer.

### 2D. UI surface

A new "Keyboard" mode toggle and a dedicated screen.

#### Mode toggle
The 3D mouse ⇄ gamepad toggle becomes mouse ⇄ gamepad ⇄ keyboard
(three positions). Or split: keep the 3D toggle for mouse / gamepad
and add a separate "Keyboard" entry in the bottom dock. The dock
collapse to 5 from `UI_BRIEF_GAPS.md` had Hub / Hosts / Activity /
Diag / Settings; if Keyboard is its own dock entry, dock becomes 6
or one of Hosts / Activity moves into Settings.

Recommend: keep dock at 5; add Keyboard to the Hub mode toggle as a
3-state switch (mouse / keyboard / gamepad).

#### Keyboard screen — layout

Portrait by default (unlike Gamepad which is landscape). Three
zones from top to bottom:

1. **Status strip.** Active modifiers, num lock indicator, the same
   FG-service / Pin / Trust chips as the Hub.
2. **Function row.** Esc, F1–F12 (collapsible behind a "Fn" toggle),
   media keys (next / prev / play / mute / vol±), Home / End / PgUp
   / PgDn, arrows.
3. **Main keyboard.** Full QWERTY, with Shift / Ctrl / Alt / Cmd /
   Tab / Enter / Backspace / Space. Sticky modifiers (tap once to
   arm, tap again to release; long-press to lock). Long Space at
   the bottom.

Layout is one of: `QWERTY (US)`, `QWERTY (UK)`, `ЙЦУКЕН (RU)`,
`Dvorak`. Layout selection lives in Tweaks → Keyboard. Layout only
affects the rendered glyphs; the wire reports HID Usage codes
that the host translates per its own keyboard layout. So the user
should pick the layout that matches the HOST keyboard layout, not
the phone OS layout. This is a common confusion — surface a one-line
hint near the layout picker.

#### Personalisation (Tweaks → Keyboard)

- Layout: US / UK / RU / Dvorak.
- Sticky modifiers vs. hold-to-press.
- Show Fn row vs. compact (no Fn).
- Show media row vs. hide.
- Show key labels in red accent (cosmetic).
- Haptic on press: off / light / medium.

#### Diagnostics screen additions

- Live key press rate (KPS).
- 6-key rollover indicator: lights when 6 keys are simultaneously
  held and a 7th is dropped.
- Last 16 key events (modifier mask + usage code) — debug-y.

### 2E. Cross-platform updates

- **Swift `BluetrackHostKit`** does not need changes for the
  keyboard report itself (HostKit only deals with the encrypted
  feedback channel + handshake; keyboard reports are HID-side, not
  BLE-feedback-side).
- **Swift `bluetrack-hid-inspector`** — `watch` and `companion`
  subcommands enumerate IOHID. They will pick up keyboard report
  IDs automatically. Add report ID 3 to the recognised set in the
  output formatting and in `host/snapshots/` examples.
- **Android tests** — extend `TranslationEngineTest` (or new
  `KeyboardReportFormatTest`) for press / release / rollover.
- **Python sender** — no changes; it talks to the BLE feedback
  channel only.

### 2F. Surprises and risks

- **Macros.** Real keyboard apps store macros (paste clipboard as
  keystrokes, etc.). Out of scope for v1; do not design it in.
  Mention "macros" in the roadmap and stop.
- **System UI overlap.** The Keyboard screen looks like the OS
  IME. Make sure that does not confuse — large title "HID
  KEYBOARD → host" at the top.
- **Localised input methods.** RU layout in QWERTY mode would need
  the user to switch the *host* keyboard layout to ЙЦУКЕН to
  produce Cyrillic. Do not pretend the phone can input arbitrary
  Unicode through HID keyboard — the wire only carries Usage codes
  the host knows. Document in the layout picker tooltip.
- **Bluetooth HID re-pair.** Adding a new report ID changes the
  descriptor → all bonded hosts must forget and re-pair. This is
  the third such break in this branch (composite gamepad, ECDH
  upgrade, identity binding). Bundle keyboard with the next
  forced-rebond release; do not ship a separate forced-rebond.
- **Keyboard while gamepad-mode is wake-training.** The wake train
  fires gamepad reports. Switching to keyboard mode should pause
  the wake train (or the host will see flickering gamepad activity
  while the user is typing).
- **macOS sandbox quirks.** Some Macs already see Bluetrack as
  "Magic Mouse"-like; with keyboard the device class will register
  as a generic HID combo. Macros / shortcuts apps may behave
  oddly. Flag for testing.

### 2G. Implementation order (one PR per step)

1. Descriptor + `HidMode.KEYBOARD` + `KeyboardReportFormat` + JVM
   tests. No UI yet. Tested via `host/macos-hid-inspector watch`.
2. `MainViewModel` API for press / release / modifiers.
3. UI: Keyboard screen with QWERTY US only, no Tweaks.
4. Tweaks → Keyboard sub-section, layouts (UK / RU / Dvorak),
   sticky modifiers, haptic.
5. Diagnostics additions (KPS, rollover, recent keys).
6. Optional: consumer control / media keys (report ID 4).
7. Re-bond instructions in `docs/CODEX_CONTEXT.md` + Pair screen
   microcopy update.

Each PR ships behind the same `new_ui` runtime tweak as the rest of
the redesign and forces a forget+re-pair cycle on test hardware.

---

## 3. What to do next

- Hand `UI_BRIEF.md`, `UI_BRIEF_GAPS.md`, `UI_BRIEF_GAPS_V2.md`,
  and §2 of this file to Claude Design when limits reset, asking
  for the keyboard surface plus answers to the 18 open decisions
  and the gamepad expansion.
- In parallel on the engineering side: the work in §1A / 1C / 1E
  does not need a new design pass. Concrete next bites are the
  fuzz test for the handshake parser, ProGuard rules with a
  `assembleRelease` smoke, the cross-platform golden-vector
  fixture, and a one-page threat model in `docs/THREAT_MODEL.md`.
