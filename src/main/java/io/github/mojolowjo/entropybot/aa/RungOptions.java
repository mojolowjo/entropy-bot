package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.brain.FailMemory;

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
     * @param trailEnd       the open-sky end of the trail back (TrailRuntime.retrace), null none; {@code trailLen} its length
     * @param sky            the nearest standable open-sky cell (a scan), null none; {@code skyDist} straight-line blocks
     * @param tableFurnaceNext the next rung down is the table/furnace one (its place steps walk to their own spot)
     */
    public record SurfaceFacts(int depth, int[] me, boolean shaftOpen, int throwaway, boolean placeAllowed, boolean pickaxe,
                               int[] trailEnd, double trailLen, int[] sky, double skyDist, boolean tableFurnaceNext) {}

    private static final Pattern SURFACE_END = Pattern.compile("no way up|unreachable|trail back|stuck|no path|no progress|timeout|couldn't|can't|could not|did not start|refused");

    public static final class Surface implements Options.OptionSet<SurfaceFacts> {
        @Override public Options.Choice choose(SurfaceFacts f, Options.OptionMemory m, long now, String planned, double cost, String plannerWhy, String afterFail) {
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            int d = Math.max(1, f.depth());
            if (!f.pickaxe()) dropped.add("climb: no pickaxe");
            else add(rows, dropped, m, now, Options.scored("climb", "climb", AaLadder.CLIMB, d * CLIMB_BLOCK + CLIMB_FIX, d + " up, a staircase", "climb", false, m));
            if (!f.shaftOpen()) dropped.add("pillar: no open shaft above");
            else if (!f.placeAllowed()) dropped.add("pillar: placing not allowed here");
            else if (f.throwaway() < d) dropped.add("pillar: " + f.throwaway() + " of " + d + " blocks to place");
            else add(rows, dropped, m, now, Options.scored("pillar", "pillar", "goto " + f.me()[0] + " " + (f.me()[1] + d) + " " + f.me()[2],
                    d * PILLAR_BLOCK + PILLAR_FIX, "an open shaft, " + f.throwaway() + " blocks", "pillar", false, m));
            if (f.trailEnd() == null) dropped.add("trail: no trail back to open sky");
            else add(rows, dropped, m, now, Options.scored("trail", "trail", "goto " + xyz(f.trailEnd()), f.trailLen(), "my trail back, " + Math.round(f.trailLen()) + " long", "trail", false, m));
            if (f.sky() == null) dropped.add("sky: no open-sky cell found near");
            else add(rows, dropped, m, now, Options.scored("sky", "goto", "goto " + xyz(f.sky()), f.skyDist() * SKY_WALK, "open sky " + Math.round(f.skyDist()) + " away", "sky", false, m));
            if (!f.tableFurnaceNext()) dropped.add("nothing: the next rung needs open ground itself");
            else if (f.sky() == null || f.skyDist() > NOTHING_SKY) dropped.add("nothing: no open spot within " + Math.round(NOTHING_SKY) + " for the next rung's walk");
            else add(rows, dropped, m, now, new Options.Option("nothing", "nothing", Options.NOTHING, 0, "the table/furnace rung walks to its own spot", "nothing", false));
            return Options.Choice.of(rows, dropped, "no way up");
        }

        @Override public boolean switchable(String end) {
            if (end == null || Options.ownerOrDanger(end)) return false;
            return FailMemory.classify(end) != null || SURFACE_END.matcher(end.toLowerCase(Locale.ROOT)).find();
        }
    }

    // ---- TOOLS: a stone pickaxe ----

    public static final String TOOLS_FACTS = "tools.facts";
    /** Fixed work estimates (walk-blocks; a minute = Options.MINUTE): the whole bootstrap ~3.3 min, its tools part ~2.5. */
    public static final double BOOTSTRAP_COST = 200, BOOTSTRAP_TOOLS_COST = 150;

    /** @param treesNear a log within 16 (the planner's "trees" fact; its goal plan needs them) */
    public record ToolsFacts(int pickTier, boolean treesNear) {}

    private static final Pattern TOOLS_END = Pattern.compile("no chests|scan base|no free spot|no trees|chopped nothing|no path|no progress|timeout|stuck|couldn't|can't|missing");

    public static final class Tools implements Options.OptionSet<ToolsFacts> {
        @Override public Options.Choice choose(ToolsFacts f, Options.OptionMemory m, long now, String planned, double cost, String plannerWhy, String afterFail) {
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            // the planner's goal stone tools covers a chest's stock too (PlanFacts.addStock: fetch steps)
            if (planned == null) dropped.add("planner: " + (plannerWhy == null ? "no plan" : plannerWhy));
            else add(rows, dropped, m, now, Options.scored("planner", "planner", planned, cost * Options.PLAN_UNIT, "goal stone tools", "planner", true, m));
            if (f.pickTier() >= 2) dropped.add("bootstrap tools: the stone tools exist (worn: the planner makes new ones)");
            else add(rows, dropped, m, now, Options.scored("bootstrap tools", "bootstrap", AaLadder.BOOTSTRAP + " tools", BOOTSTRAP_TOOLS_COST,
                    "bootstrap up to the furnace (the rungs do the rest)", "bootstrap tools", false, m));
            add(rows, dropped, m, now, Options.scored("bootstrap", "bootstrap", AaLadder.BOOTSTRAP, BOOTSTRAP_COST, "the whole bootstrap", "bootstrap", false, m));
            return Options.Choice.of(rows, dropped, "no way to stone tools");
        }

        @Override public boolean switchable(String end) {
            if (end == null || Options.ownerOrDanger(end)) return false;
            return FailMemory.classify(end) != null || TOOLS_END.matcher(end.toLowerCase(Locale.ROOT)).find();
        }
    }

    /** A row unless its memory key is blocked (then dropped with the reason). */
    static void add(List<Options.Option> rows, List<String> dropped, Options.OptionMemory m, long now, Options.Option o) {
        if (m.blocked(o.memoryKey(), now)) dropped.add(o.id() + ": " + m.reason(o.memoryKey()));
        else rows.add(o);
    }

    static String xyz(int[] p) { return p[0] + " " + p[1] + " " + p[2]; }
}
