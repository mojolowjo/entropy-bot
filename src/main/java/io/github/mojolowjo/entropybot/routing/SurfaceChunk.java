package io.github.mojolowjo.entropybot.routing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * chunks-0.23.5: one surface chunk file (format v2, {@code surface.SurfaceColumns}) as the route map reads it: the top
 * ground run (g, family f, canopy c, underside u) and the next run below (g2, u2), per column {@code (z & 15) * 16 + (x & 15)}.
 * Plain Java (Gson only), JUnit-tested. {@link #parse} never throws: a file that isn't a v2 chunk gives null.
 *
 * @param src "companion" for the companion's chunks, "bot" for the bot's own export (no src in the file).
 */
public record SurfaceChunk(String dim, int cx, int cz, long t, String src, short[] g, byte[] f, short[] c, short[] u,
                           short[] g2, short[] u2, int minFeet, int maxFeet) {

    public static final String COMPANION = "companion";

    public boolean companion() { return COMPANION.equals(src); }

    public static int index(int x, int z) { return (z & 15) * 16 + (x & 15); }

    /** True when the box with block y range [minY, minY + 15] crosses this chunk's feet band (one block either side). */
    public boolean covers(int minY) {
        return minFeet <= maxFeet && minY <= maxFeet + 1 && minY + 15 >= minFeet - 1;
    }

    public static SurfaceChunk parse(String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (o.get("v") == null || o.get("v").getAsInt() != 2) return null;
            String src = o.has("src") && o.get("src").isJsonPrimitive() ? o.get("src").getAsString() : "bot";
            short[] g = shorts(o, "g"), c = shorts(o, "c"), u = shorts(o, "u"), g2 = shorts(o, "g2"), u2 = shorts(o, "u2");
            short[] fs = shorts(o, "f");
            if (g == null || c == null || u == null || g2 == null || u2 == null || fs == null) return null;
            byte[] f = new byte[256];
            for (int i = 0; i < 256; i++) f[i] = (byte) Math.max(0, Math.min(127, fs[i]));
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int i = 0; i < 256; i++) {
                if (g[i] >= 0 || g[i] < -1) {
                    lo = Math.min(lo, g[i] + 1);
                    hi = Math.max(hi, g[i] + 1);
                }
                if (g2[i] != -1) {
                    lo = Math.min(lo, g2[i] + 1);
                    hi = Math.max(hi, g2[i] + 1);
                }
            }
            return new SurfaceChunk(o.get("dim").getAsString(), o.get("cx").getAsInt(), o.get("cz").getAsInt(), o.get("t").getAsLong(),
                    src, g, f, c, u, g2, u2, lo, hi);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static short[] shorts(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() != 256) return null;
        short[] out = new short[256];
        for (int i = 0; i < 256; i++) out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, a.get(i).getAsInt()));
        return out;
    }
}
