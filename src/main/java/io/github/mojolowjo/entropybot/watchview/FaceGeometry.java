package io.github.mojolowjo.entropybot.watchview;

/**
 * Camera v2: the four corners of a shell face, relative to a mesh origin, with texture coordinates 0..1. The quad lies
 * on the boundary between the solid block and the known-air cell, moved {@code inset} towards the air (so it never
 * fights the real face where both show). Vertical faces have v = 1 at the bottom. Pure (JUnit: FaceGeometryTest).
 */
public final class FaceGeometry {
    /** Brightness per side, like the game's flat shading: down, up, north, south, west, east. */
    public static final float[] SHADE = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};

    private FaceGeometry() {}

    /** Rows of x, y, z, u, v for the face {@code side} of the block at bx by bz, relative to the origin ox oy oz. */
    public static float[][] corners(int bx, int by, int bz, int side, double inset, int ox, int oy, int oz) {
        return corners(bx, by, bz, side, inset, ox, oy, oz, UNIT);
    }

    /** The whole block: min x y z, max x y z as fractions of the cell. */
    public static final double[] UNIT = {0, 0, 0, 1, 1, 1};

    /**
     * 0.16.1: the face of a box inside the cell ({@code box} = min x y z, max x y z, fractions 0..1, e.g. a bottom slab
     * 0 0 0 1 0.5 1, a path 0 0 0 1 0.9375 1): the quad lies on the box's side, the texture coordinates are the box's
     * fractions as vanilla's default face UVs (u along x or z, v from the top on vertical faces), so a slab's side shows
     * the lower half of the texture. Same winding as the full face (JUnit). A null or bad box is the whole cell.
     */
    public static float[][] corners(int bx, int by, int bz, int side, double inset, int ox, int oy, int oz, double[] box) {
        double[] b = box == null || box.length < 6 || !(box[0] < box[3] && box[1] < box[4] && box[2] < box[5]) ? UNIT : box;
        double fx0 = clamp01(b[0]), fy0 = clamp01(b[1]), fz0 = clamp01(b[2]), fx1 = clamp01(b[3]), fy1 = clamp01(b[4]), fz1 = clamp01(b[5]);
        double x0 = bx - ox + fx0, y0 = by - oy + fy0, z0 = bz - oz + fz0, x1 = bx - ox + fx1, y1 = by - oy + fy1, z1 = bz - oz + fz1;
        double vt = 1 - fy1, vb = 1 - fy0;                  // vertical faces: v = 0 at the cell's top
        double[][] c;
        switch (side) {
            case 0 -> { double y = y0 - inset; c = new double[][]{{x0, y, z0, fx0, fz0}, {x1, y, z0, fx1, fz0}, {x1, y, z1, fx1, fz1}, {x0, y, z1, fx0, fz1}}; }
            case 1 -> { double y = y1 + inset; c = new double[][]{{x0, y, z0, fx0, fz0}, {x0, y, z1, fx0, fz1}, {x1, y, z1, fx1, fz1}, {x1, y, z0, fx1, fz0}}; }
            case 2 -> { double z = z0 - inset; c = new double[][]{{x0, y0, z, fx0, vb}, {x0, y1, z, fx0, vt}, {x1, y1, z, fx1, vt}, {x1, y0, z, fx1, vb}}; }
            case 3 -> { double z = z1 + inset; c = new double[][]{{x0, y0, z, fx0, vb}, {x1, y0, z, fx1, vb}, {x1, y1, z, fx1, vt}, {x0, y1, z, fx0, vt}}; }
            case 4 -> { double x = x0 - inset; c = new double[][]{{x, y0, z0, fz0, vb}, {x, y0, z1, fz1, vb}, {x, y1, z1, fz1, vt}, {x, y1, z0, fz0, vt}}; }
            case 5 -> { double x = x1 + inset; c = new double[][]{{x, y0, z0, fz0, vb}, {x, y1, z0, fz0, vt}, {x, y1, z1, fz1, vt}, {x, y0, z1, fz1, vb}}; }
            default -> throw new IllegalArgumentException("side " + side);
        }
        float[][] out = new float[4][5];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 5; j++) out[i][j] = (float) c[i][j];
        return out;
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    /**
     * The quad's front-face normal from its winding (OpenGL's default: counter-clockwise seen from the front, the side
     * vanilla's back-face culling keeps): (v1 - v0) x (v2 - v0), rounded to -1/0/1. For every side it points into the air
     * cell ({@link Shell#OFF}[side]), which is what the dollhouse view relies on (JUnit checks it).
     */
    public static int[] frontNormal(float[][] c) {
        double ax = c[1][0] - c[0][0], ay = c[1][1] - c[0][1], az = c[1][2] - c[0][2];
        double bx = c[2][0] - c[0][0], by = c[2][1] - c[0][1], bz = c[2][2] - c[0][2];
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        return new int[]{(int) Math.signum(Math.round(nx * 1000)), (int) Math.signum(Math.round(ny * 1000)), (int) Math.signum(Math.round(nz * 1000))};
    }

    /**
     * Dollhouse rule (back-face culling): the face {@code side} of the block at bx by bz is drawn only when the camera is
     * on its air side of the face's plane. So from a camera above and beside a tunnel, the floor and the far wall show,
     * the ceiling and the near wall drop out. Pure.
     */
    public static boolean facesCamera(int bx, int by, int bz, int side, double camX, double camY, double camZ) {
        return switch (side) {
            case 0 -> camY < by;
            case 1 -> camY > by + 1;
            case 2 -> camZ < bz;
            case 3 -> camZ > bz + 1;
            case 4 -> camX < bx;
            case 5 -> camX > bx + 1;
            default -> throw new IllegalArgumentException("side " + side);
        };
    }
}
