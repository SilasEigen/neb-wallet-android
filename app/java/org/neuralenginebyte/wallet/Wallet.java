package org.neuralenginebyte.wallet;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calls the packaged neb-wallet program. The app holds no key code of its own. */
final class Wallet {
    static final String ADDRESS = "neb1[0-9a-z]+", HEX64 = "[0-9a-f]{64}";

    private Wallet() { }

    static File file(Context c) {
        return new File(c.getFilesDir(), "wallet.txt");
    }

    /** True when the key file is not encrypted with a password: an encrypted file names its format on a line. */
    static boolean isPlain(File f) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return !text.contains("neb-wallet-encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs neb-wallet and returns what it printed. The password travels in the environment of the child process
     *  only; it is never written to storage. */
    static String run(Context c, String password, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(c.getApplicationInfo().nativeLibraryDir + "/libnebwallet.so");
        command.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(c.getFilesDir()).redirectErrorStream(true);
        pb.environment().put("NEB_WALLET_PASSWORD", password);
        pb.environment().put("NO_COLOR", "1");
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try (InputStream in = p.getInputStream()) {
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new Exception(text.replace("neb-wallet: ", "").trim());
        return text;
    }

    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02x", b));
        return s.toString();
    }

    /** The bytes of an even number of hex digits. */
    static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String find(String text, String regex) throws Exception {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new Exception("unexpected output of the wallet");
        return m.group();
    }
}
