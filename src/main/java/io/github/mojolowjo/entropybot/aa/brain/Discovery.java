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
 * <tr><td>strip at Y</td><td>always (a mine marked, or a staircase down the policy allows)</td><td>the way down to OreStats' best band + the tunnel blocks per ore
 *   ({@value #EXPOSED} cells seen per tunnel block x the learned density there) at my pickaxe's speed</td><td>{@code [place mine x Y z north then] mine strip <ore> n}</td></tr>
 * <tr><td>clear chunk</td><td>the owner's goal only</td><td>a 16x16x{@value #SLAB} slab at the best band, over the ores it should hold</td><td>{@code dig ... ores}</td></tr>
 * </table>
 * Pure (JUnit: DiscoveryTest). Loader notes: none (the adapter, AaRuntime, reads Seen, OreStats and the caves).
 */
public final class Discovery {
    private Discovery() {}

    /** Blackboard: goal key -> the ranked options (the adapter refreshes it with the ore goals' facts). */
    public static final String FACT = "aa.oreDiscovery";

    public enum Kind { CAVE, STRIP, CLEAR_CHUNK, EXPLORE }

    public static double caveBlocksMin() { return Tuning.get("discovery.caveBlocksMin"); }
    public static double exposed() { return Tuning.get("discovery.exposed"); }
    public static double defaultTunnelPerOre() { return Tuning.get("discovery.defaultTunnelPerOre"); }
    public static double minTunnel() { return Tuning.get("discovery.minTunnel"); }
    public static double maxTunnel() { return Tuning.get("discovery.maxTunnel"); }
    /** AA_ROUND3 5: a seen opening this close is the cheap cave option; the explore walk's cap on blocks per cave expected. */
    public static int seenCaveWithin() { return Tuning.i("discovery.seenCaveWithin"); }
    public static int maxWalkPerCave() { return Tuning.i("discovery.maxWalkPerCave"); }
    public static int exploreMinutes() { return Tuning.i("discovery.exploreMinutes"); }
    public static int slab() { return Tuning.i("discovery.slab"); }
    /** aa-switch2: staircase levels per chain (2 steps each; the chain limit is 100 with the routine after it). */
    public static int stairLeg() { return Tuning.i("discovery.stairLeg"); }

    /**
     * @param caveDist   blocks to the nearest known cave within reach, -1 none
     * @param caveRate   ore of this family seen per minute of caving (Seen.caveRate, prior included)
     * @param mineMarked a mine is marked (its own Y is used)
     * @param bestY      OreStats' busiest band's middle for the family here, Integer.MIN_VALUE unknown
     * @param density    listed ore per block in that band, -1 unknown
     * @param secPerBlock seconds to dig one stone block with my best pickaxe
     * @param ownerGoal  the owner asked for it (clear chunk is offered)
     * @param downs      AA_ROUND3 4: the staircase down per heading (StairPlan.cells, read by the adapter against the policy
     *                   and the break policy); null = not read (north, the old way)
     */
    public record Facts(int caveDist, double caveRate, boolean mineMarked,
                        int meX, int meY, int meZ, int bestY, double density, double secPerBlock, boolean ownerGoal,
                        List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downs, int[] seenCave, double cavesPer100,
                        double homeDist, double horizonBlocks, long dayTime) {
        /**
         * Owner 2026-10-20 (no explore leash): homeDist = blocks to my base (-1 unknown), horizonBlocks = the
         * horizon in walk-blocks (0: none), dayTime = the world's day time (-1 unknown) for the night weight.
         */
        public Facts(int caveDist, double caveRate, boolean mineMarked,
                     int meX, int meY, int meZ, int bestY, double density, double secPerBlock, boolean ownerGoal,
                     List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downs, int[] seenCave, double cavesPer100) {
            this(caveDist, caveRate, mineMarked, meX, meY, meZ, bestY, density, secPerBlock, ownerGoal, downs, seenCave, cavesPer100, -1, 0, -1);
        }

        /**
         * AA_ROUND3 5: seenCave = the nearest cave opening I have seen (Sightings, not exhausted) within
         * {@link #SEEN_CAVE_WITHIN}, null none; cavesPer100 = the learned chance to see one per 100 blocks walked (0: not
         * read, no explore row).
         */
        public Facts(int caveDist, double caveRate, boolean mineMarked,
                     int meX, int meY, int meZ, int bestY, double density, double secPerBlock, boolean ownerGoal,
                     List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downs) {
            this(caveDist, caveRate, mineMarked, meX, meY, meZ, bestY, density, secPerBlock, ownerGoal, downs, null, 0);
        }

        public Facts(int caveDist, double caveRate, boolean mineMarked,
                     int meX, int meY, int meZ, int bestY, double density, double secPerBlock, boolean ownerGoal) {
            this(caveDist, caveRate, mineMarked, meX, meY, meZ, bestY, density, secPerBlock, ownerGoal, null);
        }
    }

    /**
     * AA_ROUND3 4: the cheapest heading down by its ground (WayUp.ground: own/foreign breaks x the policy, hazards); one
     * with a cell the policy refuses is dropped with why. Null when every heading is dropped; north when no headings were read.
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
    public record Opt(Kind kind, double cost, String why, List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, int[] site) {
        public Opt {
            steps = List.copyOf(steps);
        }

        public Opt(Kind kind, double cost, String why, List<io.github.mojolowjo.entropybot.aa.moves.Step> steps) { this(kind, cost, why, steps, null); }

        /** The steps' line (logs, aa why). */
        public String command() { return io.github.mojolowjo.entropybot.aa.moves.Step.label(steps); }

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
        long t = dayTime % 24000;  // game fact: a Minecraft day is 24000 ticks
        return t >= 13000 && t < 23000 ? 3 : t >= 11000 && t < 13000 ? 2 : 1;  // game fact: vanilla day time in ticks: 11000 dusk begins, 13000 night, 23000 dawn
    }

    /** The strip's Y:the best band, never above my feet (unknown: my level), the world's floor kept. */
    public static int stripY(Facts f) {
        if (f.bestY() == Integer.MIN_VALUE) return f.meY();
        return Math.max(-Tuning.i("discovery.floorY"), Math.min(f.bestY(), f.meY()));
    }

    /** Tunnel blocks per ore at the learned density (default {@value #DEFAULT_TUNNEL_PER_ORE} unknown). */
    public static double tunnelPerOre(double density) {
        if (density <= 0) return defaultTunnelPerOre();
        return Math.max(minTunnel(), Math.min(maxTunnel(), 1 / (density * exposed())));
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
            if (seenDist > seenCaveWithin()) seenDist = -1;
        }
        boolean knownFirst = f.caveDist() >= 0 && (seenDist < 0 || f.caveDist() <= seenDist);
        if (knownFirst) {
            double cost = f.caveDist() + Unit.blocks(60 / rate);  // structural: seconds per minute
            out.add(new Opt(Kind.CAVE, cost, String.format(Locale.ROOT, "a cave %d away, %.2f %s seen per cave-minute, %d blocks explored a minute", f.caveDist(), rate, word, (int) caveBlocksMin()),
                    List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Mine(word, left, io.github.mojolowjo.entropybot.aa.moves.Step.Mine.How.CAVE))));
        } else if (seenDist >= 0) {
            int[] c = f.seenCave();
            double cost = seenDist + Unit.blocks(60 / rate);  // structural: seconds per minute
            // the cave run walks to the seen opening itself (Mining.startCave: no known cave near, a seen one within 64)
            out.add(new Opt(Kind.CAVE, cost, String.format(Locale.ROOT, "a cave opening I saw at %d %d %d, %d away, %.2f %s seen per cave-minute", c[0], c[1], c[2], seenDist, rate, word),
                    List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Mine(word, left, io.github.mojolowjo.entropybot.aa.moves.Step.Mine.How.CAVE)), c.clone()));
        } else dropped.add("explore cave: no known cave and no cave opening seen within " + seenCaveWithin());
        // none seen: walk new land until a cave opening comes into view, valued by the learned chance per 100 blocks
        if (f.caveDist() < 0 && seenDist < 0 && f.cavesPer100() > 0) {
            double walk = Math.min(maxWalkPerCave(), 100 / f.cavesPer100());  // structural: percent
            // no leash: the way home is priced (from about walk past where I am), weighted by the night; past the horizon: dropped
            double night = nightWeight(f.dayTime());
            double home = f.homeDist() < 0 ? 0 : (f.homeDist() + walk) * night;
            double cost = walk + Unit.blocks(60 / rate) + home;  // structural: seconds per minute
            if (f.horizonBlocks() > 0 && walk + home > f.horizonBlocks())
                dropped.add(String.format(Locale.ROOT, "explore for a cave: the round trip (%.0f out, %.0f home%s) is past the horizon (%.0f)", walk, home, night > 1 ? " at night" : "", f.horizonBlocks()));
            else out.add(new Opt(Kind.EXPLORE, cost, String.format(Locale.ROOT, "no cave seen yet; %.2f cave openings seen per 100 blocks walked, about %.0f blocks to the next, then %.2f %s per cave-minute; the way home %.0f%s",
                    f.cavesPer100(), walk, rate, word, home, night > 1 ? String.format(Locale.ROOT, " (x%.0f: night)", night) : ""), List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.FindCave(exploreMinutes()), new io.github.mojolowjo.entropybot.aa.moves.Step.Mine(word, left, io.github.mojolowjo.entropybot.aa.moves.Step.Mine.How.CAVE))));
        }
        int y = stripY(f);
        int down = Math.max(0, f.meY() - y);
        double per = tunnelPerOre(f.density());
        String dens = f.density() <= 0 ? "density not learned" : String.format(Locale.ROOT, "%.4f per block learned", f.density());
        var stair = f.mineMarked() || down == 0 ? null : stairHeading(f, dropped);
        if (!f.mineMarked() && down > 0 && f.downs() != null && stair == null) dropped.add("strip at Y: no staircase heading down may be dug (the policy or the ground refuses each)");
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
            // aa-switch2 (r2-iron: 17 of 19 pointless): a staircase of 2 steps a level past the chain limit (100) was
            // refused at every start ("123 steps"); a deep band is reached in legs of STAIR_LEG levels, re-decided at each bottom
            boolean leg = !f.mineMarked() && down > stairLeg();
            List<io.github.mojolowjo.entropybot.aa.moves.Step> cmd = new ArrayList<>();
            if (!f.mineMarked()) cmd.add(new io.github.mojolowjo.entropybot.aa.moves.Step.StripLeg(f.meX(), f.meY(), f.meZ(), dir, u[0], u[1], leg ? stairLeg() : down, !leg));
            if (!leg) cmd.add(new io.github.mojolowjo.entropybot.aa.moves.Step.Mine(word, left, io.github.mojolowjo.entropybot.aa.moves.Step.Mine.How.STRIP));
            out.add(new Opt(Kind.STRIP, cost, String.format(Locale.ROOT, "%s, %s, %.0f tunnel blocks per ore, %.1f s a block%s%s",
                    f.mineMarked() ? "the marked mine" : "Y " + y + (down > 0 ? " (" + down + " down)" : ""), dens, per, dig,
                    (f.bestY() == Integer.MIN_VALUE ? ", best band not learned" : "") + (leg ? ", this leg " + stairLeg() + " of " + down + " levels" : ""),
                    g == null ? "" : String.format(Locale.ROOT, ", stairs %s ground %.0f s", dir, g.cost())), cmd));
        }
        if (!f.ownerGoal()) dropped.add("clear chunk: the owner's goals only");
        else {
            int x0 = Math.floorDiv(f.meX(), 16) * 16, z0 = Math.floorDiv(f.meZ(), 16) * 16;  // game fact: a chunk is 16 blocks
            int cells = 16 * 16 * slab();  // game fact: a chunk is 16 blocks
            double expect = f.density() > 0 ? f.density() * cells : cells / defaultTunnelPerOre() / exposed();
            double cost = (down + Unit.blocks(down * 2 * dig) + Unit.blocks(cells * dig)) / Math.max(0.5, expect);
            out.add(new Opt(Kind.CLEAR_CHUNK, cost, String.format(Locale.ROOT, "%d blocks at Y %d, about %.1f ore in it", cells, y, expect),
                    List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.ClearChunk(x0, y, z0, slab()))));
        }
        out.sort((a, b) -> Double.compare(a.cost(), b.cost()));
        return out;
    }
}
