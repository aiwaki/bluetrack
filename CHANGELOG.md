# Changelog

All notable changes to Bluetrack ship as one entry per pull request,
grouped by category. Versions follow `versionName` in
`android/app/build.gradle.kts`.

Forced re-pair lines call out PRs that change the BLE GATT service
shape or the HID descriptor — both bonded hosts (macOS, Windows, ...)
must "Forget This Device" and pair fresh after these.

## Unreleased

Targets `versionName 3.0.0` / `versionCode 3` once UI redesign lands.

### UI & Connection UX

- **#57** Real-device polish across three commits. Connection UX:
  `shouldAutoRequestDiscoverability` now gates on `hostKinds.none
  { Computer }` instead of `bondedDevices.isEmpty()` so a phone
  paired with audio / accessories still triggers the system
  discoverability prompt; TrustCard grew a tap-to-connect
  `RECOMMENDED` section with a `DISCONNECT` pill that calls
  `BluetoothHidDevice.disconnect` synchronously; manual
  disconnects honour a 60 s grace window so the auto-connect
  ticker cannot bounce the host back. Stale host clear: ACL
  broadcast receiver + per-route header back arrow + rewrite of
  `isConnected(status)` to a pure `host != null` check. Lock
  contention: the 3 s `refreshCompatibility` ticker now runs
  only while `status.host == null` so the gateway lock no
  longer blocks `BleHidGateway.send()` during active mouse
  input — fixed the visible cursor lag. Host classifier:
  `HidHostClassifier` keyword fallback rank 75 + PHONE blacklist;
  `BluetoothHostKind.classifyByName` is the single source of
  truth for HostsScreen, TrustCard recommended list, and the
  discoverability guard. `registerApp` orphan-slot failure now
  surfaces an actionable "Toggle Bluetooth off and back on"
  error. Settings cleanup: dropped Identity / Appearance /
  Diagnostics & Activity / Scan mode / About-storage rows; added
  Maintenance group with `Reset lifetime counters`; Visible-as
  reads real `BluetoothAdapter.name`; Auto-connect is a
  persisted DataStore toggle (default on) wired to a new
  `@Volatile BleHidGateway.autoConnectEnabled` gate. Activity:
  removed from the dock, reachable via the Hub `ActivityStrip`;
  `sessionLengthLabel` now anchors on `now` so the cell tracks
  wall-clock duration; HOSTS summary cell removed. Hub heartbeat
  drives its spike rate + amplitude from a real activity
  intensity (`max(lastInputAtMs, lastReportAtMs)` recency).
  PinBlock relabelled "FEEDBACK PIN" with copy explaining it is
  separate from the system Bluetooth pairing prompt. Touchpad
  motion is now normalised by the longer surface dimension so
  X and Y swipes of equal fraction emit equal cursor delta.
  First-run Welcome route (`ui/welcome/`) carries the BT /
  notifications rationale and a CTA that persists
  `TweaksRepository.onboarded` and triggers the runtime
  permission flow — replaces the immediate cold-launch system
  dialog. `AutomationStateTest` extended for the new
  `hostKinds`-based discoverability guard. 25 files changed,
  ~1100 insertions across three squash-mergeable commits.

### Input & HID

- **#72** Composite HID descriptor gains a keyboard (report ID 3,
  8-byte boot-protocol layout: modifier byte + reserved + six
  keycodes) and a Consumer-page AC Pan byte on the mouse report —
  the mouse frame widens to 5 bytes `[buttons, dx, dy, wheelY,
  wheelX]`. Data path behind the descriptor: `TranslationEngine`
  grows `processKeyDown` / `processKeyUp` / `tapKey` (modifier byte
  + six-keycode rollover) and a two-axis `processWheel` with
  per-axis fractional carry; `HidOutputBuffer` drains keyboard
  frames through an independent pass-through FIFO so a key chord
  never coalesces with — or wipes — pending cursor motion; the 8 ms
  input pacer carries both wheel axes; `BleHidGateway.send` routes
  `HidMode.KEYBOARD` to report ID 3. 11 new JUnit cases
  (`TranslationEngineKeyboardTest`, `HidOutputBufferTest`). The
  Mac-trackpad gesture handlers that feed these paths (pinch zoom,
  horizontal scroll, multi-finger swipes) land in a follow-up PR.
  **Forced re-pair**: HID descriptor shape changed — hosts (macOS,
  Windows, …) cache the report map and must "Forget This Device"
  and pair fresh.

### Protocol & Security

- **#71** Add a standalone GATT Battery Service (`0x180F`) alongside the
  feedback service so bonded hosts show the phone's battery level next
  to "Bluetrack" in their Bluetooth menu. Battery Level characteristic
  (`0x2A19`, READ + NOTIFY, uint8 0..100) sourced from
  `BatteryManager.BATTERY_PROPERTY_CAPACITY`; CCCD (`0x2902`) tracks
  notify subscribers; a runtime `ACTION_BATTERY_CHANGED` receiver
  notifies on level change, charging-state flip, or every 60 s. The
  service is added from inside `onServiceAdded` only after the feedback
  service reports `GATT_SUCCESS` (Android serializes `addService`). No
  security gating — battery is public; the encrypted feedback handshake
  / crypto path is untouched. Cadence rule extracted to a pure
  `BatteryNotifyPolicy` with 5 JUnit cases. **No re-pair**: adding a
  GATT service does not change the HID descriptor or the feedback
  service shape, so bonded hosts keep working.
- **#34** Persist lifetime HID + feedback + rejection counters across
  process kill (SharedPreferences-backed `LifetimeCountersStore`,
  throttled `LifetimeCountersAccumulator`). 11 new JUnit cases.
- **#33** Rate-limit BLE handshake writes per peer to drop floods
  before crypto. Token bucket: 4 capacity, 4 tokens/s, LRU-capped at
  64 peers. 8 new JUnit cases.
- **#32** Add `export-identity` / `import-identity` subcommands to
  the Swift CLI and Python sender. Cross-tool format compatible.
  4 new XCTest cases on the Swift side.
- **#31** Fuzz the BLE feedback handshake parser and decrypt path.
  6 deterministic fuzz tests with fixed seeds; 5 000 random inputs
  per test.
- **#30** Smoke-test the R8 release build for BLE crypto on every PR.
  ProGuard `-keep` rules for BouncyCastle X25519/Ed25519 plus the
  Bluetrack protocol entry classes; CI now `assembleRelease` and
  greps every `classes*.dex` for the expected symbols.
- **#29** Doc-only: `docs/THREAT_MODEL.md` — six adversary tiers,
  six attack surfaces with mitigations, residual risks, out-of-scope
  enumeration.
- **#28** Cross-platform golden-vector fixture
  `host/test-vectors/feedback_v1.json`. Swift / Android / Python
  byte-equal tests; CI regenerates and `git diff --exit-code` on
  every Host run. Catches silent protocol drift between platforms.
- **#26** Bind BLE feedback handshake to a TOFU-pinned host Ed25519
  identity. 128-byte handshake `eph_x25519 || id_ed25519 || sig`.
  TOFU store + Forget host action. **Forced re-pair**: GATT
  characteristic shape changed.
- **#25** Enforce a 64-frame sliding replay window on the BLE
  feedback receiver. AES-GCM tag-valid frames whose counter is an
  exact replay or older than 63 below the high-water mark are
  dropped. Wrap-around forces session rotation.
- **#24** Bind BLE feedback handshake to a peripheral-displayed
  pairing pin. 6-digit pin shown on phone status row, mixed into
  HKDF info; host CLI gains `--pin <digits>`. **Forced re-pair**:
  HKDF info changed.
- **#23** Replace static AES-128-CTR with X25519 ECDH + HKDF-SHA256
  + AES-256-GCM. New 128-byte handshake characteristic; 28-byte
  authenticated frames. **Forced re-pair**: protocol breaking
  change.

### Inspector / observability

- **#22** Cross-feed BLE peripheral name into HID-side filter in
  `companion`. Removes the manual `--name <phone>` rerun.

### Documentation

- **#27** Capture UI redesign brief, design-canvas gaps, and project
  blind-spots audit. Five new docs: `UI_BRIEF.md`,
  `UI_BRIEF_GAPS.md`, `UI_BRIEF_GAPS_V2.md`, `UI_DESIGN.md`,
  `PROJECT_AUDIT.md`. UI work is paused; doc-only delta to keep the
  context warm.

## 2.0.0 — 2026-04 (versionCode 2)

Initial public version after the rewrite to native Kotlin /
Bluetooth HID. Earlier history is in `git log` below this tag.

- HID Device profile registration via composite mouse + gamepad
  descriptor. Mode switching keeps a single registration.
- BLE GATT feedback service with static AES-128-CTR (later
  superseded — see #23).
- Foreground service + auto-connect to computer-class hosts;
  AirPods / pointing devices / keyboards explicitly ignored.
- Diagnostic touchpad with predictive filler, transport governor,
  fractional mouse deltas.
- macOS host inspector (`bluetrack-hid-inspector`) with `scan`,
  `watch`, `feedback`, `companion`, `selftest` subcommands.
- Python feedback sender for headless testing.

## 1.x

Single commit history; not separately versioned. See
`git log v2.0.0` for the original feature flow.
