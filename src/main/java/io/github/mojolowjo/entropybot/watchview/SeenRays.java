package io.github.mojolowjo.entropybot.watchview;

/**
 * The visible-faces probe's geometry ({@code watch seen}, docs/CAMERA_PLAN.md "Visible-faces idea"): ray directions
 * through the bot's own view (its yaw and pitch, the vertical FOV option, the window's aspect), the voxel walk (DDA,
 * Amanatides and Woo) that finds the first block face a ray meets, and the exact near-field pass that checks every
 * exposed face close to the eye, so nothing next to the bot is missed between rays. Pure Java (JUnit: SeenRaysTest).
 *
 * <p>What a cell does to a ray ({@link Cells}): {@link #PASS} (air, cave air, torches, rails, plants: an empty collision
 * shape and no fluid) lets it on; {@link #WATER} lets it on for at most {@code maxWater} cells; {@link #STOP} (glass,
 * leaves, ice, lava, slabs, fences, chests..., and chunks that are not loaded) ends it and records nothing;
 * {@link #HIT} (a full opaque block: stone, ores, dirt) ends it and records the face it entered.
 */
public final class SeenRays {
    public static final int PASS = 0, WATER = 1, STOP = 2, HIT = 3;
    public static final int MAX_WATER = 4;

    public interface Cells {
        int cell(int x, int y, int z);
    }

    /** A face a ray met: the block x y z, its side (Minecraft order, {@link Shell#OFF}) that faces the ray, at dist blocks. */
    public record Hit(int x, int y, int z, int side, double dist) {
        public int airX() { return x + Shell.OFF[side][0]; }

        public int airY() { return y + Shell.OFF[side][1]; }

        public int airZ() { return z + Shell.OFF[side][2]; }
    }

    public interface Sink {
        void face(int x, int y, int z, int side, double dist);
    }

    private SeenRays() {}

    /** The look vector for a Minecraft yaw (0 = south, +z; 90 = west) and pitch (positive = down), as Entity.calculateViewVector. */
    public static double[] forward(float yaw, float pitch) {
        double f = Math.toRadians(pitch), g = Math.toRadians(-yaw);
        return new double[]{Math.sin(g) * Math.cos(f), -Math.sin(f), Math.cos(g) * Math.cos(f)};
    }

    /** forward, right (on screen) and up (on screen), unit vectors. */
    public static double[][] basis(float yaw, float pitch) {
        double[] f = forward(yaw, pitch);
        double ry = Math.toRadians(yaw);
        double[] r = {-Math.cos(ry), 0, -Math.sin(ry)};
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        return new double[][]{f, r, u};
    }

    /** tan of half the vertical field of view (the FOV option is vertical, in degrees). */
    public static double tanHalf(double vfovDeg) {
        return Math.tan(Math.toRadians(Math.max(1, Math.min(179, vfovDeg)) / 2));
    }

    /** The unit direction through screen point sx, sy (-1..1 each; +sx right, +sy up). */
    public static double[] direction(double[][] basis, double tanV, double aspect, double sx, double sy) {
        double[] f = basis[0], r = basis[1], u = basis[2];
        double a = sx * tanV * aspect, b = sy * tanV;
        double x = f[0] + r[0] * a + u[0] * b, y = f[1] + r[1] * a + u[1] * b, z = f[2] + r[2] * a + u[2] * b;
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }

    /** Columns and rows of a ray grid of about {@code rays} rays with the window's aspect (width / height). */
    public static int[] grid(int rays, double aspect) {
        double a = aspect > 0.05 ? aspect : 16.0 / 9;
        int rows = Math.max(1, (int) Math.round(Math.sqrt(Math.max(1, rays) / a)));
        int cols = Math.max(1, (int) Math.round(Math.max(1, rays) / (double) rows));
        return new int[]{cols, rows};
    }

    /** The screen point of grid cell (col, row) with a jitter of jx, jy in [0,1): inside that cell, so inside the screen. */
    public static double[] jittered(int col, int row, int cols, int rows, double jx, double jy) {
        return new double[]{-1 + 2 * (col + jx) / cols, -1 + 2 * (row + jy) / rows};
    }

    /**
     * A step through the n grid cells that visits each once ((start + i * stride) mod n) and jumps across the screen, so
     * a tick cut short by its time budget leaves out cells spread everywhere, not the last rows. Coprime with n.
     */
    public static int stride(int n) {
        for (int s : new int[]{97, 89, 83, 79, 73, 71, 67, 61, 59, 53}) if (n > s && gcd(s, n) == 1) return s;
        return 1;
    }

    static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    /** True when the point lies inside the view (in front, within the FOV), with a small margin. */
    public static boolean inView(double[][] basis, double tanV, double aspect, double ex, double ey, double ez, double px, double py, double pz) {
        double dx = px - ex, dy = py - ey, dz = pz - ez;
        double[] f = basis[0], r = basis[1], u = basis[2];
        double z = dx * f[0] + dy * f[1] + dz * f[2];
        if (z <= 0.05) return false;
        double x = dx * r[0] + dy * r[1] + dz * r[2], y = dx * u[0] + dy * u[1] + dz * u[2];
        return Math.abs(x) <= z * tanV * aspect * 1.02 && Math.abs(y) <= z * tanV * 1.02;
    }

    /**
     * Walks the ray from the eye along (dx,dy,dz) through the grid and returns the first {@link #HIT} face within
     * maxDist blocks, or null (nothing within range, a {@link #STOP} cell, more than maxWater water cells, or the eye
     * itself inside a solid block). Steps one axis at a time (an exact edge or corner crossing visits a side cell first),
     * so a ray never slips diagonally between two solid blocks.
     */
    public static Hit cast(double ex, double ey, double ez, double dx, double dy, double dz, double maxDist, Cells w, int maxWater) {
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(n > 0) || !(maxDist > 0)) return null;
        dx /= n;
        dy /= n;
        dz /= n;
        int x = (int) Math.floor(ex), y = (int) Math.floor(ey), z = (int) Math.floor(ez);
        int start = w.cell(x, y, z);
        if (start == HIT || start == STOP) return null;
        int water = start == WATER ? 1 : 0;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0, stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0, stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double inf = Double.POSITIVE_INFINITY;
        double tMaxX = stepX > 0 ? (x + 1 - ex) / dx : stepX < 0 ? (ex - x) / -dx : inf;
        double tMaxY = stepY > 0 ? (y + 1 - ey) / dy : stepY < 0 ? (ey - y) / -dy : inf;
        double tMaxZ = stepZ > 0 ? (z + 1 - ez) / dz : stepZ < 0 ? (ez - z) / -dz : inf;
        double tdX = stepX != 0 ? 1 / Math.abs(dx) : inf, tdY = stepY != 0 ? 1 / Math.abs(dy) : inf, tdZ = stepZ != 0 ? 1 / Math.abs(dz) : inf;
        int limit = (int) Math.ceil(maxDist) * 3 + 8;
        for (int i = 0; i < limit; i++) {
            double t;
            int side;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX;
                x += stepX;
                tMaxX += tdX;
                side = stepX > 0 ? 4 : 5;            // entered through its west (-x) or east (+x) face
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY;
                y += stepY;
                tMaxY += tdY;
                side = stepY > 0 ? 0 : 1;            // its bottom or top face
            } else {
                t = tMaxZ;
                z += stepZ;
                tMaxZ += tdZ;
                side = stepZ > 0 ? 2 : 3;            // its north (-z) or south (+z) face
            }
            if (t > maxDist) return null;
            int c = w.cell(x, y, z);
            if (c == HIT) return new Hit(x, y, z, side, t);
            if (c == STOP) return null;
            if (c == WATER && ++water > maxWater) return null;
        }
        return null;
    }

    /** Inset points on a face (centre and four points 0.3 towards its corners), on the face's plane. */
    static double[][] facePoints(int bx, int by, int bz, int side) {
        int[] o = Shell.OFF[side];
        double cx = bx + 0.5 + o[0] * 0.5, cy = by + 0.5 + o[1] * 0.5, cz = bz + 0.5 + o[2] * 0.5;
        double[][] pts = new double[5][];
        pts[0] = new double[]{cx, cy, cz};
        int k = 1;
        for (int a = -1; a <= 1; a += 2)
            for (int b = -1; b <= 1; b += 2) {
                double px = cx, py = cy, pz = cz;
                if (o[0] != 0) { py += a * 0.3; pz += b * 0.3; }
                else if (o[1] != 0) { px += a * 0.3; pz += b * 0.3; }
                else { px += a * 0.3; py += b * 0.3; }
                pts[k++] = new double[]{px, py, pz};
            }
        return pts;
    }

    /**
     * The exact near-field pass: every exposed face (a {@link #HIT} block with a {@link #PASS} or {@link #WATER}
     * neighbour) within radius blocks of the eye that faces the eye, lies in the view and has at least one of five
     * points on it reachable by a ray whose first hit is that very face. Reports each such face once; returns how
     * many. The same visibility rule as the rays, without their gaps.
     */
    public static int near(double ex, double ey, double ez, double[][] basis, double tanV, double aspect, int radius, double maxDist, Cells w, Sink sink) {
        int cx = (int) Math.floor(ex), cy = (int) Math.floor(ey), cz = (int) Math.floor(ez);
        int found = 0;
        double r2 = (radius + 0.5) * (radius + 0.5);
        for (int bx = cx - radius; bx <= cx + radius; bx++)
            for (int by = cy - radius; by <= cy + radius; by++)
                for (int bz = cz - radius; bz <= cz + radius; bz++) {
                    double ddx = bx + 0.5 - ex, ddy = by + 0.5 - ey, ddz = bz + 0.5 - ez;
                    if (ddx * ddx + ddy * ddy + ddz * ddz > r2) continue;
                    if (w.cell(bx, by, bz) != HIT) continue;
                    for (int s = 0; s < 6; s++) {
                        if (!FaceGeometry.facesCamera(bx, by, bz, s, ex, ey, ez)) continue;
                        int[] o = Shell.OFF[s];
                        int nc = w.cell(bx + o[0], by + o[1], bz + o[2]);
                        if (nc != PASS && nc != WATER) continue;
                        for (double[] p : facePoints(bx, by, bz, s)) {
                            if (!inView(basis, tanV, aspect, ex, ey, ez, p[0], p[1], p[2])) continue;
                            double vx = p[0] - ex, vy = p[1] - ey, vz = p[2] - ez;
                            double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
                            if (d > maxDist) continue;
                            Hit h = cast(ex, ey, ez, vx, vy, vz, d + 1e-3, w, MAX_WATER);
                            if (h != null && h.x() == bx && h.y() == by && h.z() == bz && h.side() == s) {
                                sink.face(bx, by, bz, s, d);
                                found++;
                                break;
                            }
                        }
                    }
                }
        return found;
    }
}
