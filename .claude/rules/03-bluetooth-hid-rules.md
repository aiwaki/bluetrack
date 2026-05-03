# Bluetooth HID Rules

- `BleHidGateway` owns Android `BluetoothHidDevice` registration, host
  auto-connect, report IDs, and the BLE feedback GATT server.
- Mode switching must not call `unregisterApp()`. Use the composite descriptor
  and switch only the active report path.
- Never auto-connect bonded audio/accessory devices such as AirPods,
  headphones, speakers, keyboards, mice, or trackpads. Prefer computer-class or
  computer-named hosts.
- Android system prompts for Bluetooth enable, discoverability, pairing, and the
  foreground-service notification cannot be bypassed by app code.
- Current gamepad report format is:

```text
[0] buttons low
[1] buttons high
[2] hat switch, neutral = 8
[3] left X
[4] left Y
[5] right X
[6] right Y
```

- Hosts may show Bluetrack as the Android phone name. The HID service underneath
  can still expose Bluetrack's composite mouse/gamepad report map.
- Browser Gamepad API pages usually need a real button or axis gesture after the
  page is open. Keep the automatic visible gamepad wake train and the
  rate-limited touch-gesture discovery wake unless real hardware evidence shows
  a better activation strategy.
- Use `host/macos-hid-inspector` before changing descriptors. If macOS receives
  report 2, the Android HID path is alive and the issue is browser/GameController
  activation or host mapping.
- After descriptor changes, tell testers to forget and re-pair the host device.
