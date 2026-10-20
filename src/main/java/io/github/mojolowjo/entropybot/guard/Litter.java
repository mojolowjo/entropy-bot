package io.github.mojolowjo.entropybot.guard;

import java.util.List;
import java.util.Locale;

/**
 * aa-litter (director's design 2026-10-20, BRAIN_PLAN B1): every block a job plans to place has a litter price (what
 * leaving it costs, by where it stands) and a cleanup price (what taking it down costs). The option price carries
 * {@code min(litter, cleanup)}; a reuse value (the blocks will be crossed again) can cancel the litter, never more than
 * the cleanup it saves. Facts in, seconds out; pure (JUnit: LitterTest). The facts come from the same sources as
 * {@link BreakPolicy} (areas, the sky, fluids), no new rule set.
 *
 * <p>Litter per block by context: {@link Context#SURFACE} 30 s (sky-visible, or within {@link #NEAR_BUILD} blocks of a
 * known place, an area edge or a player build), {@link Context#CAVE} 5 s (open volume), {@link Context#SEALED} 2 s
 * (underground, no open space), {@link Context#OVER_FLUID} (a bridge over lava or water: no litter number, the bridge is
 * kept only when its reuse covers the whole cleanup), {@link Context#EXEMPT} 0 (scaffold inside the bot's own house or
 * mine box). Unknown facts fail to the highest price ({@code SURFACE}).
 *
 * <p>Loader notes: none (plain Java).
 */
public final class Litter {
    private Litter() {}

    public enum Context { EXEMPT, SURFACE, CAVE, SEALED, OVER_FLUID }

    /** Seconds of litter per block left standing, by context. */
    public static final double SURFACE_S = 30, CAVE_S = 5, SEALED_S = 2;
    /** A block this close to a known place, area edge or player build is on show: surface litter even underground-ish. */
    public static final int NEAR_BUILD = 16, FAR = 9999;
    /** Extra seconds per block to take it down on top of the break time (aim, drop, step). */
    public static final double CLEANUP_EXTRA_S = 0.5;
    /** Time saved by a crossing when the way around is unknown. */
    public static final double UNKNOWN_SAVED_S = 60;
    /** Seconds to place one scaffold block (jump, look, place). */
    public static final double PLACE_S = 0.6;

    /**
     * What the adapter knows about the cell the blocks go to.
     *
     * @param nearBuildDist horizontal blocks to the nearest known place, area edge or player build; -1 = not read (fails
     *                      to the surface price), {@link #FAR} = read and none known
     */
    public record Facts(boolean skyVisible, int nearBuildDist, boolean caveOpen, boolean overFluid, boolean ownBox) {
        public static final Facts UNKNOWN = new Facts(false, -1, false, false, false);
    }

    /** The context, first match wins: own box, over fluid, surface (sky or near a build), cave, sealed; unknown = surface. */
    public static Context contextOf(Facts f) {
        if (f == null) return Context.SURFACE;
        if (f.ownBox()) return Context.EXEMPT;
        if (f.overFluid()) return Context.OVER_FLUID;
        if (f.skyVisible() || (f.nearBuildDist() >= 0 && f.nearBuildDist() <= NEAR_BUILD)) return Context.SURFACE;
        if (f.caveOpen()) return Context.CAVE;
        if (f.nearBuildDist() < 0) return Context.SURFACE;           // nothing known about the neighbourhood: fail to the dearest
        return Context.SEALED;
    }

    /** Litter seconds for one block left standing; over fluid has none of its own (see {@link #price}). */
    public static double perBlock(Context c) {
        return switch (c) {
            case EXEMPT, OVER_FLUID -> 0;
            case SURFACE -> SURFACE_S;
            case CAVE -> CAVE_S;
            case SEALED -> SEALED_S;
        };
    }

    /** Seconds to break one placed block: the stone-class break time of the pickaxe tier (hand 0 .. diamond 4) + the extra. */
    public static double breakS(int tier) {
        return DrownRisk.DRY_S[Math.max(0, Math.min(4, tier))] + CLEANUP_EXTRA_S;
    }

    /** Cleanup: blocks x (break time by tool + 0.5 s) + the walk back to them. */
    public static double cleanupS(int blocks, int tier, double walkBackS) {
        if (blocks <= 0) return 0;
        return blocks * breakS(tier) + Math.max(0, walkBackS);
    }

    /**
     * Reuse value: the expected future crossings (the return leg is 1, plus revisits = the cave sighting's confidence
     * x the ore still to take there) times the seconds a crossing saves against the way around (0 or less = unknown,
     * {@link #UNKNOWN_SAVED_S}). Floored at 0.
     */
    public static double reuseS(double returnLegs, double confidence, double remainingOre, double savedS) {
        double revisits = Math.max(0, confidence) * Math.max(0, remainingOre);
        double crossings = Math.max(0, returnLegs) + revisits;
        double saved = savedS > 0 ? savedS : UNKNOWN_SAVED_S;
        return Math.max(0, crossings * saved);
    }

    /**
     * One priced decision.
     *
     * @param litterS  the net litter: blocks x per-block litter minus the (capped) reuse; for a bridge over fluid the
     *                 cleanup minus the (capped) reuse (zero = worth keeping)
     * @param keep     true: leave the blocks standing and record them as kept; false: take them down at the step's end
     * @param priceS   what the option pays: {@code min(litterS, cleanupS)}
     */
    public record Price(Context ctx, int blocks, double litterS, double cleanupS, double reuseS, double priceS, boolean keep) {
        /** "litter 30 s vs cleanup 6 s" with the reuse and the verdict, for {@code aa why}. */
        public String why() {
            if (blocks <= 0) return "litter 0 s (nothing placed)";
            if (ctx == Context.EXEMPT) return "litter 0 s (my own box)";
            String head = ctx == Context.OVER_FLUID
                    ? String.format(Locale.ROOT, "bridge over fluid: cleanup %.0f s", cleanupS)
                    : String.format(Locale.ROOT, "litter %.0f s vs cleanup %.0f s", litterS, cleanupS);
            return head + (reuseS > 0 ? String.format(Locale.ROOT, ", reuse %.0f s", reuseS) : "") + " (" + ctx.name().toLowerCase(Locale.ROOT) + ", "
                    + (keep ? "keep" : "take down") + ")";
        }
    }

    public static Price price(Facts f, int blocks, int tier, double walkBackS, double reuse) {
        Context c = contextOf(f);
        if (blocks <= 0 || c == Context.EXEMPT) return new Price(c, Math.max(0, blocks), 0, 0, 0, 0, true);
        double cleanup = cleanupS(blocks, tier, walkBackS);
        double reuseCapped = Math.min(Math.max(0, reuse), cleanup);
        if (c == Context.OVER_FLUID) {
            double net = cleanup - reuseCapped;           // cleanup only; kept when its reuse pays the whole cleanup
            return new Price(c, blocks, net, cleanup, reuseCapped, net, net <= 0);
        }
        double leave = Math.max(0, blocks * perBlock(c) - reuseCapped);
        boolean keep = leave < cleanup;
        return new Price(c, blocks, leave, cleanup, reuseCapped, Math.min(leave, cleanup), keep);
    }

    /**
     * What one pick knows about the site, read once: the distance to the nearest known build, my pickaxe tier, the walk
     * back to the blocks, the reuse terms (return legs, the cave sighting's confidence, the ore still to take there, the
     * seconds a crossing saves; 0 = unknown) and whether I stand in my own house or mine box.
     */
    public record Site(int nearBuildDist, int tier, double walkBackS, double returnLegs, double confidence, double remainingOre, double savedS, boolean ownBox) {
        public Price price(Facts at, int blocks) {
            return Litter.price(at, blocks, tier, walkBackS, reuseS(returnLegs, confidence, remainingOre, savedS));
        }
    }

    /** Seconds to place the blocks and then deal with them: the whole price of a scaffold of {@code blocks}. */
    public static double scaffoldS(Price p) {
        return p.blocks() * PLACE_S + p.priceS();
    }

    // ---- a tall tree: take the top logs with a pillar, or skip them ----

    public enum TreeWay { ALL, SKIP_TOP }

    /** Logs out of reach from the ground, the pillar it takes, and the cheaper way. */
    public record TreeTop(TreeWay way, int pillar, int topLogs, double skipS, double pillarS, Price price) {
        public String why() {
            return way == TreeWay.ALL
                    ? String.format(Locale.ROOT, "pillar %d for %d top logs %.0f s (%s) vs skip them %.0f s", pillar, topLogs, pillarS, price.why(), skipS)
                    : String.format(Locale.ROOT, "skip the top %d logs %.0f s vs pillar %d %.0f s (%s)", topLogs, skipS, pillar, pillarS, price.why());
        }
    }

    /** Standing on the ground the bot reaches this many blocks above its feet's level with the trunk in sight. */
    public static final int REACH_UP = 5;
    /** What a log is worth when it has to be found elsewhere (seconds). */
    public static final double LOG_VALUE_S = 4;

    /**
     * A tree whose top log is {@code height} above its base: the logs above {@link #REACH_UP} need a pillar of that many
     * blocks (a block per level, taken down again unless keeping is cheaper by {@link #price}) or are left standing.
     * {@code topLogs} = how many logs sit above the reach (a 1x1 trunk: the same number).
     */
    public static TreeTop tallTree(Facts f, int height, int topLogs, int tier, double walkBackS) {
        int pillar = Math.max(0, height - REACH_UP);
        if (pillar == 0 || topLogs <= 0) return new TreeTop(TreeWay.ALL, 0, 0, 0, 0, price(f, 0, tier, walkBackS, 0));
        Price p = price(f, pillar, tier, walkBackS, 0);
        double pillarS = scaffoldS(p);
        double skipS = topLogs * LOG_VALUE_S;
        return new TreeTop(pillarS <= skipS ? TreeWay.ALL : TreeWay.SKIP_TOP, pillar, topLogs, skipS, pillarS, p);
    }

    // ---- the neighbourhood of a cell: gap to the known boxes ----

    /**
     * Horizontal blocks from (x, z) to the nearest EDGE of the boxes in this dimension (outside: the gap; inside: the
     * way out to the side), {@link #FAR} when there is none. Deep inside a big area is far from its edge.
     */
    public static int nearestBoxGap(List<Box> boxes, String dim, int x, int z) {
        int best = -1;
        if (boxes == null) return FAR;
        for (Box b : boxes) {
            if (b == null || (dim != null && !dim.equals(b.dim))) continue;
            int d;
            if (x >= b.x1 && x <= b.x2 && z >= b.z1 && z <= b.z2) d = Math.min(Math.min(x - b.x1, b.x2 - x), Math.min(z - b.z1, b.z2 - z));
            else d = Math.max(Math.max(0, Math.max(b.x1 - x, x - b.x2)), Math.max(0, Math.max(b.z1 - z, z - b.z2)));
            if (best < 0 || d < best) best = d;
        }
        return best < 0 ? FAR : best;
    }
}
