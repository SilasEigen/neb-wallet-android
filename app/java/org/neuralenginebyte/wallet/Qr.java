package org.neuralenginebyte.wallet;

import java.nio.charset.StandardCharsets;

/** A QR code for a short text: version 6 (41 x 41 modules), error correction level Q, byte mode, mask 0. Level Q lets
 *  a quarter of the code be hidden, which leaves room for a logo in the middle. Enough for a NEB address (62
 *  characters); the limit is 74 bytes. */
final class Qr {
    static final int SIZE = 41;
    private static final int BLOCKS = 4, DATA_PER_BLOCK = 19, EC_PER_BLOCK = 24;

    private final boolean[][] dark = new boolean[SIZE][SIZE], fixed = new boolean[SIZE][SIZE];

    private Qr() { }

    /** The modules of the code, `[row][column]`, true for a dark one. */
    static boolean[][] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int capacity = BLOCKS * DATA_PER_BLOCK;
        if (bytes.length > capacity - 2) throw new IllegalArgumentException("text too long for the code");
        // Byte mode: 4 bits of mode, 8 bits of length, the bytes, a terminator, then padding bytes.
        byte[] data = new byte[capacity];
        int bit = 0;
        bit = put(data, bit, 0x4, 4);
        bit = put(data, bit, bytes.length, 8);
        for (byte b : bytes) bit = put(data, bit, b & 0xFF, 8);
        bit = put(data, bit, 0, 4);
        int used = (bit + 7) / 8;
        for (int i = used, pad = 0xEC; i < capacity; i++, pad ^= 0xEC ^ 0x11) data[i] = (byte) pad;

        // Four blocks, each followed by its error correction bytes; the bytes of the blocks are then interleaved.
        byte[] generator = generator(EC_PER_BLOCK);
        byte[][] ec = new byte[BLOCKS][];
        for (int b = 0; b < BLOCKS; b++) ec[b] = remainder(data, b * DATA_PER_BLOCK, DATA_PER_BLOCK, generator);
        byte[] all = new byte[BLOCKS * (DATA_PER_BLOCK + EC_PER_BLOCK)];
        int k = 0;
        for (int i = 0; i < DATA_PER_BLOCK; i++) for (int b = 0; b < BLOCKS; b++) all[k++] = data[b * DATA_PER_BLOCK + i];
        for (int i = 0; i < EC_PER_BLOCK; i++) for (int b = 0; b < BLOCKS; b++) all[k++] = ec[b][i];

        Qr q = new Qr();
        q.patterns();
        q.place(all);
        return q.dark;
    }

    private static int put(byte[] into, int bit, int value, int count) {
        for (int i = count - 1; i >= 0; i--, bit++) {
            if (((value >>> i) & 1) != 0) into[bit >>> 3] |= (byte) (0x80 >>> (bit & 7));
        }
        return bit;
    }

    // Reed-Solomon over GF(256) with the polynomial x^8 + x^4 + x^3 + x^2 + 1.

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static byte[] generator(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 2);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, int from, int length, byte[] generator) {
        byte[] result = new byte[generator.length];
        for (int n = 0; n < length; n++) {
            int factor = (data[from + n] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) multiply(generator[i] & 0xFF, factor);
        }
        return result;
    }

    // The fixed patterns, then the data.

    private void set(int x, int y, boolean on) {
        dark[y][x] = on;
        fixed[y][x] = true;
    }

    private void patterns() {
        for (int i = 0; i < SIZE; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        int[][] finders = {{3, 3}, {SIZE - 4, 3}, {3, SIZE - 4}};
        for (int[] f : finders) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int x = f[0] + dx, y = f[1] + dy, ring = Math.max(Math.abs(dx), Math.abs(dy));
                    if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) set(x, y, ring != 2 && ring != 4);
                }
            }
        }
        // The one alignment pattern of version 6, centred on module (34, 34).
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) set(34 + dx, 34 + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        // The format bits: level Q (binary 11) and mask 0, with their 10 check bits, xored with the fixed pattern.
        int format = 3 << 3, rem = format;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (format << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(SIZE - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, SIZE - 15 + i, bit(bits, i));
        set(8, SIZE - 8, true);
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    /** Writes the bytes in the zigzag of the standard, two columns at a time from the right, and applies mask 0. */
    private void place(byte[] all) {
        int i = 0;
        for (int right = SIZE - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < SIZE; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? SIZE - 1 - vert : vert;
                    if (fixed[y][x]) continue;
                    boolean on = i < all.length * 8 && bit(all[i >>> 3], 7 - (i & 7));
                    i++;
                    dark[y][x] = on ^ ((x + y) % 2 == 0);
                }
            }
        }
    }
}
