package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.craft.CraftPlanner;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.39.z47 scenarios S60 (no re-find loop) and S61 (smelt fuel from the game's data); S59 is the fork's DigDownGoalTest. */
class Z47ScenarioTest {

    private static final NeedChain.Spot STONE = new NeedChain.Spot(-11, 48, -61, 15, "minecraft:stone");

    @Test
    void s60AnExploreDoesNotEndOnASourceTheOrderDropped() {
        String word = "#stone_tool_materials|coal_ore";
        java.util.function.Function<String, NeedChain.Spot> known = part -> part.equals("#stone_tool_materials") ? STONE : null;
        // not dropped: the explore ends on it (z16)
        assertNotNull(ExploreRules.untilFound(word, known, (p, s) -> null));
        // dropped for the order (its walk answered UNREACHABLE): the explore runs on
        assertNull(ExploreRules.untilFound(word, known, (p, s) -> p.equals("#stone_tool_materials") ? "UNREACHABLE (no path)" : null));
    }

    @Test
    void s60NoRestartStormOneDropOneExploreThatRunsOn() {
        // a minute in 1 s ticks, the known stone dropped and no other stone: the explore starts once and runs on (its minutes),
        // never ending on the dropped source; run H started it 374 times in 3 min
        java.util.function.Function<String, NeedChain.Spot> known = part -> part.equals("#stone_tool_materials") ? STONE : null;
        int starts = 0;
        boolean running = false;
        long bareAt = 0;
        for (long s = 0; s < 60; s++) {
            long now = 1_000_000 + s * 1000;
            if (!running && Goals.exploreWaitS(bareAt, now, 20) == 0) {
                starts++;
                running = true;
            }
            if (running && ExploreRules.untilFound("#stone_tool_materials", known, (p, sp) -> "dropped") != null) running = false;
        }
        assertTrue(starts < 3, "explore starts in a minute: " + starts);
        assertEquals(1, starts);
    }

    @Test
    void s60ABareExploreWaitsRetrySecondsBeforeTheNext() {
        long ended = 5_000_000;
        assertEquals(20, Goals.exploreWaitS(ended, ended, 20));
        assertEquals(1, Goals.exploreWaitS(ended, ended + 19_500, 20));
        assertEquals(0, Goals.exploreWaitS(ended, ended + 20_000, 20));
        assertEquals(0, Goals.exploreWaitS(0, ended, 20), "no bare explore yet");
        // with explores that end bare at once, at most one start every retryS: 3 a minute at 20 s, never the storm
        int starts = 0;
        long bareAt = 0;
        for (long s = 0; s < 60; s++) {
            long now = 1_000_000 + s * 1000;
            if (Goals.exploreWaitS(bareAt, now, 20) == 0) {
                starts++;
                bareAt = now;
            }
        }
        assertEquals(3, starts);
    }

    private static final Map<String, Integer> FUELS = Map.of("minecraft:coal", 1600, "minecraft:oak_log", 300,
            "minecraft:oak_planks", 300, "minecraft:stick", 100, "minecraft:wooden_pickaxe", 200);

    @Test
    void s61ALogBurnsWhenThereIsNoCoal() {
        Map<String, Integer> bag = new LinkedHashMap<>();
        bag.put("minecraft:oak_log", 3);                       // 4 logs, 1 of them the smelt's input
        var f = CraftPlanner.pickFuel(bag, 1, FUELS, 200, id -> Double.NaN);
        assertEquals(new CraftPlanner.Fuel("minecraft:oak_log", 1), f, "a log burns 300 ticks: one smelts the charcoal");
    }

    @Test
    void s61TheCheapestFuelByWorthNeverATool() {
        Map<String, Integer> bag = new LinkedHashMap<>();
        bag.put("minecraft:wooden_pickaxe", 1);
        bag.put("minecraft:oak_log", 3);
        bag.put("minecraft:oak_planks", 4);
        bag.put("minecraft:stick", 4);
        Map<String, Double> worth = Map.of("minecraft:oak_log", 4.0, "minecraft:oak_planks", 1.0, "minecraft:stick", 0.5);
        var f = CraftPlanner.pickFuel(bag, 1, FUELS, 200,
                id -> id.equals("minecraft:wooden_pickaxe") ? -1 : worth.getOrDefault(id, Double.NaN));
        // planks: 1 piece x 1.0; sticks: 2 pieces x 0.5 = 1.0 (more pieces); log 4.0: planks win
        assertEquals(new CraftPlanner.Fuel("minecraft:oak_planks", 1), f);
        bag.remove("minecraft:oak_planks");
        bag.remove("minecraft:stick");
        bag.remove("minecraft:oak_log");
        assertNull(CraftPlanner.pickFuel(bag, 1, FUELS, 200, id -> id.equals("minecraft:wooden_pickaxe") ? -1 : Double.NaN), "a tool never burns");
    }

    @Test
    void s61NotAFuelInTheTableDoesNotBurn() {
        Map<String, Integer> bag = new LinkedHashMap<>();
        bag.put("minecraft:cobblestone", 64);
        assertNull(CraftPlanner.pickFuel(bag, 1, FUELS, 200, id -> 1));
        assertEquals(300, CraftPlanner.burnTicks(FUELS, "oak_log"), "a short id is looked up with the namespace");
    }
}
