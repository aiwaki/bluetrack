import XCTest
@testable import BluetrackHostKit

final class HidDescriptorDecoderTests: XCTestCase {
    /// First handful of bytes from Bluetrack's mouse descriptor — the
    /// canonical Generic Desktop + Mouse + Application Collection +
    /// Report ID 1 prelude. Asserts the decoder recognises the most
    /// common short-item shapes.
    private let mousePrelude: [UInt8] = [
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x02, // Usage (Mouse)
        0xA1, 0x01, // Collection (Application)
        0x85, 0x01, // Report ID (1)
        0xC0 // End Collection
    ]

    func testDecodesEachItemOfMousePrelude() {
        let items = HidDescriptorDecoder.decode(mousePrelude)
        XCTAssertEqual(items.count, 5)
        XCTAssertEqual(items[0].rawBytes, [0x05, 0x01])
        XCTAssertEqual(items[0].summary, "Usage Page (Generic Desktop)")
        XCTAssertEqual(items[1].rawBytes, [0x09, 0x02])
        XCTAssertEqual(items[1].summary, "Usage (2)")
        XCTAssertEqual(items[2].rawBytes, [0xA1, 0x01])
        XCTAssertEqual(items[2].summary, "Collection (Application)")
        XCTAssertEqual(items[3].rawBytes, [0x85, 0x01])
        XCTAssertEqual(items[3].summary, "Report ID (1)")
        XCTAssertEqual(items[4].rawBytes, [0xC0])
        XCTAssertEqual(items[4].summary, "End Collection")
    }

    func testDecodesSignedLogicalRanges() {
        // Logical Minimum (-127) is encoded as 0x15 0x81 (signed byte).
        // Logical Maximum (127) is encoded as 0x25 0x7F (positive byte).
        let bytes: [UInt8] = [0x15, 0x81, 0x25, 0x7F]
        let items = HidDescriptorDecoder.decode(bytes)
        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(items[0].summary, "Logical Minimum (-127)")
        XCTAssertEqual(items[1].summary, "Logical Maximum (127)")
    }

    func testDecodesTwoByteUsagePage() {
        // 0x06 Usage Page 2-byte: 0x06 0x0D 0x00 → Digitizers (0x000D)
        let items = HidDescriptorDecoder.decode([0x06, 0x0D, 0x00])
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].rawBytes, [0x06, 0x0D, 0x00])
        XCTAssertEqual(items[0].summary, "Usage Page (Digitizers)")
    }

    func testInputFlagsDataVarAbs() {
        // 0x81 0x02 = Input (Data, Variable, Absolute)
        let items = HidDescriptorDecoder.decode([0x81, 0x02])
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].summary, "Input (Data, Var, Abs)")
    }

    func testInputFlagsConstantArrayRelative() {
        // 0x81 0x05 = Input (Const, Array, Rel)
        let items = HidDescriptorDecoder.decode([0x81, 0x05])
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].summary, "Input (Const, Array, Rel)")
    }

    func testRenderTextHasHeaderHexAndRawBlock() {
        let rendered = HidDescriptorDecoder.renderText(mousePrelude)
        XCTAssertTrue(rendered.contains("Report descriptor (9 bytes)"))
        XCTAssertTrue(rendered.contains("Usage Page (Generic Desktop)"))
        XCTAssertTrue(rendered.contains("End Collection"))
        XCTAssertTrue(rendered.contains("Raw hex:"))
        // Every original byte should appear in the trailing raw dump.
        for byte in mousePrelude {
            let hex = String(format: "%02X", byte)
            XCTAssertTrue(rendered.contains(hex), "raw dump missing \(hex)")
        }
    }

    func testTruncatedShortItemReportsRemainder() {
        // 0x25 promises 1 data byte but the stream ends.
        let items = HidDescriptorDecoder.decode([0x25])
        XCTAssertEqual(items.count, 1)
        XCTAssertTrue(items[0].summary.contains("truncated"))
        XCTAssertEqual(items[0].rawBytes, [0x25])
    }

    func testLongItemBasicDecode() {
        // 0xFE | len=2 | tag=0xAA | data=0x11 0x22
        let items = HidDescriptorDecoder.decode([0xFE, 0x02, 0xAA, 0x11, 0x22])
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].rawBytes, [0xFE, 0x02, 0xAA, 0x11, 0x22])
        XCTAssertTrue(items[0].summary.contains("Long item tag=0xAA len=2"))
    }

    func testFullBluetrackMouseDescriptorRoundTripDoesNotCrash() {
        // The Bluetrack composite mouse descriptor (subset that
        // matches what the Android side actually registers). The
        // decoder should walk all bytes and emit items for each.
        let bytes: [UInt8] = [
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01,
            0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01,
            0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03,
            0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x05,
            0x81, 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
            0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08,
            0x95, 0x03, 0x81, 0x06, 0xC0, 0xC0
        ]
        let items = HidDescriptorDecoder.decode(bytes)
        // 56 bytes / typical-2-bytes-per-item ≈ 28 items; the exact
        // count is not the point — what matters is no crash and the
        // sum of raw bytes matches the input.
        let totalConsumed = items.reduce(0) { $0 + $1.rawBytes.count }
        XCTAssertEqual(totalConsumed, bytes.count, "decoder should walk every byte")
        let usagePages = items.filter { $0.summary.hasPrefix("Usage Page") }
        // Generic Desktop is declared twice (outer mouse + inner pointer
        // axes); Button page is declared between them. 3 total.
        XCTAssertEqual(
            usagePages.count, 3,
            "mouse descriptor has GD + Button + GD usage-page sequence"
        )
        let endCollections = items.filter { $0.summary == "End Collection" }
        XCTAssertEqual(endCollections.count, 2)
    }
}
