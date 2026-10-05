package io.github.mojolowjo.entropybot.route;

/**
 * One level-1 box: a chunk section, 16x16x16 blocks. {@code dim} is a small id the adapter assigns
 * (0 = overworld; stage 1 builds only the overworld). {@code sx, sy, sz} are section coordinates
 * ({@code x >> 4}), {@code sy} from -4 to 19 in the overworld.
 */
public record SectionKey(int dim, int sx, int sy, int sz) {
    public static final int SIZE = 16;

    /** The section holding block (x, y, z). */
    public static SectionKey of(int dim, int x, int y, int z) {
        return new SectionKey(dim, x >> 4, y >> 4, z >> 4);
    }

    public int minX() { return sx << 4; }
    public int minY() { return sy << 4; }
    public int minZ() { return sz << 4; }

    public boolean contains(int x, int y, int z) {
        return (x >> 4) == sx && (y >> 4) == sy && (z >> 4) == sz;
    }

    /** The face neighbour across {@code face} (see {@link Door#face()}). */
    public SectionKey neighbour(int face) {
        return new SectionKey(dim, sx + Door.DX[face], sy + Door.DY[face], sz + Door.DZ[face]);
    }

    /** Packed into one long without the dim (tables are per dim): 22 bits x, 12 bits y, 22 bits z (+ sign). */
    public long packed() {
        return pack(sx, sy, sz);
    }

    public static long pack(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 34) | ((long) (sy & 0xFFF) << 22) | (sz & 0x3FFFFF);
    }

    /** The 128x128-block tile (8x8 chunk columns, all y) that the file format groups boxes by. */
    public int tileX() { return sx >> 3; }
    public int tileZ() { return sz >> 3; }

    @Override
    public String toString() {
        return "s" + dim + "[" + sx + "," + sy + "," + sz + "]";
    }
}
