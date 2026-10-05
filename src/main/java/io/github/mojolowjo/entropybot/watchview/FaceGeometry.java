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
        double x0 = bx - ox, y0 = by - oy, z0 = bz - oz, x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        double[][] c;
        switch (side) {
            case 0 -> { double y = y0 - inset; c = new double[][]{{x0, y, z0, 0, 0}, {x1, y, z0, 1, 0}, {x1, y, z1, 1, 1}, {x0, y, z1, 0, 1}}; }
            case 1 -> { double y = y1 + inset; c = new double[][]{{x0, y, z0, 0, 0}, {x0, y, z1, 0, 1}, {x1, y, z1, 1, 1}, {x1, y, z0, 1, 0}}; }
            case 2 -> { double z = z0 - inset; c = new double[][]{{x0, y0, z, 0, 1}, {x0, y1, z, 0, 0}, {x1, y1, z, 1, 0}, {x1, y0, z, 1, 1}}; }
            case 3 -> { double z = z1 + inset; c = new double[][]{{x0, y0, z, 0, 1}, {x1, y0, z, 1, 1}, {x1, y1, z, 1, 0}, {x0, y1, z, 0, 0}}; }
            case 4 -> { double x = x0 - inset; c = new double[][]{{x, y0, z0, 0, 1}, {x, y0, z1, 1, 1}, {x, y1, z1, 1, 0}, {x, y1, z0, 0, 0}}; }
            case 5 -> { double x = x1 + inset; c = new double[][]{{x, y0, z0, 0, 1}, {x, y1, z0, 0, 0}, {x, y1, z1, 1, 0}, {x, y0, z1, 1, 1}}; }
            default -> throw new IllegalArgumentException("side " + side);
        }
        float[][] out = new float[4][5];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 5; j++) out[i][j] = (float) c[i][j];
        return out;
    }
}
