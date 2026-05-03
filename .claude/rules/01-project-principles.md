# Bluetrack Project Principles

- Treat Bluetrack as a native Android/Kotlin Bluetooth HID project, not a web or
  Flutter app.
- The phone is the Bluetooth HID device. The computer is the HID host.
- Keep HID and BLE feedback separate in architecture, UI wording, tests, and
  debugging.
- The target product feel is automatic and calm: no normal-flow manual buttons,
  clear state, and graceful recovery where Android allows it.
- Hardware-dependent failures are normal. Make them visible and actionable
  instead of hiding or pretending they are software-only.
- Preserve the current smooth touchpad baseline unless fresh hardware logs show
  exactly why it must change.
- Descriptor changes require host-side forget/re-pair during validation because
  macOS and other hosts cache HID report maps.
