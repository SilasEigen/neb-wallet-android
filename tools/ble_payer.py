"""Plays the phone that pays, from the Bluetooth radio of this computer: finds a wallet that receives, reads its
address, and writes PAYMENT_HEX to it when one is given (framed as the app does)."""
import asyncio, sys

from bleak import BleakClient, BleakScanner

SERVICE = "dd089e4b-9e69-4667-a776-a8bac347c981"
ADDRESS = "cfdeb768-aec8-404d-a768-14f8493685ae"
PAYMENT = "5b98892c-f59a-4cd7-a42e-ad116e9e5a00"


async def main():
    print("searching...")
    device = await BleakScanner.find_device_by_filter(lambda d, ad: SERVICE in ad.service_uuids, timeout=25)
    if device is None:
        sys.exit("no wallet that receives was found")
    print("found a wallet, rssi unknown, connecting")
    async with BleakClient(device) as client:
        address = (await client.read_gatt_char(ADDRESS)).decode("ascii")
        print("address", address, "mtu", client.mtu_size)
        if len(sys.argv) > 1:
            payment = bytes.fromhex(sys.argv[1])
            data = len(payment).to_bytes(2, "big") + payment
            step = int(sys.argv[2]) if len(sys.argv) > 2 else client.mtu_size - 3
            for i in range(0, len(data), step):
                await client.write_gatt_char(PAYMENT, data[i:i + step], response=True)
            print("delivered", len(payment), "bytes in", -(-len(data) // step), "writes")


asyncio.run(main())
