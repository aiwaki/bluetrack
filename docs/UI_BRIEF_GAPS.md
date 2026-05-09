# Bluetrack canvas — gaps to fill

Read after `UI_BRIEF.md`. The 8-screen canvas (Welcome → Permissions →
Pair → Hub → Gamepad → Hosts → Diagnostics → Settings) covers the
shape, but several runtime concerns of the live app are not addressed
yet. Below is the delta to add to the canvas before this can replace
the current build without losing functionality.

## Must add (functional pieces canvas does not yet show)

### 1. Pairing pin display + interaction

The phone generates a fresh 6-digit pin every time the BLE feedback
GATT server opens. The user must read it off the phone and type it
into the host CLI (`--pin <digits>`) for the encrypted feedback
channel to work. Current canvas Hub does not surface this.

Needs in the design:
- Big readable pin block (monospace, probably bigger than the
  fingerprint). Tap-to-copy.
- "Session #N" or generation counter so the user knows when it has
  rotated.
- Visible state when GATT server is closed (no pin).
- Pin is regenerated on every `Open feedback GATT` lifecycle. UX
  should not feel like the pin is "broken" when it changes — frame
  it as a per-session credential.

### 2. Host identity (Trust) — full flow, not just a row

Today the Hub has a "Trust <fingerprint> [Forget]" row. The redesign
should treat the trust state as a first-class flow:

- **Empty state** ("first run, will pin whoever speaks first") —
  needs an explicit, calm visual rather than a single label.
- **Pinned state** — fingerprint, when it was pinned, host CLI hint
  ("compare with the line your CLI printed").
- **Rejection state** — when an untrusted host tries to connect,
  surface a dismissible card: "Different host tried to connect.
  Tap Forget to re-pair."
- **Forget host** — confirmation step before wipe (it is
  irreversible from the phone side; the user has to re-pair).
- A future hook for OOB identity exchange (QR code rendering): leave
  space for it on the Trust card so we do not redesign later.

### 3. Compatibility caveats (calm, not error)

Some phones cannot run HID Device profile at all. Today the Hub just
shows "HID profile unavailable" in the system row. The redesign needs
a non-error visual for "this device cannot do part of the job" — same
class as the macOS system "Not Supported" microcopy, not a red
banner. Apply to:

- HID profile unavailable.
- BLE advertiser unavailable.
- Multiple advertisement unsupported.

### 4. Permissions screen — granular state

The canvas has a single Permissions screen. The app actually needs
three independent grants:

- Bluetooth nearby-device (BLE scan + advertise + connect).
- Notifications (the foreground-service permanent notification needs
  it on Android 13+).
- The foreground-service notification itself (system-spawned, not
  bypassable).

Show each as its own row with status (granted / denied / not asked)
and a re-request action. The current design implies "one big grant",
which does not match the reality.

### 5. System overlays / dialogs

Android shows non-bypassable system sheets for Bluetooth enable,
discoverability, pairing, and the foreground-service notification.
The redesign must not pretend these can be inlined. Plan for:

- Pre-prompt explanatory cards above each system dialog ("Android
  will ask to make the phone discoverable for 120 seconds").
- A timeline-ish reading of "we asked / system asked you / you
  approved-or-cancelled" so the user is not surprised by the second
  prompt.

### 6. Hosts screen — needs more than a list

Auto-connect today silently picks a "best HID host" from bonded
devices, ignoring AirPods / headphones / mice / keyboards / trackpads.
The Hosts screen should show:

- All bonded devices with their device class.
- Which one is the active HID host (if any).
- Which ones are deliberately ignored (audio, accessory).
- A clear visual for "computer-class but no HID capability".
- Manual "Connect" / "Forget" per host.
- Optional: an explanation chip "Connecting to mice/headphones is
  ignored on purpose" so the rule is discoverable.

### 7. Diagnostics — live rates, not totals

Current canvas shows counters as static numbers. The runtime knows:

- Reports per second (HID send rate).
- Feedback per second (encrypted packet rate).
- Rejected feedback rate (split: bad signature / untrusted host /
  replay-window drop / GCM tag failure / wrong pin).
- Last 32 timeline events.
- Replay-window state (last accepted counter, drops).
- Pin generation count (how many times the GATT lifecycle rolled).

These belong on Diagnostics with proper micro-charts. Spark-line over
the last 60 s is enough; full graphs are out of scope.

### 8. Activity timeline

The runtime emits up to 24 events per session. Canvas Hub does not
have a timeline strip. Either keep it on the Hub (compact, last 4–5)
or move it to Diagnostics (full). Either way, it has to be in the
design — today it is the only path to "why did pairing just fail" or
"the host disconnected".

### 9. Gamepad screen content

Canvas has the rotated frame but not the surface itself. We need:

- Two analog sticks (left X/Y, right X/Y) with live position.
- 16 buttons + a hat (D-pad). 7-byte gamepad report shape is fixed.
- Visual feedback on press / release.
- An indicator for the "wake train" — Bluetrack periodically emits
  visible gamepad reports + a touch-gesture nudge to coax the host's
  Gamepad API into life. It is a real state ("Waking host gamepad
  API"), not just a debug nuance.
- Forced landscape on this screen only.

### 10. Mouse touchpad mechanics

The polyline + liquid drop visual is settled, but the tap/click
semantics are not. Decide and design:

- Single-finger drag → relative mouse motion (already works).
- Single tap → left click? (Current build does NOT click.)
- Two-finger drag → scroll wheel? (Mouse descriptor has wheel byte,
  not currently exposed.)
- Right click affordance? (Mouse descriptor has 3 buttons.)
- Whatever is decided must not regress the smoothness baseline of
  the drag pipeline.

### 11. Foreground-service running indicator

While the HID keep-alive is alive, Android shows a permanent
notification. The redesign should mirror that with a calm "running"
chip somewhere on the Hub so the user understands why the
notification is there and not a leak.

### 12. Visible-as / Bluetrack Pro Engine

Hosts see the phone as `Bluetrack Pro Engine` (or the phone's
product name on some Macs — see `docs/GAMEPAD_DEBUGGING.md`). The
Hub should surface "Visible as: Bluetrack Pro Engine" so the user
knows what to look for in macOS Bluetooth Settings.

### 13. Empty / first-time states

Each new screen needs a defined empty state:

- Hosts: zero bonded → "No paired computers yet. Tap Pair to start."
- Diagnostics: pre-session → "Counters appear once a host connects."
- Settings → Identity: pre-pin → "No host trusted yet."
- Activity: zero events → "Quiet."

## Could reconsider (canvas done, but worth a second pass)

### A. Single-screen Permissions vs. inline

The canvas treats Permissions as a one-time wizard step. In practice
the app re-checks every cold start; only re-prompt when something
has been revoked. Consider an inline "missing permission" surface on
Hub instead of always routing through Permissions.

### B. Pair as a screen vs. action

"Pair" today is a single button + system sheet. A whole screen for
this might feel like a wizard step the user has to complete every
session. Consider either:

- Keep it as a Welcome substep (first run only), or
- Demote it to a primary action on Hub when no host is connected.

### C. Tweaks placement

Tweaks live in Settings via the gear. Glow / accent / theme are
live-preview-friendly. Worth checking:

- Do we want a "preview" mode in the Tweaks panel that mirrors the
  Hub in miniature so changes are obvious without bouncing back?
- Should Glass-surfaces and Diagnostics-on-hub be separated from
  cosmetic tweaks (theme/accent/glow) since they change layout, not
  just colors?

### D. 3D Mode toggle = navigation

Tapping the GAMEPAD half flips to landscape Gamepad screen. That is
discoverable for power users but invisible for new users. Consider:

- A subtle hint the first time the user lands on the Hub
  ("Tap Gamepad to switch and rotate").
- Or a separate dock route to Gamepad in addition to the toggle.

### E. Aurora intensity vs. battery

Aurora behind the glass is constantly animating. On low-power state
or while the foreground HID service is running on a cold device,
this might be visible cost. Consider:

- A "Reduce motion" tweak that freezes aurora.
- Auto-pause aurora when battery is below 20%.

### F. Welcome screen ROI

Welcome is one screen out of eight. After first run, do we ever go
back to it? If not, it might be a router target only ("About"
button somewhere) rather than a screen sitting in the dock.

### G. Bottom dock with 8 destinations

Eight icons in a fixed bottom dock is too many for a phone. Likely
collapse:

- `Hub` (default)
- `Gamepad` (the 3D toggle alternative)
- `Hosts`
- `Diagnostics`
- `Settings`

Welcome / Permissions / Pair are first-run flows, not dock entries.

## Summary of asks for Claude Design

- Add: Pin block, Trust flow (empty/pinned/rejection/Forget),
  granular Permissions, system-overlay choreography, Hosts list with
  classes + ignore states, live Diagnostics rates, Activity strip,
  Gamepad sticks/buttons surface, touchpad tap/scroll/right-click
  decisions, foreground-service chip, Visible-as line, empty states.
- Reconsider: Permissions inline vs. wizard, Pair as action vs.
  screen, Tweaks live preview, 3D toggle discoverability, aurora
  battery cost, Welcome's permanent presence, dock destinations
  (likely 5, not 8).
