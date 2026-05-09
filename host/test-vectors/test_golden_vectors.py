"""Cross-platform golden-vector test for Bluetrack BLE feedback protocol.

Loads ``feedback_v1.json`` from the repository test-vectors directory
and verifies that this Python implementation reproduces every value
the generator produced. Runs alongside the Swift and Android tests
of the same fixture so any drift between the three platforms is
caught at PR time.
"""
from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import pathlib
import struct
import sys
import types
import unittest


REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
FIXTURE_PATH = pathlib.Path(__file__).resolve().parent / "feedback_v1.json"
SENDER_PATH = REPO_ROOT / "android" / "tools" / "ble_encrypt_sender.py"


def _load_sender_module() -> types.ModuleType:
    """Import ble_encrypt_sender.py with bleak stubbed.

    The sender pulls in ``bleak`` at module import; it's not relevant
    for the crypto-only tests and not installed on every CI worker.
    Stubbing it here keeps the import side-effect-free.
    """
    if "bleak" not in sys.modules:
        fake = types.ModuleType("bleak")

        class _Stub:
            def __init__(self, *args, **kwargs):  # noqa: D401
                pass

        fake.BleakClient = _Stub
        fake.BleakScanner = _Stub
        sys.modules["bleak"] = fake
    spec = importlib.util.spec_from_file_location("ble_sender", SENDER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load {SENDER_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


from cryptography.hazmat.primitives import hashes  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import (  # noqa: E402
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.asymmetric.x25519 import (  # noqa: E402
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402
from cryptography.hazmat.primitives.kdf.hkdf import HKDF  # noqa: E402
from cryptography.hazmat.primitives.serialization import (  # noqa: E402
    Encoding,
    PublicFormat,
)


def _b64(s: str) -> bytes:
    return base64.b64decode(s)


def _hkdf(shared: bytes, info: bytes, length: int, salt: bytes) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(),
        length=length,
        salt=salt,
        info=info,
    ).derive(shared)


class GoldenVectorsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = json.loads(FIXTURE_PATH.read_text())
        cls.sender = _load_sender_module()

    def test_constants_match(self) -> None:
        c = self.fixture["constants"]
        self.assertEqual(c["public_key_size"], self.sender.PUBLIC_KEY_SIZE)
        self.assertEqual(
            c["identity_public_key_size"], self.sender.IDENTITY_PUBLIC_KEY_SIZE
        )
        self.assertEqual(
            c["identity_signature_size"], self.sender.IDENTITY_SIGNATURE_SIZE
        )
        self.assertEqual(
            c["handshake_write_payload_size"],
            self.sender.HANDSHAKE_WRITE_PAYLOAD_SIZE,
        )
        self.assertEqual(c["nonce_salt_size"], self.sender.NONCE_SALT_SIZE)
        self.assertEqual(c["frame_size"], self.sender.FRAME_SIZE)
        self.assertEqual(
            c["hkdf_salt_utf8"].encode("ascii"), self.sender.HKDF_SALT
        )
        self.assertEqual(
            c["hkdf_info_base_utf8"].encode("ascii"),
            self.sender.HKDF_INFO_BASE,
        )
        self.assertEqual(
            c["pin_prefix_utf8"].encode("ascii"), self.sender.PIN_PREFIX
        )
        self.assertEqual(
            c["nonce_salt_suffix_utf8"].encode("ascii"),
            self.sender.NONCE_SALT_SUFFIX,
        )

    def test_uuids_match(self) -> None:
        self.assertEqual(self.fixture["service_uuid"], self.sender.SERVICE_UUID)
        self.assertEqual(
            self.fixture["feedback_characteristic_uuid"],
            self.sender.FEEDBACK_CHAR_UUID,
        )
        self.assertEqual(
            self.fixture["handshake_characteristic_uuid"],
            self.sender.HANDSHAKE_CHAR_UUID,
        )

    def test_host_keys_round_trip(self) -> None:
        host = self.fixture["host"]
        host_x_seed = _b64(host["ephemeral_x25519_private_seed_b64"])
        expected_x_pub = _b64(host["ephemeral_x25519_public_b64"])
        derived_x_pub = (
            X25519PrivateKey.from_private_bytes(host_x_seed)
            .public_key()
            .public_bytes(Encoding.Raw, PublicFormat.Raw)
        )
        self.assertEqual(expected_x_pub, derived_x_pub)

        host_id_seed = _b64(host["identity_ed25519_private_seed_b64"])
        expected_id_pub = _b64(host["identity_ed25519_public_b64"])
        derived_id_pub = (
            Ed25519PrivateKey.from_private_bytes(host_id_seed)
            .public_key()
            .public_bytes(Encoding.Raw, PublicFormat.Raw)
        )
        self.assertEqual(expected_id_pub, derived_id_pub)
        self.assertEqual(
            host["identity_fingerprint"],
            hashlib.sha256(derived_id_pub).hexdigest()[:16],
        )

    def test_phone_keys_round_trip(self) -> None:
        phone = self.fixture["phone"]
        seed = _b64(phone["ephemeral_x25519_private_seed_b64"])
        expected_pub = _b64(phone["ephemeral_x25519_public_b64"])
        derived_pub = (
            X25519PrivateKey.from_private_bytes(seed)
            .public_key()
            .public_bytes(Encoding.Raw, PublicFormat.Raw)
        )
        self.assertEqual(expected_pub, derived_pub)

    def test_handshake_payload_byte_equal(self) -> None:
        host = self.fixture["host"]
        eph_pub = _b64(host["ephemeral_x25519_public_b64"])
        id_seed = _b64(host["identity_ed25519_private_seed_b64"])
        id_pub = _b64(host["identity_ed25519_public_b64"])
        priv = Ed25519PrivateKey.from_private_bytes(id_seed)
        sig = priv.sign(eph_pub)
        built = eph_pub + id_pub + sig
        self.assertEqual(_b64(self.fixture["handshake_write_payload_b64"]), built)
        # Both directions verify cleanly.
        Ed25519PublicKey.from_public_bytes(id_pub).verify(sig, eph_pub)

    def test_aes_key_and_nonce_salt_byte_equal(self) -> None:
        host = self.fixture["host"]
        phone = self.fixture["phone"]
        host_priv = X25519PrivateKey.from_private_bytes(
            _b64(host["ephemeral_x25519_private_seed_b64"])
        )
        phone_pub = _b64(phone["ephemeral_x25519_public_b64"])
        shared = host_priv.exchange(X25519PublicKey.from_public_bytes(phone_pub))
        self.assertEqual(_b64(self.fixture["shared_secret_b64"]), shared)

        pin_bytes = self.fixture["pin"].encode("ascii")
        info_key = self.sender.HKDF_INFO_BASE + self.sender.PIN_PREFIX + pin_bytes
        info_salt = info_key + self.sender.NONCE_SALT_SUFFIX

        aes_key = _hkdf(shared, info_key, 32, self.sender.HKDF_SALT)
        nonce_salt = _hkdf(
            shared, info_salt, self.sender.NONCE_SALT_SIZE, self.sender.HKDF_SALT
        )
        self.assertEqual(_b64(self.fixture["aes_key_b64"]), aes_key)
        self.assertEqual(_b64(self.fixture["nonce_salt_b64"]), nonce_salt)

        # Symmetric: phone derives the same shared secret + key + salt.
        phone_priv = X25519PrivateKey.from_private_bytes(
            _b64(phone["ephemeral_x25519_private_seed_b64"])
        )
        host_pub = _b64(host["ephemeral_x25519_public_b64"])
        shared_phone = phone_priv.exchange(
            X25519PublicKey.from_public_bytes(host_pub)
        )
        self.assertEqual(shared, shared_phone)

    def test_frames_round_trip_and_byte_equal(self) -> None:
        aes_key = _b64(self.fixture["aes_key_b64"])
        nonce_salt = _b64(self.fixture["nonce_salt_b64"])
        gcm = AESGCM(aes_key)
        for entry in self.fixture["frames"]:
            counter = entry["counter"]
            dx = float(entry["dx"])
            dy = float(entry["dy"])
            expected = _b64(entry["frame_b64"])

            counter_bytes = struct.pack("<I", counter & 0xFFFFFFFF)
            nonce = nonce_salt + counter_bytes
            plain = struct.pack("<ff", dx, dy)
            built = counter_bytes + gcm.encrypt(nonce, plain, None)
            self.assertEqual(expected, built, f"counter={counter}")

            decoded_plain = gcm.decrypt(nonce, expected[4:], None)
            rdx, rdy = struct.unpack("<ff", decoded_plain)
            self.assertAlmostEqual(rdx, dx, places=6)
            self.assertAlmostEqual(rdy, dy, places=6)

    def test_sender_feedbacksession_matches_fixture(self) -> None:
        """End-to-end: the production sender's FeedbackSession derives
        the same AES key and produces the same encrypted frame bytes
        as the fixture, given the fixture's seeds and pin."""
        host_seed = _b64(self.fixture["host"]["ephemeral_x25519_private_seed_b64"])
        phone_pub = _b64(self.fixture["phone"]["ephemeral_x25519_public_b64"])
        pin = self.fixture["pin"]

        # Replace the sender FeedbackSession's private with the fixture
        # seed so we exercise the production HKDF + AES-GCM path.
        session = self.sender.FeedbackSession()
        session._private = X25519PrivateKey.from_private_bytes(host_seed)
        session.derive_session(phone_pub, pin)
        self.assertEqual(_b64(self.fixture["aes_key_b64"]), session._key)
        self.assertEqual(_b64(self.fixture["nonce_salt_b64"]), session._nonce_salt)

        for entry in self.fixture["frames"]:
            counter = entry["counter"]
            built = session.build_packet(counter, float(entry["dx"]), float(entry["dy"]))
            self.assertEqual(_b64(entry["frame_b64"]), built, f"counter={counter}")


if __name__ == "__main__":
    unittest.main()
