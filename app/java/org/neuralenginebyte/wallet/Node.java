package org.neuralenginebyte.wallet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

/** The node the wallet asks, and what the phone remembers of it.
 *
 *  neb-wallet speaks plain HTTP to a host and a port. This class listens on the loopback address of the phone and
 *  passes each call on to the one node of the settings, over TLS when its address says so. It keeps the last
 *  answers about the coins of the wallet, the height and the chain, so that a payment can be signed with no
 *  internet: when the node cannot be reached, the saved answers are given instead. The coins that a payment still
 *  waiting for a block spends are left out, so that they are not chosen twice. */
final class Node {
    /** The public node of the project: balances and payments, no other call. */
    static final String PUBLIC = "https://rpc.neuralenginebyte.org";
    /** A JSON-RPC call of the wallet is small; a larger request is not passed on. */
    private static final int MAX_REQUEST = 65536, MAX_ANSWER = 8 << 20, PAGE = 1000, MAX_COINS = 100000;

    private static ServerSocket server;
    private static volatile String target = PUBLIC;
    private static File file;
    private static JSONObject saved = new JSONObject();
    /** True when the last answer about the chain came from what the phone remembers, not from the node. */
    static volatile boolean offline;

    private Node() { }

    /** The address to give to neb-wallet for the node `url`. */
    static synchronized String rpc(Context c, String url) throws Exception {
        Pending.open(c);
        if (file == null) {
            file = new File(c.getFilesDir(), "node.json");
            try {
                saved = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                saved = new JSONObject();
            }
        }
        if (!url.equals(saved.optString("node"))) {
            // what one node said is never given as the answer of another
            saved = new JSONObject().put("node", url);
        }
        target = url.contains("://") ? url : "http://" + url;
        if (server == null) {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            ServerSocket s = server;
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Socket socket = s.accept();
                        new Thread(() -> serve(socket)).start();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private static void serve(Socket c) {
        try (Socket socket = c) {
            socket.setSoTimeout(30000);
            byte[] body = requestBody(socket.getInputStream());
            String answer;
            try {
                JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                JSONArray params = request.optJSONArray("params");
                answer = answer(request.getString("method"), params == null ? new JSONArray() : params);
            } catch (Exception e) {
                answer = new JSONObject().put("result", JSONObject.NULL).put("id", 1)
                        .put("error", new JSONObject().put("code", -1).put("message", String.valueOf(e.getMessage())))
                        .toString();
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) {
            // the wallet reports the closed connection
        }
    }

    private static String answer(String method, JSONArray params) throws Exception {
        if (method.equals("getutxos")) {
            // The first call gets every coin of the lock at once; the call for a next page gets none.
            JSONArray coins = params.length() > 2 ? new JSONArray() : coins(params.getString(0));
            return new JSONObject().put("result", coins).put("error", JSONObject.NULL).put("id", 1).toString();
        }
        if (!method.equals("getblockcount") && !method.equals("getblockhash")) return call(method, params);
        String key = method + params;
        try {
            String answer = call(method, params);
            if (new JSONObject(answer).isNull("error")) remember(key, answer);
            offline = false;
            // The id of the chain is what a payment is signed for: it is asked once, while there is internet.
            JSONArray first = new JSONArray().put(0);
            if (recall("getblockhash" + first) == null) {
                String genesis = call("getblockhash", first);
                if (new JSONObject(genesis).isNull("error")) remember("getblockhash" + first, genesis);
            }
            return answer;
        } catch (IOException e) {
            String kept = recall(key);
            if (kept == null) throw e;
            offline = true;
            return kept;
        }
    }

    /** The unspent outputs of a lock: read from the node by pages, or the saved ones when it cannot be reached;
     *  without those that a payment waiting for a block spends. */
    private static JSONArray coins(String lock) throws Exception {
        String key = "getutxos" + lock;
        JSONArray all = new JSONArray();
        try {
            JSONObject last = null;
            while (true) {
                JSONArray params = new JSONArray().put(lock).put(PAGE);
                if (last != null) params.put(last.getString("txid")).put(last.getLong("index"));
                JSONObject answer = new JSONObject(call("getutxos", params));
                if (!answer.isNull("error")) throw new Exception(answer.getJSONObject("error").optString("message"));
                JSONArray page = answer.getJSONArray("result");
                for (int i = 0; i < page.length(); i++) all.put(page.getJSONObject(i));
                if (page.length() < PAGE) break;
                if (all.length() > MAX_COINS) throw new Exception("this address has too many coins for a phone");
                last = page.getJSONObject(page.length() - 1);
            }
            remember(key, all.toString());
            offline = false;
            Pending.seen(all);
        } catch (IOException e) {
            String kept = recall(key);
            if (kept == null) throw e;
            all = new JSONArray(kept);
            offline = true;
        }
        Set<String> held = Pending.reserved();
        JSONArray free = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject coin = all.getJSONObject(i);
            if (!held.contains(coin.getString("txid") + ":" + coin.getLong("index"))) free.put(coin);
        }
        return free;
    }

    private static synchronized void remember(String key, String answer) throws Exception {
        saved.put(key, answer);
        File next = new File(file.getPath() + ".new");
        Files.write(next.toPath(), saved.toString().getBytes(StandardCharsets.UTF_8));
        if (!next.renameTo(file)) throw new IOException("the phone could not save the answer of the node");
    }

    private static synchronized String recall(String key) {
        return saved.has(key) ? saved.optString(key) : null;
    }

    /** One call to the node. An IOException means the node could not be reached. */
    private static String call(String method, JSONArray params) throws Exception {
        byte[] body = new JSONObject().put("jsonrpc", "1.0").put("id", 1).put("method", method).put("params", params)
                .toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection h = (HttpURLConnection) new URL(target).openConnection();
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(30000);
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
            h.setRequestProperty("User-Agent", "neb-wallet-android");
            h.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = h.getOutputStream()) {
                out.write(body);
            }
            int status = h.getResponseCode();
            InputStream in = status < 400 ? h.getInputStream() : h.getErrorStream();
            ByteArrayOutputStream answer = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; in != null && (n = in.read(buf)) > 0; ) {
                answer.write(buf, 0, n);
                if (answer.size() > MAX_ANSWER) throw new Exception("the answer of the node is too large");
            }
            if (status == 429) throw new Exception("too many calls to the node, wait a minute");
            String text = new String(answer.toByteArray(), StandardCharsets.UTF_8);
            if (!text.trim().startsWith("{")) throw new Exception("the node answered HTTP " + status);
            return text;
        } finally {
            h.disconnect();
        }
    }

    /** Reads one HTTP request and returns its body. */
    private static byte[] requestBody(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0 || head.size() > 4096) throw new Exception("bad request");
            head.write(b);
            state = b == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : b == '\r' ? 1 : 0;
        }
        int length = -1;
        for (String line : head.toString("US-ASCII").split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        }
        if (length < 0 || length > MAX_REQUEST) throw new Exception("bad request");
        byte[] body = new byte[length];
        for (int got = 0; got < length; ) {
            int n = in.read(body, got, length - got);
            if (n < 0) throw new Exception("bad request");
            got += n;
        }
        return body;
    }
}
