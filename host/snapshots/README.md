# `host/snapshots/`

Hardware compatibility matrix for Bluetrack, persisted as one JSON file per
`bluetrack-hid-inspector companion` run. Drop a snapshot here whenever you
exercise the tool against a new Mac + Android phone combination so we can
diff hardware behavior over time without re-running the cockpit by hand.

## How to add a snapshot

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    companion --pin 246810 --seconds 15 --report host/snapshots/<file>.json
```

Replace `246810` with the 6-digit pin currently shown on the phone's
`Pin` status row. The pin rotates per `BleHidGateway.startGatt`.

The standalone `watch` and `feedback` subcommands accept the same flag and
produce a half-populated snapshot (the side that did not run is recorded
as `exitCode == -1`).

### Filename convention

```
<host-short>-<phone-short>-<YYYY-MM-DD>[-<note>].json
```

Examples:

- `aiwaki-mbp-pixel8-2026-05-15.json`
- `linux-mini-galaxy-s24-2026-06-01-after-firmware.json`
- `example-pass.json` (the canonical reference snapshot, hand-crafted to
  illustrate a clean two-sided run; never replace its values)

Keep names lowercase + hyphens; no spaces.

## Schema

The on-disk shape is owned by
[`BluetrackHostKit/CompanionReport.swift`](../macos-hid-inspector/Sources/BluetrackHostKit/CompanionReport.swift)
and is versioned via the top-level `tool` and `toolVersion` fields. Loaders
should refuse to interpret a snapshot whose `tool != "bluetrack-hid-inspector"`
or whose `toolVersion` is newer than the loader's known schema.

Top-level fields:

| Field | Type | Purpose |
| ----- | ---- | ------- |
| `tool` | string | Always `"bluetrack-hid-inspector"`. |
| `toolVersion` | string | Bumped when the schema changes. |
| `generatedAt` | string | ISO 8601 timestamp of the run end. |
| `totalSeconds` | number | Configured run duration (`scanTimeout + seconds`). |
| `verdict` | string | `"pass"` / `"partial"` / `"fail"` / `"skipped"`. |
| `hid` | object | HID watch outcome; see `HidWatchSnapshot`. |
| `ble` | object | BLE feedback outcome; see `BleFeedbackSnapshot`. |

A side that did not participate in the run carries `exitCode = -1`
(`CompanionReportWriter.skippedExitCode`) and minimal default values; that
is how a `feedback` or `watch` snapshot encodes the missing half.

JSON is pretty-printed with `sortedKeys` so file diffs stay small.

## Privacy

Snapshots include a `peripheralIdentifier` (a Bluetooth UUID assigned by
macOS) and a `peripheralName` (often the phone owner's macOS user name on
Apple-derived hosts, e.g. `aiwaki`). Only commit snapshots from devices you
own and are comfortable identifying in a public repository. If you need to
publish a snapshot from a device you do not own, redact `peripheralName`
to `"<redacted>"` and replace `peripheralIdentifier` with
`"00000000-0000-0000-0000-000000000000"` before committing.

## Reading snapshots back

Snapshots are valid Swift `Codable` JSON of `CompanionRunReport`. From any
script that depends on `BluetrackHostKit`:

```swift
import BluetrackHostKit
import Foundation

let url = URL(fileURLWithPath: path)
let data = try Data(contentsOf: url)
let report = try JSONDecoder().decode(CompanionRunReport.self, from: data)
print(report.verdict, report.ble.packetsSent, report.hid.eventCount)
```

Other languages can parse the JSON directly; field names match the Swift
struct property names exactly.
