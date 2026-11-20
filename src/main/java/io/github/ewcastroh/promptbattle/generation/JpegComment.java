package io.github.ewcastroh.promptbattle.generation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Reads and writes a JPEG COM segment (used by the fake providers to carry the prompt in the image). */
public final class JpegComment {

    private JpegComment() {
    }

    /** The text of the first COM segment before the image data, or null. */
    public static String read(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return null;
        }
        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return null;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > jpeg.length) {
                return null;
            }
            if (marker == 0xFE) {
                return new String(jpeg, i + 4, length - 2, StandardCharsets.UTF_8);
            }
            i += 2 + length;
        }
        return null;
    }

    /** Inserts a COM segment after the leading APPn segments. Text is truncated to fit one segment. */
    public static byte[] write(byte[] jpeg, String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int max = 65533;
        if (payload.length > max) {
            payload = java.util.Arrays.copyOf(payload, max);
        }
        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker < 0xE0 || marker > 0xEF) {
                break;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            i += 2 + length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + payload.length + 4);
        out.write(jpeg, 0, i);
        int length = payload.length + 2;
        out.write(0xFF);
        out.write(0xFE);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(jpeg, i, jpeg.length - i);
        return out.toByteArray();
    }
}
