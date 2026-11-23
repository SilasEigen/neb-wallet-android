package org.neuralenginebyte.wallet;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A payment between two phones over Bluetooth Low Energy, with no pairing.
 *
 *  The phone that receives advertises one service. Its ADDRESS value is read by the phone that pays; the signed
 *  transaction is then written to its PAYMENT value: two bytes of length, then the bytes, in as many writes as the
 *  link needs. Nothing on the link is secret: an address and a signed transaction are both public. The link is not
 *  authenticated either, so the payer compares the address with the screen of the receiver, and the receiver reads
 *  what the transaction pays before it believes anything.
 *
 *  Every method is called on the main thread, and every result comes back on it. The caller holds the Bluetooth
 *  permissions. */
@SuppressWarnings({"MissingPermission", "deprecation"})
final class BluetoothLink {
    static final UUID SERVICE = UUID.fromString("dd089e4b-9e69-4667-a776-a8bac347c981");
    static final UUID ADDRESS = UUID.fromString("cfdeb768-aec8-404d-a768-14f8493685ae");
    static final UUID PAYMENT = UUID.fromString("5b98892c-f59a-4cd7-a42e-ad116e9e5a00");
    /** A payment larger than this is refused. A payment with a few inputs is a few hundred bytes. */
    static final int MAX_PAYMENT = 15000;
    private static final long TIMEOUT_MS = 25000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;
    private final BluetoothManager manager;
    private BluetoothGattServer server;
    private AdvertiseCallback advertising;
    private ScanCallback scanning;
    private BluetoothGatt gatt;
    private Runnable timeout;

    BluetoothLink(Context context) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(BluetoothManager.class);
    }

    /** Why Bluetooth cannot be used now, or null. */
    String unavailable() {
        BluetoothAdapter a = manager == null ? null : manager.getAdapter();
        if (a == null) return "This phone has no Bluetooth.";
        if (!a.isEnabled()) return "Turn Bluetooth on, then try again.";
        return null;
    }

    // The phone that receives.

    /** Shows this phone to the phones in range and gives them `address`. `received` gets each payment that was
     *  written in full; `failed` gets the reason when the phone cannot advertise. */
    void receive(String address, Consumer<byte[]> received, Consumer<String> failed) {
        stop();
        byte[] value = address.getBytes(StandardCharsets.US_ASCII);
        server = manager.openGattServer(context, new BluetoothGattServerCallback() {
            private BluetoothDevice from;
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int id, int offset,
                    BluetoothGattCharacteristic c) {
                boolean ok = ADDRESS.equals(c.getUuid()) && offset <= value.length;
                respond(device, id, ok, offset, ok ? Arrays.copyOfRange(value, offset, value.length) : null);
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int id, BluetoothGattCharacteristic c,
                    boolean prepared, boolean responseNeeded, int offset, byte[] chunk) {
                byte[] whole = null;
                boolean ok = PAYMENT.equals(c.getUuid()) && !prepared && offset == 0 && chunk != null;
                if (ok) {
                    // one payment at a time: a write from another phone starts over
                    if (!device.equals(from)) buffer.reset();
                    from = device;
                    buffer.write(chunk, 0, chunk.length);
                    byte[] all = buffer.toByteArray();
                    int length = all.length < 2 ? -1 : (all[0] & 0xff) << 8 | all[1] & 0xff;
                    ok = all.length < 2 || length > 0 && length <= MAX_PAYMENT && all.length <= length + 2;
                    boolean complete = ok && all.length >= 2 && all.length == length + 2;
                    if (!ok || complete) buffer.reset();
                    if (complete) whole = Arrays.copyOfRange(all, 2, all.length);
                }
                if (responseNeeded) respond(device, id, ok, 0, null);
                if (whole != null) {
                    byte[] payment = whole;
                    MAIN.post(() -> received.accept(payment));
                }
            }

            private void respond(BluetoothDevice device, int id, boolean ok, int offset, byte[] data) {
                BluetoothGattServer s = server;
                if (s != null) {
                    s.sendResponse(device, id, ok ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE, offset, data);
                }
            }
        });
        BluetoothLeAdvertiser advertiser = manager.getAdapter().getBluetoothLeAdvertiser();
        if (server == null || advertiser == null) {
            stop();
            failed.accept("This phone cannot be found by Bluetooth.");
            return;
        }
        BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(ADDRESS, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ));
        service.addCharacteristic(new BluetoothGattCharacteristic(PAYMENT, BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE));
        server.addService(service);
        advertising = new AdvertiseCallback() {
            @Override
            public void onStartFailure(int code) {
                MAIN.post(() -> {
                    stop();
                    failed.accept("Bluetooth did not start (code " + code + ").");
                });
            }
        };
        advertiser.startAdvertising(new AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build(),
                new AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(new ParcelUuid(SERVICE)).build(),
                advertising);
    }

    // The phone that pays.

    /** Looks for a phone that receives and reads its address. */
    void find(BiConsumer<BluetoothDevice, String> found, Consumer<String> failed) {
        stop();
        BluetoothLeScanner scanner = manager.getAdapter().getBluetoothLeScanner();
        if (scanner == null) {
            failed.accept("Bluetooth is off.");
            return;
        }
        arm(failed, "No phone that receives was found in range."
                + (Build.VERSION.SDK_INT < 31 ? " On this version of Android, Location must be on for a search." : ""));
        scanning = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                MAIN.post(() -> {
                    if (scanning != this) return;
                    scanner.stopScan(this);
                    scanning = null;
                    connect(result.getDevice(), null, address -> found.accept(result.getDevice(), address), failed);
                });
            }

            @Override
            public void onScanFailed(int code) {
                MAIN.post(() -> finish(() -> failed.accept("Bluetooth did not search (code " + code + ").")));
            }
        };
        scanner.startScan(
                Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanning);
    }

    /** Writes a signed payment to the phone found by `find`. */
    void deliver(BluetoothDevice device, byte[] payment, Runnable delivered, Consumer<String> failed) {
        stop();
        arm(failed, "The other phone did not answer.");
        connect(device, payment, x -> delivered.run(), failed);
    }

    /** One connection: with `payment` it writes it, without it reads the address. */
    private void connect(BluetoothDevice device, byte[] payment, Consumer<String> done, Consumer<String> failed) {
        byte[] framed = null;
        if (payment != null) {
            framed = new byte[payment.length + 2];
            framed[0] = (byte) (payment.length >> 8);
            framed[1] = (byte) payment.length;
            System.arraycopy(payment, 0, framed, 2, payment.length);
        }
        byte[] data = framed;
        gatt = device.connectGatt(context, false, new BluetoothGattCallback() {
            private int mtu = 23, sent;

            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(247)) g.discoverServices();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    end(g, () -> failed.accept("The Bluetooth link was lost (code " + status + ")."));
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt g, int size, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = size;
                g.discoverServices();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                BluetoothGattService s = g.getService(SERVICE);
                if (s == null) end(g, () -> failed.accept("The other phone is not a NEB wallet that receives."));
                else if (data == null) g.readCharacteristic(s.getCharacteristic(ADDRESS));
                else write(g);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                byte[] v = c.getValue();
                String address = status == BluetoothGatt.GATT_SUCCESS && v != null
                        ? new String(v, StandardCharsets.US_ASCII) : "";
                if (address.matches(Wallet.ADDRESS)) end(g, () -> done.accept(address));
                else end(g, () -> failed.accept("The other phone gave no NEB address."));
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    end(g, () -> failed.accept("The other phone refused the payment."));
                } else if (sent == data.length) {
                    end(g, () -> done.accept(""));
                } else {
                    write(g);
                }
            }

            private void write(BluetoothGatt g) {
                BluetoothGattCharacteristic c = g.getService(SERVICE).getCharacteristic(PAYMENT);
                int n = Math.min(mtu - 3, data.length - sent);
                c.setValue(Arrays.copyOfRange(data, sent, sent + n));
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                sent += n;
                if (!g.writeCharacteristic(c)) end(g, () -> failed.accept("Bluetooth did not send the payment."));
            }

            /** Reports once, on the main thread, for the connection that is still the current one. */
            private void end(BluetoothGatt g, Runnable report) {
                MAIN.post(() -> {
                    if (gatt == g) finish(report);
                });
            }
        }, BluetoothDevice.TRANSPORT_LE);
    }

    private void arm(Consumer<String> failed, String why) {
        timeout = () -> finish(() -> failed.accept(why));
        MAIN.postDelayed(timeout, TIMEOUT_MS);
    }

    private void finish(Runnable report) {
        stop();
        report.run();
    }

    /** Ends whatever is running: advertising, the search, a connection. */
    void stop() {
        if (timeout != null) MAIN.removeCallbacks(timeout);
        timeout = null;
        BluetoothAdapter a = manager == null ? null : manager.getAdapter();
        try {
            if (scanning != null && a != null && a.getBluetoothLeScanner() != null) {
                a.getBluetoothLeScanner().stopScan(scanning);
            }
            if (advertising != null && a != null && a.getBluetoothLeAdvertiser() != null) {
                a.getBluetoothLeAdvertiser().stopAdvertising(advertising);
            }
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
            if (server != null) server.close();
        } catch (RuntimeException ignored) {
            // Bluetooth was turned off, or the permission was taken back: there is nothing left to stop
        }
        scanning = null;
        advertising = null;
        gatt = null;
        server = null;
    }
}
