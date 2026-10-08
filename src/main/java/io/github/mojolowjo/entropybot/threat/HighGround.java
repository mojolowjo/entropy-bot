package io.github.mojolowjo.entropybot.threat;

import java.util.List;

/**
 * 0.25.1 high-ground rule (the owner's play, 2026-10-08: several mobs, he stood on a ledge above a cave mouth and hit
 * them as they came up): with 2+ melee mobs closing in, a standing cell 1..3 above the highest of them with exactly
 * one way up (one neighbour a mob can step or jump in from) within {@link #MAX_MOVES} moves of the bot. The bot
 * holds it and fights what comes up. The dead-end rule's hold wins over it; a losing fight still retreats ("only safer").
 * Pure (JUnit on text grids); loader notes: none.
 */
public final class HighGround {
    private HighGround() {}

    public static final int MAX_MOVES = 6, MIN_MOBS = 2;

    public record Spot(int x, int y, int z, int moves, int above) {
        public String at() { return x + " " + y + " " + z; }
    }

    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    /** mobs: world {x, y, z} of each melee mob closing in. Null when fewer than 2, no grid, or no such cell. */
    public static Spot find(ReachGrid g, int bx, int by, int bz, List<int[]> mobs) {
        if (g == null || mobs == null || mobs.size() < MIN_MOBS) return null;
        int lx = bx - g.ox, ly = by - g.oy, lz = bz - g.oz;
        if (!g.in(lx, ly, lz)) return null;
        int top = Integer.MIN_VALUE;
        for (int[] m : mobs) top = Math.max(top, m[1]);
        int[] dist = g.search(lx, ly, lz, false);
        int layer = g.sx * g.sz;
        Spot best = null;
        for (int i = 0; i < dist.length; i++) {
            int d = dist[i];
            if (d < 0 || d > MAX_MOVES || (best != null && d >= best.moves())) continue;
            int x = i % g.sx, y = i / layer, z = (i % layer) / g.sx;
            int above = y + g.oy - top;
            if (above < 1 || above > 3 || !g.walkable(x, y, z)) continue;
            if (approaches(g, x, y, z) != 1) continue;
            best = new Spot(x + g.ox, y + g.oy, z + g.oz, d, above);
        }
        return best;
    }

    /** How many lower neighbouring cells a walking mob can step up from into this one (grid coordinates): the ways up. */
    static int approaches(ReachGrid g, int x, int y, int z) {
        int n = 0;
        for (int k = 0; k < 4; k++) if (g.walkEdge(x + DX[k], y - 1, z + DZ[k], x, y, z)) n++;
        return n;
    }

    /**
     * Combine with the fight-or-flee verdict: a dead end's HOLD, a HOME (losing, a creeper in a group, low health) and a
     * plain FIGHT with fewer than 2 melee mobs stay; else HOLD on the high ground.
     */
    public static FightOrFlee.Result apply(FightOrFlee.Result r, Spot s, int melee, boolean on) {
        if (r == null || s == null || !on || melee < MIN_MOBS) return r;
        if (r.verdict() == FightOrFlee.Verdict.HOLD || r.verdict() == FightOrFlee.Verdict.HOME) return r;
        return new FightOrFlee.Result(FightOrFlee.Verdict.HOLD, r.margin(), r.loss(), "hold high ground at " + s.at() + " (" + s.above()
                + " above them, one way up) vs " + melee + " mobs: " + r.why());
    }

    public static boolean isHighGround(FightOrFlee.Result r) {
        return r != null && r.verdict() == FightOrFlee.Verdict.HOLD && r.why().startsWith("hold high ground");
    }
}
