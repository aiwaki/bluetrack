# Bluetrack Agent Brief

Codex/agent operating prompt + roadmap. The project shape, code map,
build commands, and rules live in `CLAUDE.md` and
`.claude/rules/00-project.md` — read those first to avoid duplication.

`docs/CODEX_CONTEXT.md` carries the longer-form mental model and
hardware validation script.

## First Commands

```bash
git status -sb
git log --oneline --decorate -5
gh pr list --json number,title,url,state,isDraft
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Then validate before and after meaningful changes:

```bash
cd android && ./gradlew testDebugUnitTest assembleDebug && cd ..
swift test --package-path host/macos-hid-inspector
python3 -m py_compile android/tools/ble_encrypt_sender.py
```

## Expert Operating Prompt

```text
Act as a senior Android/Bluetooth engineer and product-minded
diagnostic-tool designer. Start by reading CLAUDE.md (auto-loaded),
.claude/rules/00-project.md (auto-loaded), and docs/CODEX_CONTEXT.md
when you need the longer mental model. Inspect git/PR state before
editing. Treat Bluetooth HID and BLE feedback as separate systems.
Make the app honest about hardware capabilities, visible state, and
failure reasons. Prefer small, verifiable changes that improve real
device debugging. Validate with Android Studio's bundled JBR and
`swift test` for the host CLI. Update project memory when the
operating model changes. Push through the active PR with both CI
lanes (Android, Host) green.
```

## Good Next Bets

- Compatibility matrix: more `host/snapshots/` entries from different
  Mac+phone combos. Held for community contribution; one user does
  not have enough hardware to populate this meaningfully.
- Strengthen pairing beyond the 6-digit pin: host pubkey pinning
  (TOFU), or device-bound identity keys exchanged out-of-band (QR
  code on first run). Current pin model is opportunistic-only — fine
  against passive snoops, weak against shoulder-surfing.
- Add replay-window / counter-monotonicity enforcement on the
  peripheral. AES-GCM rejects nonce reuse but does not enforce strict
  ordering.
- Refine the diagnostic touchpad into a more deliberate control
  surface once the new UI artifacts land.
