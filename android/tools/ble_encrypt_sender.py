#!/usr/bin/env python3
import asyncio
import struct
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from bleak import BleakClient

DEVICE_ADDRESS = "00:11:22:33:44:55"
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


async def send_loop() -> None:
    counter = 0
    async with BleakClient(DEVICE_ADDRESS) as client:
        while True:
            dx = 1.25
            dy = -0.75
            packet = build_packet(counter, dx, dy)
            await client.write_gatt_char(CHAR_UUID, packet, response=False)
            counter = (counter + 1) & 0xFFFFFFFF
            await asyncio.sleep(0.005)


if __name__ == "__main__":
    asyncio.run(send_loop())
