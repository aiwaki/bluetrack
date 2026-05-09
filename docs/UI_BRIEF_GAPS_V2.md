# Bluetrack canvas v3 — second pass

Read after `UI_BRIEF.md` and `UI_BRIEF_GAPS.md`. Claude Design v3
landed almost everything from the first gaps doc. Two threads still
need attention before this can ship as the new build:

1. 18 small decisions that v3 referenced but did not nail down.
2. The gamepad surface and the overall aesthetic should be pushed
   further — see "Gamepad — full treatment" and "Aesthetic uplift"
   below.

---

## 1. Open decisions from v3

Most of these are 1–2 sentence answers. They affect what engineering
can actually build.

### Mechanics not yet decided

1. **Touchpad gestures.** v3 shows a "tap / scroll / right-click
   legend" but does not commit to mechanics. Pick exactly one mapping
   for each of:
   - Single tap on the touchpad.
   - Two-finger drag.
   - Right click affordance (long-press? two-finger tap? a bottom
     edge zone? a dedicated button under the surface?).
   - Middle click (skip if not surfaced; current Mouse descriptor
     supports it).
   The "do not regress smooth drag" rule still applies.

2. **3D toggle discoverability.** Tapping the GAMEPAD half flips to
   the landscape Gamepad screen. v3 does not surface this. Add a
   one-shot first-run hint, or a small chevron / "Open" affordance
   on the toggle. Or both.

3. **Activity filters.** v3 shows `All / Hosts / Warnings`. "Hosts"
   reads ambiguous (HID host connection events vs Trust events).
   Replace with `All / Pairing / Feedback / Errors`, or add a `Trust`
   filter as a fourth.

4. **Manual Connect on Hosts.** v3 shows ignored audio / pointing /
   keyboard with reasons. Decide: do we expose an "Connect anyway"
   override, or keep the rule absolute? If exposed, it goes behind a
   confirm sheet ("This device is unlikely to behave as a HID host.
   Connect anyway?").

### Privacy / safety concerns

5. **PIN copy-to-clipboard.** Clipboard is readable by other apps.
   The pin is short-lived but the copy lasts longer than the session.
   Add: auto-clear clipboard after ~30 s, with a microcopy
   ("Copied. Will clear in 30 s.").

6. **Tweaks live-preview PIN.** The preview must NOT show the real
   active session pin (visual leak when sharing the screen). Use a
   placeholder like `888 888` or the literal text "PIN".

7. **Diag PIN lifecycle.** v3 lists "generations / session / current".
   Show only counters and ages. Never store or surface historic pin
   values — only the count of how many times it rolled.

### Numbers that diverge from runtime

8. **Replay window range.** v3 visualises ±32. The actual sliding
   window is 64 entries `[last_counter − 63 .. last_counter]`. Use
   that range, not ±32. Fix the chart.

9. **Rejection breakdown.** v3 has 5 categories. The runtime emits at
   least 7 possible reasons:
   - Wrong frame size (not 28 bytes).
   - GCM tag failure (wrong pin, wrong key, or tampered ciphertext).
   - Replay-window drop.
   - Wrong-length handshake.
   - Bad Ed25519 signature.
   - Untrusted host (TOFU mismatch).
   - X25519 derivation failure (malformed peer pubkey).
   Either expand to 7, or pick a clear collapse plan and label it
   ("Crypto failure" rolls up tag + sig + derivation, etc.).

### Behaviour gaps

10. **Trust rejection card.** Decide: dismissible per appearance,
    persistent until Forget/match, or auto-collapse after N seconds?
    Also: on a second consecutive untrusted attempt, does it stack
    ("3 attempts in 60 s") or just stay at one card?

11. **Aurora auto-pause.** v3 has manual Reduce Motion only. Add an
    auto-pause when battery is below 20 % OR when a phone-side
    "Power Saver" is active. Reduce Motion overrides.

12. **Hub Activity strip linking.** Tap on a strip event → opens the
    full Activity screen with that event focused, or no link?
    Recommend the link — current build hides this rich timeline
    behind logs.

### Naming / wording

13. **FG-chip toggle.** Rename the Tweaks toggle to "Show running
    indicator" instead of "Foreground service". The chip is a UI
    affordance; the underlying service is not optional.

14. **Forget host wording.** Confirm sheet should read: "Forget host?
    Bluetrack will pin a new host on the next handshake. If you also
    want to roll the host's identity, run the host CLI with
    `--reset-host-identity`." Without this hint users will silently
    pin themselves back into the same identity.

### Settings/About

15. **Settings → Permissions.** Decide: is this a duplicate of the
    Permissions screen, or a link? Recommend a small "Manage
    permissions →" link card; do not duplicate the screen.

16. **Settings → About.** Surface: app version, identity file path
    (`~/.config/...host_identity_v1.json`-equivalent — Android
    SharedPreferences explanation is fine), a "Reset trusted host"
    nuclear option (same as the Hub's Forget but discoverable from
    Settings), and a build hash.

### Tweaks

17. **Density.** v3 lists a Density tweak. Define what it changes:
    `Compact / Comfortable / Spacious` mapping to specific paddings
    around panels and rows. Otherwise it will be reinvented during
    port.

18. **Wake-train chip.** Show state AND reason. Microcopy: "Waking
    host gamepad API — browsers need a button event to recognise
    new gamepads." Without it, users assume something is wrong.

---

## 2. Gamepad — full treatment

User-stated bar: "полноценный геймпад, иначе будто бы смысла нет."
v3 has the layout but is too thin. The Gamepad screen should feel
like a real controller surface, not a debug overlay.

### Required controls (descriptor-bound)

The current Android HID gamepad descriptor sends a 7-byte report:
buttons low / buttons high / hat / left X / left Y / right X /
right Y. So the surface MUST address all 16 buttons, the hat, and
both sticks. Map them visibly:

- **Two analog sticks** (left and right). Big circular thumb pads.
  Drag dot inside the ring. Optional inner deadzone ring rendered.
  Click-in (L3 / R3) on a sustained press or a dedicated icon over
  the stick.
- **D-pad / hat.** Cross shape, 8-way. Live highlight on the
  currently asserted direction (hat values 0–7, neutral 8).
- **Face buttons.** A / B / X / Y. Press states with neon glow.
- **Shoulder + triggers.** LB / RB above; LT / RT below. The current
  descriptor has 16 buttons total; LT and RT consume two of them
  (digital), with the analog 0–127 shoulder pressure NOT in the
  current descriptor — call this out as "digital triggers for now"
  in a tooltip.
- **Start / Back / Guide / Menu.** Centre rail.
- **Capture / Share button.** If we have a 16th button to spare,
  optional. Otherwise omit.

### State surfaces

Beyond the buttons themselves, the screen should carry:

- **Wake-train chip** (already in v3). Add the reason microcopy
  from item 18 above.
- **Connection lozenge** at the top: host name + latency, e.g.
  "MacBook Pro · 14 ms". Greyed when disconnected.
- **Frame counter** (the gamepad report sequence). Tiny, monospace,
  bottom corner. Useful for diag without dominating.
- **HID host status pulse** — a small dot that pulses with each
  outgoing report so the user sees "frames are flowing" even when
  no input is happening (the wake train fires on its own cadence).
- **Right-edge fold-out diag panel** (optional, off by default,
  toggled by a side handle). Shows live X/Y values, trigger
  pressure (0–1 if we ever add analog), and the last 8 reports.

### Tactile

- **Haptic on press** for face / shoulder / D-pad. `VibratorManager`
  short blip on press, none on release. Tweaks gets a Haptics
  on/off.
- **Distinct press sound** off by default (Android gamepads are
  silent; respect that). Tweaks has it for accessibility users.
- **Hold-to-recenter** on a stick if the user wants to confirm the
  centre is at zero (drift recovery affordance). One-shot, not a
  recalibration.

### Personalisation (Tweaks → Gamepad sub-section)

- Stick deadzone slider.
- Stick sensitivity curve (linear / quadratic / cubic).
- Layout swap: ABXY ↔ Nintendo (BAYX) face button labels. Wire only,
  the bits stay the same.
- Trigger threshold (digital firing point: 0.3 / 0.5 / 0.7).
- Optional: vibration intensity.

These are settings, not on the live screen. Live screen stays clean.

### Layout discipline

- **Forced landscape.** No portrait option. Lock orientation while
  the screen is foregrounded; restore on leave.
- **Thumb reach.** Sticks centred at thumb-natural height (~55 % of
  the screen height in landscape). Face buttons / D-pad at the same
  vertical band.
- **Edge gutters.** Generous left/right insets so a phone case does
  not blanket the controls.
- **One-handed mode.** Tweaks toggle to slide the controls inward by
  ~12 % so a smaller phone is still reachable.

---

## 3. Aesthetic uplift

v3 reads as polished but generic glassmorphism. The user wants more
identity. Directions Claude Design can push on:

### A. Stronger Bluetrack signature

- Lean harder on the neon red. Today the accent is a cool red used
  sparingly. Try: title text and key counters in pure neon red with
  a 1-pixel inset white line so it reads on dark, plus a vivid red
  "ribbon" at the top of the Hub for one frame on a fresh handshake.
- Add a wordmark treatment, not just plain "Bluetrack" in the
  default sans. Custom kerning + a subtle red bracket / underline.

### B. Texture under the glass

- Aurora alone is a whole-app cliché. Layer something Bluetrack-
  specific: faint scrolling diagonal lattice (suggesting Bluetooth
  packets in flight), or a slow sine-wave pulse at the bottom of
  every screen that speeds up briefly when frames are flowing.
- The Hub gets a "heartbeat" line at the very bottom — a thin neon
  trace that pulses each time a HID report goes out. Once seen,
  this is the visual signature of the app.

### C. Spatial depth

- 3D mode toggle is currently a lone hero. Apply the same depth
  language elsewhere:
  - Pin block hovers slightly above the Hub plane on a soft drop
    shadow with a parallax wobble on device tilt (use the
    accelerometer; gated by Reduce Motion).
  - Trust card has a card-flip animation between "empty / pinned /
    rejection" states instead of cross-fading.

### D. Motion identity

- The dock indicator and toggle springs are good. Standardise on a
  single "Bluetrack curve" spec — e.g. spring stiffness 320,
  damping 0.78 — and apply it everywhere a settle happens. v3's
  motion is currently inconsistent.
- Avoid bouncing for diagnostic numbers. Counters tick with a
  monospace pulse (not a bouncing scale-in).

### E. Density choice for power users

- Add a "Verifier" Tweaks layout that compresses the Hub so Pin,
  Trust, Activity strip, and a sparkline of HID rate fit in one
  fold without scrolling. Power users live there. Stays distinct
  from Compact/Comfortable in the regular Density tweak.

### F. Light theme honesty

- v3 implies light theme exists. Confirm it has been designed with
  the same care, or call it out explicitly as a follow-up. Neon on
  light is hard — likely needs different glow intensities. Better
  to ship dark-only than to half-build light.

---

## 4. What we need back from Claude Design

For each of the 18 numbered points in §1: a one-sentence decision.

For Gamepad in §2: a fully designed landscape screen including the
state surfaces and the Tweaks sub-section.

For aesthetic uplift in §3: at least one of the directions adopted
(neon ribbon / heartbeat / wordmark are the safest; spatial depth
and motion identity standardisation are the most ambitious).

After that we can start the Compose port — see `UI_DESIGN.md` for
the engineering port plan.
