package io.github.mojolowjo.entropybot.threat;

/**
 * 0.23.6 cliff and lava guard for raw run inputs (the creeper sprint, the instant start): the next {@link #AHEAD} cells
 * along the run direction, read from the grid snapshot, must not hold a drop of more than {@link #MAX_DROP} blocks, lava,
 * water or fire. A bad heading is turned (the nearest of +-45, +-90, +-135 degrees that is clear) or the run stops.
 * Pure: the grid is the threat grid ({@link ReachGrid}); fire has no collision box so the caller passes a fire test.
 *
 * <p>Loader notes: none (the grid is filled from vanilla getters in ThreatRuntime).
 */
public final class RunGuard {
    private RunGuard() {}

    public static final int AHEAD = 2, MAX_DROP = 3;

    /** Fire at a world cell (the grid cannot see it: no collision box). */
    public interface Fire { boolean at(int x, int y, int z); }

    /** ok, or why not: "drop 5", "lava", "water", "fire". */
    public record Check(boolean ok, String why, int x, int y, int z) {}

    /** The verdict for a heading: go (the same), turn (to dirX/dirZ), or stop. */
    public record Verdict(String act, double dirX, double dirZ, String why) {
        public boolean go() { return act.equals("go"); }
        public boolean stop() { return act.equals("stop"); }
    }

    static byte code(ReachGrid g, int wx, int wy, int wz) {
        int x = wx - g.ox, y = wy - g.oy, z = wz - g.oz;
        if (!g.in(x, y, z)) return wy < g.oy ? ReachGrid.SOLID : ReachGrid.AIR;
        return g.codes[g.idx(x, y, z)];
    }

    static boolean floor(byte c) { return c == ReachGrid.SOLID || c == ReachGrid.TALL || c == ReachGrid.LOW; }

    /** The cells one and two blocks ahead of (px, pz) at feet level fy, along (dx, dz) (normalised here). */
    public static Check check(ReachGrid g, double px, int fy, double pz, double dx, double dz, Fire fire) {
        if (g == null) return new Check(true, "no grid", 0, 0, 0);
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return new Check(true, "standing", 0, 0, 0);
        dx /= len;
        dz /= len;
        int lastX = (int) Math.floor(px), lastZ = (int) Math.floor(pz), y = fy;
        for (int k = 1; k <= AHEAD; k++) {
            int cx = (int) Math.floor(px + dx * k), cz = (int) Math.floor(pz + dz * k);
            if (cx == lastX && cz == lastZ) continue;
            lastX = cx;
            lastZ = cz;
            byte feet = code(g, cx, y, cz), head = code(g, cx, y + 1, cz);
            if (feet == ReachGrid.LAVA || head == ReachGrid.LAVA) return new Check(false, "lava", cx, y, cz);
            if (feet == ReachGrid.WATER || head == ReachGrid.WATER) return new Check(false, "water", cx, y, cz);
            if (fire != null && (fire.at(cx, y, cz) || fire.at(cx, y + 1, cz))) return new Check(false, "fire", cx, y, cz);
            if (floor(feet)) {
                // a step up (or a wall): the run's jump handles one block, a wall just stops it; never a fall
                if (!floor(head) && code(g, cx, y + 2, cz) != ReachGrid.LAVA) y = y + (feet == ReachGrid.LOW ? 0 : 1);
                else return new Check(true, "wall", cx, y, cz);
                continue;
            }
            // the floor under it
            int drop = 0;
            int yy = y - 1;
            while (drop <= MAX_DROP + 1) {
                byte c = code(g, cx, yy, cz);
                if (c == ReachGrid.LAVA) return new Check(false, "lava", cx, yy, cz);
                if (c == ReachGrid.WATER) return new Check(false, "water", cx, yy, cz);
                if (fire != null && fire.at(cx, yy, cz)) return new Check(false, "fire", cx, yy, cz);
                if (floor(c)) break;
                drop++;
                yy--;
            }
            if (drop > MAX_DROP) return new Check(false, "drop " + drop, cx, y, cz);
            y -= drop;
        }
        return new Check(true, "clear", 0, 0, 0);
    }

    /** Go, turn to the nearest clear heading (+-45, +-90, +-135), or stop. */
    public static Verdict decide(ReachGrid g, double px, int fy, double pz, double dx, double dz, Fire fire) {
        Check c = check(g, px, fy, pz, dx, dz, fire);
        if (c.ok()) return new Verdict("go", dx, dz, c.why());
        for (int deg : new int[]{45, -45, 90, -90, 135, -135}) {
            double r = Math.toRadians(deg), cos = Math.cos(r), sin = Math.sin(r);
            double nx = dx * cos - dz * sin, nz = dx * sin + dz * cos;
            if (check(g, px, fy, pz, nx, nz, fire).ok()) return new Verdict("turn", nx, nz, c.why() + " ahead, turned " + deg);
        }
        return new Verdict("stop", 0, 0, c.why() + " ahead, no clear heading");
    }
}
