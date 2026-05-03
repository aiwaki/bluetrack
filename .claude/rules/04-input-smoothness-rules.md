# Input Smoothness Rules

- Touchpad smoothness is currently a good hardware-tested baseline. Do not
  casually retune constants.
- Current pipeline:
  - Android touch events enqueue motion.
  - `MainViewModel` drains accumulated deltas every 8 ms.
  - `TranslationEngine` preserves fractional mouse deltas.
  - `HidOutputBuffer` decouples Bluetooth sending from the input pacer.
  - `HidTransportGovernor` backs off after measured `sendReport` stalls.
  - `TouchMotionPredictor` fills short touch-delivery gaps and reconciles
    predicted motion against real touch motion.
- Do not block the input pacer on Bluetooth calls, Compose state updates, logging,
  or compatibility refresh work.
- Use `adb logcat -s BluetrackInput Bluetrack` when debugging input.
- Interpret logs carefully:
  - `touch gap`: Android delayed touch delivery.
  - `pacer gap`: the app's input clock stalled.
  - `queue latency`: ViewModel motion queue lagged.
  - `output queue latency`: HID transport backed up.
  - `HID send`: Android/Bluetooth sendReport blocked.
- Prefer small, measured changes over speculative smoothing.
