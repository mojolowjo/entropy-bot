package io.github.mojolowjo.entropybot.threat;

/**
 * 0.24.4 dead-end rule (pure, JUnit on text grids): the cells "behind" the bot are the standing cells it reaches no
 * later than the mob does (a search from each). Fewer than {@link #MIN_CELLS} of them within {@link #RANGE} moves is a
 * dead end: a retreat would corner it (live, 0.24.3: a strip-mine branch). Then the mouth is the bot's side's
 * one-wide cell nearest the mob (at most 2 walkable neighbours: one mob at a time), else the bot's own cell.
 */
public final class DeadEnd {
    private DeadEnd() {}

    public static final int RANGE = 12, MIN_CELLS = 24;

    /** mouth: world {x, y, z} or null. */
    public record Check(boolean deadEnd, int cells, int[] mouth) {}

    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    /** bot and mob in world coordinates. */
    public static Check check(ReachGrid g, int bx, int by, int bz, int mx, int my, int mz) {
        if (g == null) return null;
        int lbx = bx - g.ox, lby = by - g.oy, lbz = bz - g.oz, lmx = mx - g.ox, lmy = my - g.oy, lmz = mz - g.oz;
        if (!g.in(lbx, lby, lbz)) return null;
        int[] fromBot = g.search(lbx, lby, lbz, false);
        int[] fromMob = g.in(lmx, lmy, lmz) ? g.search(lmx, lmy, lmz, false) : null;
        int layer = g.sx * g.sz, cells = 0, best = -1, bestMob = Integer.MAX_VALUE;
        for (int i = 0; i < fromBot.length; i++) {
            int d = fromBot[i];
            if (d < 0 || d > RANGE) continue;
            int dm = fromMob == null ? -1 : fromMob[i];
            if (dm >= 0 && dm < d) continue;            // the mob gets there first: not behind the bot
            cells++;
            int x = i % layer % g.sx, y = i / layer, z = (i % layer) / g.sx;
            if (oneWide(g, x, y, z) && dm >= 0 && dm < bestMob) {
                bestMob = dm;
                best = i;
            }
        }
        if (cells >= MIN_CELLS) return new Check(false, cells, null);
        int[] mouth;
        if (best >= 0) mouth = new int[]{best % layer % g.sx + g.ox, best / layer + g.oy, (best % layer) / g.sx + g.oz};
        else mouth = new int[]{bx, by, bz};
        return new Check(true, cells, mouth);
    }

    /** At most 2 of the 4 neighbours are walkable (same level, one up or one down). */
    static boolean oneWide(ReachGrid g, int x, int y, int z) {
        int n = 0;
        for (int k = 0; k < 4; k++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (g.walkable(x + DX[k], y + dy, z + DZ[k])) {
                    n++;
                    break;
                }
            }
        }
        return n <= 2;
    }
}
