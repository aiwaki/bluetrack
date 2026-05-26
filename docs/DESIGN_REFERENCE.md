# Design reference — Bluetrack v2.4 mock

Reference shared 2026-05-23. Six panels (Welcome / Permissions /
Pair / Hub live / Hosts / Diagnostics). Visual direction we want
to converge towards in future polish PRs.

## Visual language

- **Background palette.** Near-black with a radial deep-red glow
  off-centre (more saturated than the current `palette.crit`
  pulse). Subtle vignette top-left → bottom-right.
- **Type stack.** Heavy sans display headings ("Three permissions.",
  "Pair a host.", "Bluetrack", "MacBook Pro 14"") in 28–48 sp,
  weight 800, tight tracking (-0.025 em). Mono caps for chips
  and stat labels (`HID BROADCAST · 2.4 GHZ`, `DISCOVERABLE ·
  4:52`, `LIVE · 60HZ`).
- **Accent colour.** Single bright red `#E10000-ish` carries CTAs
  (`Get started`, `Grant access`, `Skip — pair later`), live
  status dots, hostname highlights. Everything else is mono /
  fg2 / fg3. No mint, no cool — the reference is two-colour:
  red + greyscale.
- **Surface shapes.** 28–32 dp rounded rectangles, soft inner
  shadow, ~6 % white highlight on top edge. Border hairlines at
  `glassBorder` strength. No glass blur — the reference reads
  flat which matches our current `glassEnabled=false` baseline.
- **CTA pills.** Full-width red `Get started` / `Grant access`
  buttons at the bottom of every onboarding step, with subtle
  red glow halo around them. White caps text, bold mono.
- **Hero affordance.** Welcome screen has a centred red disc
  with concentric pulse rings ("HID BROADCAST"). That's the
  same idea as our `StatusHero` breath ring but turned into the
  primary visual instead of an accessory.

## Per-screen takeaways

### 01 · Welcome
- App version chip next to wordmark (`BLUETRACK · V2.4`).
- Big tagline split across two lines ("Your phone. **Their
  mouse.**") with the second half in the red accent.
- Sub-headline (≤ 3 lines) covers the value prop + platform
  matrix in one breath.
- Page indicator dots at the bottom above the CTA.

### 02 · Permissions
- Numbered count step in the corner (`02 OF 03`).
- "Three permissions." headline tells the user the scope up
  front — no "tap to learn more".
- Per-permission row: square red icon tile + label + one-line
  body + status pill (`GRANTED` / `NEEDED`). Status pills are
  the only place red shows up other than the CTA.
- Mono callout box at the bottom for caveats ("On Android 12+,
  all three are required.").

### 03 · Pair
- "Pair a host." 48 sp display, host placeholder in red
  inline (`Bluetooth-A4E7.`).
- Discoverable card: dashed red countdown ruler under the
  device name. Tells the user the discoverability window is
  ticking, no separate timer chip needed.
- Two numbered step squares (`1 Open Bluetooth` / `2 Tap
  Bluetrack-A4E7`). Mono labels.
- `Skip — pair later` CTA — first non-progressive button in
  the flow.

### 04 · Hub (live)
- Header microline `HID GATEWAY` + wordmark "Bluetrack".
- Status row: red dot + `CONNECTED · INPUT LIVE` in caps.
- Display name MASSIVE (~64 sp) — host stays the visual anchor.
- Address / age / latency line in mono (`A4:83:E7 · paired
  3 days · 6.2 ms`).
- Mode toggle: two pills (MOUSE / GAMEPAD) with the active one
  filled red, the other glass.
- Touchpad surface labels its edges (`SCROLL UP`, `L-CLICK`,
  `R-CLICK`, `SCROLL DOWN · 2-TAP`) plus a small dot in the
  centre.
- Footer stat triplet: `REPORTS 12.4k now` / `LATENCY 6 ms ·
  99p` / `UPTIME 2:41 hh:mm`. The currently active cell is
  red, others are white / fg2.
- Custom dock at the bottom: 4 icons + an active highlight
  pill (red filled circle). Cleaner than our 4 dock entries.

### 06 · Hosts
- Top counter chip (`03 OF 12`) — total bonded count.
- "NOW CONNECTED" card promoted above the bonded list with
  the same big-display treatment as Hub.
- Bonded rows: square icon avatar (⌘ for iMac, lambda for
  Steam Deck, ▲ for tablet), name + platform + `2 days ago`,
  red `CONNECT` pill on the right.
- `+` icon top-right for "add new host" affordance.
- Hamburger left of title — implies a per-route side panel
  we don't have today; could land as the gear → settings link.

### 07 · Diagnostics
- "PIPELINE HEALTH 98/100" as a single big number with a
  per-second bar chart under it. Replaces our LiveRateHero
  hidRate / fbRate pair. Single composite health score is a
  cleaner at-a-glance.
- FRAME BUDGET cards: small per-stage bars (TOUCH GAP, PACER
  GAP, QUEUE LAT, HID SEND) each with a current value, a
  utilisation bar, and a `max Xms` caption.
- WARNINGS · LAST 5M log: timestamp, label, value in red.
  Replaces the rejections table for the steady-state view —
  rejections become a sub-section instead of the headline.

## Notes for future polish PRs

1. Pull the **dock** towards a tighter pill — 4 icons + an
   active-state mint circle behind the current one. Our slabs
   are wider than they need to be.
2. Promote the **host name** on Hub to the reference's display
   weight (60 sp+). Today's `StatusHero` is closer to 26 sp.
3. Add **page indicator dots** to the Welcome flow.
4. Move stats from `LiveRateHero` to a single **PIPELINE HEALTH**
   score + per-stage bar chart. Computed from frame budget
   counters already in `InputDiagnostics`.
5. Per-permission rows on Welcome could carry the actual
   runtime grant state (we already have that via
   `hasBluetoothPermissions()` + `hasNotificationsPermission()`).
6. Hub footer stat triplet (`REPORTS · LATENCY · UPTIME`) reads
   way cleaner than the current MetricTile pair — drop them
   back into the Hub as a single line above the touchpad.
7. Touchpad surface edge labels (`SCROLL UP` / `L-CLICK` /
   `R-CLICK` / `SCROLL DOWN · 2-TAP`) are a great UX hint we
   should consider adopting; users currently have to discover
   double-tap themselves.
8. "Pipeline Health" score over time as a bar chart instead of
   line chart reads better at a glance — bigger areas of red
   stand out faster than dips on a line.

## Branding assets

`docs/assets/branding/` (added 2026-05-23):

- `icon-clear-*.png` — light gradient app icon `[·]` bracket
  glyph in red on warm white. Sizes 48 / 60 / 80 / 120 / 128 /
  152 / 180 / 192 / 256 / 512 / 1024.
- `icon-mono-*.png` — monochrome variant for Android notification
  icons / launcher tints. Sizes 192 / 512 / 1024.
- `banner-1280x320-slim.png`, `banner-1280x640.png` — repo
  banners. Slim version sits at the top of `README.md`; full
  version is the GitHub social card.
- `debug-shape.png` — debug primitive, kept alongside.

Wire-up plan for the Android launcher icon lives in
`docs/GAMEPAD_FOLLOWUP.md` next sprint.
