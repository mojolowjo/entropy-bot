package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AA_ROUND3 3 (fair play): the ways to <i>find</i> ore when none I have seen is left, as priced options for the ore
 * pickers (the IRON rung's scorer, the owner's ore goals). The unit is the scorer's: walk-blocks (1 s = {@link Unit#WALK_BPS}),
 * here per ore found, so the rows weigh against walking to a seen ore.
 *
 * <table>
 * <tr><th>Option</th><th>When</th><th>Cost per ore</th><th>Runs</th></tr>
 * <tr><td>explore cave</td><td>a known cave within reach</td><td>its distance + 60 s per {@value #CAVE_BLOCKS_MIN} blocks explored,
 *   over the ore seen per minute of caving (learned by Seen, a prior until then)</td><td>{@code mine cave <ore> n}</td></tr>
 * <tr><td>strip at Y</td><td>a mine marked, or my areas here</td><td>the way down to OreStats' best band + the tunnel blocks per ore
 *   ({@value #EXPOSED} cells seen per tunnel block x the learned density there) at my pickaxe's speed</td><td>{@code [place mine x Y z north then] mine strip <ore> n}</td></tr>
 * <tr><td>clear chunk</td><td>the owner's goal only, in my areas</td><td>a 16x16x{@value #SLAB} slab at the best band, over the ores it should hold</td><td>{@code dig ... ores}</td></tr>
 * </table>
 * Pure (JUnit: DiscoveryTest). Loader notes: none (the adapter, AaRuntime, reads Seen, OreStats and the caves).
 */
public final class Discovery {
    private Discovery() {}

    /** Blackboard: goal key -> the ranked options (the adapter refreshes it with the ore goals' facts). */
    public static final String FACT = "aa.oreDiscovery";

    public enum Kind { CAVE, STRIP, CLEAR_CHUNK, EXPLORE }

    public static final double CAVE_BLOCKS_MIN = 16, EXPOSED = 8, DEFAULT_TUNNEL_PER_ORE = 60, MIN_TUNNEL = 4, MAX_TUNNEL = 2000;
    /** AA_ROUND3 5: a seen opening this close is the cheap cave option; the explore walk's cap on blocks per cave expected. */
    public static final int SEEN_CAVE_WITHIN = 64, MAX_WALK_PER_CAVE = 3000, EXPLORE_MINUTES = 5;
    public static final int SLAB = 4;

    /**
     * @param caveDist   blocks to the nearest known cave within reach, -1 none
     * @param caveRate   ore of this family seen per minute of caving (Seen.caveRate, prior included)
     * @param mineMarked a mine is marked (its own Y is used)
     * @param inAreas    I stand in my areas (a mine may be placed here)
     * @param stripWhy   why strip is not possible (null: it is)
     * @param bestY      OreStats' busiest band's middle for the family here, Integer.MIN_VALUE unknown
     * @param density    listed ore per block in that band, -1 unknown
     * @param secPerBlock seconds to dig one stone block with my best pickaxe
     * @param ownerGoal  the owner asked for it (clear chunk is offered)
     * @param downs      AA_ROUND3 4: the staircase down per heading (StairPlan.cells, read by the adapter against my areas
     *                   and the break policy); null = not read (north, the old way)
     */
    public record Facts(int caveDist, double caveRate, boolean mineMarked, boolean inAreas, String stripWhy,
                        int meX, int meY, int meZ, int bestY, double density, double secPerBlock, boolean ownerGoal,
                        List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downs, int[] seenCave, double cavesPer100,
                        double homeDist, double horizonBlocks, long dayTime) {
        /**
         * Owner 2026-10-20 (no explore leash): homeDist = blocks to my base/areas (-1 unknown), horizonBlocks = the
         * horizon in walk-blocks (0: none), dayTime = the world's day time (-1 unknown) for the night weight.
         */
        public Facts(int caveDist, double caveRate, boolean mineMarked, boolean inAreas, String stripWhy,
                     int meX, int meY, int meZ, int bestY, double density, double secPerBlock, boolean ownerGoal,
                     List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downs, int[] seenCave, double cavesPer100) {
            this(caveDist, caveRate, mineMarked, inAreas, stripWhy, meX, meY, meZ, bestY, density, secPerBlock, ownerGoal, downs, seenCave, cavesPer100, -1, 0, -1);
        }

        /**
         * AA_ROUND3 5: seenCave = the nearest cave opening I have seen (Sightings, not exhausted) within
         * {@link #SEEN_CAVE_WITHIN}, null none; cavesPer100 = the learned chance to see one per 100 blocks walked (0: not
         * read, no explore row).
         */
        public Facts(int caveDist, double caveRate, boolean mineMarked, boolean inAreas, String stripWhy,
                     int meX, int meY, int meZ, int bestY, double density, double secPerBlock, boolean ownerGoal,
                     List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downs) {
            this(caveDist, caveRate, mineMarked, inAreas, stripWhy, meX, meY, meZ, bestY, density, secPerBlock, ownerGoal, downs, null, 0);
        }

        public Facts(int caveDist, double caveRate, boolean mineMarked, boolean inAreas, String stripWhy,
                     int meX, int meY, int meZ, int bestY, double density, double secPerBlock, boolean ownerGoal) {
            this(caveDist, caveRate, mineMarked, inAreas, stripWhy, meX, meY, meZ, bestY, density, secPerBlock, ownerGoal, null);
        }
    }

    /**
     * AA_ROUND3 4 (aa-fair: the staircase ran north and its bottom left my areas, so place mine refused): the cheapest
     * heading down by its ground (WayUp.ground: own/foreign breaks x the policy, hazards); one leaving my areas or with a
     * cell the policy denies is dropped with why. Null when every heading is dropped; north when no headings were read.
     */
    static io.github.mojolowjo.entropybot.aa.WayUp.Heading stairHeading(Facts f, List<String> dropped) {
        if (f.downs() == null) return null;
        io.github.mojolowjo.entropybot.aa.WayUp.Heading best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (var h : f.downs()) {
            var g = io.github.mojolowjo.entropybot.aa.WayUp.ground(h);
            if (!g.ok()) {
                dropped.add("strip at Y: stairs " + h.dir() + " " + g.dropWhy());
                continue;
            }
            if (g.cost() < bestCost) {
                bestCost = g.cost();
                best = h;
            }
        }
        return best;
    }

    /** site: where it happens (a seen cave opening), null when not one place (AA_ROUND3 5: the sequencer reads it). */
    public record Opt(Kind kind, double cost, String why, String command, int[] site) {
        public Opt(Kind kind, double cost, String why, String command) { this(kind, cost, why, command, null); }

        public String label() {
            return switch (kind) {
                case CAVE -> "explore cave";
                case STRIP -> "strip at Y";
                case CLEAR_CHUNK -> "clear chunk";
                case EXPLORE -> "explore for a cave";
            };
        }

        public String text() { return label() + " " + Math.round(cost) + " (" + why + ")"; }
    }

    /** The way home's night weight: 1 by day, 2 at dusk (11000..13000), 3 at night (13000..23000); unknown 1. */
    public static double nightWeight(long dayTime) {
        if (dayTime < 0) return 1;
        long t = dayTime % 24000;
        return t >= 13000 && t < 23000 ? 3 : t >= 11000 && t < 13000 ? 2 : 1;
    }

    /** The strip's Y:the best band, never above my feet (unknown: my level), the world's floor kept. */
    public static int stripY(Facts f) {
        if (f.bestY() == Integer.MIN_VALUE) return f.meY();
        return Math.max(-58, Math.min(f.bestY(), f.meY()));
    }

    /** Tunnel blocks per ore at the learned density (default {@value #DEFAULT_TUNNEL_PER_ORE} unknown). */
    public static double tunnelPerOre(double density) {
        if (density <= 0) return DEFAULT_TUNNEL_PER_ORE;
        return Math.max(MIN_TUNNEL, Math.min(MAX_TUNNEL, 1 / (density * EXPOSED)));
    }

    /** The options for one ore family ({@code word} as the mine verb takes it), cheapest first; the ones that can't run go to dropped. */
    public static List<Opt> rank(Facts f, String word, int n, List<String> dropped) {
        List<Opt> out = new ArrayList<>();
        int left = Math.max(1, n);
        double dig = Math.max(0.05, f.secPerBlock());
        double rate = Math.max(0.01, f.caveRate());
        // AA_ROUND3 5: a cave opening I have seen (Sightings) within 64: walk there, then cave from it
        int seenDist = -1;
        if (f.seenCave() != null) {
            int[] c = f.seenCave();
            seenDist = (int) Math.round(Math.sqrt((double) (c[0] - f.meX()) * (c[0] - f.meX()) + (double) (c[1] - f.meY()) * (c[1] - f.meY()) + (double) (c[2] - f.meZ()) * (c[2] - f.meZ())));
            if (seenDist > SEEN_CAVE_WITHIN) seenDist = -1;
        }
        boolean knownFirst = f.caveDist() >= 0 && (seenDist < 0 || f.caveDist() <= seenDist);
        if (knownFirst) {
            double cost = f.caveDist() + Unit.blocks(60 / rate);
            out.add(new Opt(Kind.CAVE, cost, String.format(Locale.ROOT, "a cave %d away, %.2f %s seen per cave-minute, %d blocks explored a minute", f.caveDist(), rate, word, (int) CAVE_BLOCKS_MIN),
                    "mine cave " + word + " " + left));
        } else if (seenDist >= 0) {
            int[] c = f.seenCave();
            double cost = seenDist + Unit.blocks(60 / rate);
            // the cave run walks to the seen opening itself (Mining.startCave: no known cave near, a seen one within 64)
            out.add(new Opt(Kind.CAVE, cost, String.format(Locale.ROOT, "a cave opening I saw at %d %d %d, %d away, %.2f %s seen per cave-minute", c[0], c[1], c[2], seenDist, rate, word),
                    "mine cave " + word + " " + left, c.clone()));
        } else dropped.add("explore cave: no known cave and no cave opening seen within " + SEEN_CAVE_WITHIN);
        // none seen: walk new land until a cave opening comes into view, valued by the learned chance per 100 blocks
        if (f.caveDist() < 0 && seenDist < 0 && f.cavesPer100() > 0) {
            double walk = Math.min(MAX_WALK_PER_CAVE, 100 / f.cavesPer100());
            // no leash: the way home is priced (from about walk past where I am), weighted by the night; past the horizon: dropped
            double night = nightWeight(f.dayTime());
            double home = f.homeDist() < 0 ? 0 : (f.homeDist() + walk) * night;
            double cost = walk + Unit.blocks(60 / rate) + home;
            if (f.horizonBlocks() > 0 && walk + home > f.horizonBlocks())
                dropped.add(String.format(Locale.ROOT, "explore for a cave: the round trip (%.0f out, %.0f home%s) is past the horizon (%.0f)", walk, home, night > 1 ? " at night" : "", f.horizonBlocks()));
            else out.add(new Opt(Kind.EXPLORE, cost, String.format(Locale.ROOT, "no cave seen yet; %.2f cave openings seen per 100 blocks walked, about %.0f blocks to the next, then %.2f %s per cave-minute; the way home %.0f%s",
                    f.cavesPer100(), walk, rate, word, home, night > 1 ? String.format(Locale.ROOT, " (x%.0f: night)", night) : ""), "find cave " + EXPLORE_MINUTES + " then mine cave " + word + " " + left));
        }
        int y = stripY(f);
        int down = Math.max(0, f.meY() - y);
        double per = tunnelPerOre(f.density());
        String dens = f.density() <= 0 ? "density not learned" : String.format(Locale.ROOT, "%.4f per block learned", f.density());
        var stair = f.mineMarked() || down == 0 ? null : stairHeading(f, dropped);
        if (!f.inAreas()) dropped.add("strip at Y: " + (f.stripWhy() == null ? "outside my areas" : f.stripWhy()));
        else if (!f.mineMarked() && down > 0 && f.downs() != null && stair == null) dropped.add("strip at Y: no staircase heading down stays in my areas and may be dug");
        else {
            // the way down: a staircase digs about 2 blocks per level and walks it, plus its heading's ground (AA_ROUND3 4)
            var g = stair == null ? null : io.github.mojolowjo.entropybot.aa.WayUp.ground(stair);
            double descend = down + Unit.blocks(down * 2 * dig) + (g == null ? 0 : Unit.blocks(g.cost()));
            double cost = descend + Unit.blocks(per * 2 * dig);
            // live (aa-fair r1-r3): "place mine" needs a cell to stand in, goto refuses a cell inside stone and "dig stairs"
            // will not start inside a chain: the staircase's own steps (StairPlan, the cheapest heading, no torches) down
            // to the band, then the mine is marked where I stand
            String dir = stair == null ? "north" : stair.dir();
            int[] u = io.github.mojolowjo.entropybot.aa.WayUp.dir(dir);
            StringBuilder sb = new StringBuilder();
            for (String st : io.github.mojolowjo.entropybot.commands.StairPlan.steps(f.meX(), f.meY(), f.meZ(), u[0], u[1], down))
                if (!st.startsWith("place torch")) sb.append(st).append(" then ");
            String cmd = f.mineMarked() ? "mine strip " + word + " " + left : sb + "place mine " + dir + " then mine strip " + word + " " + left;
            out.add(new Opt(Kind.STRIP, cost, String.format(Locale.ROOT, "%s, %s, %.0f tunnel blocks per ore, %.1f s a block%s%s",
                    f.mineMarked() ? "the marked mine" : "Y " + y + (down > 0 ? " (" + down + " down)" : ""), dens, per, dig,
                    f.bestY() == Integer.MIN_VALUE ? ", best band not learned" : "",
                    g == null ? "" : String.format(Locale.ROOT, ", stairs %s ground %.0f s", dir, g.cost())), cmd));
        }
        if (!f.ownerGoal()) dropped.add("clear chunk: the owner's goals only");
        else if (!f.inAreas()) dropped.add("clear chunk: outside my areas");
        else {
            int x0 = Math.floorDiv(f.meX(), 16) * 16, z0 = Math.floorDiv(f.meZ(), 16) * 16;
            int cells = 16 * 16 * SLAB;
            double expect = f.density() > 0 ? f.density() * cells : cells / DEFAULT_TUNNEL_PER_ORE / EXPOSED;
            double cost = (down + Unit.blocks(down * 2 * dig) + Unit.blocks(cells * dig)) / Math.max(0.5, expect);
            out.add(new Opt(Kind.CLEAR_CHUNK, cost, String.format(Locale.ROOT, "%d blocks at Y %d, about %.1f ore in it", cells, y, expect),
                    "dig " + x0 + " " + y + " " + z0 + " " + (x0 + 15) + " " + (y + SLAB - 1) + " " + (z0 + 15) + " ores"));
        }
        out.sort((a, b) -> Double.compare(a.cost(), b.cost()));
        return out;
    }
}
