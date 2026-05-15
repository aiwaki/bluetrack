# Bluetrack — idea backlog

Captured from a brainstorm pass after the v3 protocol / observability
wave landed (#22–#37). The UI redesign is a separate workstream and is
not duplicated here (see `docs/UI_BRIEF*.md` and `docs/UI_DESIGN.md`).
The threat-model / audit follow-ups are tracked in
`docs/THREAT_MODEL.md` and `docs/PROJECT_AUDIT.md` and are also not
duplicated here.

Each idea is graded `S / M / L` by rough scope; `★` marks the ones
with the best leverage-to-cost ratio for the current state of the
project.

## A. Product features

### A1. Voice → keyboard HID `L`
Android speech recognition → keystrokes on the host through the
keyboard HID descriptor that lands with the keyboard mode. Killer
accessibility feature; gated on the keyboard PR shipping first.

### A2. Clipboard sync `M`
Bidirectional via the existing BLE feedback channel: a new
characteristic exposes clipboard text. Host writes → phone copies into
Android clipboard. Phone copies → host reads. Encrypted under the same
AES-256-GCM session.

### A3. File push `M`
Small files (< 1 KiB) chunked through BLE feedback. Demo-grade
"AirDrop-light for your own PC". Not core to Bluetrack's HID identity;
keep behind a toggle.

### A4. Multi-touch gestures `S` ★
Two-finger drag → wheel scroll (Mouse descriptor's wheel byte is
unused today). Pinch → Ctrl+scroll. Three-finger swipe → keystrokes
once keyboard mode is live.

### A5. Tap-to-click + edge zones for right-click `S` ★
Mouse descriptor has 3 buttons; today only motion is wired. Decide
mechanics during UI redesign, ship behind a Tweaks toggle in the
meantime.

## B. Bidirectional protocol

### B1. Notify characteristic phone → host `M`
Push events instead of polling: "pin rotated", "host fingerprint
changed", "battery low", "untrusted handshake attempt". Low-latency,
no spam reads.

### B2. Health-check request/response `M`
Host writes a small `query` value; phone responds with an encrypted
`{uptime, lifetime_counters_summary, hid_state}` snapshot. No need to
read the screen for diagnostics.

## C. Devops / distribution

### C1. Signed release APK on git tag `S` ★
GitHub Actions: push `vX.Y.Z` → build release APK + AAB → publish as
GitHub Release artifact. Keystore via Actions secret. Once configured,
zero ongoing cost.

### C2. F-Droid metadata `S`
`fastlane/metadata/android/` with description, screenshots, per-version
changelog. Submission optional; the metadata is the long-tail step.

### C3. Renovate / Dependabot `S` ★
Auto-PRs for BouncyCastle, kotlinx, AGP, `cryptography`, etc. CI gates
catch breakage so this is safe to leave on.

### C4. Lint / format pre-commit + CI gate `S` ★
ktlint + SwiftFormat + black. Pre-commit hook + a CI check that fails
on diff. Stops formatting drift from eating review time.

### C5. PR template (this PR) `S` ★
`.github/pull_request_template.md` with the protocol / crypto
checklist. Captures the muscle memory from #22–#37 so future PRs do
not silently skip the CHANGELOG / golden-vector / threat-model steps.

## D. Performance / reliability

### D1. Adaptive HID send rate `M`
`HidTransportGovernor` already backs off on stalls; add the symmetric
half — increase send cadence when the last N reports flowed within
Y ms. Closed-loop control instead of stay-at-floor.

### D2. Stall watchdog `S`
If zero reports in T seconds AND host is connected AND mode != idle →
attempt one `register` cycle. Silent recovery from rare stalls.

### D3. Reconnect-on-launch `S`
Persist last connected host MAC. On launch, try a direct re-connect
before falling back to advertise + wait. Cuts the post-app-relaunch
gap.

### D4. Systrace markers `S`
`Trace.beginSection / endSection` around HID send, pacer drain, AES
encrypt. Enables Perfetto profiling on real hardware without code
changes per investigation.

## E. Tooling

### E1. Bug-report zip `M` ★
"Send diagnostics" action gathers last 5 min of logcat (own tags only)
+ `GatewayStatus` snapshot + lifetime counters + last 16 timeline
events into a sharable zip. Off-line; no telemetry.

### E2. Replay mode `M`
Host CLI records an input session (timestamps + reports) to JSON,
then plays it back deterministically. Regression tests on real
hardware; bug repro without manual choreography.

### E3. HID descriptor dumper `S` ★
`bluetrack-hid-inspector dump-descriptor` → emits the report
descriptor binary plus a human-readable decode. Paste into hidviz /
Wireshark.

### E4. Latency tester `M`
`bluetrack-hid-inspector latency` drives inputs at known timestamps
via the feedback channel and observes via IOHID. Prints an RTT
histogram. Backs up the smoothness baseline with hard numbers.

## F. Security beyond the threat-model backlog

### F1. Encrypted-at-rest identity `M`
macOS Keychain / Linux Secret Service / Windows Credential Manager
instead of plaintext JSON in `~/.config/...`. Closes the Tier-D
(shell access on host) exposure.

### F2. Identity passphrase wrap `M`
Optional `--identity-passphrase`; identity JSON wrapped in AEAD with
a key derived from the passphrase (argon2 or pbkdf2). Less ergonomic
than Keychain but portable.

## G. Long-shot / out-of-scope-but-tempting

### G1. Web-based companion `L`
Bluetooth Web API in Chrome. No CLI, no Python — open a page, enter
the pin, talk to the phone. Lowers barrier to entry; mostly demo.

### G2. Wear OS companion `L`
Watch shows pin / Trust state / recent rejection events. Pure
nice-to-have.

## Picks for the next pass

If we resume from this list cold, the cheapest-but-high-leverage
sequence is: C5 (this PR) → C4 (lint/format CI) → C1 (signed release
on tag) → E3 (descriptor dumper) → E1 (bug-report zip) → A4
(multi-touch gestures) → C3 (Renovate).

Anything higher up the stack (A1 voice, B1 notify, F1 Keychain) is
worth doing once the UI redesign work returns — they expand the
protocol surface and benefit from the new screens being settled
first.
