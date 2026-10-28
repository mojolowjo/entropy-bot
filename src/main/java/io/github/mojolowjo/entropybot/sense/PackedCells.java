package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 0.39.z78 J6: cells ({@link Sightings#cell} packed longs) as one deflated base64 string for the files (an opening's
 * cells in a label, a cave record's seen cells). Pure (JUnit: J6CaveLabelsTest, J6bCaveRecordTest). Loader notes: none.
 */
public final class PackedCells {
    private PackedCells() {}

    /** Packed cells, 8 bytes each, deflated, base64. */
    public static String pack(Collection<Long> cells) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 * cells.size());           // structural: 8 bytes a long
        for (long c : cells) b.putLong(c);
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        d.setInput(b.array());
        d.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];                                                       // structural: a copy buffer
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** The cells of {@link #pack}; a broken string throws IllegalArgumentException (the reader counts it skipped). */
    public static List<Long> unpack(String s) {
        try {
            java.util.zip.Inflater in = new java.util.zip.Inflater();
            in.setInput(java.util.Base64.getDecoder().decode(s));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];                                                   // structural: a copy buffer
            while (!in.finished()) {
                int n = in.inflate(buf);
                if (n == 0 && (in.needsInput() || in.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            in.end();
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(out.toByteArray());
            List<Long> cells = new ArrayList<>();
            while (b.remaining() >= 8) cells.add(b.getLong());                            // structural: 8 bytes a long
            return cells;
        } catch (java.util.zip.DataFormatException | IllegalArgumentException e) {
            throw new IllegalArgumentException("packed cells not readable: " + e.getMessage(), e);
        }
    }
}
