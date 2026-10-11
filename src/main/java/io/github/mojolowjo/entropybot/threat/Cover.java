package io.github.mojolowjo.entropybot.threat;

import java.util.List;
import java.util.Locale;

/**
 * 0.25.1 cover rule (the owner's play, 2026-10-08: shot by a skeleton, he stepped behind a wall until it came close):
 * a ranged attacker (a bow/crossbow mob by kind, or one whose recent hits were arrows) further than
 * {@code threat.coverRange}: move to the nearest standing cell within {@link #MAX_MOVES} moves that is out of its line
 * of sight (both the eye and the chest ray from its eyes blocked by a solid block or a fence), wait there until it is
 * within {@code threat.coverCloseAt} or {@code threat.coverWait} seconds passed, then fight. Never a retreat home.
 * Pure (JUnit on text grids); loader notes: none.
 */
public final class Cover {
    private Cover() {}

    public static final int MAX_MOVES = 6;
    /** A hit of this kind in the last 10 s marks its attacker as ranged. */
    static final long HIT_MS = 10_000;

    /** cell {x, y, z, moves} and the block that hides it {x, y, z}. */
    public record Spot(int x, int y, int z, int moves, int bx, int by, int bz) {
        public String at() { return x + " " + y + " " + z; }
    }

    /** A bow/crossbow mob, or the hit log shows an arrow from this kind in the last 10 s. */
    public static boolean ranged(String kind, List<HitLog.Hit> recent, long nowMs) {
        if (ThreatRules.moveOf(kind) == ThreatRules.Move.RANGED) return true;
        if (recent == null) return false;
        String k = ThreatRules.path(kind);
        for (HitLog.Hit h : recent) {
            if (h == null || nowMs - h.atMs() > HIT_MS) continue;
            String t = h.type() == null ? "" : h.type().toLowerCase(Locale.ROOT);
            if ((t.contains("arrow") || t.contains("trident") || t.contains("projectile")) && k.equals(ThreatRules.path(h.source()))) return true;
        }
        return false;
    }

    /** Take cover now: a ranged attacker, the rule on, further than the range. */
    public static boolean wanted(boolean ranged, double dist, int coverRange) {
        return ranged && coverRange > 0 && dist > coverRange;
    }

    /** In cover: time to fight (it closed in, or the wait ran out). */
    public static boolean over(double dist, long waitedTicks, int closeAt, int waitS) {
        return dist <= closeAt || waitedTicks >= waitS * 20L;
    }

    /**
     * The nearest cell (fewest moves from the bot, then furthest from the mob) a 1x2 body stands in that the mob's eyes
     * at (mx, my, mz) cannot see. Null: no grid, the bot outside it, or nothing within {@link #MAX_MOVES}.
     */
    public static Spot find(ReachGrid g, int bx, int by, int bz, double mx, double my, double mz) {
        if (g == null) return null;
        int lx = bx - g.ox, ly = by - g.oy, lz = bz - g.oz;
        if (!g.in(lx, ly, lz)) return null;
        int[] dist = g.search(lx, ly, lz, false);
        int layer = g.sx * g.sz;
        Spot best = null;
        double bestAway = -1;
        for (int i = 0; i < dist.length; i++) {
            int d = dist[i];
            if (d < 0 || d > MAX_MOVES || (best != null && d > best.moves())) continue;
            int x = i % g.sx, y = i / layer, z = (i % layer) / g.sx;
            if (!g.walkable(x, y, z)) continue;
            double wx = x + g.ox + 0.5, wy = y + g.oy, wz = z + g.oz + 0.5;
            int[] hide = blocker(g, mx, my, mz, wx, wy + 1.6, wz);
            if (hide == null || blocker(g, mx, my, mz, wx, wy + 0.9, wz) == null) continue;
            double away = (wx - mx) * (wx - mx) + (wz - mz) * (wz - mz);
            if (best == null || d < best.moves() || away > bestAway) {
                best = new Spot(x + g.ox, y + g.oy, z + g.oz, d, hide[0], hide[1], hide[2]);
                bestAway = away;
            }
        }
        return best;
    }

    /** The first solid or fence cell on the segment (world coordinates), or null when the view is clear. */
    static int[] blocker(ReachGrid g, double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = Math.max(1, (int) Math.ceil(len / 0.2));
        for (int k = 1; k < n; k++) {
            double t = k / (double) n;
            int x = (int) Math.floor(fx + dx * t), y = (int) Math.floor(fy + dy * t), z = (int) Math.floor(fz + dz * t);
            byte c = g.code(x - g.ox, y - g.oy, z - g.oz);
            if (c == ReachGrid.SOLID || c == ReachGrid.TALL) return new int[]{x, y, z};
        }
        return null;
    }

    /** "take cover from skeleton at x y z (12 away): behind x y z until it closes". */
    public static String line(String kind, int mx, int my, int mz, double dist, Spot s, int closeAt, int waitS) {
        return "take cover from " + ThreatRules.path(kind) + " at " + mx + " " + my + " " + mz + " (" + Math.round(dist) + " away): behind "
                + s.bx() + " " + s.by() + " " + s.bz() + " at " + s.at() + " until it closes (within " + closeAt + ", at most " + waitS + " s)";
    }
}
