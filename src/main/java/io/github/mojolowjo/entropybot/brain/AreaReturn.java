package io.github.mojolowjo.entropybot.brain;

import java.util.List;
import java.util.function.Predicate;

/**
 * 0.27.3 {@code area.return}: the bot stands outside every area of its dimension (and the near-me zone), so every job
 * is refused ("I am outside my areas"); the brain walks it back. Pure (JUnit): which area is nearest, which cell a
 * couple of blocks inside its edge is nearest to the bot, and which height in that column the bot can stand on.
 * Loader notes: none (the game side, commands.BrainRuntime, reads the areas and the block states).
 */
public final class AreaReturn {
    private AreaReturn() {}

    /** How far inside the edge the target lies (smaller for a narrow area). */
    public static final int MARGIN = 2;
    /** How far up and down from the bot's level the stand search looks. */
    public static final int Y_RANGE = 24;

    /** A rectangular area (x1 <= x2, z1 <= z2). */
    public record Area(String name, int x1, int z1, int x2, int z2) {}

    /** Where to walk: the area's name, the target column, the flat distance to it. y is filled by {@link #withY}. */
    public record Target(String name, int x, int y, int z, double dist, boolean hasY) {
        public String chain() { return hasY ? "goto " + x + " " + y + " " + z : "goto " + x + " " + z; }
    }

    /** The nearest area's nearest inside cell (MARGIN in from the edge), or null when there is none. */
    public static Target nearest(List<Area> areas, int bx, int bz) {
        Target best = null;
        if (areas == null) return null;
        for (Area a : areas) {
            int mx = Math.min(MARGIN, (a.x2() - a.x1()) / 2), mz = Math.min(MARGIN, (a.z2() - a.z1()) / 2);
            int x = clamp(bx, a.x1() + mx, a.x2() - mx), z = clamp(bz, a.z1() + mz, a.z2() - mz);
            double d = Math.hypot(x - bx, z - bz);
            if (best == null || d < best.dist()) best = new Target(a.name(), x, 0, z, d, false);
        }
        return best;
    }

    /** The target with the stand height nearest to botY (standable: feet cell x y z), or without one when none. */
    public static Target withY(Target t, int botY, Predicate<int[]> standable) {
        if (t == null || standable == null) return t;
        Integer y = standY(t.x(), botY, t.z(), standable);
        return y == null ? t : new Target(t.name(), t.x(), y, t.z(), t.dist(), true);
    }

    /** The standable feet height nearest to botY in the column (up first on a tie), within Y_RANGE; null if none. */
    public static Integer standY(int x, int botY, int z, Predicate<int[]> standable) {
        for (int d = 0; d <= Y_RANGE; d++) {
            if (standable.test(new int[]{x, botY + d, z})) return botY + d;
            if (d > 0 && standable.test(new int[]{x, botY - d, z})) return botY - d;
        }
        return null;
    }

    /**
     * Whether the node fires: the setting on, outside every area and the near-me zone with an area in this dimension,
     * and none of: the owner's own order, escort/guard/defend, the shelter, a park after 3 failed walks.
     */
    public static boolean fires(boolean on, Target t, boolean inside, String ownerJob, String passive, boolean sheltering, String parked) {
        return on && t != null && !inside && ownerJob == null && passive == null && !sheltering && parked == null;
    }

    /** The one whisper per time outside. */
    public static String whisper(String name) {
        return "I am outside my areas - walking back into " + name;
    }

    /** The why/status line while outside: what it does or why it doesn't. */
    public static String why(boolean on, Target t, boolean inside, String ownerJob, String passive, boolean sheltering, String parked) {
        if (inside || t == null) return null;
        if (!on) return "outside my areas, area.return is off";
        if (parked != null) return "outside my areas, the walk back is parked (" + parked + ")";
        if (ownerJob != null) return "outside my areas, but your order runs";
        if (passive != null) return "outside my areas, but " + passive;
        if (sheltering) return "outside my areas, but I am in my shelter";
        return "outside my areas: walking back into " + t.name() + " (" + t.x() + " " + t.z() + ", " + Math.round(t.dist()) + " blocks)";
    }

    private static int clamp(int v, int lo, int hi) { return lo > hi ? (lo + hi) / 2 : Math.max(lo, Math.min(hi, v)); }
}
