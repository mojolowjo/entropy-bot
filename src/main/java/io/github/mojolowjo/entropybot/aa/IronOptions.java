package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.brain.Failures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.36.a8 (MINING_PLAN 3.6, phase 4), one-planner: the per-ore pricing of the owner's {@code mine <ore> N} verb (Mining's
 * per-ore loop through {@code Goals.pickPriced}). AA's needs no longer use it (the iron need is the chain's: NeedChain).
 * It prices the <i>how</i> of one ore step from the facts the caller reads ({@link Facts}) and from what failed here
 * before ({@link Memory}); the ore's name comes from the owner's order ({@link Choice#steps(String, int)}):
 *
 * <table>
 * <tr><th>Way</th><th>When</th><th>Cost (blocks of walking)</th><th>Runs</th></tr>
 * <tr><td>WALK_KNOWN</td><td>a known ore with a dry open face</td><td>distance x1 sky-open / x2 cave, x1.5 not loaded, + the clear</td><td>{@code mine iron N}</td></tr>
 * <tr><td>DIG_KNOWN</td><td>a buried known ore under at most {@value #DIG_MAX} blocks, not a dig down from in the water</td><td>distance + {@value #DIG_BLOCK} per block + the clear</td><td>{@code mine iron N dig}</td></tr>
 * <tr><td>PILLAR_KNOWN</td><td>a known ore {@value #PILLAR_MIN_UP}+ above me, enough throwaway blocks, placing allowed</td><td>distance + {@value #PLACE_BLOCK} per block up + the clear</td><td>{@code goto <over the ore> then mine iron N dig} (a TRAVEL walk places by the policy's costs: refilling my own shaft is nearly free)</td></tr>
 * <tr><td>CAVE</td><td>a known cave with frontier left</td><td>distance to its entrance + {@value #CAVE_TIME}</td><td>{@code mine cave iron N}</td></tr>
 * <tr><td>STRIP</td><td>a mine marked</td><td>{@value #STRIP_TIME}</td><td>{@code mine strip iron N}</td></tr>
 * </table>
 * Score = cost x (1 + failures of that option here); an option the policy's floor denies, or whose failure is still
 * remembered ({@link #RETRY_MS} per failure), is dropped with its reason. The nearest ores are each an option of their own,
 * so "give up this ore, pick another" is the next row. Smelting: at the camp furnace when it is within {@value #SMELT_WALK},
 * else on the spot ({@code smelt raw_iron N here}: the furnace from the bag, or one crafted from 8 cobblestone with the spare
 * table) when the bag allows it.
 *
 * <p>A task that ends failed is classified ({@link #switchable}): a precondition or a mining end ("none I could reach",
 * "in the water", "no path", "no progress"...) is recorded against its option and the rung switches to the next best at
 * once (at most {@link #MAX_SWITCHES} per task) instead of failing and cooling down; the owner's stop, a fight or a craft
 * failure end the rung as before. Pure (JUnit: IronOptionsTest). Loader notes: none (the facts come from Mining.aaOres).
 */
public final class IronOptions {
    private IronOptions() {}

    /** The blackboard fact name the iron facts once had (kept for the tests' fakes; nothing sets it now). */
    public static final String FACTS = "iron.facts";

    public enum Way { WALK_KNOWN, DIG_KNOWN, PILLAR_KNOWN, CAVE, STRIP, WATER_STAND, WATER_PLACE, DRY_SIDE }

    public enum Smelt { CAMP, HERE }

    /**
     * One known ore as the adapter sees it.
     *
     * @param openFace   a dry air cell touches it (walkable to)
     * @param skyOpen    one of its open neighbours sees the sky
     * @param depth      blocks from the surface down to it (1 = at the surface), -1 unknown (not loaded)
     * @param wetDigDown the water rule: I am in water and would have to dig down to it
     * @param deny       the policy's reason against breaking it under MINE (null = allowed); floor = never
     */
    public record Ore(int x, int y, int z, double dist, boolean loaded, boolean openFace, boolean skyOpen, int depth, boolean wetDigDown, String deny, boolean floor,
                      double drown, String drownWhy, WaterWays.Wet wet) {
        /** aa-costs: wetDigDown now means the drowning risk is fatal; drown is the dig's drowning cost (walk-blocks), drownWhy its terms. */
        public Ore(int x, int y, int z, double dist, boolean loaded, boolean openFace, boolean skyOpen, int depth, boolean wetDigDown, String deny, boolean floor) {
            this(x, y, z, dist, loaded, openFace, skyOpen, depth, wetDigDown, deny, floor, 0, null, null);
        }

        /** aa-water: {@code wet} the ore's water facts (null: a dry ore). */
        public Ore(int x, int y, int z, double dist, boolean loaded, boolean openFace, boolean skyOpen, int depth, boolean wetDigDown, String deny, boolean floor,
                   double drown, String drownWhy) {
            this(x, y, z, dist, loaded, openFace, skyOpen, depth, wetDigDown, deny, floor, drown, drownWhy, null);
        }

        public String key() { return x + " " + y + " " + z; }
    }

    /**
     * @param caveDist     blocks to the nearest known cave entrance with frontier left, -1 none
     * @param stripAllowed a mine is marked; else {@code stripWhy} says why not
     * @param furnaceDist  blocks to the nearest known furnace (the camp's), -1 none
     */
    public record Facts(List<Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int caveDist,
                        boolean stripAllowed, String stripWhy, int furnaceDist, boolean furnaceInBag, boolean tableInBag, int cobble,
                        io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit, Fair fair,
                        io.github.mojolowjo.entropybot.guard.Litter.Site litter, int rawIron, double digBlock) {
        /**
         * aa-times: {@code digBlock} = walk-blocks per stone block dug with my best pickaxe, from the game's break time
         * (GameTimesLive, converted through Unit); this constructor keeps the old constant {@link #DIG_BLOCK} (tests).
         */
        public Facts(List<Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int caveDist,
                     boolean stripAllowed, String stripWhy, int furnaceDist, boolean furnaceInBag, boolean tableInBag, int cobble,
                     io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit, Fair fair,
                     io.github.mojolowjo.entropybot.guard.Litter.Site litter, int rawIron) {
            this(ores, meY, inWater, throwaway, placeAllowed, caveDist, stripAllowed, stripWhy, furnaceDist, furnaceInBag, tableInBag, cobble, kit, fair, litter, rawIron, digBlockV());
        }

        /** The dig term per block: the game's when read, else {@link #DIG_BLOCK}. */
        public double digPerBlock() { return digBlock > 0 && Double.isFinite(digBlock) ? digBlock : digBlockV(); }

        /** aa-trees: -1 = raw_iron in the bag not known (tests): a chain that smelts is taken as it is. */
        public Facts(List<Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int caveDist,
                     boolean stripAllowed, String stripWhy, int furnaceDist, boolean furnaceInBag, boolean tableInBag, int cobble,
                     io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit, Fair fair,
                     io.github.mojolowjo.entropybot.guard.Litter.Site litter) {
            this(ores, meY, inWater, throwaway, placeAllowed, caveDist, stripAllowed, stripWhy, furnaceDist, furnaceInBag, tableInBag, cobble, kit, fair, litter, -1);
        }

        /** aa-litter: null = no litter term (tests, unreadable facts). */
        public Facts(List<Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int caveDist,
                     boolean stripAllowed, String stripWhy, int furnaceDist, boolean furnaceInBag, boolean tableInBag, int cobble,
                     io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit, Fair fair) {
            this(ores, meY, inWater, throwaway, placeAllowed, caveDist, stripAllowed, stripWhy, furnaceDist, furnaceInBag, tableInBag, cobble, kit, fair, null);
        }

        public Facts(List<Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int caveDist,
                     boolean stripAllowed, String stripWhy, int furnaceDist, boolean furnaceInBag, boolean tableInBag, int cobble,
                     io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit) {
            this(ores, meY, inWater, throwaway, placeAllowed, caveDist, stripAllowed, stripWhy, furnaceDist, furnaceInBag, tableInBag, cobble, kit, null, null);
        }

        public Facts(List<Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int caveDist,
                     boolean stripAllowed, String stripWhy, int furnaceDist, boolean furnaceInBag, boolean tableInBag, int cobble) {
            this(ores, meY, inWater, throwaway, placeAllowed, caveDist, stripAllowed, stripWhy, furnaceDist, furnaceInBag, tableInBag, cobble, null, null, null);
        }
    }

    /**
     * AA_ROUND3 3 (aa fair on): the ore cells I have seen ({@link Ore#key()}s; an ore not in it is dropped "not seen")
     * and the discovery options (cave and strip priced by what was learned) in place of the fixed CAVE/STRIP rows. Null
     * in {@link Facts}: today's behaviour.
     */
    public record Fair(java.util.Set<String> seen, List<io.github.mojolowjo.entropybot.aa.brain.Discovery.Opt> discovery, List<String> dropped) {
        /** The strip option's steps before its strip mining (the staircase down and the mine's mark), or null. */
        List<Step> stripPrefix() { return before(io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.STRIP); }

        /** aa-switch2: the strip option is only a staircase leg (the band is deeper than one leg): it runs alone. */
        boolean stripLeg() {
            for (var o : discovery) if (o.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.STRIP)
                return o.steps().stream().noneMatch(s -> s instanceof Step.Mine);
            return false;
        }

        /** AA_ROUND3 5: no cave known or seen: the explore walk before the cave run, or null. */
        List<Step> cavePrefix() { return before(io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.EXPLORE); }

        /** The discovery option's steps before its mine step (all of them for a leg with none), or null when none come first. */
        private List<Step> before(io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind k) {
            for (var o : discovery) if (o.kind() == k) {
                List<Step> out = new ArrayList<>();
                for (Step s : o.steps()) {
                    if (s instanceof Step.Mine) break;
                    out.add(s);
                }
                return out.isEmpty() ? null : out;
            }
            return null;
        }

        /** AA_ROUND3 5: the seen cave opening the CAVE row walks to, or null. */
        int[] caveSite() {
            for (var o : discovery) if (o.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.CAVE) return o.site();
            return null;
        }
    }

    public static double clear() { return Tuning.get("ironOptions.clear"); }
    public static double digBlockV() { return Tuning.get("ironOptions.digBlock"); }
    public static double placeBlock() { return Tuning.get("ironOptions.placeBlock"); }
    public static double caveTime() { return Tuning.get("ironOptions.caveTime"); }
    public static double stripTime() { return Tuning.get("ironOptions.stripTime"); }
    public static double stale() { return Tuning.get("ironOptions.stale"); }
    public static double caveFace() { return Tuning.get("ironOptions.caveFace"); }
    /**
     * Owner's rule 2026-10-19 (live r2b: iron 17-18 deep never picked): depth is a cost, not a limit. Up to {@link #DIG_MAX}
     * a block costs {@link #DIG_BLOCK}; each block past it {@link #DIG_DEEP_BLOCK} (a deep column: the climb back, the fall
     * and water risk). {@link #DIG_HARD} is the dig verb's own reach (Mining's column dig for the {@code at} form).
     */
    public static double digDeepBlock() { return Tuning.get("ironOptions.digDeepBlock"); }
    public static int digHard() { return Tuning.i("ironOptions.digHard"); }

    /** Tunnel cells a strip digs for one find (its wear term counts their blocks: {@link #stripBreaks}). */
    public static int stripBlocks() { return Tuning.i("ironOptions.stripBlocks"); }

    /** 0.39.z64 review 1: the blocks those cells break - each tunnel cell is the fork's TUNNEL_HEIGHT blocks (feet and head). */
    public static int stripBreaks() { return baritone.api.process.IStripProcess.TUNNEL_HEIGHT * stripBlocks(); }

    /**
     * Tool wear as a cost (ToolWear, director 2026-10-19): the extra seconds the cheapest way (use the pickaxe, craft a
     * new one first, by hand) adds over a fresh stone pickaxe's dig time, which {@link #digCost} already holds. 0 with
     * no kit (tests, unreadable facts).
     */
    public static double wear(Facts f, int blocks) {
        if (f.kit() == null) return 0;
        var b = io.github.mojolowjo.entropybot.aa.brain.ToolWear.best(blocks, f.kit());
        // aa-times review: digCost already holds the blocks at the kit's own (live) seconds, so only what the cheapest
        // way adds over that stays here (the wear / replacement, a craft first); never the fixed stone baseline
        // round 4A N (unit mix): ToolWear answers seconds, the option rows are walk-blocks: converted here
        return io.github.mojolowjo.entropybot.aa.brain.Unit.blocks(wearS(f, blocks));
    }

    /** The wear term in seconds (aa why prints both). */
    public static double wearS(Facts f, int blocks) {
        if (f.kit() == null) return 0;
        var b = io.github.mojolowjo.entropybot.aa.brain.ToolWear.best(blocks, f.kit());
        return Math.max(0, b.seconds() - blocks * f.kit().toolSeconds());
    }

    static String wearWhy(Facts f, int blocks) {
        if (f.kit() == null) return "";
        return ", tools " + io.github.mojolowjo.entropybot.aa.brain.ToolWear.text(blocks, f.kit()) + " +" + Math.round(wearS(f, blocks)) + " s = +" + Math.round(wear(f, blocks)) + " blocks";
    }

    /** The dig's cost for an ore {@code depth} blocks under the surface (walk-blocks), at the constant {@link #DIG_BLOCK}. */
    public static double digCost(int depth) { return digCost(depth, digBlockV()); }

    /**
     * aa-times: the same with the game's dig term per block ({@code digBlock} walk-blocks, Facts.digPerBlock); past
     * {@link #DIG_MAX} each block keeps its extra ({@code DIG_DEEP_BLOCK - DIG_BLOCK}: the climb back, the fall and water risk).
     */
    public static double digCost(int depth, double digBlock) {
        int d = Math.max(1, depth);
        double deep = digBlock + (digDeepBlock() - digBlockV());
        return Math.min(d, digMax()) * digBlock + Math.max(0, d - digMax()) * deep;
    }

    /** aa-water: the block WATER_PLACE puts under its stand (the adapter offers PLACE only with cobblestone in the bag). */
    public static final String PLACE_BLOCK_ID = "cobblestone";

    public static int digMax() { return Tuning.i("ironOptions.digMax"); }
    public static int pillarMinUp() { return Tuning.i("ironOptions.pillarMinUp"); }
    public static int smeltWalk() { return Tuning.i("ironOptions.smeltWalk"); }
    public static int furnaceCobble() { return Tuning.i("ironOptions.furnaceCobble"); }
    public static int smeltHereCost() { return Tuning.i("ironOptions.smeltHereCost"); }
    public static int defaultN() { return Tuning.i("ironOptions.defaultN"); }
    /** Switches within one task before the rung fails as before. */
    public static int maxSwitches() { return Options.maxSwitches(); }
    /** A failed option is skipped this long per failure (at most an hour). */
    public static long retryMs() { return Options.retryMs(); }
    public static long retryMaxMs() { return Options.retryMaxMs(); }

    /** aa-water: where the way stands before it digs (STAND, PLACE, DRY), with the cell placed into (PLACE), else null. */
    /** {@code after}: the step that ends the way (aa-litter: take the pillar down or keep it), null for none. */
    public record Scored(Way way, Ore target, double cost, String why, int[] stand, int[] place, Step after) {
        public Scored(Way way, Ore target, double cost, String why) { this(way, target, cost, why, null, null, null); }

        public Scored(Way way, Ore target, double cost, String why, int[] stand, int[] place) { this(way, target, cost, why, stand, place, null); }

        String text() { return way.name().toLowerCase(Locale.ROOT) + (target == null ? "" : " " + target.key()) + " " + Math.round(cost) + " (" + why + ")"; }
    }

    /** The chosen way (null: none can run), its smelt choice, the line for {@code aa why}. */
    public record Choice(Way way, Ore target, Smelt smelt, List<Scored> ranked, List<String> dropped, int[] stand, int[] place, Step after) {
        public Choice(Way way, Ore target, Smelt smelt, List<Scored> ranked, List<String> dropped) { this(way, target, smelt, ranked, dropped, null, null, null); }

        public Choice(Way way, Ore target, Smelt smelt, List<Scored> ranked, List<String> dropped, int[] stand, int[] place) { this(way, target, smelt, ranked, dropped, stand, place, null); }

        public String why() {
            StringBuilder b = new StringBuilder();
            if (way == null) b.append("no way to iron");
            else b.append(ranked.get(0).text());
            if (ranked.size() > 1) {
                b.append("; then ");
                for (int i = 1; i < ranked.size() && i < 4; i++) b.append(i > 1 ? ", " : "").append(ranked.get(i).text());  // structural: display: how many items a text lists
            }
            if (!dropped.isEmpty()) b.append("; dropped: ").append(String.join(", ", dropped));
            b.append("; smelt ").append(smelt.name().toLowerCase(Locale.ROOT));
            return b.toString();
        }

        /**
         * The ore step for the chosen way. AA round 2 item 5: a known ore is one target ({@code mine iron 1 at x y z
         * dig|walk}: that cell and its vein, then the step ends); the rung re-picks for the next one.
         */
        private void tail(List<Step> out) {
            if (after != null) out.add(after);
        }

        /** rm-execution: the ore step for the chosen way as typed steps. */
        public List<Step> steps(String ore, int n) {
            List<Step> out = new ArrayList<>();
            if (target != null && stand != null && (way == Way.WATER_STAND || way == Way.WATER_PLACE || way == Way.DRY_SIDE)) {
                if (place != null) out.add(Step.Build.one(PLACE_BLOCK_ID, place[0], place[1], place[2]));
                out.add(new Step.Walk(stand[0], stand[1], stand[2], 0));
                out.add(new Step.MineAt(ore, target.x(), target.y(), target.z(), true));
                return out;
            }
            if (target != null && (way == Way.WALK_KNOWN || way == Way.DIG_KNOWN || way == Way.PILLAR_KNOWN)) {
                if (way == Way.PILLAR_KNOWN) out.add(new Step.Walk(target.x(), target.y() + 1, target.z(), 0));
                out.add(new Step.MineAt(ore, target.x(), target.y(), target.z(), way != Way.WALK_KNOWN));
                if (way == Way.PILLAR_KNOWN) tail(out);
                return out;
            }
            switch (way) {
                case WALK_KNOWN -> out.add(new Step.Mine(ore, n, Step.Mine.How.KNOWN));
                case DIG_KNOWN, WATER_STAND, WATER_PLACE, DRY_SIDE -> out.add(new Step.Mine(ore, n, Step.Mine.How.DIG));
                case PILLAR_KNOWN -> {
                    out.add(new Step.Walk(target.x(), target.y() + 1, target.z(), 0));
                    out.add(new Step.Mine(ore, n, Step.Mine.How.DIG));
                    tail(out);
                }
                case CAVE -> out.add(new Step.Mine(ore, n, Step.Mine.How.CAVE));
                case STRIP -> out.add(new Step.Mine(ore, n, Step.Mine.How.STRIP));
            }
            return out;
        }

        /** The ore step's label (Step.label of {@link #steps}): logs and tests. */
        public String command(String ore, int n) { return Step.label(steps(ore, n)); }
    }

    // ---- the memory of what failed here ----

    /** (way, target) -> failures, skipped until, the last reason: a view of the generic {@link Options.OptionMemory}. */
    public static final class Memory {
        private final Options.OptionMemory m;

        public Memory() { this(new Options.OptionMemory()); }

        public Memory(Options.OptionMemory m) { this.m = m; }

        /**
         * Round 2 item 5: walk, dig and pillar name their ore ({@code mine iron 1 at x y z}), so a failure is remembered
         * against that ore and the next ore is the next option (was: per way, when {@code mine iron N} picked its own).
         */
        static String key(Way w, Ore o) {
            if (w == null) return "smelt here";
            return w + (o == null ? "" : " " + o.key());
        }

        public void failed(Way w, Ore o, String reason, long now) { m.failed(key(w, o), reason, now); }

        /** Smelting on the spot failed: back to the camp for a while. */
        public void smeltHereFailed(String reason, long now) { failed(null, null, reason, now); }

        public int fails(Way w, Ore o) { return m.fails(key(w, o)); }

        public boolean blocked(Way w, Ore o, long now) { return m.blocked(key(w, o), now); }

        public String reason(Way w, Ore o) { return m.reason(key(w, o)); }

        public int size() { return m.size(); }

        public String describe(long now) { return m.describe(now); }
    }

    // ---- the scorer ----

    public static Choice choose(Facts f, Memory m, long now) {
        List<Scored> ranked = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Ore above = null;
        for (Ore o : f.ores()) {
            if (f.fair() != null && !f.fair().seen().contains(o.key())) { dropped.add(o.key() + ": not seen"); continue; }
            if (o.floor()) { dropped.add(o.key() + ": " + o.deny()); continue; }
            if (o.deny() != null) { dropped.add(o.key() + ": " + o.deny()); continue; }
            // aa-water (1f): a wet ore: the floating dig is priced into walk and dig; standing ways are rows of their own
            double floatB = 0;
            String floatWhy = null;
            if (o.wet() != null) {
                WaterWays.Wet w = o.wet();
                for (WaterWays.Way ww : new WaterWays.Way[]{WaterWays.Way.STAND, WaterWays.Way.PLACE, WaterWays.Way.DRY}) {
                    Way way = ww == WaterWays.Way.STAND ? Way.WATER_STAND : ww == WaterWays.Way.PLACE ? Way.WATER_PLACE : Way.DRY_SIDE;
                    WaterWays.Priced pr = WaterWays.price(ww, w);
                    if (pr == null) continue;
                    if (m.blocked(way, o, now)) { dropped.add(way.name().toLowerCase(Locale.ROOT) + " " + o.key() + ": " + m.reason(way, o)); continue; }
                    Scored base = scored(way, o, pr.seconds() * WaterWays.WALK_BPS + clear(), pr.why() + String.format(Locale.ROOT, ", %.0f s", pr.seconds()), m);
                    ranked.add(new Scored(base.way(), o, base.cost(), base.why(), pr.stand(),
                            ww == WaterWays.Way.PLACE ? new int[]{pr.stand()[0], pr.stand()[1] - 1, pr.stand()[2]} : null));
                }
                if (w.inWater() || w.oreDepth() > 0) {
                    WaterWays.Priced fl = WaterWays.price(WaterWays.Way.FLOAT, w);
                    if (fl == null) { dropped.add("float " + o.key() + ": no air to dig it floating"); continue; }
                    floatB = fl.seconds() * WaterWays.WALK_BPS;
                    floatWhy = fl.why();
                }
            }
            if (o.openFace()) {
                if (m.blocked(Way.WALK_KNOWN, o, now)) dropped.add("walk " + o.key() + ": " + m.reason(Way.WALK_KNOWN, o));
                else ranked.add(scored(Way.WALK_KNOWN, o, o.dist() * (o.skyOpen() ? 1 : caveFace()) * (o.loaded() ? 1 : stale()) + clear() + floatB,
                        (o.loaded() ? (o.skyOpen() ? "open to the sky" : "an open face in a cave") : "known from before, not loaded")
                                + (floatWhy == null ? "" : ", " + floatWhy + " +" + Math.round(floatB)), m));
            } else if (o.wetDigDown()) dropped.add("dig " + o.key() + ": digging down from in the water" + (o.drownWhy() == null ? "" : " (" + o.drownWhy() + ")"));
            else if (!o.loaded()) dropped.add("dig " + o.key() + ": buried, not loaded");
            else if (o.depth() > digHard()) dropped.add("dig " + o.key() + ": " + o.depth() + " blocks deep (the dig verb reaches " + digHard() + ")");
            else if (m.blocked(Way.DIG_KNOWN, o, now)) dropped.add("dig " + o.key() + ": " + m.reason(Way.DIG_KNOWN, o));
            else ranked.add(scored(Way.DIG_KNOWN, o, o.dist() + digCost(o.depth(), f.digPerBlock()) + clear() + o.drown() + floatB + wear(f, o.depth()), "buried " + o.depth() + " deep"
                    + String.format(Locale.ROOT, ", dig %.1f/block", f.digPerBlock()) + wearWhy(f, o.depth())
                    + (floatWhy == null ? "" : ", " + floatWhy + " +" + Math.round(floatB))
                    + (o.depth() > digMax() ? " (+" + Math.round(digCost(o.depth(), f.digPerBlock()) - digMax() * f.digPerBlock()) + " past " + digMax() + ")" : "")
                    + (o.drownWhy() == null ? "" : ", " + o.drownWhy() + " +" + Math.round(o.drown())), m));
            int up = o.y() - f.meY();
            if (up >= pillarMinUp() && above == null) above = o;
            if (up >= pillarMinUp() && !m.blocked(Way.PILLAR_KNOWN, o, now)) {
                if (!f.placeAllowed()) { if (above == o) dropped.add("pillar " + o.key() + ": placing not allowed here"); }
                else if (f.throwaway() < up) { if (above == o) dropped.add("pillar " + o.key() + ": " + f.throwaway() + " of " + up + " blocks to place"); }
                else {
                    // aa-litter: the scaffold is priced min(litter, cleanup) and the step ends with the matching take-down / keep
                    var lp = litterPrice(f, o, up);
                    double lb = lp == null ? 0 : lp.priceS() * WaterWays.WALK_BPS;
                    Scored sc = scored(Way.PILLAR_KNOWN, o, o.dist() + up * placeBlock() + clear() + lb, up + " up, " + f.throwaway() + " blocks to place" + (lp == null ? "" : ", " + lp.why()), m);
                    ranked.add(new Scored(sc.way(), o, sc.cost(), sc.why(), null, null, lp == null || lp.blocks() <= 0 || lp.ctx() == io.github.mojolowjo.entropybot.guard.Litter.Context.EXEMPT ? null
                            : new Step.Restore(lp.keep(), lp.keep() ? 0 : io.github.mojolowjo.entropybot.restore.PlacedPlan.defaultRadius())));
                }
            } else if (up >= pillarMinUp()) dropped.add("pillar " + o.key() + ": " + m.reason(Way.PILLAR_KNOWN, o));
        }
        if (f.fair() != null) {
            // AA_ROUND3 3: the discovery ways, priced by what was learned (cave yield, the best band's density)
            for (var d : f.fair().discovery()) {
                Way w = d.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.CAVE || d.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.EXPLORE ? Way.CAVE
                        : d.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.STRIP ? Way.STRIP : null;
                if (w == null) continue;
                if (m.blocked(w, null, now)) dropped.add(d.label() + ": " + m.reason(w, null));
                else ranked.add(scored(w, null, d.cost() + (w == Way.STRIP ? wear(f, stripBreaks()) : 0), d.label() + ": " + d.why() + (w == Way.STRIP ? wearWhy(f, stripBreaks()) : ""), m));
            }
            dropped.addAll(f.fair().dropped());
        } else if (f.caveDist() >= 0) {
            if (m.blocked(Way.CAVE, null, now)) dropped.add("cave: " + m.reason(Way.CAVE, null));
            else ranked.add(scored(Way.CAVE, null, f.caveDist() + caveTime(), "a known cave " + f.caveDist() + " away", m));
        } else dropped.add("cave: none known");
        if (f.fair() != null) { /* the strip row came with the discovery ways */ }
        else if (!io.github.mojolowjo.entropybot.aa.brain.StripWays.on()) dropped.add("strip: " + io.github.mojolowjo.entropybot.aa.brain.StripWays.OFF);     // 0.39.z78 strip-off
        else if (f.stripAllowed()) {
            if (m.blocked(Way.STRIP, null, now)) dropped.add("strip: " + m.reason(Way.STRIP, null));
            else ranked.add(scored(Way.STRIP, null, stripTime() + wear(f, stripBreaks()), "a mine is marked" + wearWhy(f, stripBreaks()), m));
        } else dropped.add("strip: " + (f.stripWhy() == null ? "not allowed" : f.stripWhy()));
        ranked.sort((a, b) -> Double.compare(a.cost(), b.cost()));
        Smelt smelt = smeltWhere(f, m, now);
        if (ranked.isEmpty()) return new Choice(null, null, smelt, ranked, dropped);
        return new Choice(ranked.get(0).way(), ranked.get(0).target(), smelt, ranked, dropped, ranked.get(0).stand(), ranked.get(0).place(), ranked.get(0).after());
    }

    /**
     * aa-litter: the price of the scaffold of a pillar {@code up} blocks to ore {@code o}: the context at the ore (open to the
     * sky = surface, an open face = cave, else sealed), the cleanup with my pickaxe, the return leg as the reuse. Null without
     * the site facts.
     */
    static io.github.mojolowjo.entropybot.guard.Litter.Price litterPrice(Facts f, Ore o, int up) {
        var s = f.litter();
        if (s == null || up <= 0) return null;
        var at = new io.github.mojolowjo.entropybot.guard.Litter.Facts(o.skyOpen(), s.nearBuildDist(), !o.skyOpen() && o.openFace(), false, s.ownBox());
        return s.price(at, up);
    }

    private static Scored scored(Way w, Ore o, double cost, String why, Memory m) {
        int fails = m.fails(w, o);
        return new Scored(w, o, cost * (1 + fails), fails > 0 ? why + ", failed " + fails + "x" : why);
    }

    /**
     * The camp furnace within {@link #SMELT_WALK}: there; else on the spot when a furnace is in the bag ({@code smelt ...
     * here}); else the camp. Review: a furnace crafted on the spot from cobble is not offered yet (the
     * smelt step would end "no furnace"); the table rung keeps a spare table, a spare furnace is the next step.
     */
    public static Smelt smeltWhere(Facts f, Memory m, long now) {
        if (!f.furnaceInBag() || m.blocked(null, null, now)) return Smelt.CAMP;
        if (f.furnaceDist() < 0) return Smelt.HERE;
        return f.furnaceDist() <= smeltWalk() ? Smelt.CAMP : Smelt.HERE;
    }

    // ---- what failed, and may the rung switch ----

    private static final Pattern MINE_END = Pattern.compile("none i could reach|out of reach|buried|in the water|too deep|no progress|no path|stuck|couldn't get to|"
            + "no known cave|no mine marked|ore known|nothing but water|walked \\d+ block|no .* ore (known|listed)|try \\d+/\\d+|cave .* (done|finished|explored)");

    /** The step the chain stopped at ("stopped at step 2 (mine iron 3 dig): ..."), or null. */
    public static String failedStep(String end) { return Options.Chains.failedStep(end); }

    /** "ore" (the mine/goto step or no step named), "smelt", or "other" (a craft, a fetch...). */
    public static String failedPart(String end) {
        String st = failedStep(end);
        if (st == null) return "ore";
        String w = st.toLowerCase(Locale.ROOT);
        if (w.startsWith("mine") || w.startsWith("goto") || w.startsWith("find") || w.startsWith("place mine") || w.startsWith("dig")) return "ore";      // aa-trees: the discovery prefix steps (find cave, place mine, dig stairs) are the ore way too
        if (w.startsWith("smelt")) return "smelt";
        return "other";
    }

    /** A failure the scorer may answer with another option: a precondition (FailMemory's classes) or a mining end; never the owner's stop or a fight. */
    public static boolean switchable(String end) {
        if (end == null) return false;
        String part = failedPart(end);
        if (part.equals("other")) return false;        // a craft or fetch step: no other way mines it
        String t = end.toLowerCase(Locale.ROOT);
        if (Options.ownerOrDanger(end)) return false;
        if (part.equals("smelt")) return true;         // the smelt on the spot did not work out: the camp furnace is the other way
        return Failures.classify(end) != null || MINE_END.matcher(t).find();
    }
}
