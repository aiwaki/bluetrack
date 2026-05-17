# Bluetrack design canvas — v1

Source-of-truth design hand-off from Claude Design (claude.ai/design).
Generated from the `UI_BRIEF_GAPS_V2.md` brief; the canvas was
delivered as `App onboarding-handoff.zip` and unpacked here so the
files are reviewable in the repo and version-controlled with the
Compose port that follows.

**Read order:** `CANVAS_README.md` (Claude Design's own hand-off
note) → `Bluetrack.html` (entry point) → `tokens.css` (design
tokens) → individual `*.jsx` screens.

## Screens shipped

| File              | Artboards                                                    |
| ----------------- | ------------------------------------------------------------ |
| `onboarding.jsx`  | `01 Welcome`, `02 Permissions` (granular)                    |
| `hub.jsx`         | `Hub · empty (no host)`, `Hub · connected`                   |
| `hosts.jsx`       | `Hosts · empty`, `Hosts · 6 bonded`                          |
| `activity.jsx`    | `Activity · empty`, `Activity · live`                        |
| `diagnostics.jsx` | `Diagnostics · pre-session`, `Diagnostics · live`            |
| `settings.jsx`    | `Settings`                                                   |
| `gamepad.jsx`     | `Gamepad · live` (landscape, 760×360)                        |

Shared components live in `shared.jsx`, `android-frame.jsx`,
`design-canvas.jsx`. The Tweaks panel + live preview live in
`tweaks-panel.jsx`.

## Tokens (canonical values)

Pulled from `tokens.css`; the Compose port mirrors these into a
`BluetrackTheme`:

- Accent: `#ff2a3a` (`--mint`), bright `#ff5566`, deep `#c4001a`.
  Glow stack: `rgba(255,42,58,0.55)` and `rgba(255,42,58,0.18)`.
- Surfaces (dark): `--bg-0` `#08090a` → `--bg-3` `#20242a`.
- Hairlines: `rgba(255,255,255,0.07)` / `rgba(255,255,255,0.14)`.
- Foreground tiers: `#f4f5f4` at 100%/74%/50%/30%.
- Calm grey: `rgba(244,245,244,0.42)` — used for "not supported"
  states; never red.
- Radius: 8 / 12 / 16 / 22 / pill.
- Spacing: 4 / 8 / 12 / 14 / 16 / 18 / 22 / 28.
- Touch targets: 24 / 36 / 44 / 50.
- Glass: `rgba(20,22,25,0.55)` / `rgba(24,27,30,0.72)`, blur 22px /
  28px, saturate 180% / 200%.
- Motion curve: `cubic-bezier(0.16, 1.18, 0.32, 1)`, 380 ms — the
  Bluetrack settle.
- Typography: Geist (sans) + Geist Mono (counters / pin / fingerprint).

## Tweaks panel knobs

From `Bluetrack.html` defaults:

| Knob              | Default       | Notes                                |
| ----------------- | ------------- | ------------------------------------ |
| `theme`           | `dark`        | `light` marked WIP in this canvas.   |
| `glass`           | `on`          | `off` flattens surfaces.             |
| `motion`          | `full`        | `reduced` kills aurora + lattice.    |
| `density`         | `comfortable` | `compact` / `spacious` alternatives. |
| `accent`          | `#ff2a3a`     | 5 swatches: red / orange / violet / mint / cool. |
| `neonStrength`    | `1.0`         | Slider 0.2–1.6, scales glow stack.   |
| `showFGChip`      | `true`        | "Show running indicator" wording.    |
| `deadzone`        | `0.12`        | Gamepad stick deadzone.              |
| `curve`           | `linear`      | Gamepad sensitivity curve.           |
| `faceLayout`      | `abxy`        | Gamepad face button labels.          |
| `triggerThreshold`| `0.5`         | Gamepad digital trigger threshold.   |

## Decisions Claude Design committed

These resolve the 18 open questions raised in
`docs/UI_BRIEF_GAPS_V2.md` — useful reference for the Compose port:

- Activity filters: `All / Pairing / Feedback / Trust / Errors`
  (Trust kept as its own filter; not merged into Pairing).
- Diagnostics rejection breakdown: 7 categories (matches runtime).
- Replay window viz: `[last − 63, last]`, 64-wide.
- PIN preview in Tweaks: literal placeholder `888 888`, never the
  real session pin.
- FG-chip Tweaks label: "Show running indicator".
- Forget host: confirm sheet with CLI hint, deferred to gamepad PR.
- Settings → Permissions: link card, no screen duplication.
- Dock: 5 destinations — Hub / Hosts / Activity / Diag / Settings.
- Welcome: first-run only.
- 3D mode toggle: replaced by a calm in-Hub `Open Gamepad` action
  (the Hub also has a Gamepad shortcut chip — discoverability fix
  for our reconsider D).

## Why this is in the repo

The Compose port will land in 9 staged PRs per
`docs/UI_DESIGN.md`. Reviewers of each port PR can diff the canvas
against the Compose output here, so visual drift is catchable in
review without firing up the canvas separately. Subsequent design
revisions ship as `docs/design/v2/`, `v3/`, …; we never overwrite
`v1/` so the audit trail stays.
