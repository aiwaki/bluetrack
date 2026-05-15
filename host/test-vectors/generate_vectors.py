#!/usr/bin/env python3
"""Generate cross-platform Bluetrack feedback-protocol golden vectors.

Deterministic — fixed seeds for X25519 / Ed25519 keys, fixed pin,
fixed counters and floats. The output JSON is committed to the repo
and consumed by Swift / Android / Python test suites to verify they
all agree on byte layout, key derivation, signature, and AES-GCM
encryption / decryption.

Run from the repo root:
    python3 host/test-vectors/generate_vectors.py

The script writes:
    host/test-vectors/feedback_v1.json

Re-run only when the protocol changes (new field on the handshake,
new HKDF info string, new frame layout, etc.). Diff the JSON in the
PR so reviewers see exactly what bytes shift.
"""

from __future__ import annotations

import base64
import json
import pathlib
import struct
from typing import List

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
)
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PrivateFormat,
    PublicFormat,
    NoEncryption,
)

# Mirror constants from FeedbackCrypto across all three platforms.
PROTOCOL_VERSION = "v1-pin-tofu-replay"
PUBLIC_KEY_SIZE = 32
IDENTITY_PUBLIC_KEY_SIZE = 32
IDENTITY_SIGNATURE_SIZE = 64
HANDSHAKE_WIRE_SIZE = (
    PUBLIC_KEY_SIZE + IDENTITY_PUBLIC_KEY_SIZE + IDENTITY_SIGNATURE_SIZE
)
NONCE_SALT_SIZE = 8
FRAME_SIZE = 28
HKDF_SALT = b"bluetrack-feedback-v1"
HKDF_INFO_BASE = b"aes-256-gcm key+nonce-salt"
PIN_PREFIX = b"|pin:"
NONCE_SALT_SUFFIX = b"|nonce-salt"

PIN = "246810"

# Fixed 32-byte seeds for deterministic keys. Picked once and frozen.
HOST_X25519_SEED = bytes(range(32))
PHONE_X25519_SEED = bytes(range(32, 64))
HOST_ED25519_SEED = bytes(range(64, 96))


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def _x25519_public(seed: bytes) -> tuple[X25519PrivateKey, bytes]:
    priv = X25519PrivateKey.from_private_bytes(seed)
    pub = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return priv, pub


def _ed25519_keypair(seed: bytes) -> tuple[Ed25519PrivateKey, bytes]:
    priv = Ed25519PrivateKey.from_private_bytes(seed)
    pub = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return priv, pub


def _hkdf(shared: bytes, info: bytes, length: int) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(),
        length=length,
        salt=HKDF_SALT,
        info=info,
    ).derive(shared)


def _build_handshake(
    eph_pub: bytes, id_priv: Ed25519PrivateKey, id_pub: bytes
) -> bytes:
    sig = id_priv.sign(eph_pub)
    assert len(sig) == IDENTITY_SIGNATURE_SIZE
    payload = eph_pub + id_pub + sig
    assert len(payload) == HANDSHAKE_WIRE_SIZE
    return payload


def _derive_session(
    host_priv: X25519PrivateKey, phone_pub: bytes, pin: str
) -> tuple[bytes, bytes, bytes]:
    pin_bytes = pin.encode("ascii")
    info_key = HKDF_INFO_BASE + PIN_PREFIX + pin_bytes
    info_salt = info_key + NONCE_SALT_SUFFIX
    shared = host_priv.exchange(X25519PublicKey.from_public_bytes(phone_pub))
    aes_key = _hkdf(shared, info_key, 32)
    nonce_salt = _hkdf(shared, info_salt, NONCE_SALT_SIZE)
    return shared, aes_key, nonce_salt


def _frame(counter: int, dx: float, dy: float, key: bytes, nonce_salt: bytes) -> bytes:
    counter_bytes = struct.pack("<I", counter & 0xFFFFFFFF)
    nonce = nonce_salt + counter_bytes
    plain = struct.pack("<ff", dx, dy)
    ciphertext_with_tag = AESGCM(key).encrypt(nonce, plain, None)
    return counter_bytes + ciphertext_with_tag


def main() -> None:
    host_x_priv, host_x_pub = _x25519_public(HOST_X25519_SEED)
    phone_x_priv, phone_x_pub = _x25519_public(PHONE_X25519_SEED)
    host_id_priv, host_id_pub = _ed25519_keypair(HOST_ED25519_SEED)

    handshake = _build_handshake(host_x_pub, host_id_priv, host_id_pub)
    shared, aes_key, nonce_salt = _derive_session(host_x_priv, phone_x_pub, PIN)

    # Symmetric: phone derives the SAME aes_key + nonce_salt from its
    # own private + the host's public. Verify that here so the fixture
    # is internally consistent before we hand it to the platforms.
    shared_check, aes_key_check, nonce_salt_check = _derive_session(
        phone_x_priv, host_x_pub, PIN
    )
    assert shared_check == shared, "X25519 ECDH mismatch"
    assert aes_key_check == aes_key, "AES key derivation mismatch"
    assert nonce_salt_check == nonce_salt, "Nonce salt derivation mismatch"

    # Frames at varied counters and floats. Includes counter 0 to lock
    # in nonce layout and counter 0xFFFFFFFF to lock in wrap behaviour.
    cases: List[tuple[int, float, float]] = [
        (0, 1.25, -0.75),
        (1, 0.0, 0.0),
        (7, -12.5, 99.125),
        (42, 127.0, -127.0),
        (0xFFFFFFFE, 3.5, -3.5),
        (0xFFFFFFFF, 0.0, 0.0),
    ]
    frames = []
    for counter, dx, dy in cases:
        encrypted = _frame(counter, dx, dy, aes_key, nonce_salt)
        assert len(encrypted) == FRAME_SIZE
        frames.append(
            {
                "counter": counter,
                "dx": dx,
                "dy": dy,
                "frame_b64": _b64(encrypted),
            }
        )

    # Identity fingerprint = first 16 hex chars of SHA-256(id_pub).
    import hashlib

    fingerprint = hashlib.sha256(host_id_pub).hexdigest()[:16]

    fixture = {
        "protocol_version": PROTOCOL_VERSION,
        "service_uuid": "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263",
        "feedback_characteristic_uuid": "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6",
        "handshake_characteristic_uuid": "4846ff88-f2d4-4df2-9500-9bf8ed23f9e6",
        "constants": {
            "public_key_size": PUBLIC_KEY_SIZE,
            "identity_public_key_size": IDENTITY_PUBLIC_KEY_SIZE,
            "identity_signature_size": IDENTITY_SIGNATURE_SIZE,
            "handshake_write_payload_size": HANDSHAKE_WIRE_SIZE,
            "nonce_salt_size": NONCE_SALT_SIZE,
            "frame_size": FRAME_SIZE,
            "hkdf_salt_utf8": HKDF_SALT.decode("ascii"),
            "hkdf_info_base_utf8": HKDF_INFO_BASE.decode("ascii"),
            "pin_prefix_utf8": PIN_PREFIX.decode("ascii"),
            "nonce_salt_suffix_utf8": NONCE_SALT_SUFFIX.decode("ascii"),
        },
        "host": {
            "ephemeral_x25519_private_seed_b64": _b64(HOST_X25519_SEED),
            "ephemeral_x25519_public_b64": _b64(host_x_pub),
            "identity_ed25519_private_seed_b64": _b64(HOST_ED25519_SEED),
            "identity_ed25519_public_b64": _b64(host_id_pub),
            "identity_fingerprint": fingerprint,
        },
        "phone": {
            "ephemeral_x25519_private_seed_b64": _b64(PHONE_X25519_SEED),
            "ephemeral_x25519_public_b64": _b64(phone_x_pub),
        },
        "pin": PIN,
        "handshake_write_payload_b64": _b64(handshake),
        "shared_secret_b64": _b64(shared),
        "aes_key_b64": _b64(aes_key),
        "nonce_salt_b64": _b64(nonce_salt),
        "frames": frames,
    }

    out_path = pathlib.Path(__file__).resolve().parent / "feedback_v1.json"
    encoded = json.dumps(fixture, indent=2, sort_keys=True) + "\n"
    out_path.write_text(encoded, encoding="utf-8")
    print(f"wrote {out_path} ({len(encoded)} bytes, {len(frames)} frames)")


if __name__ == "__main__":
    main()
