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
import struct
from typing import Optional

from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PublicFormat,
)

SERVICE_UUID = "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263"
FEEDBACK_CHAR_UUID = "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6"
HANDSHAKE_CHAR_UUID = "4846ff88-f2d4-4df2-9500-9bf8ed23f9e6"

PUBLIC_KEY_SIZE = 32
FRAME_SIZE = 28
NONCE_SALT_SIZE = 8
PIN_MIN_LENGTH = 4
PIN_MAX_LENGTH = 12
HKDF_SALT = b"bluetrack-feedback-v1"
HKDF_INFO_BASE = b"aes-256-gcm key+nonce-salt"
PIN_PREFIX = b"|pin:"
NONCE_SALT_SUFFIX = b"|nonce-salt"


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
) -> None:
    """Write our 32-byte X25519 pubkey, read peer pubkey, derive session."""
    await client.write_gatt_char(HANDSHAKE_CHAR_UUID, session.public_key, response=True)
    peer = await client.read_gatt_char(HANDSHAKE_CHAR_UUID)
    session.derive_session(bytes(peer), pin)


async def send_loop(
    address: Optional[str],
    dx: float,
    dy: float,
    interval: float,
    scan_timeout: float,
    pin: str,
) -> None:
    counter = 0
    target = await find_target(address, scan_timeout)
    async with BleakClient(target) as client:
        session = FeedbackSession()
        await perform_handshake(client, session, pin)
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
    args = parser.parse_args()
    if normalized_pin_bytes(args.pin) is None:
        parser.error(
            f"--pin must be {PIN_MIN_LENGTH}..{PIN_MAX_LENGTH} ASCII digits"
        )
    return args


if __name__ == "__main__":
    args = parse_args()
    asyncio.run(
        send_loop(
            args.address,
            args.dx,
            args.dy,
            args.interval,
            args.scan_timeout,
            args.pin,
        )
    )
