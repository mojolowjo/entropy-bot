package io.github.mojolowjo.entropybot.recorder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One chunk's baseline: the block ids of all its 16x16x16 sections at the time it was taken. Each section is a
 * palette plus 4096 indices ({@code (y * 16 + z) * 16 + x}, like the game's own containers), or a single id when the
 * section holds one block only (all air: palette {"minecraft:air"}, no indices). On disk: gzip of a small binary
 * format ("EBR1", dim, cx, cz, minY, takenMs, then each section's palette and indices as bytes or shorts).
 * Pure; immutable after construction.
 */
public final class ChunkBase {
    static final int MAGIC = 0x45425231; // "EBR1"
    public static final String AIR = "minecraft:air";

    public final String dim;
    public final int cx, cz, minY;
    public final long takenMs;
    final String[][] palettes;
    final short[][] idx;

    public ChunkBase(String dim, int cx, int cz, int minY, long takenMs, String[][] palettes, short[][] idx) {
        this.dim = dim;
        this.cx = cx;
        this.cz = cz;
        this.minY = minY;
        this.takenMs = takenMs;
        this.palettes = palettes;
        this.idx = idx;
    }

    public int sections() { return palettes.length; }

    public int maxY() { return minY + palettes.length * 16 - 1; }

    /** The block id at x y z (world coordinates), or null outside the chunk's height or columns. */
    public String id(int x, int y, int z) {
        if (x >> 4 != cx || z >> 4 != cz || y < minY || y > maxY()) return null;
        int s = (y - minY) >> 4;
        String[] p = palettes[s];
        if (p == null || p.length == 0) return null;
        short[] ix = idx[s];
        if (ix == null) return p[0];
        int i = (((y - minY) & 15) * 16 + (z & 15)) * 16 + (x & 15);
        return p[ix[i] & 0xffff];
    }

    /** Builds a section from 4096 ids (any order of first appearance): palette + indices, or one id. */
    public static void section(String[] cells, String[][] palettes, short[][] idx, int s) {
        java.util.HashMap<String, Integer> pal = new java.util.HashMap<>();
        java.util.ArrayList<String> order = new java.util.ArrayList<>();
        short[] ix = new short[4096];
        for (int i = 0; i < 4096; i++) {
            String id = cells[i] == null ? AIR : cells[i];
            Integer k = pal.get(id);
            if (k == null) {
                k = order.size();
                pal.put(id, k);
                order.add(id);
            }
            ix[i] = (short) (int) k;
        }
        palettes[s] = order.toArray(new String[0]);
        idx[s] = order.size() == 1 ? null : ix;
    }

    // ---- disk ----

    public void write(OutputStream out) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(new BufferedOutputStream(out, 1 << 15), 1 << 15);
        DataOutputStream d = new DataOutputStream(gz);
        d.writeInt(MAGIC);
        d.writeUTF(dim);
        d.writeInt(cx);
        d.writeInt(cz);
        d.writeInt(minY);
        d.writeLong(takenMs);
        d.writeShort(palettes.length);
        byte[] bytes = new byte[4096];
        for (int s = 0; s < palettes.length; s++) {
            String[] p = palettes[s] == null ? new String[]{AIR} : palettes[s];
            d.writeShort(p.length);
            for (String id : p) d.writeUTF(id.startsWith("minecraft:") ? id.substring(10) : id);
            if (p.length == 1) continue;
            short[] ix = idx[s];
            if (p.length <= 256) {
                for (int i = 0; i < 4096; i++) bytes[i] = (byte) ix[i];
                d.write(bytes);
            } else {
                for (int i = 0; i < 4096; i++) d.writeShort(ix[i]);
            }
        }
        d.flush();
        gz.finish();
        gz.flush();
    }

    public static ChunkBase read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new GZIPInputStream(new BufferedInputStream(in, 1 << 15), 1 << 15));
        if (d.readInt() != MAGIC) throw new IOException("not a recorder baseline");
        String dim = d.readUTF();
        int cx = d.readInt(), cz = d.readInt(), minY = d.readInt();
        long taken = d.readLong();
        int n = d.readShort();
        if (n < 0 || n > 256) throw new IOException("bad section count " + n);
        String[][] pal = new String[n][];
        short[][] idx = new short[n][];
        byte[] bytes = new byte[4096];
        for (int s = 0; s < n; s++) {
            int pn = d.readShort() & 0xffff;
            if (pn < 1 || pn > 4096) throw new IOException("bad palette size " + pn);
            String[] p = new String[pn];
            for (int i = 0; i < pn; i++) {
                String id = d.readUTF();
                p[i] = id.indexOf(':') < 0 ? "minecraft:" + id : id;
            }
            pal[s] = p;
            if (pn == 1) continue;
            short[] ix = new short[4096];
            if (pn <= 256) {
                d.readFully(bytes);
                for (int i = 0; i < 4096; i++) ix[i] = (short) (bytes[i] & 0xff);
            } else {
                for (int i = 0; i < 4096; i++) ix[i] = d.readShort();
            }
            for (int i = 0; i < 4096; i++) if ((ix[i] & 0xffff) >= pn) throw new IOException("bad index");
            idx[s] = ix;
        }
        return new ChunkBase(dim, cx, cz, minY, taken, pal, idx);
    }
}
