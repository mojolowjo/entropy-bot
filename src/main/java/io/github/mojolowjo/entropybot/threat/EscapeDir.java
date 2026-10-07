package io.github.mojolowjo.entropybot.threat;

/**
 * 0.23.2: which way to sprint from a creeper. Sixteen headings that lead away (at most about 70 degrees off straight
 * away); each one is walked cell by cell on the reach grid with the bot's own moves (1x2 body, step up 1, drop at most
 * 3, never into lava or water) and the longest free run wins, ties to the straighter heading. No grid, or no heading
 * with a run of 2: straight away. Pure Java.
 */
public final class EscapeDir {
    private EscapeDir() {}

    public static final int MAX_RUN = 10;
    static final double MIN_DOT = 0.34;

    /** {dirX, dirZ, run}: a unit direction and the free cells along it (run -1 = no grid, straight away). */
    public static double[] choose(ReachGrid g, int bwx, int bwy, int bwz, double awayX, double awayZ) {
        double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (len < 1e-6) { awayX = 1; awayZ = 0; len = 1; }
        awayX /= len;
        awayZ /= len;
        if (g == null) return new double[]{awayX, awayZ, -1};
        double best = -1, bx = awayX, bz = awayZ;
        int bestRun = -1;
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8;
            double dx = Math.cos(a), dz = Math.sin(a);
            double dot = dx * awayX + dz * awayZ;
            if (dot < MIN_DOT) continue;
            int run = run(g, bwx - g.ox, bwy - g.oy, bwz - g.oz, dx, dz);
            double score = Math.min(run, MAX_RUN) + 2 * dot;
            if (score > best) { best = score; bx = dx; bz = dz; bestRun = run; }
        }
        if (bestRun < 2) return new double[]{awayX, awayZ, bestRun};
        return new double[]{bx, bz, bestRun};
    }

    /** Free cells from grid cell (x, y, z) along (dx, dz), at most {@link #MAX_RUN}. */
    static int run(ReachGrid g, int x, int y, int z, double dx, double dz) {
        int cx = x, cy = y, cz = z, n = 0;
        for (int step = 1; step <= MAX_RUN * 2 && n < MAX_RUN; step++) {
            int nx = (int) Math.floor(x + 0.5 + dx * step * 0.5), nz = (int) Math.floor(z + 0.5 + dz * step * 0.5);
            if (nx == cx && nz == cz) continue;
            if (nx != cx && nz != cz) {                      // a diagonal step: one side must be open too
                if (next(g, cx, cy, cz, nx, cz) == Integer.MIN_VALUE && next(g, cx, cy, cz, cx, nz) == Integer.MIN_VALUE) return n;
            }
            int ny = next(g, cx, cy, cz, nx, nz);
            if (ny == Integer.MIN_VALUE) return n;
            cx = nx;
            cy = ny;
            cz = nz;
            n++;
        }
        return n;
    }

    /** The y the bot ends up at stepping from (cx, cy, cz) to column (nx, nz), or MIN_VALUE when it can't. */
    static int next(ReachGrid g, int cx, int cy, int cz, int nx, int nz) {
        if (!g.in(nx, cy, nz)) return Integer.MIN_VALUE;
        for (int dy : new int[]{0, 1, -1, -2, -3}) {
            int ny = cy + dy;
            if (!g.in(nx, ny, nz) || g.code(nx, ny, nz) == ReachGrid.WATER) continue;
            if (g.walkEdge(cx, cy, cz, nx, ny, nz)) return ny;
            if (dy == 0 && !ReachGrid.pass(g.code(nx, cy, nz))) continue;      // a wall: maybe a step up
        }
        return Integer.MIN_VALUE;
    }
}
