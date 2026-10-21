package gz.dynamiccode;

import java.nio.charset.Charset;

/** Base64 编解码与输入清理。 */
public final class Codec {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Codec() {
    }

    /** 去掉空白、引号等，只保留 Base64 字符（含 URL-safe 字符）。 */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || c == '-' || c == '_' || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 宽容解码：支持换行、URL-safe、缺失 padding。 */
    public static byte[] decode(String text) {
        if (text == null) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-') {
                c = '+';
            } else if (c == '_') {
                c = '/';
            }
            if (c == '=' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/') {
                sb.append(c);
            }
        }
        int len = sb.length();
        if (len == 0) {
            return new byte[0];
        }
        while (len % 4 != 0) {
            sb.append('=');
            len++;
        }

        byte[] out = new byte[len / 4 * 3];
        int pos = 0;
        for (int i = 0; i < len; i += 4) {
            int value = 0;
            int pad = 0;
            for (int j = 0; j < 4; j++) {
                char c = sb.charAt(i + j);
                int digit;
                if (c == '=') {
                    digit = 0;
                    pad++;
                } else if (c >= 'A' && c <= 'Z') {
                    digit = c - 'A';
                } else if (c >= 'a' && c <= 'z') {
                    digit = c - 'a' + 26;
                } else if (c >= '0' && c <= '9') {
                    digit = c - '0' + 52;
                } else if (c == '+') {
                    digit = 62;
                } else {
                    digit = 63;
                }
                value = (value << 6) | digit;
            }
            out[pos++] = (byte) ((value >> 16) & 0xFF);
            if (pad < 2) {
                out[pos++] = (byte) ((value >> 8) & 0xFF);
            }
            if (pad < 1) {
                out[pos++] = (byte) (value & 0xFF);
            }
        }
        if (pos == out.length) {
            return out;
        }
        byte[] result = new byte[pos];
        System.arraycopy(out, 0, result, 0, pos);
        return result;
    }

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(ALPHABET[b0 >> 2]);
            sb.append(ALPHABET[((b0 & 0x03) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < data.length ? ALPHABET[((b1 & 0x0F) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < data.length ? ALPHABET[b2 & 0x3F] : '=');
        }
        return sb.toString();
    }

    public static String text(byte[] data) {
        return data == null ? "" : new String(data, UTF8);
    }
}
