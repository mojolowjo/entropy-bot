package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.brain.FailMemory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.36.a8 (MINING_PLAN 3.6, phase 4): the option scorer inside AA's IRON rung. The planner still says <i>what</i>
 * ({@code mine iron N then smelt raw_iron N then craft iron_pickaxe 1 ...}); the scorer replaces the <i>how</i> of the
 * ore step and of the smelt step from the facts the adapter reads ({@link Facts}, a {@code Supplier} on the blackboard
 * under {@link #FACTS}) and from what failed here before ({@link Memory}):
 *
 * <table>
 * <tr><th>Way</th><th>When</th><th>Cost (blocks of walking)</th><th>Runs</th></tr>
 * <tr><td>WALK_KNOWN</td><td>a known ore with a dry open face</td><td>distance x1 sky-open / x2 cave, x1.5 not loaded, + the clear</td><td>{@code mine iron N}</td></tr>
 * <tr><td>DIG_KNOWN</td><td>a buried known ore under at most {@value #DIG_MAX} blocks, not a dig down from in the water</td><td>distance + {@value #DIG_BLOCK} per block + the clear</td><td>{@code mine iron N dig}</td></tr>
 * <tr><td>PILLAR_KNOWN</td><td>a known ore {@value #PILLAR_MIN_UP}+ above me, enough throwaway blocks, placing allowed</td><td>distance + {@value #PLACE_BLOCK} per block up + the clear</td><td>{@code goto <over the ore> then mine iron N dig} (a TRAVEL walk places by the policy's costs: refilling my own shaft is nearly free)</td></tr>
 * <tr><td>CAVE</td><td>a known cave with frontier left</td><td>distance to its entrance + {@value #CAVE_TIME}</td><td>{@code mine cave iron N}</td></tr>
 * <tr><td>STRIP</td><td>a mine marked, inside my areas</td><td>{@value #STRIP_TIME}</td><td>{@code mine strip iron N}</td></tr>
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
 * failure end the rung as before. {@link #why()} is the options line for {@code aa why}. Pure (JUnit: IronOptionsTest).
 * Loader notes: none (the facts come from the adapter, {@code AaRuntime.ironFacts}).
 */
public final class IronOptions {
    private IronOptions() {}

    /** The blackboard fact: a {@code Supplier<Facts>} the adapter sets (none: the planner's chain runs as it is). */
    public static final String FACTS = "iron.facts";
    public static final String FAMILY = "iron";

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
     * @param stripAllowed a mine is marked and I stand in my areas; else {@code stripWhy} says why not
     * @param furnaceDist  blocks to the nearest known furnace (the camp's), -1 none
     */
    public record Facts(List<Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int caveDist,
                        boolean stripAllowed, String stripWhy, int furnaceDist, boolean furnaceInBag, boolean tableInBag, int cobble,
                        io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit, Fair fair,
                        io.github.mojolowjo.entropybot.guard.Litter.Site litter, int rawIron) {
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
        /** The strip option's steps before "mine strip" ("place mine x y z north"), or null. */
        String stripPrefix() {
            for (var o : discovery) if (o.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.STRIP) {
                int i = o.command().indexOf(" then mine strip");     // "[dig stairs north n then ]place mine north"
                return i > 0 ? o.command().substring(0, i) : null;
            }
            return null;
        }

        /** AA_ROUND3 5: no cave known or seen: the explore walk before "mine cave" ("find cave 5"), or null. */
        String cavePrefix() {
            for (var o : discovery) if (o.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.EXPLORE) {
                int i = o.command().indexOf(" then mine cave");
                return i > 0 ? o.command().substring(0, i) : null;
            }
            return null;
        }

        /** AA_ROUND3 5: the seen cave opening the CAVE row walks to, or null. */
        int[] caveSite() {
            for (var o : discovery) if (o.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.CAVE) return o.site();
            return null;
        }
    }

    public static final double CLEAR = 10, DIG_BLOCK = 3, PLACE_BLOCK = 5, CAVE_TIME = 120, STRIP_TIME = 200, STALE = 1.5, CAVE_FACE = 2;
    /**
     * Owner's rule 2026-10-19 (live r2b: iron 17-18 deep never picked): depth is a cost, not a limit. Up to {@link #DIG_MAX}
     * a block costs {@link #DIG_BLOCK}; each block past it {@link #DIG_DEEP_BLOCK} (a deep column: the climb back, the fall
     * and water risk). {@link #DIG_HARD} is the dig verb's own reach (Mining's column dig for the {@code at} form).
     */
    public static final double DIG_DEEP_BLOCK = 6;
    public static final int DIG_HARD = 40;

    /** Blocks a strip tunnel digs for one find (its wear term). */
    public static final int STRIP_BLOCKS = 60;

    /**
     * Tool wear as a cost (ToolWear, director 2026-10-19): the extra seconds the cheapest way (use the pickaxe, craft a
     * new one first, by hand) adds over a fresh stone pickaxe's dig time, which {@link #digCost} already holds. 0 with
     * no kit (tests, unreadable facts).
     */
    static double wear(Facts f, int blocks) {
        if (f.kit() == null) return 0;
        var b = io.github.mojolowjo.entropybot.aa.brain.ToolWear.best(blocks, f.kit());
        return Math.max(0, b.seconds() - blocks * io.github.mojolowjo.entropybot.aa.brain.ToolWear.withTool(io.github.mojolowjo.entropybot.aa.brain.ToolWear.STONE_SPEED));
    }

    static String wearWhy(Facts f, int blocks) {
        if (f.kit() == null) return "";
        return ", tools " + io.github.mojolowjo.entropybot.aa.brain.ToolWear.text(blocks, f.kit()) + " +" + Math.round(wear(f, blocks));
    }

    /** The dig's cost for an ore {@code depth} blocks under the surface (walk-blocks). */
    public static double digCost(int depth) {
        int d = Math.max(1, depth);
        return Math.min(d, DIG_MAX) * DIG_BLOCK + Math.max(0, d - DIG_MAX) * DIG_DEEP_BLOCK;
    }

    /** aa-water: the block WATER_PLACE puts under its stand (the adapter offers PLACE only with cobblestone in the bag). */
    public static final String PLACE_BLOCK_ID = "cobblestone";

    public static final int DIG_MAX = 16, PILLAR_MIN_UP = 3, SMELT_WALK = 24, FURNACE_COBBLE = 8, SMELT_HERE_COST = 10, DEFAULT_N = 3;
    /** Switches within one task before the rung fails as before. */
    public static final int MAX_SWITCHES = Options.MAX_SWITCHES;
    /** A failed option is skipped this long per failure (at most an hour). */
    public static final long RETRY_MS = Options.RETRY_MS, RETRY_MAX_MS = Options.RETRY_MAX_MS;

    /** aa-water: where the way stands before it digs (STAND, PLACE, DRY), with the cell placed into (PLACE), else null. */
    /** {@code after}: the step that ends the way (aa-litter: "restore placed 24" / "restore keep pillar"), null for none. */
    public record Scored(Way way, Ore target, double cost, String why, int[] stand, int[] place, String after) {
        public Scored(Way way, Ore target, double cost, String why) { this(way, target, cost, why, null, null, null); }

        public Scored(Way way, Ore target, double cost, String why, int[] stand, int[] place) { this(way, target, cost, why, stand, place, null); }

        String text() { return way.name().toLowerCase(Locale.ROOT) + (target == null ? "" : " " + target.key()) + " " + Math.round(cost) + " (" + why + ")"; }
    }

    /** The chosen way (null: none can run), its smelt choice, the line for {@code aa why}. */
    public record Choice(Way way, Ore target, Smelt smelt, List<Scored> ranked, List<String> dropped, int[] stand, int[] place, String after) {
        public Choice(Way way, Ore target, Smelt smelt, List<Scored> ranked, List<String> dropped) { this(way, target, smelt, ranked, dropped, null, null, null); }

        public Choice(Way way, Ore target, Smelt smelt, List<Scored> ranked, List<String> dropped, int[] stand, int[] place) { this(way, target, smelt, ranked, dropped, stand, place, null); }

        public String why() {
            StringBuilder b = new StringBuilder();
            if (way == null) b.append("no way to iron");
            else b.append(ranked.get(0).text());
            if (ranked.size() > 1) {
                b.append("; then ");
                for (int i = 1; i < ranked.size() && i < 4; i++) b.append(i > 1 ? ", " : "").append(ranked.get(i).text());
            }
            if (!dropped.isEmpty()) b.append("; dropped: ").append(String.join(", ", dropped));
            b.append("; smelt ").append(smelt.name().toLowerCase(Locale.ROOT));
            return b.toString();
        }

        /**
         * The ore step for the chosen way. AA round 2 item 5: a known ore is one target ({@code mine iron 1 at x y z
         * dig|walk}: that cell and its vein, then the step ends); the rung re-picks for the next one.
         */
        private String tail() { return after == null ? "" : " then " + after; }

        public String command(int n) {
            if (target != null && stand != null && (way == Way.WATER_STAND || way == Way.WATER_PLACE || way == Way.DRY_SIDE)) {
                String at = "goto " + stand[0] + " " + stand[1] + " " + stand[2] + " then mine " + FAMILY + " 1 at " + target.key() + " dig";
                return place != null ? "place " + PLACE_BLOCK_ID + " " + place[0] + " " + place[1] + " " + place[2] + " then " + at : at;
            }
            if (target != null && (way == Way.WALK_KNOWN || way == Way.DIG_KNOWN || way == Way.PILLAR_KNOWN)) {
                String at = "mine " + FAMILY + " 1 at " + target.key() + (way == Way.WALK_KNOWN ? " walk" : " dig");
                return way == Way.PILLAR_KNOWN ? "goto " + target.x() + " " + (target.y() + 1) + " " + target.z() + " then " + at + tail() : at;
            }
            return switch (way) {
                case WALK_KNOWN -> "mine " + FAMILY + " " + n;
                case DIG_KNOWN -> "mine " + FAMILY + " " + n + " dig";
                case PILLAR_KNOWN -> "goto " + target.x() + " " + (target.y() + 1) + " " + target.z() + " then mine " + FAMILY + " " + n + " dig" + tail();
                case CAVE -> "mine cave " + FAMILY + " " + n;
                case STRIP -> "mine strip " + FAMILY + " " + n;
                case WATER_STAND, WATER_PLACE, DRY_SIDE -> "mine " + FAMILY + " " + n + " dig";
            };
        }
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
                    Scored base = scored(way, o, pr.seconds() * WaterWays.WALK_BPS + CLEAR, pr.why() + String.format(Locale.ROOT, ", %.0f s", pr.seconds()), m);
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
                else ranked.add(scored(Way.WALK_KNOWN, o, o.dist() * (o.skyOpen() ? 1 : CAVE_FACE) * (o.loaded() ? 1 : STALE) + CLEAR + floatB,
                        (o.loaded() ? (o.skyOpen() ? "open to the sky" : "an open face in a cave") : "known from before, not loaded")
                                + (floatWhy == null ? "" : ", " + floatWhy + " +" + Math.round(floatB)), m));
            } else if (o.wetDigDown()) dropped.add("dig " + o.key() + ": digging down from in the water" + (o.drownWhy() == null ? "" : " (" + o.drownWhy() + ")"));
            else if (!o.loaded()) dropped.add("dig " + o.key() + ": buried, not loaded");
            else if (o.depth() > DIG_HARD) dropped.add("dig " + o.key() + ": " + o.depth() + " blocks deep (the dig verb reaches " + DIG_HARD + ")");
            else if (m.blocked(Way.DIG_KNOWN, o, now)) dropped.add("dig " + o.key() + ": " + m.reason(Way.DIG_KNOWN, o));
            else ranked.add(scored(Way.DIG_KNOWN, o, o.dist() + digCost(o.depth()) + CLEAR + o.drown() + floatB + wear(f, o.depth()), "buried " + o.depth() + " deep" + wearWhy(f, o.depth())
                    + (floatWhy == null ? "" : ", " + floatWhy + " +" + Math.round(floatB))
                    + (o.depth() > DIG_MAX ? " (+" + Math.round(digCost(o.depth()) - DIG_MAX * DIG_BLOCK) + " past " + DIG_MAX + ")" : "")
                    + (o.drownWhy() == null ? "" : ", " + o.drownWhy() + " +" + Math.round(o.drown())), m));
            int up = o.y() - f.meY();
            if (up >= PILLAR_MIN_UP && above == null) above = o;
            if (up >= PILLAR_MIN_UP && !m.blocked(Way.PILLAR_KNOWN, o, now)) {
                if (!f.placeAllowed()) { if (above == o) dropped.add("pillar " + o.key() + ": placing not allowed here"); }
                else if (f.throwaway() < up) { if (above == o) dropped.add("pillar " + o.key() + ": " + f.throwaway() + " of " + up + " blocks to place"); }
                else {
                    // aa-litter: the scaffold is priced min(litter, cleanup) and the step ends with the matching take-down / keep
                    var lp = litterPrice(f, o, up);
                    double lb = lp == null ? 0 : lp.priceS() * WaterWays.WALK_BPS;
                    Scored sc = scored(Way.PILLAR_KNOWN, o, o.dist() + up * PLACE_BLOCK + CLEAR + lb, up + " up, " + f.throwaway() + " blocks to place" + (lp == null ? "" : ", " + lp.why()), m);
                    ranked.add(new Scored(sc.way(), o, sc.cost(), sc.why(), null, null, lp == null || lp.blocks() <= 0 || lp.ctx() == io.github.mojolowjo.entropybot.guard.Litter.Context.EXEMPT ? null
                            : lp.keep() ? "restore keep pillar" : "restore placed " + io.github.mojolowjo.entropybot.restore.PlacedPlan.DEFAULT_RADIUS));
                }
            } else if (up >= PILLAR_MIN_UP) dropped.add("pillar " + o.key() + ": " + m.reason(Way.PILLAR_KNOWN, o));
        }
        if (f.fair() != null) {
            // AA_ROUND3 3: the discovery ways, priced by what was learned (cave yield, the best band's density)
            for (var d : f.fair().discovery()) {
                Way w = d.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.CAVE || d.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.EXPLORE ? Way.CAVE
                        : d.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.STRIP ? Way.STRIP : null;
                if (w == null) continue;
                if (m.blocked(w, null, now)) dropped.add(d.label() + ": " + m.reason(w, null));
                else ranked.add(scored(w, null, d.cost() + (w == Way.STRIP ? wear(f, STRIP_BLOCKS) : 0), d.label() + ": " + d.why() + (w == Way.STRIP ? wearWhy(f, STRIP_BLOCKS) : ""), m));
            }
            dropped.addAll(f.fair().dropped());
        } else if (f.caveDist() >= 0) {
            if (m.blocked(Way.CAVE, null, now)) dropped.add("cave: " + m.reason(Way.CAVE, null));
            else ranked.add(scored(Way.CAVE, null, f.caveDist() + CAVE_TIME, "a known cave " + f.caveDist() + " away", m));
        } else dropped.add("cave: none known");
        if (f.fair() != null) { /* the strip row came with the discovery ways */ }
        else if (f.stripAllowed()) {
            if (m.blocked(Way.STRIP, null, now)) dropped.add("strip: " + m.reason(Way.STRIP, null));
            else ranked.add(scored(Way.STRIP, null, STRIP_TIME + wear(f, STRIP_BLOCKS), "a mine is marked" + wearWhy(f, STRIP_BLOCKS), m));
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
     * here}); else the camp (the planner's way). Review: a furnace crafted on the spot from cobble is not offered yet (the
     * smelt step would end "no furnace"); the table rung keeps a spare table, a spare furnace is the next step.
     */
    public static Smelt smeltWhere(Facts f, Memory m, long now) {
        if (!f.furnaceInBag() || m.blocked(null, null, now)) return Smelt.CAMP;
        if (f.furnaceDist() < 0) return Smelt.HERE;
        return f.furnaceDist() <= SMELT_WALK ? Smelt.CAMP : Smelt.HERE;
    }

    // ---- the chain rewrite ----

    private static final Pattern MINE_STEP = Pattern.compile("^mine\\s+(?:strip\\s+|cave\\s+)?(?:deepslate_)?iron(?:_ore)?(?:\\s+(\\d+))?\\b.*$");
    private static final Pattern SMELT_STEP = Pattern.compile("^smelt\\s+(?!collect\\b|jobs\\b|mode\\b|forget\\b)\\S+(?:\\s+\\d+)?$");

    /** The planner's steps with the ore step and the smelt step replaced by the choice; unchanged when no step matches. */
    public static String rewrite(List<String> steps, Choice c) { return rewrite(steps, c, false); }

    /** @param afterOre the ore step is done already (a smelt-on-the-spot failure): it is left out */
    public static String rewrite(List<String> steps, Choice c, boolean afterOre) {
        List<String> out = new ArrayList<>();
        for (String s : steps) {
            String t = s.trim();
            Matcher mm = MINE_STEP.matcher(t.toLowerCase(Locale.ROOT));
            if (afterOre && mm.matches()) continue;
            if (c.way() != null && mm.matches()) {
                int n = mm.group(1) == null ? DEFAULT_N : Integer.parseInt(mm.group(1));
                out.add(c.command(n));
                // round 2 item 5: one ore is not the planner's N: the chain ends after it, the rung picks again
                // (the next ore, or the smelt once the bag has enough)
                if (n > 1 && perOre(c)) break;
            } else if (c.smelt() == Smelt.HERE && SMELT_STEP.matcher(t.toLowerCase(Locale.ROOT)).matches()) out.add(t + " here");
            else out.add(t);
        }
        return String.join(" then ", out);
    }

    /** The choice mines one named ore ({@code mine iron 1 at ...}). */
    static boolean perOre(Choice c) {
        return c.target() != null && c.way() != Way.CAVE && c.way() != Way.STRIP;
    }

    private static final Pattern SMELT_IRON = Pattern.compile("^smelt\\s+(?:raw_iron|iron_ingot)(?:\\s+(\\d+))?(?:\\s+here)?$");

    /**
     * aa-trees (STOCK_IRON live: "smelt iron_ingot 16" planned with no raw iron, then "missing 16 raw_iron" at once): a chain
     * that smelts iron the bag lacks, with no ore step and no fetch of raw_iron, gets "mine iron <short>" in front of the smelt,
     * so the ways below price the ore (a seen ore; else, under fair play, explore a cave / strip at the learned Y; their
     * density is OreStats, statistics learned from what was seen, not targeting). Unknown bag (-1) or enough raw iron: as it is.
     */
    static List<String> withOreStep(List<String> steps, Facts f) {
        if (f.rawIron() < 0 || hasMineStep(steps)) return steps;
        for (String s : steps) if (!SMELT_IRON.matcher(s.trim().toLowerCase(Locale.ROOT)).matches() && s.toLowerCase(Locale.ROOT).contains("raw_iron")) return steps;
        for (int i = 0; i < steps.size(); i++) {
            Matcher m = SMELT_IRON.matcher(steps.get(i).trim().toLowerCase(Locale.ROOT));
            if (!m.matches()) continue;
            int n = m.group(1) == null ? DEFAULT_N : Integer.parseInt(m.group(1)), lack = n - f.rawIron();
            if (lack <= 0) return steps;
            List<String> out = new ArrayList<>(steps);
            out.add(i, "mine " + FAMILY + " " + lack);
            return out;
        }
        return steps;
    }

    /** The chain holds an ore step (else the scorer has nothing to choose, the smelt choice still applies). */
    public static boolean hasMineStep(List<String> steps) {
        for (String s : steps) if (MINE_STEP.matcher(s.trim().toLowerCase(Locale.ROOT)).matches()) return true;
        return false;
    }

    public static List<String> split(String chain) { return Options.Chains.split(chain); }

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
        return FailMemory.classify(end) != null || MINE_END.matcher(t).find();
    }


    // ---- the rung's option set and picker (S0: on the generic Options framework) ----

    /**
     * IRON's {@link Options.OptionSet}: every ranked way becomes an option whose command is the planner's chain with the
     * ore step (and the smelt step) rewritten; a smelt-on-the-spot failure goes on at the camp without the ore step.
     */
    public static final class Set implements Options.OptionSet<Facts> {
        private Smelt lastSmelt = Smelt.CAMP;

        @Override public Options.Choice choose(Facts f, Options.OptionMemory om, long now, String planned, double plannedCost, String plannerWhy, String afterFail) {
            if (planned == null) return new Options.Choice(null, List.of(), List.of(), "no plan for iron" + (plannerWhy == null ? "" : ": " + plannerWhy));
            List<String> steps = withOreStep(split(planned), f);
            Choice c = IronOptions.choose(f, new Memory(om), now);
            lastSmelt = c.smelt();
            if (afterFail != null && failedPart(afterFail).equals("smelt")) {       // the ore is mined: the camp smelts
                String rest = rewrite(steps, c, true);
                Options.Option o = rest.isEmpty() ? null : new Options.Option("camp smelt", "smelt", rest, 0, "the ore is mined; on at the camp", "smelt camp", true);
                return new Options.Choice(o, o == null ? List.of() : List.of(o), c.dropped(), c.why());
            }
            if (c.way() == null) {
                if (hasMineStep(steps)) return new Options.Choice(null, List.of(), c.dropped(), c.why());
                Options.Option o = new Options.Option("planner", "planner", rewrite(steps, c), plannedCost * Options.PLAN_UNIT, "the planner's chain, no ore step", "planner", true);
                return new Options.Choice(o, List.of(o), c.dropped(), c.why());
            }
            List<Options.Option> rows = new ArrayList<>();
            int oreIdx = mineStepIndex(steps);
            for (Scored s : c.ranked()) {
                Choice one = new Choice(s.way(), s.target(), c.smelt(), c.ranked(), c.dropped(), s.stand(), s.place());
                String id = s.way().name().toLowerCase(Locale.ROOT) + (s.target() == null ? "" : " " + s.target().key());
                String cmd = rewrite(steps, one);
                // AA_ROUND3 3: strip at the best band with no mine marked: the mine is placed there first
                String pre = f.fair() == null ? null : s.way() == Way.STRIP ? f.fair().stripPrefix() : s.way() == Way.CAVE ? f.fair().cavePrefix() : null;
                if (pre != null) cmd = pre + " then " + cmd;
                rows.add(new Options.Option(id, s.way().name().toLowerCase(Locale.ROOT), cmd, s.cost(), s.why(), Memory.key(s.way(), s.target()), true,
                        returnLeg(s, f, oreIdx), s.target() != null ? io.github.mojolowjo.entropybot.aa.brain.Site.at(s.target().x(), s.target().y(), s.target().z())
                        : s.way() == Way.CAVE && f.fair() != null && f.fair().caveSite() != null
                        ? io.github.mojolowjo.entropybot.aa.brain.Site.at(f.fair().caveSite()[0], f.fair().caveSite()[1], f.fair().caveSite()[2]) : null));
            }
            // aa-replan: ranked by the round trip (go + do + return)
            rows.sort(java.util.Comparator.comparingDouble(Options.Option::total));
            return new Options.Choice(rows.get(0), rows, c.dropped(), Options.Choice.line(rows, c.dropped(), "no way to iron") + "; smelt " + c.smelt().name().toLowerCase(Locale.ROOT));
        }

        @Override public boolean switchable(String end) { return IronOptions.switchable(end); }

        @Override public String blame(Options.Choice last, Options.Option ran, String end) {
            String part = failedPart(end);
            // aa-trees: a smelt that lacks the ore is the ore way's failure, charged to it (held), not to the smelt place
            if (end != null && end.toLowerCase(Locale.ROOT).contains("missing") && end.toLowerCase(Locale.ROOT).contains("raw_iron"))
                return ran == null || ran.way().equals("planner") || ran.way().equals("smelt") ? null : ran.memoryKey();
            if (part.equals("smelt")) return lastSmelt == Smelt.HERE ? Memory.key(null, null) : null;
            if (part.equals("ore")) return ran == null || ran.way().equals("planner") || ran.way().equals("smelt") ? null : ran.memoryKey();
            return null;
        }
    }

    /** The first ore step's index in the steps (0-based), -1 none. */
    static int mineStepIndex(List<String> steps) {
        for (int i = 0; i < steps.size(); i++) if (MINE_STEP.matcher(steps.get(i).trim().toLowerCase(Locale.ROOT)).matches()) return i;
        return -1;
    }

    /**
     * aa-replan: the way back after the ore step. A dig to a buried ore digs its own shaft down (aa380: straight down):
     * pillar it (refill x0.05) when the bag has a block per level, else the stairs/walk; an open face in a cave or a cave
     * walks back its distance; a pillar up drops back down; an ore open to the sky and a strip mine need none here.
     */
    static Replan.Ret returnLeg(Scored s, Facts f, int oreIdx) {
        Ore o = s.target();
        return switch (s.way()) {
            case DIG_KNOWN -> o == null || oreIdx < 0 ? Replan.Ret.NONE : Replan.chooseReturn(Math.max(1, o.depth()), true, f.throwaway(), f.placeAllowed(), 0, oreIdx);
            case WALK_KNOWN -> o == null || o.skyOpen() ? Replan.Ret.NONE : Replan.chooseReturn(0, false, f.throwaway(), f.placeAllowed(), o.dist(), oreIdx);
            case PILLAR_KNOWN -> o == null ? Replan.Ret.NONE : Replan.chooseReturn(0, false, f.throwaway(), f.placeAllowed(), Math.max(0, o.y() - f.meY()), oreIdx < 0 ? -1 : oreIdx + 1);
            case CAVE -> Replan.chooseReturn(0, false, f.throwaway(), f.placeAllowed(), Math.max(0, f.caveDist()), oreIdx);
            case STRIP -> Replan.Ret.NONE;
            case WATER_STAND, WATER_PLACE, DRY_SIDE -> o == null ? Replan.Ret.NONE : Replan.chooseReturn(0, false, f.throwaway(), f.placeAllowed(), o.dist(), oreIdx);
        };
    }

    /**
     * aa-r1fix (round 1: 3x "failed STOCK_IRON: smelt iron_ingot N: missing raw_iron"): the idle stock_iron desire ran the planner's
     * chain bare. The ore step now comes first and is priced through the iron option set (the same ways as the IRON rung).
     * Returns the chain to run; null with {@code why[0]} set when no ore way can run now. No facts: the chain as it is.
     */
    static String idleChain(Object factsSupplier, String planned, long now, String[] why) {
        if (!(factsSupplier instanceof java.util.function.Supplier<?> s)) return planned;
        Object fo;
        try {
            fo = s.get();
        } catch (RuntimeException e) {
            return planned;
        }
        if (!(fo instanceof Facts f)) return planned;
        Options.Choice c = new Set().choose(f, new Options.OptionMemory(), now, planned, 0, null, null);
        if (c.best() == null) {
            why[0] = "no iron option (" + c.why() + ")";
            return null;
        }
        return c.best().command();
    }

    /** The IRON rung's {@link Rung.Picker}: the generic picker over {@link Set}. */
    public static final class Picker extends Options.Picker<Facts> {
        public Picker() { super("IRON", FACTS, Facts.class, new Set()); }

        public Picker(java.util.function.Consumer<String> errors) { super("IRON", FACTS, Facts.class, new Set(), errors); }
    }
}