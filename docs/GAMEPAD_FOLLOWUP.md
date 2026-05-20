# Gamepad follow-up

Tracking work items that surfaced while polishing the gamepad
route but are too large for the current PR and warrant their own
release notes — most touch the HID descriptor or HID report
format, which means a forced re-pair on every bonded host (per
`CLAUDE.md` HID rule).

## 1. macOS games do not see Bluetrack as a gamepad

**Symptom.** On a paired MacBook Pro, the Hub touchpad cursor works
fine (mouse mode), but switching to gamepad mode does not register
in native macOS games. The phone screen shows reports incrementing,
the BT link stays connected, but in-game controller binding
prompts never fire.

**Diagnosis.** Bluetrack advertises a perfectly valid Generic
Desktop / Gamepad (Usage Page 0x01, Usage 0x05) HID descriptor —
16 buttons, hat switch, four 8-bit axes (X, Y, Z, Rz). `bluetrack-
hid-inspector watch` on the Mac confirms reports land at the
IOHID layer. The issue is that modern macOS games use Apple's
`GCController` framework instead of `IOHIDManager`, and
`GCController` only surfaces controllers that match an internal
allowlist (MFi, Xbox Wireless, DualSense / DualShock, Joy-Con).
Generic HID gamepads are silently filtered out.

**Options.**

A. **Document as known macOS limitation.** Cheapest. Bluetrack as
   a Steam Input device (via `IOHIDManager`) already works in
   Steam Big Picture. Native non-Steam macOS games are out.

B. **Emulate an Xbox One Wireless controller.** Match the exact
   HID descriptor, VID (`0x045E`), PID (`0x02E0` / `0x02FD`), and
   report ID layout of an Xbox One Wireless controller so
   `GCController` accepts it. Risks: Apple validates more than
   the descriptor (some firmware handshakes are checked), and
   Microsoft may revoke pairing if their authentication payload
   isn't reproduced. Forced re-pair release; possibly fragile
   across macOS minor updates.

C. **Emulate a DualSense / DualShock 4.** Same trade-offs as (B);
   the DualSense profile is reverse-engineered well enough that
   open-source projects (e.g. `DualSenseY`) already inject reports
   over a generic HID transport. Forced re-pair release.

D. **Ship a small companion macOS daemon.** Reads Bluetrack's
   generic HID reports, re-injects them as a virtual MFi or
   Xbox-like controller via a kernel extension or `virtualHID`
   library. Hardest path; carries kext / driver signing
   requirements.

Recommended next step: (A) for now plus a Settings hint pointing
users at Steam Big Picture; revisit (B) only if there's
enough user demand to justify the forced re-pair and the
maintenance burden of tracking Apple's allowlist.

## 2. Gamepad surface redesign

User reference attached in PR #57 discussion (2026-05-20 12:39 GMT+5).
Notable differences from current implementation:

- Larger centre HOME button (red glow), with SELECT / START
  stacked on either side rather than below the D-pad.
- L1 / R1 as horizontal pills near the screen corners; L2 / R2
  as vertical bars along the right edge (held by thumbs in
  landscape grip).
- Vertical stat rails on the left ("POLL", "LAT", "REPORTS",
  "UPTIME") and right ("● GAMEPAD LIVE", host name, "XINPUT ·
  16-BTN") edges — replace the current top rail.
- Left thumb area: L stick (top) + D-pad (mid) + R stick
  (bottom-left).
- Right thumb area: ABXY (top-right) + L2 / R2 bars stacked
  vertically.

Scope is large enough to be its own PR; not blocking the current
polish PR. Suggest tackling after the macOS gamepad-recognition
decision lands, so the redesigned surface can carry a clearer
status footer once we know which host frameworks Bluetrack
targets.
