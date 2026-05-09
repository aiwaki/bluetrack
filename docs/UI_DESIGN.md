# Bluetrack UI/UX Spec

Source: Claude Design canvas (8 screens + hero components + tweaks). This
document is the living port spec for Jetpack Compose. When something on
the canvas changes, update this file before touching Kotlin.

Companion doc: `docs/CODEX_CONTEXT.md` for the runtime model that this UI
is the front-end of.

## Top-Level Shell

`ScreenShell` — common chrome for every non-fullscreen screen.

- Scrollable content area (vertical).
- Bottom **dock**, absolute-pinned on top of an `bt-glass` layer
  (Apple-glass = backdrop-blur + saturate + aurora background).
- Dock is icons-only; active item gets a spring-eased indicator.
- Aurora background animates slowly under the glass layer (independent of
  scroll).
- Insets: dock floats above safe-area; content respects bottom inset by
  default.

Compose port:
- `ScreenShell(content: @Composable () -> Unit, dock: @Composable () -> Unit)`.
- Dock = `Surface` with custom blur effect (`Modifier.blur` is not enough —
  needs `RenderEffect` on API 31+; fall back to a tinted scrim below 31).
- Aurora = `Canvas` with two radial gradients drifting on
  `rememberInfiniteTransition`.

## Screens

| # | Screen        | Purpose                                                     |
|---|---------------|-------------------------------------------------------------|
| 1 | Welcome       | First-launch hero. Branding + "Get started" CTA.            |
| 2 | Permissions   | Request Bluetooth nearby-device + foreground-service prompt. |
| 3 | Pair          | Walk through Android discoverability + host-side pair.      |
| 4 | Hub (live)    | Day-to-day surface: status, touchpad, BLE/HID rows.         |
| 5 | Gamepad       | **Landscape** sticks/buttons surface (forced rotation in prod). |
| 6 | Hosts         | List of bonded HID hosts; auto-connect rules visible.       |
| 7 | Diagnostics   | Counters (reports, feedback, rejections), latency, logs.    |
| 8 | Settings      | Theme, accent, glow, glass toggle, identity controls.       |

### Notes per screen

**Welcome.** Static; transitions to Permissions. Use this to show the
neon red identity (`#ff2a3a` with multi-layer glow).

**Permissions.** Mirrors current `MainActivity` permission rationale flow.
Shown only when permissions are missing — do not gate on every launch
once granted.

**Pair.** Triggers `vm.discoverable(seconds)` and the system pairing
sheet. Status row reflects `status.pairing`.

**Hub.** The current `BluetrackContent`. Contains:
- Header panel (`status.hid` / connection age).
- Touchpad surface (the heaviest custom piece — see Touchpad below).
- System panel (`BT / HID / Pair / BLE / Pin / Trust` rows + Forget).
- Activity timeline.

**Gamepad.** Tapping the dock `GAMEPAD` icon (or a header chip) flips to
this screen. **Production builds force landscape;** the canvas demo just
rotates inside a portrait frame.

**Hosts.** Bonded list from `compatibility.bondedDevices`, with the
auto-connect "best HID host" highlighted.

**Diagnostics.** Numeric panels for HID `reportsSent`,
`feedbackPackets`, `rejectedFeedbackPackets`, plus the latency hints
from `adb logcat -s BluetrackInput Bluetrack`. Surface `Trust`
fingerprint and replay-window stats here too.

**Settings.** Tweaks panel + identity controls:
- Theme (dark / light).
- Accent (5 swatches: red / orange-red / orange / red-violet / violet).
- Glow strength slider (modulates the neon multi-layer shadow
  intensity).
- Diagnostics-on-hub toggle (compact metrics on the hub for live runs).
- Glass surfaces toggle (off → flat surfaces, on → `bt-glass`).
- "Forget host" lives on the hub `Trust` row, but Settings is where the
  user can also wipe the local pairing pin display etc.

## Hero Components

### 1) 3D Mode Toggle (mouse ⇄ gamepad)

- Spring animation, **360 ms total**.
- Tapping the `GAMEPAD` half also navigates to the Gamepad screen.
- Compose: `Animatable<Float>`, `spring(stiffness = 250f, damping = 0.85f)`,
  3D look via `graphicsLayer { rotationY = … cameraDistance = … }`.
- Lives in: hub header.

### 2) Reactive Touchpad

- Smooth polyline (no dot dust). Stroke width and alpha **fade along the
  trail**: thicker/opaque at the finger, thinner/transparent at the tail.
- Liquid drop at the finger: wide radial-gradient blob with a breathing
  pulse animation; the trail narrows non-linearly behind it.
- Canvas-only on the canvas demo runs from `mousemove`. **Real device
  must use the existing `MainViewModel.processMotion(...)` touch handler
  via `pointerInput { detectDragGestures }` or `pointerInteropFilter`.**
- Compose: `Canvas { … }`, `Path` for the polyline, `RadialGradient` for
  the drop, `rememberInfiniteTransition` for the breath.

Performance budget: 8 ms drain cadence (matches `MainViewModel`); the
canvas redraw must not lag behind that. Profile on real hardware before
adopting.

### 3) Neon Display Status

- Status row that uses the accent color with a multi-layer glow (4–6
  shadow passes at decreasing alpha). Replaces the old mint.
- Used for hero status text on Welcome and on the hub header.
- Compose: stack `Text` instances at increasing blur radii, each with
  reducing alpha. Wrap in a single `Box` with `Modifier.blendMode` if
  the device supports it.

## Design Tokens

### Color

- Neon red: `#ff2a3a` (replaces mint as the primary accent).
- Glow stack: 4 passes — `0 0 12px @ 60%`, `0 0 28px @ 35%`,
  `0 0 56px @ 18%`, `0 0 96px @ 10%`. Tunable by Glow slider.
- Accent variants (Tweaks): 5 swatches across red/orange/violet — keep
  hue family family-balanced; do not introduce greens or blues to the
  palette without Claude Design sign-off.

### Type

- Sans-only family.
- Body: **Geist**.
- Numeric metrics: **Geist Mono**.
- No serif anywhere. No display fonts.

Compose port: declare a `BluetrackTypography` with a `geist` and a
`geistMono` `FontFamily` loaded from `res/font/`.

### Glass

Two layers:
- `bt-glass`: backdrop-blur ~24, saturate ~120%, low-alpha tint.
- `bt-glass-strong`: backdrop-blur ~40, saturate ~140%, slightly more
  tint; for the dock and overlay sheets.
- Aurora behind the glass: two soft radial gradients drifting on a long
  loop (>= 12 s).

Compose port: `Modifier` extension `glass(strong: Boolean)` that:
1. Applies a tinted `Surface` color.
2. Renders aurora siblings under it via a `Box(Modifier.matchParentSize())`
   placed *behind* the glass surface.
3. Uses `RenderEffect.createBlurEffect` on API 31+; on older API,
   falls back to a flat tint with the toggle disabled.

Tweaks toggle: Glass surfaces ⇄ flat. Default = on.

### Buttons

- Primary: white text on accent (`#ff2a3a`-family) with **inset white
  outline** + drop shadow. Reads in both themes.
- Secondary: ghost on glass.
- Destructive: same as primary but accent shifted toward warm amber on
  hover.

## Animations

| Where                    | Curve / Spec                                  |
|--------------------------|-----------------------------------------------|
| Screen enter             | Staggered fade-up, 80 ms steps, max 4 items.  |
| Tweaks popup             | Scale-in (`0.92 → 1.0`), 220 ms, spring back. |
| Liquid drop              | Pulse breath, 1.4 s loop, ease-in-out.        |
| Aurora                   | 12 s drift, infinite, linear.                 |
| 3D mode toggle           | Spring, 360 ms total.                         |
| Dock indicator           | Spring, follows active item.                  |
| Touchpad trail           | Per-segment alpha curve, non-linear.          |

Compose: `tween` for screen-enter, `spring` for everything that has a
"settle" feel (toggles, dock indicator, popup), `rememberInfiniteTransition`
for aurora and breath.

## Tweaks Panel

Lives only inside Settings (the gear icon). The verifier-mode toolbar
deliberately does NOT host tweaks — keep them in the app proper.

Tweaks contents:
1. Theme (dark / light).
2. Accent (5 swatches).
3. Glow strength slider.
4. Diagnostics-on-hub toggle.
5. Glass surfaces toggle.

Persistence: `DataStore<Preferences>` keyed under `bluetrack_tweaks`.
Apply via a `CompositionLocal<TweakState>` provider at the root of the
Activity.

## Caveats and TODOs

- **Touchpad input.** Canvas demo uses `mousemove`. On Android, swap to
  the existing pointer pipeline that already feeds
  `MainViewModel.processMotion(...)`. Do not regress smoothness — the
  current touchpad is a hardware-tested baseline.
- **Forced landscape.** Production Gamepad screen should declare
  `screenOrientation = "sensorLandscape"` in the Activity (or use
  `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE` programmatically
  while the screen is foregrounded). The canvas frame is portrait-only
  for the demo.
- **Glass on older devices.** `RenderEffect` is API 31+. Fall back to
  a flat tint and disable the Tweaks "Glass surfaces" toggle on older
  builds; surface why in a tooltip.
- **3D mode toggle hit-target.** The whole `GAMEPAD` half also routes
  navigation. Make sure tap-down on the toggle does not start a drag
  on the touchpad behind it.

## Port Plan

Suggested order (one PR per step):
1. Tokens: colors, typography, glass modifier, glow Modifier. No screen
   changes. Visual diff on the existing Hub only.
2. `ScreenShell` + dock with placeholder routes.
3. Move existing `BluetrackContent` into the `Hub` route inside the
   shell. Preserve the touchpad pipeline.
4. Welcome / Permissions / Pair routes (mostly static + existing flows).
5. Hero components: 3D mode toggle, neon status text, liquid-drop
   touchpad enhancements (touchpad smoothness regression test required
   on real hardware).
6. Gamepad route + forced landscape.
7. Hosts + Diagnostics routes.
8. Settings + Tweaks panel + DataStore persistence.
9. Polish: aurora, animations curves, dark/light theming, accessibility
   (focus indicators, contrast in both themes).

Each step ships behind a runtime tweak (`new_ui = true|false` in
DataStore) so it can be flipped off if hardware testing reveals
regressions.
