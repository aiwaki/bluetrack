# Changelog

All notable changes to Bluetrack ship as one entry per pull request,
grouped by category. Versions follow `versionName` in
`android/app/build.gradle.kts`.

Forced re-pair lines call out PRs that change the BLE GATT service
shape or the HID descriptor — both bonded hosts (macOS, Windows, ...)
must "Forget This Device" and pair fresh after these.

## Unreleased

Targets `versionName 3.0.0` / `versionCode 3` once UI redesign lands.

### Protocol & Security

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
