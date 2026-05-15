import XCTest
@testable import BluetrackHostKit

final class HostIdentityTests: XCTestCase {
    func testFreshIdentityHas32BytePublicKey() {
        let id = HostIdentity()
        XCTAssertEqual(id.publicKeyBytes.count, FeedbackCrypto.identityPublicKeySize)
    }

    func testFingerprintIs16HexChars() {
        let id = HostIdentity()
        let fp = id.fingerprint()
        XCTAssertEqual(fp.count, 16)
        for ch in fp.unicodeScalars {
            XCTAssertTrue(
                (ch.value >= 0x30 && ch.value <= 0x39) ||
                    (ch.value >= 0x61 && ch.value <= 0x66),
                "fingerprint must be lowercase hex, got '\(fp)'"
            )
        }
    }

    func testFingerprintIsStableAcrossInstances() {
        let id1 = HostIdentity()
        let pub = id1.publicKeyBytes
        XCTAssertEqual(id1.fingerprint(), HostIdentity.fingerprint(of: pub))
    }

    func testTwoFreshIdentitiesDiffer() {
        let a = HostIdentity()
        let b = HostIdentity()
        XCTAssertNotEqual(a.publicKeyBytes, b.publicKeyBytes)
        XCTAssertNotEqual(a.fingerprint(), b.fingerprint())
    }

    func testSignAndVerifyRoundTrip() throws {
        let id = HostIdentity()
        let payload = Data([0x01, 0x02, 0x03, 0x04, 0x05])
        let sig = try id.sign(payload)
        XCTAssertEqual(sig.count, FeedbackCrypto.identitySignatureSize)
        // Verify via the public key.
        let pub = try CryptoKitPublicKey(rawRepresentation: id.publicKeyBytes)
        XCTAssertTrue(pub.isValidSignature(sig, for: payload))
    }

    func testLoadOrGenerateRoundTripsThroughDisk() throws {
        let url = makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: url) }

        let original = try HostIdentity.loadOrGenerate(at: url)
        let loaded = try HostIdentity.loadOrGenerate(at: url)
        XCTAssertEqual(original.publicKeyBytes, loaded.publicKeyBytes)
        XCTAssertEqual(original.fingerprint(), loaded.fingerprint())
    }

    func testSaveSetsFilePermissionsTo0600() throws {
        let url = makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: url) }

        _ = try HostIdentity.loadOrGenerate(at: url)
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        let perms = attrs[.posixPermissions] as? NSNumber
        XCTAssertEqual(perms?.intValue, 0o600)
    }

    func testLoadRejectsCorruptedFile() throws {
        let url = makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: url) }

        let bogus = Data("{\"private_key_b64\":\"not-base64-padded\"}".utf8)
        try bogus.write(to: url)
        XCTAssertThrowsError(try HostIdentity.load(at: url))
    }

    func testResetRemovesIdentityFile() throws {
        let url = makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: url) }

        _ = try HostIdentity.loadOrGenerate(at: url)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        try HostIdentity.reset(at: url)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        // No-op when already absent.
        XCTAssertNoThrow(try HostIdentity.reset(at: url))
    }

    func testExportRoundTripsThroughDestination() throws {
        let src = makeTempFileURL()
        let dst = makeTempFileURL()
        defer {
            try? FileManager.default.removeItem(at: src)
            try? FileManager.default.removeItem(at: dst)
        }

        let original = try HostIdentity.loadOrGenerate(at: src)
        try HostIdentity.export(from: src, to: dst)
        XCTAssertTrue(FileManager.default.fileExists(atPath: dst.path))
        let exported = try HostIdentity.load(at: dst)
        XCTAssertEqual(original.publicKeyBytes, exported.publicKeyBytes)
        XCTAssertEqual(original.fingerprint(), exported.fingerprint())

        let attrs = try FileManager.default.attributesOfItem(atPath: dst.path)
        XCTAssertEqual(
            (attrs[.posixPermissions] as? NSNumber)?.intValue,
            0o600,
            "exported identity must keep mode 0600"
        )
    }

    func testImportInstallsAndBacksUpExistingDestination() throws {
        let src = makeTempFileURL()
        let dst = makeTempFileURL()
        let bak = dst.appendingPathExtension("bak")
        defer {
            try? FileManager.default.removeItem(at: src)
            try? FileManager.default.removeItem(at: dst)
            try? FileManager.default.removeItem(at: bak)
        }

        // dst starts with identity A.
        let original = try HostIdentity.loadOrGenerate(at: dst)
        // src has identity B.
        let incoming = try HostIdentity.loadOrGenerate(at: src)
        XCTAssertNotEqual(original.publicKeyBytes, incoming.publicKeyBytes)

        try HostIdentity.importIdentity(from: src, to: dst)

        // dst now holds identity B; the original is preserved as
        // dst.bak so the user can recover from a mistaken import.
        let nowAtDst = try HostIdentity.load(at: dst)
        XCTAssertEqual(nowAtDst.publicKeyBytes, incoming.publicKeyBytes)
        XCTAssertTrue(FileManager.default.fileExists(atPath: bak.path))
        let backup = try HostIdentity.load(at: bak)
        XCTAssertEqual(backup.publicKeyBytes, original.publicKeyBytes)
    }

    func testImportRejectsCorruptSourceWithoutTouchingDestination() throws {
        let src = makeTempFileURL()
        let dst = makeTempFileURL()
        defer {
            try? FileManager.default.removeItem(at: src)
            try? FileManager.default.removeItem(at: dst)
        }

        // Real identity at dst.
        let original = try HostIdentity.loadOrGenerate(at: dst)
        // Garbage at src.
        try Data("{\"private_key_b64\":\"not-base64-padded\"}".utf8).write(to: src)

        XCTAssertThrowsError(try HostIdentity.importIdentity(from: src, to: dst))

        // dst untouched.
        let stillThere = try HostIdentity.load(at: dst)
        XCTAssertEqual(stillThere.publicKeyBytes, original.publicKeyBytes)
        // No backup created (since import failed before touching dst).
        let bak = dst.appendingPathExtension("bak")
        XCTAssertFalse(FileManager.default.fileExists(atPath: bak.path))
    }

    func testImportIntoFreshDestinationDoesNotCreateBackup() throws {
        let src = makeTempFileURL()
        let dst = makeTempFileURL()
        defer {
            try? FileManager.default.removeItem(at: src)
            try? FileManager.default.removeItem(at: dst)
        }

        let incoming = try HostIdentity.loadOrGenerate(at: src)
        // dst does not exist yet.
        XCTAssertFalse(FileManager.default.fileExists(atPath: dst.path))
        try HostIdentity.importIdentity(from: src, to: dst)
        let placed = try HostIdentity.load(at: dst)
        XCTAssertEqual(placed.publicKeyBytes, incoming.publicKeyBytes)
        // No spurious .bak from an import into empty space.
        let bak = dst.appendingPathExtension("bak")
        XCTAssertFalse(FileManager.default.fileExists(atPath: bak.path))
    }

    private func makeTempFileURL() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("bluetrack-host-identity-tests", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("identity-\(UUID().uuidString).json")
    }
}

// Workaround so the test target does not need to import CryptoKit directly;
// route through the BluetrackHostKit test bundle while keeping the
// CryptoKit dependency private to that module.
import CryptoKit

private typealias CryptoKitPublicKey = Curve25519.Signing.PublicKey
