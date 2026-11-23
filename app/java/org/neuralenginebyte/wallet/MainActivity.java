package org.neuralenginebyte.wallet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.Network;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A wallet in the look of the NEB terminal programs: one home screen with the balance and three actions, then one
 *  screen per action. The key is on the phone, encrypted with a password. Keys, scanning and signing are done by
 *  the packaged neb-wallet program. */
public class MainActivity extends Activity {
    /** The fee that neb-wallet adds to a payment. */
    static final String FEE = "0.0001";
    static final String DEFAULT_NODE = Node.PUBLIC;
    /** The wallet locks itself after this time in the background. */
    static final long LOCK_AFTER_MS = 5 * 60 * 1000;
    static final int HOME = 0, RECEIVE = 1, SEND = 2, SETTINGS = 3;
    static final int GREEN = 120, WHITE = 231, GREY = 244, DIM = 240, GROUND = 233, CARD = 234, EDGE = 238;

    private SharedPreferences prefs;
    File walletFile;
    private float dp;
    private LinearLayout top, body, bottom;
    /** The password of the session, in memory only, until the wallet is locked. */
    String password;
    private long stoppedAt;
    private int screen = HOME;
    boolean hidden;
    private boolean refreshing, offline;
    private String balance = "", maturing = "", height = "", nodeError = "";
    /** What the user allows before Bluetooth is used: before Android 12, a search needs the location permission. */
    private static final String[] BLUETOOTH = Build.VERSION.SDK_INT >= 31
            ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE}
            : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    BluetoothLink link;
    /** True while this phone shows itself to receive a payment by Bluetooth. */
    private boolean listening;
    /** The phone found by Bluetooth on the Send screen, and the address it gave. */
    private BluetoothDevice peer;
    private String peerAddress = "";
    private Runnable allowed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("neb", 0);
        walletFile = Wallet.file(this);
        dp = getResources().getDisplayMetrics().density;
        link = new BluetoothLink(this);
        Pending.open(this);
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // the kept payments go to the network as soon as there is one
                runOnUiThread(MainActivity.this::refresh);
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(GROUND));
        // The window runs under the system bars: the content keeps clear of them, and of the keyboard.
        root.setPadding(px(20), px(14), px(20), px(14));
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(px(20) + bars.left, px(14) + bars.top, px(20) + bars.right, px(14) + bars.bottom);
            return insets;
        });
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(-1, px(48)));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, px(18), 0, px(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = System.currentTimeMillis();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (password != null && stoppedAt != 0 && System.currentTimeMillis() - stoppedAt > LOCK_AFTER_MS) lock();
        else if (password != null) refresh();
    }

    @Override
    public void onBackPressed() {
        if (password != null && screen != HOME) go(HOME);
        else super.onBackPressed();
    }

    void go(int to) {
        link.stop();
        listening = false;
        peer = null;
        screen = to;
        render();
    }

    // The look: a dark ground, one fixed-width font, green for what matters, rounded frames.

    static int color(int n) {
        if (n == GREEN) return 0xFF54D087;  // the green of the logo
        if (n >= 232) {
            int g = 8 + 10 * (n - 232);
            return 0xFF000000 | g << 16 | g << 8 | g;
        }
        n -= 16;
        int[] v = {0, 95, 135, 175, 215, 255};
        return 0xFF000000 | v[n / 36] << 16 | v[(n / 6) % 6] << 8 | v[n % 6];
    }

    int px(float v) {
        return (int) (v * dp);
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color(color));
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable shape(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(fill));
        g.setCornerRadius(px(radius));
        g.setStroke(px(1), color(stroke));
        return g;
    }

    private LinearLayout.LayoutParams wide(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, px(topMargin), 0, 0);
        return lp;
    }

    /** A round button of the top bar, with one character as its sign. */
    private TextView round(String sign, Runnable run) {
        TextView t = text(sign, 18, WHITE, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(shape(CARD, EDGE, 22));
        t.setOnClickListener(v -> run.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(px(44), px(44)));
        return t;
    }

    /** A round button of the top bar with a system icon. `dim` greys it out, for a switch that is off. */
    private ImageView roundIcon(int icon, boolean dim, Runnable run) {
        ImageView v = new ImageView(this);
        v.setImageResource(icon);
        v.setColorFilter(color(dim ? DIM : WHITE));
        v.setPadding(px(11), px(11), px(11), px(11));
        v.setBackground(shape(CARD, EDGE, 22));
        v.setOnClickListener(x -> run.run());
        v.setLayoutParams(new LinearLayout.LayoutParams(px(44), px(44)));
        return v;
    }

    /** The top bar of an inner screen: the way back, and the title in the middle. */
    private void title(String name) {
        top.addView(round("<", () -> go(HOME)));
        TextView t = text(name, 20, WHITE, true);
        t.setGravity(Gravity.CENTER);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(new View(this), new LinearLayout.LayoutParams(px(44), px(44)));
    }

    /** A wide button at the bottom of a screen. `main` fills it with green. */
    private TextView button(LinearLayout into, String label, boolean main, Runnable run) {
        TextView t = text(label, 16, main ? GROUND : WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(10), px(17), px(10), px(17));
        t.setBackground(main ? shape(GREEN, GREEN, 16) : shape(CARD, EDGE, 16));
        t.setOnClickListener(v -> run.run());
        into.addView(t, wide(10));
        return t;
    }

    LinearLayout card(LinearLayout into) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(CARD, EDGE, 16));
        c.setPadding(px(16), px(14), px(16), px(16));
        into.addView(c, wide(12));
        return c;
    }

    TextView line(LinearLayout into, String s, float sp, int color) {
        TextView t = text(s, sp, color, false);
        t.setPadding(0, px(6), 0, 0);
        t.setLineSpacing(px(2), 1);
        into.addView(t);
        return t;
    }

    private TextView label(LinearLayout into, String s) {
        TextView t = text(s, 12, GREY, false);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, px(4), 0, 0);
        into.addView(t);
        return t;
    }

    private EditText field(LinearLayout into, String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine();
        e.setInputType(inputType);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(15);
        e.setTextColor(color(WHITE));
        e.setHintTextColor(color(DIM));
        e.setBackground(shape(CARD, EDGE, 14));
        e.setPadding(px(16), px(16), px(16), px(16));
        into.addView(e, wide(8));
        return e;
    }

    void render() {
        top.removeAllViews();
        body.removeAllViews();
        bottom.removeAllViews();
        // While a password can be typed on the screen, the screen is kept out of screenshots and recordings.
        if (password == null) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!walletFile.exists()) welcome();
        else if (password == null) locked();
        else if (screen == RECEIVE) receive();
        else if (screen == SEND) send();
        else if (screen == SETTINGS) settings();
        else home();
    }

    private String node() {
        return prefs.getString("node", DEFAULT_NODE);
    }

    /** The address neb-wallet is given for the node of the settings. */
    String rpc() throws Exception {
        return Node.rpc(this, node());
    }

    private String address() {
        return prefs.getString("address", "");
    }

    /** The address on three lines, as it is easier to compare. */
    static String wrapped(String address) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < address.length(); i += 21) {
            s.append(address, i, Math.min(address.length(), i + 21)).append(i + 21 < address.length() ? "\n" : "");
        }
        return s.toString();
    }

    void copy(String label, String value) {
        ClipData clip = ClipData.newPlainText(label, value);
        if (label.equals("NEB key")) {
            // a secret: the system keeps it out of the clipboard preview
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
    }

    // First start and unlock.

    private void brand() {
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_fg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(220), px(220));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(logo, lp);
    }

    private void welcome() {
        brand();
        TextView t = text("Your NEB wallet", 22, WHITE, true);
        t.setGravity(Gravity.CENTER);
        body.addView(t);
        TextView s = line(body, "One key, kept on this phone and encrypted with a password you choose.", 14, GREY);
        s.setGravity(Gravity.CENTER);
        button(bottom, "Create a wallet", true, () -> askNewPassword("Choose a wallet password", pw -> work(() -> {
            Wallet.run(this, pw, "--wallet", walletFile.getPath(), "new");
            return new String[] {readAddress(pw), exportKey(pw)};
        }, r -> {
            opened(pw, r[0]);
            KeyDialog.show(this, r[1]);
        }, this::error)));
        button(bottom, "Import a key", false, this::importKey);
    }

    private void importKey() {
        EditText input = new EditText(this);
        input.setHint("64 hex characters");
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        secure(new AlertDialog.Builder(this).setTitle("Secret key").setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    String key = input.getText().toString().trim();
                    askNewPassword("Choose a wallet password", pw -> work(() -> {
                        Wallet.run(this, "", "--wallet", walletFile.getPath(), "import", key);
                        Wallet.run(this, pw, "--wallet", walletFile.getPath(), "encrypt");
                        return readAddress(pw);
                    }, a -> opened(pw, a), e -> {
                        walletFile.delete();
                        error(e);
                    }));
                })
                .setNegativeButton("Cancel", null).create());
    }

    private void locked() {
        brand();
        if (Wallet.isPlain(walletFile)) {
            // A key file left without a password gets one before anything else.
            TextView t = line(body, "The key on this phone has no password yet.", 14, GREY);
            t.setGravity(Gravity.CENTER);
            button(bottom, "Set a password", true, () -> askNewPassword("Choose a wallet password", pw -> work(() -> {
                Wallet.run(this, pw, "--wallet", walletFile.getPath(), "encrypt");
                return readAddress(pw);
            }, a -> opened(pw, a), this::error)));
            return;
        }
        label(body, "PASSWORD");
        EditText input = field(body, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(bottom, "Unlock", true, () -> {
            String pw = input.getText().toString();
            work(() -> readAddress(pw), a -> opened(pw, a), e -> error("Wrong password."));
        });
    }

    private String readAddress(String pw) throws Exception {
        return Wallet.find(Wallet.run(this, pw, "--wallet", walletFile.getPath(), "address"), Wallet.ADDRESS);
    }

    private String exportKey(String pw) throws Exception {
        return Wallet.find(Wallet.run(this, pw, "--wallet", walletFile.getPath(), "export"), Wallet.HEX64);
    }

    private void opened(String pw, String address) {
        password = pw;
        prefs.edit().putString("address", address).apply();
        screen = HOME;
        render();
        refresh();
    }

    private void lock() {
        link.stop();
        listening = false;
        password = null;
        balance = maturing = height = nodeError = "";
        screen = HOME;
        render();
    }

    // The screens.

    private void home() {
        // Top bar: the brand on the left; hide the amounts, and the settings, on the right.
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setBackground(shape(CARD, EDGE, 22));
        brand.setPadding(px(12), 0, px(14), 0);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_mark);
        brand.addView(mark, new LinearLayout.LayoutParams(px(26), px(23)));
        TextView name = text("NEB", 14, WHITE, true);
        name.setPadding(px(8), 0, 0, 0);
        brand.addView(name);
        top.addView(brand, new LinearLayout.LayoutParams(-2, px(44)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        ImageView eye = roundIcon(android.R.drawable.ic_menu_view, hidden, () -> {
            hidden = !hidden;
            render();
        });
        eye.setContentDescription("Show or hide the amounts");
        top.addView(eye);
        ImageView gear = roundIcon(android.R.drawable.ic_menu_preferences, false, () -> go(SETTINGS));
        gear.setContentDescription("Settings");
        ((LinearLayout.LayoutParams) gear.getLayoutParams()).setMargins(px(10), 0, 0, 0);
        top.addView(gear);

        // The balance, alone in the middle.
        String shown = hidden ? "****" : balance.isEmpty() ? "-" : balance;
        TextView amount = text(shown, 50, WHITE, true);
        amount.setGravity(Gravity.CENTER);
        amount.setPadding(0, px(34), 0, 0);
        body.addView(amount);
        TextView unit = text("NEB", 16, GREEN, true);
        unit.setGravity(Gravity.CENTER);
        body.addView(unit);
        String under = refreshing ? "asking the node..."
                : !nodeError.isEmpty() ? "○ node offline"
                : height.isEmpty() ? "" : offline ? "○ no internet · as of block " + height
                : "● block " + height;
        TextView state = text(under, 13, nodeError.isEmpty() && !refreshing && !offline ? GREEN : GREY, false);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, px(8), 0, px(30));
        body.addView(state);

        // Three actions.
        LinearLayout row = new LinearLayout(this);
        String[][] tiles = {{"↓", "Receive"}, {"↑", "Send"}, {"↻", "Refresh"}};
        Runnable[] runs = {() -> go(RECEIVE), () -> go(SEND), this::refresh};
        for (int i = 0; i < tiles.length; i++) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(shape(CARD, EDGE, 16));
            tile.setPadding(0, px(18), 0, px(18));
            TextView sign = text(tiles[i][0], 24, GREEN, true);
            sign.setGravity(Gravity.CENTER);
            tile.addView(sign);
            TextView word = text(tiles[i][1], 14, WHITE, false);
            word.setGravity(Gravity.CENTER);
            word.setPadding(0, px(6), 0, 0);
            tile.addView(word);
            final Runnable run = runs[i];
            tile.setOnClickListener(v -> run.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(i == 0 ? 0 : px(10), 0, 0, 0);
            row.addView(tile, lp);
        }
        body.addView(row);

        // What the node says about the coins that are not spendable yet, or why there is no figure.
        TextView head = text("Coins", 20, WHITE, true);
        head.setPadding(0, px(34), 0, 0);
        body.addView(head);
        LinearLayout c = card(body);
        if (!nodeError.isEmpty()) {
            line(c, "The node did not answer.", 14, WHITE);
            line(c, nodeError, 12, GREY);
            line(c, "Node: " + node(), 12, GREY);
        } else if (!maturing.isEmpty()) {
            line(c, "Spendable   " + (hidden ? "****" : balance) + " NEB", 14, WHITE);
            line(c, "Maturing  + " + (hidden ? "****" : maturing) + " NEB", 14, GREEN);
            line(c, "A mined block can be spent 100 blocks after it was found.", 12, GREY);
        } else {
            line(c, balance.isEmpty() ? "No figure yet." : "Nothing is maturing.", 14, WHITE);
            line(c, "Coins you receive or mine appear here.", 12, GREY);
        }
        Payments.card(this, body);
    }

    private void receive() {
        title("Receive");
        QrView qr = new QrView(this, address(), getDrawable(R.drawable.ic_mark));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(250), px(250));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, px(6), 0, px(22));
        body.addView(qr, lp);
        TextView l = label(body, "YOUR ADDRESS");
        l.setGravity(Gravity.CENTER);
        TextView a = line(body, wrapped(address()), 17, WHITE);
        a.setGravity(Gravity.CENTER);
        TextView note = line(body, "Give it to the sender, to your miner or to your pool.", 12, GREY);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, px(16), 0, 0);
        button(bottom, "Copy Address", true, () -> copy("NEB address", address()));
        button(bottom, "Share", false, () -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, address()), "NEB address")));
        if (listening) {
            line(bottom, "● Waiting for a payment by Bluetooth. Keep this screen open.", 13, GREEN)
                    .setGravity(Gravity.CENTER);
            button(bottom, "Stop Bluetooth", false, () -> go(RECEIVE));
        } else {
            button(bottom, "Receive by Bluetooth", false, () -> withBluetooth(() -> {
                listening = true;
                link.receive(address(), p -> Payments.arrived(this, p), e -> {
                    listening = false;
                    render();
                    error(e);
                });
                render();
            }));
        }
    }

    /** Runs `then` once Bluetooth is allowed and on. */
    private void withBluetooth(Runnable then) {
        for (String p : BLUETOOTH) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                allowed = () -> withBluetooth(then);
                requestPermissions(BLUETOOTH, 1);
                return;
            }
        }
        String why = link.unavailable();
        if (why != null) error(why);
        else then.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        Runnable then = allowed;
        allowed = null;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) then = null;
        if (then != null && results.length > 0) then.run();
        else error("Without this permission the wallet cannot use Bluetooth.");
    }

    private void send() {
        title("Send");
        label(body, "SEND TO");
        EditText to = field(body, "Enter NEB address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        TextView paste = text("Paste from the clipboard", 13, GREEN, false);
        paste.setPadding(px(4), px(10), 0, px(4));
        paste.setOnClickListener(v -> {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) to.setText(String.valueOf(clip.getItemAt(0).coerceToText(this)).trim());
        });
        body.addView(paste);
        // The way to the other phone: a framed row, quieter than the button of the screen.
        String ask = "Find the other phone · Bluetooth";
        TextView find = text(ask, 14, GREEN, false);
        find.setGravity(Gravity.CENTER);
        find.setPadding(px(10), px(13), px(10), px(13));
        find.setBackground(shape(CARD, EDGE, 14));
        find.setOnClickListener(v -> withBluetooth(() -> {
            find.setText("Searching...");
            link.find((device, address) -> {
                peer = device;
                peerAddress = address;
                to.setText(address);
                find.setText("Found. Compare the address with the other screen.");
            }, e -> {
                find.setText(ask);
                error(e);
            });
        }));
        LinearLayout.LayoutParams findAt = wide(8);
        findAt.bottomMargin = px(18);
        body.addView(find, findAt);
        label(body, "AMOUNT");
        EditText amount = field(body, "0.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        line(body, "Spendable " + (balance.isEmpty() ? "-" : balance) + " NEB · fee " + FEE + " NEB", 12, GREY)
                .setPadding(px(4), px(10), 0, 0);

        // The button says what is missing, and turns green once the payment can be reviewed.
        TextView review = button(bottom, "", true, () -> { });
        Runnable check = () -> {
            boolean hasAddress = to.getText().toString().trim().matches(Wallet.ADDRESS);
            boolean hasAmount = amount.getText().toString().trim().matches("[0-9]*\\.?[0-9]+");
            boolean ready = hasAddress && hasAmount;
            review.setText(!hasAddress ? "Enter Address" : !hasAmount ? "Enter Amount" : "Review");
            review.setTextColor(color(ready ? GROUND : GREY));
            review.setBackground(ready ? shape(GREEN, GREEN, 16) : shape(CARD, EDGE, 16));
            review.setEnabled(ready);
        };
        TextWatcher watch = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                check.run();
            }
        };
        to.addTextChangedListener(watch);
        amount.addTextChangedListener(watch);
        check.run();
        review.setOnClickListener(v -> {
            String address = to.getText().toString().trim(), value = amount.getText().toString().trim();
            AlertDialog.Builder ask2 = new AlertDialog.Builder(this).setTitle("Send " + value + " NEB?");
            BluetoothDevice device = peer;
            if (device != null && address.equals(peerAddress)) {
                ask2.setNeutralButton("By Bluetooth", (d, w) -> Payments.pay(this, device, address, value));
            }
            ask2.setMessage("To\n" + wrapped(address) + "\n\nFee " + FEE + " NEB\n\nA payment cannot be undone.")
                    .setPositiveButton("Send", (d, w) -> work(
                            () -> Wallet.run(this, password, "--rpc", rpc(), "--wallet",
                                    walletFile.getPath(), "send", address, value),
                            out -> {
                                new AlertDialog.Builder(this).setTitle("Sent").setMessage(out.trim())
                                        .setPositiveButton("OK", null).show();
                                go(HOME);
                                refresh();
                            }, this::error))
                    .setNegativeButton("Cancel", null).show();
        });
    }

    private void settings() {
        title("Settings");
        LinearLayout n = card(body);
        label(n, "NODE");
        line(n, "The wallet asks this node for the balance and gives it the payments.", 13, GREY);
        EditText url = field(n, DEFAULT_NODE, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(node());
        button(n, "Save", false, () -> {
            prefs.edit().putString("node", url.getText().toString().trim()).apply();
            go(HOME);
            refresh();
        });
        line(n, "The default is the public node of the project, over HTTPS. Your own node: http://HOST:PORT. The "
                + "phone remembers its coins, to sign a payment with no internet.", 12, GREY);

        LinearLayout k = card(body);
        label(k, "KEY");
        line(k, "The key is in the private storage of this app, encrypted with your password. It is not in cloud "
                + "backups: removing the app removes the key.", 13, GREY);
        button(k, "Show secret key", false, () -> askPassword(pw -> work(() -> exportKey(pw), key -> KeyDialog.show(this, key),
                e -> error("Wrong password."))));
        button(bottom, "Lock", false, this::lock);
    }

    /** Asks the node for the balance. */
    void refresh() {
        if (password == null || refreshing) return;
        refreshing = true;
        if (screen == HOME) render();
        String pw = password;
        new Thread(() -> {
            String out, err = "";
            try {
                out = Wallet.run(this, pw, "--rpc", rpc(), "--wallet", walletFile.getPath(), "balance");
                Payments.outbox(this, pw);
            } catch (Exception e) {
                out = "";
                err = String.valueOf(e.getMessage());
            }
            String result = out, failure = err;
            runOnUiThread(() -> {
                refreshing = false;
                nodeError = failure;
                offline = Node.offline;
                if (failure.isEmpty()) {
                    height = group(result, "height\\s+(\\d+)");
                    balance = group(result, "total\\s+([0-9.]+) NEB");
                    maturing = group(result, "immature ([0-9.]+)");
                }
                if (password != null && screen == HOME) render();
            });
        }).start();
    }

    static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    // Windows.

    /** Runs a wallet call off the main thread: deriving a key from a password takes a moment. */
    <T> void work(Callable<T> job, Consumer<T> ok, Consumer<String> fail) {
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Working...").setCancelable(false).show();
        new Thread(() -> {
            try {
                T result = job.call();
                runOnUiThread(() -> {
                    wait.dismiss();
                    ok.accept(result);
                });
            } catch (Exception e) {
                String message = String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    wait.dismiss();
                    fail.accept(message);
                });
            }
        }).start();
    }

    void error(String text) {
        // neb-wallet counts in base units; the screen says it in plain words
        if (text.startsWith("selection: available")) text = "Not enough spendable NEB for this amount and the fee.";
        new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show();
    }

    /** Shows a window that takes a password or a key: it is kept out of screenshots and screen recordings. */
    void secure(AlertDialog dialog) {
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private EditText passwordField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void askPassword(Consumer<String> ok) {
        EditText input = passwordField("password");
        secure(new AlertDialog.Builder(this).setTitle("Password of the wallet").setView(input)
                .setPositiveButton("OK", (d, w) -> ok.accept(input.getText().toString()))
                .setNegativeButton("Cancel", null).create());
    }

    /** A new password, typed twice, 8 characters or more. */
    private void askNewPassword(String title, Consumer<String> ok) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText first = passwordField("password (8 characters or more)"), again = passwordField("again");
        box.addView(first);
        box.addView(again);
        secure(new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setMessage("It encrypts the key on this phone and opens the wallet. It cannot be recovered: keep it "
                        + "with the backup of the key.")
                .setPositiveButton("OK", (d, w) -> {
                    String p = first.getText().toString();
                    if (p.length() < 8) error("The password needs 8 characters or more.");
                    else if (!p.equals(again.getText().toString())) error("The two passwords differ.");
                    else ok.accept(p);
                })
                .setNegativeButton("Cancel", null).create());
    }
}
