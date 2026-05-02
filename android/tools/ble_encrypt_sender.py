#!/usr/bin/env python3
import argparse
import asyncio
import struct
from typing import Optional
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263"
CHAR_UUID = "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6"
KEY = b"BluetrackKey1234"  # 16 bytes
SALT12 = b"BluetrackSal"   # 12 bytes static IV prefix


def build_packet(counter: int, dx: float, dy: float) -> bytes:
    counter_le = struct.pack("<I", counter)
    plain = struct.pack("<ff", dx, dy)
    iv = SALT12 + counter_le
    encryptor = Cipher(algorithms.AES(KEY), modes.CTR(iv)).encryptor()
    encrypted = encryptor.update(plain) + encryptor.finalize()
    return counter_le + encrypted


async def find_target(address: Optional[str], timeout: float):
    if address:
        return address

    service_uuid = SERVICE_UUID.lower()
    device = await BleakScanner.find_device_by_filter(
        lambda _, adv: service_uuid in [uuid.lower() for uuid in adv.service_uuids],
        timeout=timeout,
    )
    if device is None:
        raise RuntimeError(f"No Bluetrack feedback advertiser found for service {SERVICE_UUID}")
    return device


async def send_loop(address: Optional[str], dx: float, dy: float, interval: float, scan_timeout: float) -> None:
    counter = 0
    target = await find_target(address, scan_timeout)
    async with BleakClient(target) as client:
        while True:
            packet = build_packet(counter, dx, dy)
            await client.write_gatt_char(CHAR_UUID, packet, response=False)
            counter = (counter + 1) & 0xFFFFFFFF
            await asyncio.sleep(interval)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Send encrypted Bluetrack BLE feedback frames.")
    parser.add_argument("--address", help="Optional BLE address. If omitted, scan by Bluetrack service UUID.")
    parser.add_argument("--dx", type=float, default=1.25, help="Correction X value.")
    parser.add_argument("--dy", type=float, default=-0.75, help="Correction Y value.")
    parser.add_argument("--interval", type=float, default=0.005, help="Seconds between frames.")
    parser.add_argument("--scan-timeout", type=float, default=10.0, help="Seconds to scan when address is omitted.")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    asyncio.run(send_loop(args.address, args.dx, args.dy, args.interval, args.scan_timeout))
