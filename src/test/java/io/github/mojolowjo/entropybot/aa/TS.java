package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.List;

/**
 * rm-execution2 test helper: a routine the tests write as the planner would ("craft x 1 then smelt y 2") typed the way the
 * planner's side types it (commands.PlanLines, via FakePort.steps). Tests only; AA never parses text.
 */
public final class TS {
    private TS() {}

    /** The routine as typed steps; a line with no typed step fails the test at once. */
    public static List<Step> s(String routine) {
        List<Step> out = io.github.mojolowjo.entropybot.aa.core.FakePort.steps(routine.replace(" > ", " then "));
        if (out == null) throw new AssertionError("no typed step in: " + routine);
        return out;
    }

    /** The chains' world facts: a world that knows nothing (the adapter installs the real one). */
    public static void world(io.github.mojolowjo.entropybot.aa.core.Blackboard bb) {
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<NeedChain.World>) () -> new NeedChain.World(java.util.Map.of(), false, w -> null));
    }

    /**
     * one-planner: the game's data (TestData.planner, with the pickaxes tag) and a world around the bot: a tree 10 m, stone 16 m,
     * coal ore 20 m, iron ore 25 m; the bag and the stations read from {@code bb.in}. The caller clears TestData after.
     */
    public static void richWorld(io.github.mojolowjo.entropybot.aa.core.Blackboard bb) {
        var d = io.github.mojolowjo.entropybot.aa.brain.TestData.planner();
        for (String m : List.of("wooden", "stone", "iron")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.install();
        java.util.Map<String, NeedChain.Spot> spots = java.util.Map.of("minecraft:oak_log", new NeedChain.Spot(10, 64, 0, 10, "minecraft:oak_log", 64),
                "minecraft:cobblestone", new NeedChain.Spot(0, 60, 16, 16, "minecraft:stone", 200), "minecraft:coal", new NeedChain.Spot(0, 40, 20, 20, "minecraft:coal_ore", 40),
                "minecraft:raw_iron", new NeedChain.Spot(0, 30, 20, 25, "minecraft:iron_ore", 40));
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<NeedChain.World>) () -> new NeedChain.World(bb.in.bag, bb.in.tableKnown, bb.in.furnaceKnown,
                item -> spots.containsKey(item) ? List.of(spots.get(item)) : List.of(), null, null, null, 0));
    }

    /** One need goal ("I need 1 of it") as a need's goal steps. */
    public static List<Step> plan(String goal) { return List.of(new Step.Obtain(goal, 1)); }

    /** The steps with every compound step replaced by its parts (as aa/moves/Steps runs them), as their label. */
    public static String flat(List<Step> steps) {
        List<Step> out = new java.util.ArrayList<>();
        for (Step s : steps) if (s instanceof Step.Compound c) out.addAll(c.parts()); else out.add(s);
        return Step.label(out);
    }

    /** The steps' label (what AA logs). */
    public static String label(List<Step> steps) { return steps == null ? null : Step.label(steps); }
}
