<!--
Bluetrack PR template. Tick what applies; delete what does not.
The cross-platform crypto path is sensitive — protocol-touching PRs
have a mandatory checklist below.
-->

## Summary

<!-- 1–3 bullets. Why this change exists; what it enables. -->

-

## Changes

<!-- Bullet list of concrete touch points. -->

-

## Test plan

- [ ] `./gradlew testDebugUnitTest assembleDebug` from `android/`
- [ ] `swift test --package-path host/macos-hid-inspector` (if host or
      shared crypto touched)
- [ ] `python3 host/test-vectors/test_golden_vectors.py` (if protocol
      shape touched)
- [ ] Real hardware run (describe what was exercised)

## Protocol / crypto checklist

<!-- Skip if the PR does not touch the BLE feedback handshake, the HID
descriptor, the AES-GCM frame, or any of the cross-platform fixtures.
If even one box is unchecked, explain why in the PR description. -->

- [ ] No protocol shape change (handshake bytes, HKDF inputs, frame
      layout, descriptor)
- [ ] Or: protocol change is described in the PR body AND the
      cross-platform fixture is regenerated
      (`python3 host/test-vectors/generate_vectors.py`) AND the diff
      to `host/test-vectors/feedback_v1.json` is intentional
- [ ] CHANGELOG.md updated under "Unreleased" with the PR number
- [ ] If forced re-pair: line added under the relevant section
      in CHANGELOG.md and a re-pair note in the PR body
- [ ] `docs/THREAT_MODEL.md` reviewed (and updated if the change
      shifts attack surface or residual risk)
- [ ] ProGuard `-keep` rules in `android/app/proguard-rules.pro`
      still cover anything new the GATT thread reaches via
      reflection-able code paths

## UI / state checklist

<!-- Skip if no UI / GatewayStatus field added. -->

- [ ] New `GatewayStatus` field has a sensible default so existing
      `updateStatus(...)` call sites do not need to change
- [ ] `GatewayStatusReducer` test covers the new field's
      passthrough / reset behaviour
- [ ] No new manual button on the normal flow (Bluetrack feel:
      automatic and calm)

## Hardware / forced re-pair

- [ ] No descriptor or service shape change → re-pair not required
- [ ] Or: re-pair instructions added to the PR body and the
      relevant CHANGELOG.md entry is marked **Forced re-pair**

## Docs

- [ ] No public-facing surface change → docs untouched
- [ ] Or: `README.md` / `docs/CODEX_CONTEXT.md` / `CLAUDE.md` /
      `AGENTS.md` updated as needed
