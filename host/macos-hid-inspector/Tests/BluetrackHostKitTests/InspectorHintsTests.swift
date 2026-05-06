import XCTest
@testable import BluetrackHostKit

final class InspectorHintsTests: XCTestCase {

    func testReturnsNilForEmptyCandidates() {
        XCTAssertNil(
            InspectorHints.bestPhoneRename(currentNameFilter: "Bluetrack", candidates: [])
        )
    }

    func testReturnsBareBluetoothNameWhenAvailable() {
        let candidates = [
            CandidateProductPair(product: "aiwaki", transport: "Bluetooth", looksLikeGamepad: false)
        ]
        XCTAssertEqual(
            InspectorHints.bestPhoneRename(currentNameFilter: "Bluetrack", candidates: candidates),
            "aiwaki"
        )
    }

    func testPrefersBareNameOverParenthesizedAccessory() {
        let candidates = [
            CandidateProductPair(product: "Magic Mouse (aiwaki)", transport: "Bluetooth", looksLikeGamepad: false),
            CandidateProductPair(product: "aiwaki", transport: "Bluetooth", looksLikeGamepad: false),
        ]
        XCTAssertEqual(
            InspectorHints.bestPhoneRename(currentNameFilter: "Bluetrack", candidates: candidates),
            "aiwaki"
        )
    }

    func testSkipsCandidatesAlreadyMatchingFilter() {
        let candidates = [
            CandidateProductPair(product: "Bluetrack Pro", transport: "Bluetooth", looksLikeGamepad: false),
            CandidateProductPair(product: "aiwaki", transport: "Bluetooth", looksLikeGamepad: false),
        ]
        XCTAssertEqual(
            InspectorHints.bestPhoneRename(currentNameFilter: "Bluetrack", candidates: candidates),
            "aiwaki"
        )
    }

    func testSkipsNonBluetoothCandidates() {
        let candidates = [
            CandidateProductPair(product: "USB Gamepad", transport: "USB", looksLikeGamepad: true),
            CandidateProductPair(product: "aiwaki", transport: "Bluetooth", looksLikeGamepad: false),
        ]
        XCTAssertEqual(
            InspectorHints.bestPhoneRename(currentNameFilter: "Bluetrack", candidates: candidates),
            "aiwaki"
        )
    }

    func testSkipsEmptyProductNames() {
        let candidates = [
            CandidateProductPair(product: "", transport: "Bluetooth", looksLikeGamepad: false),
            CandidateProductPair(product: "pixel-8", transport: "Bluetooth", looksLikeGamepad: false),
        ]
        XCTAssertEqual(
            InspectorHints.bestPhoneRename(currentNameFilter: "Bluetrack", candidates: candidates),
            "pixel-8"
        )
    }

    func testPrefersGamepadLikeCandidates() {
        let candidates = [
            CandidateProductPair(product: "phone-name", transport: "Bluetooth", looksLikeGamepad: false),
            CandidateProductPair(product: "controller", transport: "Bluetooth", looksLikeGamepad: true),
        ]
        XCTAssertEqual(
            InspectorHints.bestPhoneRename(currentNameFilter: "Bluetrack", candidates: candidates),
            "controller"
        )
    }

    func testReturnsNilWhenOnlyAlreadyMatchingCandidatesExist() {
        let candidates = [
            CandidateProductPair(product: "Bluetrack Pro", transport: "Bluetooth", looksLikeGamepad: true)
        ]
        XCTAssertNil(
            InspectorHints.bestPhoneRename(currentNameFilter: "Bluetrack", candidates: candidates)
        )
    }

    // MARK: - bleNameToHidFilter

    func testBleNameToHidFilterReturnsNilForNilName() {
        XCTAssertNil(
            InspectorHints.bleNameToHidFilter(blePeripheralName: nil, currentFilter: "Bluetrack")
        )
    }

    func testBleNameToHidFilterReturnsNilForEmptyName() {
        XCTAssertNil(
            InspectorHints.bleNameToHidFilter(blePeripheralName: "", currentFilter: "Bluetrack")
        )
        XCTAssertNil(
            InspectorHints.bleNameToHidFilter(blePeripheralName: "   ", currentFilter: "Bluetrack")
        )
    }

    func testBleNameToHidFilterReturnsPhoneNameWhenDifferent() {
        XCTAssertEqual(
            InspectorHints.bleNameToHidFilter(blePeripheralName: "aiwaki", currentFilter: "Bluetrack"),
            "aiwaki"
        )
    }

    func testBleNameToHidFilterReturnsNilWhenAlreadyMatching() {
        XCTAssertNil(
            InspectorHints.bleNameToHidFilter(blePeripheralName: "Bluetrack Pro", currentFilter: "Bluetrack")
        )
    }

    func testBleNameToHidFilterIsCaseInsensitive() {
        XCTAssertNil(
            InspectorHints.bleNameToHidFilter(blePeripheralName: "bluetrack", currentFilter: "Bluetrack")
        )
        XCTAssertNil(
            InspectorHints.bleNameToHidFilter(blePeripheralName: "BLUETRACK PRO", currentFilter: "Bluetrack")
        )
    }

    func testBleNameToHidFilterTrimsWhitespace() {
        XCTAssertEqual(
            InspectorHints.bleNameToHidFilter(blePeripheralName: "  pixel-8  ", currentFilter: "Bluetrack"),
            "pixel-8"
        )
    }
}
