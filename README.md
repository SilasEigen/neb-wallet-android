# NEB Android wallet

An Android wallet for [NEB](https://github.com/SilasEigen/neb). The keys, coin selection and signing belong to the NEB node's `neb-wallet` program, built for Android and packaged in the app. The app adds the screens, a store for what the phone knows about the chain, and a Bluetooth link between two phones.

Status: early. It has run on phones with Android 10 and Android 15, and one phone has paid the other over Bluetooth. The package is signed with a debug key, so it is a test build.

## What it does

- Creates a key or imports one, and stores it encrypted with a password, in `neb-wallet`'s key file format.
- Shows the balance: spendable coins and coins still maturing.
- Receive: the address as text and as a QR code.
- Send: an address and an amount, a review window with the fee, then the payment.
- Pays another phone over Bluetooth, with or without internet.
- Locks itself after 5 minutes in the background.

## Paying another phone

The phone that receives opens Receive and taps "Receive by Bluetooth". The phone that pays opens Send and taps "Find the other phone". It gets the other phone's address; compare it with the other screen. Enter an amount, tap Review, then "By Bluetooth".

The paying phone signs the payment and hands it over the Bluetooth link. Both phones keep the signed payment and give it to the network once they have internet. The first one to get online puts it on the chain. Until then it shows under Pending on the home screen.

With no internet, the paying phone signs with the coins it saw the last time it was online. It sets aside the coins of a payment that is waiting for a block, so it does not pick them twice.

A payment you receive offline is a signed promise until it is in a block: the payer could have spent the same coins somewhere else. The app says so each time.

The link is Bluetooth Low Energy with no pairing. It carries an address and a signed transaction, both public. Nothing authenticates the link, so check the address on both screens before you pay.

## The node

The wallet reads the chain from a NEB node over JSON-RPC. By default it asks the project's public node over HTTPS. That node only reports balances and relays signed payments: the key never leaves the phone. Its operator can see which IP address asks about which NEB address.

To use your own node, enter `http://HOST:PORT` in Settings.

## The key

The key file is in the app's private storage, encrypted with your password, and excluded from cloud backups. Removing the app removes the key. Write the key down first: Settings, then "Show secret key".

## Building

You need Rust with the `aarch64-linux-android` and `x86_64-linux-android` targets, the Android NDK, the Android SDK build tools with a platform, a JDK and git. Set `ANDROID_HOME`. There is no Gradle.

```
./build.sh
app/build-apk.sh
```

`build.sh` fetches the NEB sources at the tag named in the script and applies `patches/neb-wallet-commands.patch`, which adds four commands to `neb-wallet`: `sign`, `submit`, `received` and `export`. It builds the program for phones and for the emulator, with source paths mapped to fixed prefixes, so the binaries do not carry the paths of the machine that built them. Set `NEB_REPO` to build from a local clone.

`app/build-apk.sh` compiles the Java sources and writes `neb-wallet.apk`, signed with the Android SDK's debug key.

The app runs on Android 10 or later.

## Test tools

`tools/` holds what the app was tried with:

- `ui.py` reads and taps the screen of a phone over adb.
- `ble_payer.py` plays the paying phone from a computer's Bluetooth radio.
- `fake_node.py` is a pretend node that gives a test wallet a coin that does not exist.
- `fake_payment.rs` prints a signed payment that spends a coin that does not exist.

A payment made with the last two has the right form, and a real node refuses it. That is enough to try the path between two phones without spending anything.

## License

Apache-2.0. See [LICENSE](LICENSE).
