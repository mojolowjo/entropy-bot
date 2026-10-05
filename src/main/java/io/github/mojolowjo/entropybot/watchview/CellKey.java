package io.github.mojolowjo.entropybot.watchview;

/**
 * A block position packed into a long, the same layout as Minecraft's {@code BlockPos.asLong} (x 26 bits at the top,
 * z 26 bits, y 12 bits at the bottom), so a key made here and one made by the game agree. Pure Java.
 */
public final class CellKey {
    private CellKey() {}

    public static long of(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int x(long k) { return (int) (k >> 38); }

    public static int y(long k) { return (int) (k << 52 >> 52); }

    public static int z(long k) { return (int) (k << 26 >> 38); }

    public static long offset(long k, int dx, int dy, int dz) {
        return of(x(k) + dx, y(k) + dy, z(k) + dz);
    }

    public static String text(long k) { return x(k) + " " + y(k) + " " + z(k); }
}
