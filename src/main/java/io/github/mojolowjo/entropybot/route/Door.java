package io.github.mojolowjo.entropybot.route;

/**
 * An opening on one face of a box: a connected run of crossing moves into (or out of) one face neighbour.
 *
 * <p><b>Faces:</b> 0 = -X (west), 1 = +X (east), 2 = -Y (down), 3 = +Y (up), 4 = -Z (north), 5 = +Z (south).
 *
 * <p><b>Mask:</b> 256 bits over the face plane, bit {@code u + 16 * v}, with (u, v) = (z, y) on the X faces,
 * (x, z) on the Y faces and (x, y) on the Z faces, all local 0..15. Each crossing move marks both its ends
 * (projected onto the plane, clamped), so the two boxes sharing a face mark the same bits for the same opening
 * and the graph matches doors by overlapping masks.
 *
 * <p><b>Diagonals (review R2):</b> only moves that end in a <i>face</i> neighbour are doors. A diagonal that ends in an
 * edge- or corner-adjacent box is not a door (a small overestimate, safe): the box's crossings may still step
 * through that margin cell (see {@link SectionRecord}), but a door is only ever left by a move into a face neighbour.
 *
 * <p>{@code rep*} is the box cell nearest the run's middle (for "legs" mode); {@code min*}/{@code max*} bound the box
 * cells of the door (both the cells left from and the cells entered at), world coordinates, for the heuristic's
 * "blocks to the door". {@code canLeave}: some move leaves the box through this door; {@code canEnter}: some move
 * enters it (a fall is one way).
 */
public record Door(int face, long[] mask,
                   int repX, int repY, int repZ,
                   int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                   boolean canLeave, boolean canEnter) {

    public static final int[] DX = {-1, 1, 0, 0, 0, 0};
    public static final int[] DY = {0, 0, -1, 1, 0, 0};
    public static final int[] DZ = {0, 0, 0, 0, -1, 1};

    /** The face on the other side (0 <-> 1, 2 <-> 3, 4 <-> 5). */
    public static int opposite(int face) {
        return face ^ 1;
    }

    public Door {
        if (face < 0 || face > 5) throw new IllegalArgumentException("face " + face);
        if (mask == null || mask.length != 4) throw new IllegalArgumentException("mask must be 4 longs");
    }

    public Cell rep() {
        return new Cell(repX, repY, repZ);
    }

    public boolean maskBit(int u, int v) {
        int b = u + 16 * v;
        return (mask[b >> 6] & (1L << (b & 63))) != 0;
    }

    public int maskCount() {
        return Long.bitCount(mask[0]) + Long.bitCount(mask[1]) + Long.bitCount(mask[2]) + Long.bitCount(mask[3]);
    }

    /** Straight-line blocks from (x, y, z) to the nearest point of this door's cell bounds (0 inside). */
    public double distanceTo(int x, int y, int z) {
        double dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0);
        double dy = y < minY ? minY - y : (y > maxY ? y - maxY : 0);
        double dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
