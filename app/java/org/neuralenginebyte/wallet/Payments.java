package org.neuralenginebyte.wallet;

import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A payment that travels by Bluetooth, with or without internet on either phone.
 *
 *  The phone that pays signs with the coins it knows, keeps the signed payment and hands it over. The phone that
 *  receives reads what it pays to its key and keeps it too. Each phone gives it to the network as soon as it has
 *  internet: the first one that does puts it on the chain. Until it is in a block a payment received with no
 *  internet is a signed promise, because the coins it spends could have been spent elsewhere. */
final class Payments {
    private static final String PROMISE = "Until it is in a block it is a signed promise: the coins it spends could "
            + "have been spent elsewhere.";

    private Payments() { }

    /** A signed payment arrived by Bluetooth. */
    static void arrived(MainActivity a, byte[] payment) {
        String pw = a.password, hex = Wallet.hex(payment);
        if (pw == null) return;
        a.work(() -> {
            String out = Wallet.run(a, pw, "--wallet", a.walletFile.getPath(), "received", hex);
            String amount = MainActivity.group(out, "received ([0-9.]+) NEB");
            String txid = MainActivity.group(out, "txid\\s+(\\w+)");
            if (txid.isEmpty() || amount.isEmpty() || amount.matches("[0.]+")) {
                throw new Exception("A payment arrived by Bluetooth, but it pays nothing to this wallet. It was not "
                        + "kept.");
            }
            Pending.add(txid, hex, false, amount, "", new ArrayList<>());
            return "It pays this wallet " + amount + " NEB.\n\n" + push(a, pw, txid, hex) + "\n\n" + PROMISE;
        }, text -> {
            new AlertDialog.Builder(a).setTitle("Payment by Bluetooth").setMessage(text).setPositiveButton("OK", null)
                    .show();
            a.refresh();
        }, a::error);
    }

    /** Signs a payment to `address`, keeps it, hands it to the phone found by Bluetooth, then gives it to the
     *  network too when this phone has internet. */
    static void pay(MainActivity a, BluetoothDevice device, String address, String value) {
        String pw = a.password;
        a.work(() -> Wallet.run(a, pw, "--rpc", a.rpc(), "--wallet", a.walletFile.getPath(), "sign", address, value),
                out -> {
                    String hex = MainActivity.group(out, "signed\\s+([0-9a-f]+)");
                    String txid = MainActivity.group(out, "txid\\s+(\\w+)");
                    List<String> spends = new ArrayList<>();
                    Matcher m = Pattern.compile("spends\\s+(\\w+:\\d+)").matcher(out);
                    while (m.find()) spends.add(m.group(1));
                    if (hex.isEmpty() || txid.isEmpty() || spends.isEmpty()) {
                        a.error("The wallet did not sign the payment.");
                        return;
                    }
                    try {
                        // kept before it leaves the phone: its coins are not chosen for another payment
                        Pending.add(txid, hex, true, value, address, spends);
                    } catch (Exception e) {
                        a.error(String.valueOf(e.getMessage()));
                        return;
                    }
                    AlertDialog wait = new AlertDialog.Builder(a).setMessage("Sending by Bluetooth...")
                            .setCancelable(false).show();
                    a.link.deliver(device, Wallet.bytes(hex), () -> {
                        wait.dismiss();
                        a.go(MainActivity.HOME);
                        a.work(() -> push(a, pw, txid, hex), text -> new AlertDialog.Builder(a)
                                .setTitle("Delivered by Bluetooth")
                                .setMessage("The other phone has the signed payment.\n\n" + text)
                                .setPositiveButton("OK", null).show(), a::error);
                    }, e -> {
                        wait.dismiss();
                        a.go(MainActivity.HOME);
                        a.error(e + "\n\nThe signed payment is kept under Pending and its coins are set aside. It may "
                                + "have reached the other phone: ask before you pay again.");
                    });
                }, a::error);
    }

    /** Gives one kept payment to the node and records what the node said. Returns it in plain words. */
    static String push(MainActivity a, String pw, String txid, String hex) {
        String said;
        try {
            Wallet.run(a, pw, "--rpc", a.rpc(), "--wallet", a.walletFile.getPath(), "submit", hex);
            said = "";
        } catch (Exception e) {
            said = String.valueOf(e.getMessage());
        }
        if (said.isEmpty() || said.contains("already known")) {
            Pending.state(txid, Pending.GIVEN, "");
            return "The network has it. It counts once it is in a block.";
        }
        // code -1 is this phone failing to reach the node; any other code is the answer of the node
        if (said.contains("(code -1)") || said.startsWith("connect ")) {
            Pending.state(txid, Pending.KEPT, "");
            return "No internet now. The payment is kept on this phone and goes to the network when the internet is "
                    + "back.";
        }
        Pending.state(txid, Pending.REFUSED, said);
        return "The node refused it:\n" + said;
    }

    /** Gives the network every payment it does not have yet. Runs off the main thread, at each refresh. */
    static void outbox(MainActivity a, String pw) {
        for (JSONObject p : Pending.all()) {
            if (Pending.KEPT.equals(p.optString("state"))) push(a, pw, p.optString("txid"), p.optString("hex"));
        }
    }

    /** The payments waiting for a block, on the home screen. */
    static void card(MainActivity a, LinearLayout into) {
        List<JSONObject> all = Pending.all();
        if (all.isEmpty()) return;
        TextView head = a.text("Pending", 20, MainActivity.WHITE, true);
        head.setPadding(0, a.px(26), 0, 0);
        into.addView(head);
        LinearLayout c = a.card(into);
        for (JSONObject p : all) {
            String state = p.optString("state");
            String words = Pending.GIVEN.equals(state) ? "waiting for a block"
                    : Pending.REFUSED.equals(state) ? "refused by the node" : "kept, waiting for the internet";
            String sign = p.optBoolean("paid") ? "↑ " : "↓ ";
            TextView row = a.line(c, sign + (a.hidden ? "****" : p.optString("amount")) + " NEB · " + words, 14,
                    Pending.REFUSED.equals(state) ? MainActivity.GREY : MainActivity.WHITE);
            row.setOnClickListener(v -> detail(a, p));
        }
        a.line(c, "The coins of a payment you made, and its change, are set aside until it is in a block. Touch a "
                + "payment to see it or to forget it.", 12, MainActivity.GREY);
    }

    private static void detail(MainActivity a, JSONObject p) {
        boolean paid = p.optBoolean("paid");
        String txid = p.optString("txid"), note = p.optString("note");
        String text = (paid ? "Paid " : "Received ") + p.optString("amount") + " NEB by Bluetooth"
                + (paid ? "\n\nTo\n" + MainActivity.wrapped(p.optString("address")) : "")
                + "\n\ntxid\n" + txid.substring(0, Math.min(32, txid.length())) + "\n"
                + txid.substring(Math.min(32, txid.length())) + (note.isEmpty() ? "" : "\n\nThe node said:\n" + note);
        new AlertDialog.Builder(a).setTitle("Pending payment").setMessage(text).setPositiveButton("Close", null)
                .setNeutralButton("Forget", (d, w) -> new AlertDialog.Builder(a).setTitle("Forget this payment?")
                        .setMessage(paid ? "Its coins become spendable again in this wallet. The signed payment may "
                                + "still be on the other phone, which can give it to the network: only forget a "
                                + "payment that did not reach it, or that the node refused."
                                : "This phone stops giving it to the network. Coins already in a block are not "
                                + "affected.")
                        .setPositiveButton("Forget", (d2, w2) -> {
                            Pending.forget(txid);
                            a.render();
                            a.refresh();
                        }).setNegativeButton("Cancel", null).show())
                .show();
    }
}
