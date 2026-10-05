package io.github.mojolowjo.entropybot.watchview;

/**
 * 0.17.1: the surface from a chunk scan, not from rays (docs/CAMERA_PLAN.md "Built: surface from a chunk scan"). The
 * owner's question was how Minecraft draws without gaps: it meshes every block face that touches air or a see-through
 * block in every loaded section (a neighbour test, no view test) and lets the depth buffer sort out what is in front.
 * Doing that everywhere would be x-ray underground, so the scan does it only where nothing can be hidden: faces whose
 * air side lies in a <b>sky column</b>.
 *
 * <p>The sky rule (pure, JUnit {@code SkyScanTest}): walking a block column (x, z) down from above the highest block,
 * every cell is a sky cell until the first cell that hides what is below it from above: an opaque full block, a
 * non-full block with a shape (slab, stairs, path, fence, chest), a full non-opaque shape that is not leaves (spawner,
 * lava), or more than {@code maxWater} cells of water (deep sea floors stay out, as with the rays). That cell is the
 * column's floor. Air, plants (no collision shape), glass and <b>leaves</b> keep the column open, so the ground under a
 * tree crown is a sky cell (you see it from the side and, with Fancy leaves, through the crown); a cave mouth's inside,
 * a tunnel or an overhang's underside has rock above it and is not. Why not the light level: {@code canSeeSky} (sky
 * light 15) drops to 14 under one leaf layer and stays high a few blocks into a cave mouth, so it is wrong both ways.
 *
 * <p>The face rule: for every sky cell c and each of its six neighbours n, the face of n towards c is recorded when n
 * is something drawn and c lets it show ({@link #shows}): air and plants always; water and glass except towards more of
 * the same; leaves only with Fancy graphics (with Fast leaves are opaque cubes: what lies behind a leaf, including the
 * next leaf, is hidden, as vanilla's Fast look), and the game's own neighbour test ({@code Block.shouldRenderFace})
 * agrees ({@link World#faceShows}). Each face is found from exactly one cell (the one in front of it), so a column scan
 * never reports a face twice.
 */
public final class SkyScan {
    /** What a cell is for the scan. */
    public static final int AIR = 0, PLANT = 1, WATER = 2, GLASS = 3, LEAVES = 4, SOLID = 5, SHAPE = 6, PARTIAL = 7, UNLOADED = 8;
    public static final int MAX_WATER = SeenRays.MAX_WATER;

    public interface World {
        /** The cell's kind (constants above). */
        int kind(int x, int y, int z);

        /** The game's neighbour test: would vanilla draw the face {@code side} of the block at x y z (Block.shouldRenderFace)? */
        boolean faceShows(int x, int y, int z, int side);
    }

    public interface Sink {
        /** A face to draw: block x y z, its side (Shell.OFF order); the sky cell in front of it is x y z + OFF[side]. */
        void face(int x, int y, int z, int side);
    }

    private SkyScan() {}

    /** The cell hides everything below it from above: the column's floor. */
    public static boolean blocksSky(int k) {
        return k == SOLID || k == SHAPE || k == PARTIAL || k == UNLOADED;
    }

    /** A cell whose faces are drawn. */
    public static boolean drawable(int k) {
        return k == WATER || k == GLASS || k == LEAVES || k == SOLID || k == SHAPE || k == PARTIAL;
    }

    /** Opaque for the corner shading (vanilla's ambient occlusion darkens next to full cubes, leaves included). */
    public static boolean shades(int k) {
        return k == SOLID || k == SHAPE || k == LEAVES;
    }

    /** Is the face of a block of kind n towards a cell of kind c drawn (before the game's own test)? */
    public static boolean shows(int n, int c, boolean fancy) {
        if (!drawable(n)) return false;
        return switch (c) {
            case AIR, PLANT -> true;
            case WATER -> n != WATER;
            case GLASS -> n != GLASS;
            case LEAVES -> fancy;                 // Fast: leaves are opaque cubes, the face behind one is hidden
            default -> false;
        };
    }

    /**
     * The y of the column's floor (the first cell from {@code top} down that {@link #blocksSky}, or the cell where the
     * water grew deeper than maxWater), or {@code minY - 1} when there is none down to minY.
     */
    public static int floor(World w, int x, int z, int top, int minY, int maxWater) {
        int water = 0;
        for (int y = top; y >= minY; y--) {
            int k = w.kind(x, y, z);
            if (blocksSky(k)) return y;
            if (k == WATER && ++water > maxWater) return y;
        }
        return minY - 1;
    }

    /**
     * Scans one column: every sky cell from {@code top} (the first all-air cell above the highest block of this column
     * and its four neighbours) down to just above the floor, and the faces round it. Returns the floor's y.
     */
    public static int column(World w, int x, int z, int top, int minY, boolean fancy, Sink sink) {
        int floor = floor(w, x, z, top, minY, MAX_WATER);
        for (int y = top; y > floor; y--) {
            int c = w.kind(x, y, z);
            for (int d = 0; d < 6; d++) {
                int[] o = Shell.OFF[d];
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                int n = w.kind(nx, ny, nz);
                if (!shows(n, c, fancy)) continue;
                int side = d ^ 1;                 // Shell.OFF pairs: down/up, north/south, west/east
                if (!w.faceShows(nx, ny, nz, side)) continue;
                sink.face(nx, ny, nz, side);
            }
        }
        return floor;
    }

    /**
     * Corner shading levels (0 darkest .. 3 open) for the four corners of a face, as vanilla's classic ambient occlusion:
     * for the cell in front of the face and each corner, the two edge cells and the corner cell in that plane; both edges
     * shaded = 0, else 3 minus the shaded cells. {@code corners} are the face's vertices (block coordinates x y z in the
     * first three columns, any origin), in their drawing order; {@code centre} the face's middle in the same coordinates
     * (a box face's middle for a slab or path); the result follows the corners' order.
     */
    public static int[] cornerShade(int bx, int by, int bz, int side, float[][] corners, double[] centre, Shade shade) {
        int[] o = Shell.OFF[side];
        int cx = bx + o[0], cy = by + o[1], cz = bz + o[2];
        int axis = o[0] != 0 ? 0 : o[1] != 0 ? 1 : 2;
        int a1 = axis == 0 ? 1 : 0, a2 = axis == 2 ? 1 : 2;
        int[] out = new int[corners.length];
        for (int i = 0; i < corners.length; i++) {
            int s1 = corners[i][a1] > centre[a1] ? 1 : -1, s2 = corners[i][a2] > centre[a2] ? 1 : -1;
            int[] e1 = new int[3], e2 = new int[3];
            e1[a1] = s1;
            e2[a2] = s2;
            boolean side1 = shade.at(cx + e1[0], cy + e1[1], cz + e1[2]);
            boolean side2 = shade.at(cx + e2[0], cy + e2[1], cz + e2[2]);
            boolean corner = shade.at(cx + e1[0] + e2[0], cy + e1[1] + e2[1], cz + e1[2] + e2[2]);
            out[i] = side1 && side2 ? 0 : 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0));
        }
        return out;
    }

    public interface Shade {
        boolean at(int x, int y, int z);
    }

    /** Brightness of a corner shading level (vanilla's AO is about this strong with smooth lighting on). */
    public static float shadeFactor(int level) {
        return switch (Math.max(0, Math.min(3, level))) {
            case 0 -> 0.5f;
            case 1 -> 0.68f;
            case 2 -> 0.84f;
            default -> 1f;
        };
    }
}
