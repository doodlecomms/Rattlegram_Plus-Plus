package com.bi3bju.rattlegram_plus;

import java.nio.charset.StandardCharsets;

/**
 * Optional "hide call sign" for encrypted transmissions: the modem header carries a placeholder and the
 * real call sign travels inside the encrypted text (or picture), as {@code ~C:CALL} plus a newline.
 * Only stations with the password can see who sent it. Pure Java so it can be tested off the phone.
 */
final class HiddenCall {
    static final String PREFIX = "~C:";
    /** Call sign shown in the modem header while the real one is hidden. */
    static final String PLACEHOLDER = "ANONYMOUS";

    private HiddenCall() {}

    /** Bytes the hidden call sign adds to a message before encryption. */
    static int overhead(String call) {
        return PREFIX.length() + call.length() + 1;
    }

    static String wrap(String call, String text) {
        return PREFIX + call + "\n" + text;
    }

    /** Returns {call, text} if text carries a hidden call sign, else null. */
    static String[] unwrap(String text) {
        if (text == null || !text.startsWith(PREFIX))
            return null;
        int nl = text.indexOf('\n');
        if (nl < 0)
            return null;
        String call = text.substring(PREFIX.length(), nl).trim();
        if (!validCall(call))
            return null;
        return new String[]{call, text.substring(nl + 1)};
    }

    static byte[] wrap(String call, byte[] data) {
        byte[] head = (PREFIX + call + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + data.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(data, 0, out, head.length, data.length);
        return out;
    }

    /** Returns the hidden call sign at the start of data, or null. */
    static String callIn(byte[] data) {
        int max = Math.min(data.length, PREFIX.length() + 10);
        if (data.length <= PREFIX.length() || !new String(data, 0, PREFIX.length(), StandardCharsets.US_ASCII).equals(PREFIX))
            return null;
        for (int i = PREFIX.length(); i < max; ++i)
            if (data[i] == '\n') {
                String call = new String(data, PREFIX.length(), i - PREFIX.length(), StandardCharsets.US_ASCII).trim();
                return validCall(call) ? call : null;
            }
        return null;
    }

    /** data without the hidden call sign prefix (call must come from callIn(data)). */
    static byte[] strip(byte[] data, String call) {
        int skip = PREFIX.length() + call.length() + 1;
        byte[] out = new byte[data.length - skip];
        System.arraycopy(data, skip, out, 0, out.length);
        return out;
    }

    private static boolean validCall(String call) {
        return call.length() >= 1 && call.length() <= 9 && call.matches("[A-Za-z0-9 ]+");
    }
}
