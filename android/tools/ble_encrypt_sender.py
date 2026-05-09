#!/usr/bin/env python3
"""Encrypted BLE feedback sender for Bluetrack.

Per-session ECDH (X25519) + HKDF-SHA256 + AES-256-GCM. The 28-byte frame
layout matches the Swift `FeedbackSession` and the Android `FeedbackSession`:

    [0..3]    counter (uint32, little-endian)
    [4..11]   AES-256-GCM ciphertext of (Float32 dx, Float32 dy) little-endian
    [12..27]  AES-256-GCM 16-byte authentication tag

Handshake on connect:
    1. Generate ephemeral X25519 keypair.
    2. Write 32-byte public key to the handshake characteristic.
    3. Read the peer's 32-byte public key from the handshake characteristic.
    4. Derive shared secret via X25519 and HKDF-SHA256 (32-byte AES key +
       8-byte nonce salt).

The host MUST keep counters unique within a session. Wrap-around is
handled by treating the counter as `& 0xFFFFFFFF`; the peripheral rejects
duplicate (key, counter) pairs via tag failure since GCM nonces collide.
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
import pathlib
import stat
import struct
import sys
from typing import Optional

from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives import hashes, serialization
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

SERVICE_UUID = "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263"
FEEDBACK_CHAR_UUID = "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6"
HANDSHAKE_CHAR_UUID = "4846ff88-f2d4-4df2-9500-9bf8ed23f9e6"

PUBLIC_KEY_SIZE = 32
FRAME_SIZE = 28
NONCE_SALT_SIZE = 8
PIN_MIN_LENGTH = 4
PIN_MAX_LENGTH = 12
IDENTITY_PUBLIC_KEY_SIZE = 32
IDENTITY_SIGNATURE_SIZE = 64
HANDSHAKE_WRITE_PAYLOAD_SIZE = (
    PUBLIC_KEY_SIZE + IDENTITY_PUBLIC_KEY_SIZE + IDENTITY_SIGNATURE_SIZE
)
HKDF_SALT = b"bluetrack-feedback-v1"
HKDF_INFO_BASE = b"aes-256-gcm key+nonce-salt"
PIN_PREFIX = b"|pin:"
NONCE_SALT_SUFFIX = b"|nonce-salt"

DEFAULT_IDENTITY_PATH = (
    pathlib.Path.home()
    / ".config"
    / "bluetrack-hid-inspector-py"
    / "host_identity_v1.json"
)


class HostIdentity:
    """Long-term Ed25519 identity persisted on disk under
    ``~/.config/bluetrack-hid-inspector-py/host_identity_v1.json``. The
    peripheral pins this identity on first use and refuses subsequent
    handshakes that present a different identity public key, so users
    can roll the host (CLI ``--reset-host-identity``) or roll the phone
    (Forget host) deliberately.
    """

    def __init__(self, signing_key: Ed25519PrivateKey) -> None:
        self._signing_key = signing_key

    @property
    def public_key_bytes(self) -> bytes:
        return self._signing_key.public_key().public_bytes(
            Encoding.Raw, PublicFormat.Raw
        )

    def sign(self, data: bytes) -> bytes:
        return self._signing_key.sign(data)

    def fingerprint(self) -> str:
        return host_identity_fingerprint(self.public_key_bytes)

    @classmethod
    def generate(cls) -> "HostIdentity":
        return cls(Ed25519PrivateKey.generate())

    @classmethod
    def load_or_generate(cls, path: pathlib.Path) -> "HostIdentity":
        if path.exists():
            return cls.load(path)
        identity = cls.generate()
        identity.save(path)
        return identity

    @classmethod
    def load(cls, path: pathlib.Path) -> "HostIdentity":
        with open(path, "r", encoding="utf-8") as fh:
            stored = json.load(fh)
        b64 = stored.get("private_key_b64")
        if not isinstance(b64, str):
            raise ValueError(f"identity file {path} missing private_key_b64")
        raw = base64.b64decode(b64.encode("ascii"))
        if len(raw) != 32:
            raise ValueError(
                f"identity file {path} has {len(raw)}-byte seed (need 32)"
            )
        return cls(Ed25519PrivateKey.from_private_bytes(raw))

    def save(self, path: pathlib.Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(path.parent, 0o700)
        except FileNotFoundError:
            pass
        raw = self._signing_key.private_bytes(
            Encoding.Raw, PrivateFormat.Raw, NoEncryption()
        )
        payload = json.dumps(
            {"private_key_b64": base64.b64encode(raw).decode("ascii")},
            sort_keys=True,
            indent=2,
        )
        # Atomic-ish write: temp file + rename.
        tmp = path.with_suffix(path.suffix + ".tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write(payload)
        os.replace(tmp, path)
        try:
            os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
        except OSError:
            pass


def host_identity_fingerprint(identity_pubkey: bytes) -> str:
    """First 16 hex chars of SHA-256(identity_pubkey). Matches the Swift
    and Android implementations."""
    digest = hashlib.sha256(identity_pubkey).hexdigest()
    return digest[:16]


def reset_host_identity(path: pathlib.Path) -> None:
    """Delete the identity file at ``path``; no-op if absent."""
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def build_handshake_payload(
    ephemeral_public_key: bytes, identity: HostIdentity
) -> bytes:
    """128-byte handshake payload: eph || id_pub || sig(eph)."""
    if len(ephemeral_public_key) != PUBLIC_KEY_SIZE:
        raise ValueError(
            f"ephemeral pubkey must be {PUBLIC_KEY_SIZE} bytes"
        )
    sig = identity.sign(ephemeral_public_key)
    return ephemeral_public_key + identity.public_key_bytes + sig


def normalized_pin_bytes(pin: str) -> Optional[bytes]:
    """Trim whitespace, validate digits-only and length, return UTF-8 bytes."""
    trimmed = pin.strip()
    if not (PIN_MIN_LENGTH <= len(trimmed) <= PIN_MAX_LENGTH):
        return None
    if not trimmed.isascii() or not trimmed.isdigit():
        return None
    return trimmed.encode("ascii")


class FeedbackSession:
    """Mirror of the Kotlin/Swift FeedbackSession.

    Construction generates a fresh ephemeral X25519 keypair. Call
    ``derive_session(peer_pubkey)`` after exchanging public keys with the
    peer; afterwards ``build_packet`` and ``decode_packet`` are usable.
    """

    def __init__(self) -> None:
        self._private = X25519PrivateKey.generate()
        self._key: Optional[bytes] = None
        self._nonce_salt: Optional[bytes] = None

    @property
    def public_key(self) -> bytes:
        return self._private.public_key().public_bytes(
            Encoding.Raw, PublicFormat.Raw
        )

    @property
    def is_ready(self) -> bool:
        return self._key is not None and self._nonce_salt is not None

    def derive_session(self, peer_public_key: bytes, pin: str) -> None:
        if len(peer_public_key) != PUBLIC_KEY_SIZE:
            raise ValueError(
                f"peer public key must be {PUBLIC_KEY_SIZE} bytes, "
                f"got {len(peer_public_key)}"
            )
        pin_bytes = normalized_pin_bytes(pin)
        if pin_bytes is None:
            raise ValueError(
                f"pin must be {PIN_MIN_LENGTH}..{PIN_MAX_LENGTH} ASCII digits"
            )
        peer = X25519PublicKey.from_public_bytes(peer_public_key)
        shared = self._private.exchange(peer)
        info_key = HKDF_INFO_BASE + PIN_PREFIX + pin_bytes
        info_salt = info_key + NONCE_SALT_SUFFIX
        self._key = HKDF(
            algorithm=hashes.SHA256(),
            length=32,
            salt=HKDF_SALT,
            info=info_key,
        ).derive(shared)
        self._nonce_salt = HKDF(
            algorithm=hashes.SHA256(),
            length=NONCE_SALT_SIZE,
            salt=HKDF_SALT,
            info=info_salt,
        ).derive(shared)

    def build_packet(self, counter: int, dx: float, dy: float) -> bytes:
        if self._key is None or self._nonce_salt is None:
            raise RuntimeError("session not ready: call derive_session first")
        counter_bytes = struct.pack("<I", counter & 0xFFFFFFFF)
        nonce = self._nonce_salt + counter_bytes
        plain = struct.pack("<ff", dx, dy)
        ciphertext_with_tag = AESGCM(self._key).encrypt(nonce, plain, None)
        return counter_bytes + ciphertext_with_tag


async def find_target(address: Optional[str], timeout: float):
    if address:
        return address

    service_uuid = SERVICE_UUID.lower()
    device = await BleakScanner.find_device_by_filter(
        lambda _, adv: service_uuid in [uuid.lower() for uuid in adv.service_uuids],
        timeout=timeout,
    )
    if device is None:
        raise RuntimeError(
            f"No Bluetrack feedback advertiser found for service {SERVICE_UUID}"
        )
    return device


async def perform_handshake(
    client: BleakClient,
    session: FeedbackSession,
    pin: str,
    identity: HostIdentity,
) -> None:
    """Write 128-byte handshake (eph || id_pub || sig), read peer pubkey,
    derive session. The peripheral verifies the Ed25519 signature and
    TOFU-pins the identity pubkey before responding GATT_SUCCESS."""
    payload = build_handshake_payload(session.public_key, identity)
    if len(payload) != HANDSHAKE_WRITE_PAYLOAD_SIZE:
        raise RuntimeError(
            f"handshake payload is {len(payload)} bytes (expected {HANDSHAKE_WRITE_PAYLOAD_SIZE})"
        )
    await client.write_gatt_char(HANDSHAKE_CHAR_UUID, payload, response=True)
    peer = await client.read_gatt_char(HANDSHAKE_CHAR_UUID)
    session.derive_session(bytes(peer), pin)


async def send_loop(
    address: Optional[str],
    dx: float,
    dy: float,
    interval: float,
    scan_timeout: float,
    pin: str,
    identity: HostIdentity,
) -> None:
    counter = 0
    target = await find_target(address, scan_timeout)
    async with BleakClient(target) as client:
        session = FeedbackSession()
        await perform_handshake(client, session, pin, identity)
        while True:
            packet = session.build_packet(counter, dx, dy)
            await client.write_gatt_char(FEEDBACK_CHAR_UUID, packet, response=False)
            counter = (counter + 1) & 0xFFFFFFFF
            await asyncio.sleep(interval)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Send encrypted Bluetrack BLE feedback frames "
        "(X25519 ECDH handshake + AES-256-GCM)."
    )
    parser.add_argument(
        "--address",
        help="Optional BLE address. If omitted, scan by Bluetrack service UUID.",
    )
    parser.add_argument("--dx", type=float, default=1.25, help="Correction X value.")
    parser.add_argument("--dy", type=float, default=-0.75, help="Correction Y value.")
    parser.add_argument("--interval", type=float, default=0.005, help="Seconds between frames.")
    parser.add_argument(
        "--scan-timeout", type=float, default=10.0, help="Seconds to scan when address is omitted."
    )
    parser.add_argument(
        "--pin",
        required=True,
        help=(
            "Pairing pin shown on the Bluetrack status row "
            f"({PIN_MIN_LENGTH}..{PIN_MAX_LENGTH} ASCII digits). The peripheral "
            "mixes it into AES-256-GCM key derivation; without the correct pin, "
            "frames will not authenticate."
        ),
    )
    parser.add_argument(
        "--host-identity-path",
        default=str(DEFAULT_IDENTITY_PATH),
        help=(
            "Override location for the long-term Ed25519 host identity file. "
            f"Default: {DEFAULT_IDENTITY_PATH}"
        ),
    )
    parser.add_argument(
        "--reset-host-identity",
        action="store_true",
        help=(
            "Delete the host identity file before this run, generating a "
            "new one. Use after you intentionally tap \"Forget host\" on "
            "the phone, or after the phone pinned a stale identity."
        ),
    )
    args = parser.parse_args()
    if normalized_pin_bytes(args.pin) is None:
        parser.error(
            f"--pin must be {PIN_MIN_LENGTH}..{PIN_MAX_LENGTH} ASCII digits"
        )
    return args


def load_host_identity_or_exit(path: pathlib.Path, reset: bool) -> HostIdentity:
    if reset:
        try:
            reset_host_identity(path)
            print(
                f"Reset host identity at {path} (next session will TOFU-pin on the phone again).",
                file=sys.stderr,
            )
        except OSError as exc:
            print(f"Could not reset host identity at {path}: {exc}", file=sys.stderr)
            sys.exit(72)
    existed_before = path.exists()
    try:
        identity = HostIdentity.load_or_generate(path)
    except (OSError, ValueError) as exc:
        print(f"Could not load host identity at {path}: {exc}", file=sys.stderr)
        sys.exit(73)
    action = "loaded" if existed_before else "generated"
    print(
        f"Host identity {identity.fingerprint()} (Ed25519, {action} at {path}).",
        file=sys.stderr,
    )
    if not existed_before:
        print(
            "This is a new identity. The phone will TOFU-pin it on the next handshake.",
            file=sys.stderr,
        )
    return identity


if __name__ == "__main__":
    args = parse_args()
    identity = load_host_identity_or_exit(
        pathlib.Path(args.host_identity_path),
        args.reset_host_identity,
    )
    asyncio.run(
        send_loop(
            args.address,
            args.dx,
            args.dy,
            args.interval,
            args.scan_timeout,
            args.pin,
            identity,
        )
    )
