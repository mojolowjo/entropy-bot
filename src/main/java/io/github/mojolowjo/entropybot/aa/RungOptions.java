package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Failures;

import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * S2 (MINING_PLAN 11): the SURFACE and TOOLS rungs' option sets on the {@link Options} framework. Pure (JUnit:
 * RungOptionsTest); the facts come from the adapter ({@code AaRuntime.surfaceFacts / toolsFacts}).
 */
public final class RungOptions {
    private RungOptions() {}

    // ---- SURFACE: out from under ground ----

    public static final String SURFACE_FACTS = "surface.facts";
    /** S2 review (aa40b risk): "nothing" only with an open-sky cell this close (the next rung's walk is short). */
    public static final double NOTHING_SKY = 8;
    public static final double CLIMB_BLOCK = 3, CLIMB_FIX = 10, PILLAR_BLOCK = 1, PILLAR_FIX = 5, SKY_WALK = 2;

    /**
     * @param depth          blocks of ground over the bot, or for a hole open to the sky the levels up to step out
     * @param me             the bot's feet
     * @param shaftOpen      the column above the head is open up to the surface
     * @param sky            the nearest standable open-sky cell (a scan), null none; {@code skyDist} straight-line blocks
     * @param tableFurnaceNext the next rung down is the table/furnace one (its place steps walk to their own spot)
     */
    public record SurfaceFacts(int depth, int[] me, boolean shaftOpen, int throwaway, boolean placeAllowed, boolean pickaxe,
                               int[] sky, double skyDist, boolean tableFurnaceNext,
                               io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit,
                               List<Replan.Cell> column, List<WayUp.Heading> headings) {
        public SurfaceFacts(int depth, int[] me, boolean shaftOpen, int throwaway, boolean placeAllowed, boolean pickaxe,
                            int[] sky, double skyDist, boolean tableFurnaceNext) {
            this(depth, me, shaftOpen, throwaway, placeAllowed, pickaxe, sky, skyDist, tableFurnaceNext, null);
        }

        /** Without the column and heading facts (pillar by the open-shaft flag, the climb verb row). */
        public SurfaceFacts(int depth, int[] me, boolean shaftOpen, int throwaway, boolean placeAllowed, boolean pickaxe,
                            int[] sky, double skyDist, boolean tableFurnaceNext,
                            io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit) {
            this(depth, me, shaftOpen, throwaway, placeAllowed, pickaxe, sky, skyDist, tableFurnaceNext, kit, null, null);
        }
    }

    private static final Pattern SURFACE_END = Pattern.compile("no way up|unreachable|trail back|stuck|no path|no progress|timeout|couldn't|can't|could not|did not start|refused");

    public static final class Surface implements Options.OptionSet<SurfaceFacts> {
        /** AA_ROUND3 1: the SURFACE rung is the one way-up set ({@link WayUp}). */
        @Override public Options.Choice choose(SurfaceFacts f, Options.OptionMemory m, long now, List<Step> planned, double cost, String plannerWhy, String afterFail) {
            return WayUp.choose(f, m, now);
        }

        /** The climb verb's row (no heading facts): priced by ToolWear with a kit, else the old depth x 3 + 10. */
        static void climbRow(List<Options.Option> rows, List<String> dropped, Options.OptionMemory m, long now, SurfaceFacts f, int d) {
            if (f.kit() != null) climb(rows, dropped, m, now, f, d);
            else if (!f.pickaxe()) dropped.add("climb: no pickaxe");
            else add(rows, dropped, m, now, Options.typed("climb", "climb", List.of(new Step.Climb()), d * CLIMB_BLOCK + CLIMB_FIX, d + " up, a staircase", "climb", m));
        }

        /**
         * Tool wear as a cost (ToolWear): the staircase's blocks ({@value ToolWear#STAIR_BLOCKS_PER_UP} per level) priced
         * per way; the climb verb digs with a pickaxe, so USE is the climb, CRAFT is a stone pickaxe's craft and the climb,
         * and HAND has no verb (dropped with its price, so aa why still shows it). Seconds = walk-blocks.
         */
        static void climb(List<Options.Option> rows, List<String> dropped, Options.OptionMemory m, long now, SurfaceFacts f, int d) {
            int blocks = d * io.github.mojolowjo.entropybot.aa.brain.ToolWear.STAIR_BLOCKS_PER_UP;
            for (var p : io.github.mojolowjo.entropybot.aa.brain.ToolWear.price(blocks, f.kit())) {
                String terms = d + " up, a staircase: " + blocks + " blocks, " + p.terms();
                switch (p.way()) {
                    case USE -> add(rows, dropped, m, now, Options.typed("climb", "climb", List.of(new Step.Climb()), p.seconds() + d + CLIMB_FIX, terms, "climb", m));
                    case CRAFT -> add(rows, dropped, m, now, Options.typed("craft+climb", "climb",
                            Options.seq(io.github.mojolowjo.entropybot.aa.brain.ToolWear.prefix(p.way()), new Step.Climb()), p.seconds() + d + CLIMB_FIX, terms, "climb", m));
                    case HAND -> dropped.add("climb by hand " + Math.round(p.seconds()) + "s: the climb verb digs with a pickaxe" + (f.kit().pickaxe() ? "" : " and I have none"));
                }
            }
            if (!f.kit().pickaxe() && f.kit().craftS() == io.github.mojolowjo.entropybot.aa.brain.ToolWear.INF) dropped.add("climb: no pickaxe and nothing to make one");
        }

        @Override public boolean switchable(String end) {
            if (end == null || Options.ownerOrDanger(end)) return false;
            return Failures.classify(end) != null || SURFACE_END.matcher(end.toLowerCase(Locale.ROOT)).find();
        }
    }

    // ---- TOOLS: a stone pickaxe ----

    public static final String TOOLS_FACTS = "tools.facts";
    /** Fixed work estimates (walk-blocks; a minute = Options.MINUTE): the whole bootstrap ~3.3 min, its tools part ~2.5. */
    public static final double BOOTSTRAP_COST = 200, BOOTSTRAP_TOOLS_COST = 150;

    /** @param treesNear a log within 16 (the planner's "trees" fact; its goal plan needs them) */
    public record ToolsFacts(int pickTier, boolean treesNear, boolean pickWanted, boolean swordWanted, int cobble, int sticks, int planks, boolean table) {
        /** The pickaxe facts only (no sword wanted). */
        public ToolsFacts(int pickTier, boolean treesNear) { this(pickTier, treesNear, true, false, 0, 0, 0, false); }

        /** Round 4A N: a stone sword from the bag (2 cobblestone, a stick or 2 planks for one, a table known). */
        public boolean swordMats() { return cobble >= 2 && (sticks >= 1 || planks >= 2) && table; }
    }

    private static final Pattern TOOLS_END = Pattern.compile("no chests|scan base|no free spot|no trees|chopped nothing|no path|no progress|timeout|stuck|couldn't|can't|missing");

    public static final class Tools implements Options.OptionSet<ToolsFacts> {
        @Override public Options.Choice choose(ToolsFacts f, Options.OptionMemory m, long now, List<Step> planned, double cost, String plannerWhy, String afterFail) {
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            // round 4A N: the sword alone when the pickaxe is fine (a zombie seen, no weapon): one craft from the bag
            if (f.swordWanted()) {
                double s = io.github.mojolowjo.entropybot.aa.brain.ToolWear.CRAFT_S;
                if (!f.swordMats()) dropped.add("sword only: no 2 cobblestone + a stick + a table (" + f.cobble() + " cobblestone, " + f.sticks() + " sticks, "
                        + f.planks() + " planks, table " + f.table() + "); the planner's goal makes one");
                else add(rows, dropped, m, now, Options.typed("sword only", "craft", List.of(new Step.Craft("stone_sword", 1)), io.github.mojolowjo.entropybot.aa.brain.Unit.blocks(s),
                        "a stone sword from the bag: craft " + Math.round(s) + " s", "sword only", m));
                if (!f.pickWanted()) {
                    if (planned != null) add(rows, dropped, m, now, Options.typed("planner", "planner", planned, cost * Options.planUnit(), "the planner's stone tools (the sword in it)", "planner", true, m));
                    dropped.add("bootstrap: the pickaxe is fine (the sword alone is wanted)");
                    return Options.Choice.of(rows, dropped, "no way to a sword");
                }
            }
            // the planner's goal stone tools covers a chest's stock too (PlanFacts.addStock: fetch steps)
            if (planned == null) dropped.add("planner: " + (plannerWhy == null ? "no plan" : plannerWhy));
            else add(rows, dropped, m, now, Options.typed("planner", "planner", planned, cost * Options.planUnit(), "the planner's stone tools", "planner", true, m));
            if (f.pickTier() >= 2) dropped.add("bootstrap tools: the stone tools exist (worn: the planner makes new ones)");
            else add(rows, dropped, m, now, Options.typed("bootstrap tools", "bootstrap", List.of(new Step.Bootstrap(true)), BOOTSTRAP_TOOLS_COST,
                    "bootstrap up to the furnace (the rungs do the rest)", "bootstrap tools", m));
            add(rows, dropped, m, now, Options.typed("bootstrap", "bootstrap", List.of(new Step.Bootstrap(false)), BOOTSTRAP_COST, "the whole bootstrap", "bootstrap", m));
            return Options.Choice.of(rows, dropped, "no way to stone tools");
        }

        @Override public boolean switchable(String end) {
            if (end == null || Options.ownerOrDanger(end)) return false;
            return Failures.classify(end) != null || TOOLS_END.matcher(end.toLowerCase(Locale.ROOT)).find();
        }
    }

    /** A row unless its memory key is blocked (then dropped with the reason). */
    static void add(List<Options.Option> rows, List<String> dropped, Options.OptionMemory m, long now, Options.Option o) {
        if (m.blocked(o.memoryKey(), now)) dropped.add(o.id() + ": " + m.reason(o.memoryKey()));
        else rows.add(o);
    }

}
