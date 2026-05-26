package dev.xd.bluetrack.ui

/**
 * Which input surface the Hub renders below the StatusHero.
 *
 * Distinct from `HidMode` (the wire-level report shape the gateway
 * registers — MOUSE / GAMEPAD). Both surfaces here emit MOUSE
 * reports; the gamepad lives on its own fullscreen flip route
 * driven by `gamepadActive`.
 *
 *  - [TOUCHPAD] — the existing on-screen virtual trackpad: tap,
 *    drag, 2-finger scroll, edge boost, acceleration. The user's
 *    finger drives the cursor.
 *  - [MOUSE]    — mouse mirror passthrough. The phone waits for a
 *    USB-OTG or Bluetooth mouse connected to the phone, captures
 *    its pointer (so the on-phone cursor disappears) and forwards
 *    every motion / scroll / button event as a HID report to the
 *    paired host. Lets the user keep using a real mouse while the
 *    phone sits in the path as the input filter — the BLE
 *    feedback channel can still inject host-side corrections.
 */
enum class TouchpadSurfaceMode {
    TOUCHPAD,
    MOUSE,
}
