import Foundation

/// Pure-Swift decoder for the HID Report Descriptor short-item byte
/// stream documented in the USB HID Class Definition (Device Class
/// Definition for HID 1.11, §6.2.2). Inputs the bytes from
/// `IOHIDDeviceGetProperty(device, kIOHIDReportDescriptorKey)`; outputs
/// one row per item with the raw prefix + data bytes and a human-
/// readable interpretation. Used by the `dump-descriptor` subcommand
/// of `bluetrack-hid-inspector` so users can inspect the composite
/// descriptor Bluetrack registered on this phone, and so we can verify
/// the descriptor survives bonding / re-pair cycles.
///
/// The decoder is deliberately tolerant: any unknown prefix renders as
/// a row containing the raw bytes and a `??` tag, so a forensic dump
/// never aborts mid-way through a real descriptor that happens to
/// include rare vendor-defined items.
public enum HidDescriptorDecoder {
    /// One decoded entry. `rawBytes` is the slice of the original
    /// stream this row consumed (prefix byte + data bytes for short
    /// items; `0xFE` + size + tag + data for long items). `summary`
    /// is the human-readable interpretation.
    public struct Item: Equatable {
        public let rawBytes: [UInt8]
        public let summary: String

        public init(rawBytes: [UInt8], summary: String) {
            self.rawBytes = rawBytes
            self.summary = summary
        }
    }

    /// Decode a raw HID report descriptor byte stream. Returns one
    /// `Item` per descriptor item, in order. A trailing truncated item
    /// (e.g. a prefix promising 4 data bytes when only 2 remain) is
    /// emitted as a `summary` row labelled `truncated` so the caller
    /// can surface it without losing context.
    public static func decode(_ bytes: [UInt8]) -> [Item] {
        var items: [Item] = []
        var index = 0
        while index < bytes.count {
            let prefix = bytes[index]
            if prefix == 0xFE {
                // Long item: 0xFE | size (1 byte) | tag (1 byte) | data
                let raw = remaining(bytes, from: index, count: 3)
                if raw.count < 3 {
                    items.append(.init(rawBytes: raw, summary: "Long item (truncated header)"))
                    return items
                }
                let dataLen = Int(raw[1])
                let totalLen = 3 + dataLen
                let totalRaw = remaining(bytes, from: index, count: totalLen)
                if totalRaw.count < totalLen {
                    items.append(.init(rawBytes: totalRaw, summary: "Long item (truncated data)"))
                    return items
                }
                items.append(
                    .init(
                        rawBytes: totalRaw,
                        summary: "Long item tag=0x\(hex(raw[2])) len=\(dataLen)"
                    )
                )
                index += totalLen
                continue
            }

            let sizeCode = Int(prefix & 0x03)
            let dataLen = sizeCode == 3 ? 4 : sizeCode
            let totalLen = 1 + dataLen
            let raw = remaining(bytes, from: index, count: totalLen)
            if raw.count < totalLen {
                items.append(
                    .init(
                        rawBytes: raw,
                        summary: "Short item (truncated; expected \(totalLen) bytes, got \(raw.count))"
                    )
                )
                return items
            }

            let typeCode = (prefix >> 2) & 0x03
            let tag = (prefix >> 4) & 0x0F
            let data = Array(raw.dropFirst())
            let value = unsignedLittleEndian(data)
            let signed = signedLittleEndian(data)
            let summary: String = switch typeCode {
            case 0: mainItemSummary(tag: tag, data: data, value: value)
            case 1: globalItemSummary(tag: tag, value: value, signed: signed)
            case 2: localItemSummary(tag: tag, value: value)
            default: "Reserved item type=\(typeCode) tag=0x\(hex(tag))"
            }
            items.append(.init(rawBytes: raw, summary: summary))
            index += totalLen
        }
        return items
    }

    /// Convenience: produce a `hidviz`-style multi-line string with
    /// one item per line plus a trailing raw hex dump suitable for
    /// pasting into Wireshark or hidviz.
    public static func renderText(_ bytes: [UInt8]) -> String {
        var lines: [String] = []
        lines.append("Report descriptor (\(bytes.count) bytes)")
        lines.append(String(repeating: "─", count: 64))
        for item in decode(bytes) {
            let hexBytes = item.rawBytes
                .map { String(format: "%02X", $0) }
                .joined(separator: " ")
            let padded = hexBytes.padding(toLength: 17, withPad: " ", startingAt: 0)
            lines.append("  \(padded) \(item.summary)")
        }
        lines.append(String(repeating: "─", count: 64))
        lines.append("Raw hex:")
        // 16 bytes per line for paste-ability.
        var idx = 0
        while idx < bytes.count {
            let end = min(idx + 16, bytes.count)
            let chunk = bytes[idx..<end]
                .map { String(format: "%02X", $0) }
                .joined(separator: " ")
            lines.append("  " + chunk)
            idx = end
        }
        return lines.joined(separator: "\n") + "\n"
    }

    // MARK: - Item summaries

    private static func mainItemSummary(tag: UInt8, data: [UInt8], value: UInt64) -> String {
        let name: String
        switch tag {
        case 0x8: name = "Input"
        case 0x9: name = "Output"
        case 0xB: name = "Feature"
        case 0xA: return "Collection (\(collectionName(value)))"
        case 0xC: return "End Collection"
        default: return "Main tag=0x\(hex(tag)) value=0x\(hexValue(value))"
        }
        if data.isEmpty {
            return "\(name) (no data)"
        }
        let flags = inputOutputFeatureFlags(value)
        return "\(name) (\(flags))"
    }

    private static func globalItemSummary(tag: UInt8, value: UInt64, signed: Int64) -> String {
        switch tag {
        case 0x0: "Usage Page (\(usagePageName(UInt16(truncatingIfNeeded: value))))"
        case 0x1: "Logical Minimum (\(signed))"
        case 0x2: "Logical Maximum (\(signed))"
        case 0x3: "Physical Minimum (\(signed))"
        case 0x4: "Physical Maximum (\(signed))"
        case 0x5: "Unit Exponent (\(signed))"
        case 0x6: "Unit (0x\(hexValue(value)))"
        case 0x7: "Report Size (\(value))"
        case 0x8: "Report ID (\(value))"
        case 0x9: "Report Count (\(value))"
        case 0xA: "Push"
        case 0xB: "Pop"
        default: "Global tag=0x\(hex(tag)) value=0x\(hexValue(value))"
        }
    }

    private static func localItemSummary(tag: UInt8, value: UInt64) -> String {
        switch tag {
        case 0x0: "Usage (\(value))"
        case 0x1: "Usage Minimum (\(value))"
        case 0x2: "Usage Maximum (\(value))"
        case 0x3: "Designator Index (\(value))"
        case 0x4: "Designator Minimum (\(value))"
        case 0x5: "Designator Maximum (\(value))"
        case 0x7: "String Index (\(value))"
        case 0x8: "String Minimum (\(value))"
        case 0x9: "String Maximum (\(value))"
        case 0xA: "Delimiter (\(value))"
        default: "Local tag=0x\(hex(tag)) value=0x\(hexValue(value))"
        }
    }

    // MARK: - Helpers

    private static func collectionName(_ value: UInt64) -> String {
        switch value {
        case 0x00: "Physical"
        case 0x01: "Application"
        case 0x02: "Logical"
        case 0x03: "Report"
        case 0x04: "Named Array"
        case 0x05: "Usage Switch"
        case 0x06: "Usage Modifier"
        default: "0x\(hexValue(value))"
        }
    }

    private static func inputOutputFeatureFlags(_ value: UInt64) -> String {
        var flags: [String] = []
        flags.append((value & 0x01) != 0 ? "Const" : "Data")
        flags.append((value & 0x02) != 0 ? "Var" : "Array")
        flags.append((value & 0x04) != 0 ? "Rel" : "Abs")
        if (value & 0x08) != 0 { flags.append("Wrap") }
        if (value & 0x10) != 0 { flags.append("NonLinear") }
        if (value & 0x20) != 0 { flags.append("NoPreferred") }
        if (value & 0x40) != 0 { flags.append("NullState") }
        if (value & 0x80) != 0 { flags.append("Volatile") }
        return flags.joined(separator: ", ")
    }

    private static func usagePageName(_ page: UInt16) -> String {
        switch page {
        case 0x01: "Generic Desktop"
        case 0x02: "Simulation Controls"
        case 0x05: "Game Controls"
        case 0x06: "Generic Device Controls"
        case 0x07: "Keyboard / Keypad"
        case 0x08: "LEDs"
        case 0x09: "Button"
        case 0x0A: "Ordinal"
        case 0x0B: "Telephony"
        case 0x0C: "Consumer"
        case 0x0D: "Digitizers"
        case 0x0F: "PID Page"
        case 0x84: "Power Device"
        case 0x85: "Battery System"
        default: "0x\(String(format: "%02X", page))"
        }
    }

    private static func unsignedLittleEndian(_ data: [UInt8]) -> UInt64 {
        var v: UInt64 = 0
        for (i, b) in data.enumerated() {
            v |= UInt64(b) << (8 * i)
        }
        return v
    }

    private static func signedLittleEndian(_ data: [UInt8]) -> Int64 {
        if data.isEmpty { return 0 }
        let raw = unsignedLittleEndian(data)
        let bits = data.count * 8
        let signBit: UInt64 = 1 << (bits - 1)
        if (raw & signBit) != 0 {
            // Sign-extend.
            let mask = (UInt64.max << bits)
            return Int64(bitPattern: raw | mask)
        }
        return Int64(raw)
    }

    private static func remaining(_ bytes: [UInt8], from offset: Int, count: Int) -> [UInt8] {
        let end = min(offset + count, bytes.count)
        return Array(bytes[offset..<end])
    }

    private static func hex(_ byte: UInt8) -> String {
        String(format: "%02X", byte)
    }

    private static func hexValue(_ value: UInt64) -> String {
        String(format: "%X", value)
    }
}
