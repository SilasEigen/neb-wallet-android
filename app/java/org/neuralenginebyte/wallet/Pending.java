package org.neuralenginebyte.wallet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The signed payments that are not in a block yet, kept on the phone: the ones this wallet paid by Bluetooth and
 *  the ones it received. A payment stays here until one of its outputs shows among the coins of the wallet, or
 *  until the user forgets it. A signed payment holds no secret. */
final class Pending {
    /** Not given to a node yet; taken by a node; refused by a node. */
    static final String KEPT = "kept", GIVEN = "given", REFUSED = "refused";

    private static File file;
    private static JSONArray list = new JSONArray();

    private Pending() { }

    static synchronized void open(Context c) {
        if (file != null) return;
        file = new File(c.getFilesDir(), "pending.json");
        try {
            list = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            list = new JSONArray();
        }
    }

    /** Keeps a signed payment. `spends` are the coins a payment of this wallet uses, as txid:index. */
    static synchronized void add(String txid, String hex, boolean paid, String amount, String address,
            List<String> spends) throws Exception {
        if (find(txid) >= 0) return;
        list.put(new JSONObject().put("txid", txid).put("hex", hex).put("paid", paid).put("amount", amount)
                .put("address", address).put("spends", new JSONArray(spends)).put("state", KEPT).put("note", ""));
        save();
    }

    static synchronized List<JSONObject> all() {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) out.add(list.optJSONObject(i));
        return out;
    }

    /** The coins that the payments of this wallet still waiting for a block spend. */
    static synchronized Set<String> reserved() {
        Set<String> out = new HashSet<>();
        for (JSONObject p : all()) {
            JSONArray spends = p.optJSONArray("spends");
            for (int i = 0; spends != null && i < spends.length(); i++) out.add(spends.optString(i));
        }
        return out;
    }

    static synchronized void state(String txid, String state, String note) {
        int i = find(txid);
        if (i < 0) return;
        try {
            list.getJSONObject(i).put("state", state).put("note", note);
            save();
        } catch (Exception ignored) {
            // the state is asked again at the next refresh
        }
    }

    static synchronized void forget(String txid) {
        int i = find(txid);
        if (i < 0) return;
        list.remove(i);
        try {
            save();
        } catch (Exception ignored) {
            // it is forgotten again at the next change
        }
    }

    /** The node listed these coins of the wallet: a payment that made one of them is in a block.
     *  Limit: a payment of this wallet with no change output makes no coin here and stays until it is forgotten;
     *  ask the node about its inputs when that matters. */
    static synchronized void seen(JSONArray coins) {
        Set<String> made = new HashSet<>();
        for (int i = 0; i < coins.length(); i++) made.add(coins.optJSONObject(i).optString("txid"));
        boolean changed = false;
        for (int i = list.length() - 1; i >= 0; i--) {
            if (made.contains(list.optJSONObject(i).optString("txid"))) {
                list.remove(i);
                changed = true;
            }
        }
        try {
            if (changed) save();
        } catch (Exception ignored) {
            // found again at the next refresh
        }
    }

    private static int find(String txid) {
        for (int i = 0; i < list.length(); i++) {
            if (txid.equals(list.optJSONObject(i).optString("txid"))) return i;
        }
        return -1;
    }

    private static void save() throws Exception {
        File next = new File(file.getPath() + ".new");
        Files.write(next.toPath(), list.toString().getBytes(StandardCharsets.UTF_8));
        if (!next.renameTo(file)) throw new Exception("the phone could not save the payment");
    }
}
