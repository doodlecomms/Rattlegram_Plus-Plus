package com.bi3bju.rattlegram_plus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Splits a compressed picture into ordinary Rattlegram text messages ("parts") and puts it back together.
 * <p>
 * Part text: {@code ~P} + picture ID (4 hex) + part index (2 hex) + total parts (2 hex) + data parts (2 hex)
 * + {@code p} (plain) or {@code e} (encrypted) + Base64 data.
 * The first "data parts" parts carry the picture; the rest are spare parts (Reed-Solomon erasure code over
 * GF(256), Cauchy matrix), so any {@code data parts} of the {@code total} parts are enough to rebuild it.
 * <p>
 * The data parts together carry one blob, zero-padded to a whole number of parts:
 * 2-byte length + (plain: picture bytes + CRC-32) or (encrypted: 12-byte nonce + AES-256-GCM(picture), key
 * SHA-256(password), the same scheme as encrypted text messages).
 * Pure Java (no Android classes) so it can be tested off the phone.
 */
final class PictureCodec {
    static final String PREFIX = "~P";
    static final int HEADER_CHARS = 13;
    /** Fast: fills a whole Rattlegram message (170 bytes). */
    static final int FAST_PACKET_CHARS = 170;
    /** Robust: fits the strongest Rattlegram mode (85 bytes), about 3 dB more noise tolerance. */
    static final int ROBUST_PACKET_CHARS = 85;
    static final int MAX_PARTS = 255;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private PictureCodec() {}

    /** Picture bytes carried in each part for a part size. */
    static int bytesPerPacket(int packetChars) {
        return (packetChars - HEADER_CHARS) / 4 * 3;
    }

    /** Spare parts added to a picture of this many data parts: about 12 %, at least 2. */
    static int spareParts(int dataParts) {
        return Math.max(2, (dataParts + 7) / 8);
    }

    /** Data parts needed for a picture of this many bytes. */
    static int dataPartCount(int imageBytes, boolean encrypted, int packetChars) {
        int blob = 2 + imageBytes + (encrypted ? NONCE_BYTES + TAG_BITS / 8 : 4);
        int per = bytesPerPacket(packetChars);
        return (blob + per - 1) / per;
    }

    /** Total parts sent (data + spare) for a picture of this many bytes. */
    static int packetCount(int imageBytes, boolean encrypted, int packetChars) {
        int k = dataPartCount(imageBytes, encrypted, packetChars);
        return k + spareParts(k);
    }

    /** Builds the parts for a picture. password == null sends it unencrypted. */
    static List<String> buildPackets(byte[] image, String password, int packetChars, int id) throws Exception {
        boolean encrypted = password != null && !password.isEmpty();
        byte[] body;
        if (encrypted) {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(password), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(image);
            body = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, body, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, body, NONCE_BYTES, sealed.length);
        } else {
            CRC32 crc = new CRC32();
            crc.update(image);
            long c = crc.getValue();
            body = new byte[image.length + 4];
            System.arraycopy(image, 0, body, 0, image.length);
            for (int i = 0; i < 4; ++i)
                body[image.length + i] = (byte) (c >>> (24 - 8 * i));
        }
        int per = bytesPerPacket(packetChars);
        int k = (2 + body.length + per - 1) / per;
        int m = spareParts(k);
        if (body.length > 0xffff || k + m > MAX_PARTS)
            throw new IllegalArgumentException("picture too large");
        byte[][] parts = new byte[k + m][per];
        byte[] blob = new byte[k * per];
        blob[0] = (byte) (body.length >> 8);
        blob[1] = (byte) body.length;
        System.arraycopy(body, 0, blob, 2, body.length);
        for (int i = 0; i < k; ++i)
            System.arraycopy(blob, i * per, parts[i], 0, per);
        for (int j = 0; j < m; ++j)
            for (int i = 0; i < k; ++i) {
                int c = cauchy(j, i, k);
                for (int b = 0; b < per; ++b)
                    parts[k + j][b] ^= GF.mul(c, parts[i][b] & 0xff);
            }
        List<String> packets = new ArrayList<>(k + m);
        for (int i = 0; i < k + m; ++i)
            packets.add(String.format(Locale.US, "%s%04x%02x%02x%02x%c%s",
                    PREFIX, id & 0xffff, i, k + m, k, encrypted ? 'e' : 'p', base64Encode(parts[i])));
        return packets;
    }

    /** Whether a part of this picture is encrypted. */
    static boolean isEncrypted(String packet) {
        return packet.length() > HEADER_CHARS && packet.charAt(HEADER_CHARS - 1) == 'e';
    }

    /** One received part. */
    static final class Packet {
        int id, index, total, dataParts;
        boolean encrypted;
        byte[] data;
    }

    /** Parses a received message; returns null if it is not a picture part. */
    static Packet parse(String text) {
        if (text == null || text.length() <= HEADER_CHARS || !text.startsWith(PREFIX))
            return null;
        try {
            Packet p = new Packet();
            p.id = Integer.parseInt(text.substring(2, 6), 16);
            p.index = Integer.parseInt(text.substring(6, 8), 16);
            p.total = Integer.parseInt(text.substring(8, 10), 16);
            p.dataParts = Integer.parseInt(text.substring(10, 12), 16);
            char flag = text.charAt(12);
            if (flag != 'p' && flag != 'e')
                return null;
            p.encrypted = flag == 'e';
            p.data = base64Decode(text.substring(HEADER_CHARS));
            if (p.dataParts < 1 || p.dataParts > p.total || p.index >= p.total || p.data == null || p.data.length == 0)
                return null;
            return p;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Collects the parts of one picture from one sender. */
    static final class Assembly {
        final int id, total, dataParts;
        final boolean encrypted;
        private final byte[][] parts;
        private int received, partSize = -1;

        Assembly(Packet first) {
            id = first.id;
            total = first.total;
            dataParts = first.dataParts;
            encrypted = first.encrypted;
            parts = new byte[total][];
        }

        /** Whether the part belongs to this picture (same ID, layout and encryption). */
        boolean matches(Packet p) {
            return p.id == id && p.total == total && p.dataParts == dataParts && p.encrypted == encrypted;
        }

        /** Adds a part; returns false if it was already present or does not fit. */
        boolean add(Packet p) {
            if (parts[p.index] != null || (partSize >= 0 && p.data.length != partSize))
                return false;
            partSize = p.data.length;
            parts[p.index] = p.data;
            ++received;
            return true;
        }

        int received() {
            return received;
        }

        /** Enough parts (data or spare) have arrived to rebuild the picture. */
        boolean complete() {
            return received >= dataParts;
        }

        /** How many more parts are needed. */
        int needed() {
            return Math.max(0, dataParts - received);
        }

        /**
         * Returns the picture bytes once complete(), or null if the checksum fails or, for an encrypted
         * picture, the password is missing or wrong.
         */
        byte[] assemble(String password) {
            if (!complete())
                return null;
            byte[][] data = recoverDataParts();
            byte[] blob = new byte[dataParts * partSize];
            for (int i = 0; i < dataParts; ++i)
                System.arraycopy(data[i], 0, blob, i * partSize, partSize);
            int length = (blob[0] & 0xff) << 8 | (blob[1] & 0xff);
            if (length > blob.length - 2)
                return null;
            try {
                if (encrypted) {
                    if (password == null || password.isEmpty() || length <= NONCE_BYTES)
                        return null;
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key(password), new GCMParameterSpec(TAG_BITS, blob, 2, NONCE_BYTES));
                    return cipher.doFinal(blob, 2 + NONCE_BYTES, length - NONCE_BYTES);
                }
                if (length < 4)
                    return null;
                int n = length - 4;
                CRC32 crc = new CRC32();
                crc.update(blob, 2, n);
                long c = crc.getValue();
                for (int i = 0; i < 4; ++i)
                    if (blob[2 + n + i] != (byte) (c >>> (24 - 8 * i)))
                        return null;
                byte[] image = new byte[n];
                System.arraycopy(blob, 2, image, 0, n);
                return image;
            } catch (Exception e) {
                return null;
            }
        }

        /** Rebuilds missing data parts from spare parts (solves the erasure equations over GF(256)). */
        private byte[][] recoverDataParts() {
            int k = dataParts;
            byte[][] data = new byte[k][];
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < k; ++i) {
                if (parts[i] != null)
                    data[i] = parts[i];
                else
                    missing.add(i);
            }
            if (missing.isEmpty())
                return data;
            // Use as many spare parts as there are missing data parts
            List<Integer> spares = new ArrayList<>();
            for (int i = k; i < total && spares.size() < missing.size(); ++i)
                if (parts[i] != null)
                    spares.add(i - k);
            int n = missing.size();
            // For each spare j: sum over missing i of C[j][i]*x_i = spare_j - sum over present i of C[j][i]*d_i
            int[][] a = new int[n][n];
            byte[][] rhs = new byte[n][partSize];
            for (int r = 0; r < n; ++r) {
                int j = spares.get(r);
                System.arraycopy(parts[k + j], 0, rhs[r], 0, partSize);
                for (int i = 0; i < k; ++i) {
                    int c = cauchy(j, i, k);
                    if (data[i] != null) {
                        for (int b = 0; b < partSize; ++b)
                            rhs[r][b] ^= GF.mul(c, data[i][b] & 0xff);
                    }
                }
                for (int col = 0; col < n; ++col)
                    a[r][col] = cauchy(j, missing.get(col), k);
            }
            // Gauss-Jordan elimination (every square Cauchy matrix is invertible)
            for (int col = 0; col < n; ++col) {
                int pivot = col;
                while (a[pivot][col] == 0)
                    ++pivot;
                int[] ta = a[pivot]; a[pivot] = a[col]; a[col] = ta;
                byte[] tr = rhs[pivot]; rhs[pivot] = rhs[col]; rhs[col] = tr;
                int inv = GF.inv(a[col][col]);
                for (int c = 0; c < n; ++c)
                    a[col][c] = GF.mul(a[col][c], inv);
                for (int b = 0; b < partSize; ++b)
                    rhs[col][b] = (byte) GF.mul(rhs[col][b] & 0xff, inv);
                for (int r = 0; r < n; ++r) {
                    int f = a[r][col];
                    if (r == col || f == 0)
                        continue;
                    for (int c = 0; c < n; ++c)
                        a[r][c] ^= GF.mul(f, a[col][c]);
                    for (int b = 0; b < partSize; ++b)
                        rhs[r][b] ^= GF.mul(f, rhs[col][b] & 0xff);
                }
            }
            for (int col = 0; col < n; ++col)
                data[missing.get(col)] = rhs[col];
            return data;
        }
    }

    /** Cauchy matrix element for spare part j and data part i: 1 / ((k + j) xor i). */
    private static int cauchy(int j, int i, int k) {
        return GF.inv((k + j) ^ i);
    }

    /** Arithmetic in GF(256) with the polynomial x^8 + x^4 + x^3 + x^2 + 1. */
    private static final class GF {
        private static final int[] EXP = new int[512];
        private static final int[] LOG = new int[256];

        static {
            int x = 1;
            for (int i = 0; i < 255; ++i) {
                EXP[i] = x;
                LOG[x] = i;
                x <<= 1;
                if (x >= 256)
                    x ^= 0x11d;
            }
            for (int i = 255; i < 512; ++i)
                EXP[i] = EXP[i - 255];
        }

        static int mul(int a, int b) {
            return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]];
        }

        static int inv(int a) {
            return EXP[255 - LOG[a]];
        }
    }

    private static SecretKeySpec key(String password) throws Exception {
        byte[] k = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(k, "AES");
    }

    // Standard Base64 (RFC 4648) with padding; android.util.Base64 is not available off the phone
    // and java.util.Base64 needs Android 8.
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64Encode(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b = (in[i] & 0xff) << 16;
            if (i + 1 < in.length) b |= (in[i + 1] & 0xff) << 8;
            if (i + 2 < in.length) b |= in[i + 2] & 0xff;
            sb.append(B64[b >> 18 & 63]).append(B64[b >> 12 & 63]);
            sb.append(i + 1 < in.length ? B64[b >> 6 & 63] : '=');
            sb.append(i + 2 < in.length ? B64[b & 63] : '=');
        }
        return sb.toString();
    }

    static byte[] base64Decode(String s) {
        if (s.length() % 4 != 0)
            return null;
        int pad = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
        byte[] out = new byte[s.length() / 4 * 3 - pad];
        int o = 0;
        for (int i = 0; i < s.length(); i += 4) {
            int b = 0;
            for (int j = 0; j < 4; ++j) {
                char c = s.charAt(i + j);
                int v;
                if (c >= 'A' && c <= 'Z') v = c - 'A';
                else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
                else if (c >= '0' && c <= '9') v = c - '0' + 52;
                else if (c == '+') v = 62;
                else if (c == '/') v = 63;
                else if (c == '=' && i + j >= s.length() - pad) v = 0;
                else return null;
                b = b << 6 | v;
            }
            for (int k = 0; k < 3 && o < out.length; ++k)
                out[o++] = (byte) (b >> (16 - 8 * k));
        }
        return out;
    }
}
