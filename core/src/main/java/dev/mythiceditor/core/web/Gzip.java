package dev.mythiceditor.core.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/** Small gzip helper with a selectable compression level (the stock GZIPOutputStream only offers the default). */
final class Gzip {
    private Gzip() {}

    static byte[] compress(byte[] data, int level) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos) {
            {
                def.setLevel(Math.max(Deflater.BEST_SPEED, Math.min(Deflater.BEST_COMPRESSION, level)));
            }
        }) {
            gz.write(data);
        }
        return bos.toByteArray();
    }
}
