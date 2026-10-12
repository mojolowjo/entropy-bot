package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.camp.BootstrapPlan;
import io.github.mojolowjo.entropybot.camp.BuildCount;
import io.github.mojolowjo.entropybot.camp.JunkRules;
import io.github.mojolowjo.entropybot.clear.Tools;
import io.github.mojolowjo.entropybot.world.SurfaceRule;
import io.github.mojolowjo.entropybot.world.SurfaceRule.Cell;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.28.1: trial fix package 1 (docs/TRIAL_FIX_PLAN.md in the bridge repo), the pure rules. */
class TrialFixTest {

    // ---- B: chains with area ----

    @Test
    void areaChainGoesToTheChainRunner() {
        String raw = "area here 48 camp then bootstrap";
        assertFalse(Texts.isPolicyVerbAlone("area", raw), "a chain is not answered by the area parser");
        assertEquals(List.of("area here 48 camp", "bootstrap"), Texts.splitChain(raw));
        assertTrue(Texts.isPolicyVerbAlone("area", "area here 48 camp"));
        assertTrue(Texts.isPolicyVerbAlone("fence", "fence on"));
        assertFalse(Texts.isPolicyVerbAlone("dig", "dig 1 2 3 4 5 6"));
        // each step parses on its own: with and without the type word
        assertNull(PolicyCommands.parseNew(List.of("here", "48", "camp")).error());
        assertNull(PolicyCommands.parseNew(List.of("here", "48", "camp", "neutral")).error());
        assertEquals(List.of("area here 48 camp neutral", "bootstrap"), Texts.splitChain("area here 48 camp neutral then bootstrap"));
    }

    // ---- A: surface snap ----

    /** A column from a map of y -> cell (AIR elsewhere above 0, SOLID at or under 0). */
    private static SurfaceRule.Column col(Map<Integer, Cell> m) {
        return y -> m.getOrDefault(y, y <= 0 ? Cell.SOLID : Cell.AIR);
    }

    @Test
    void treeTopSnapsToTheGround() {
        // the trial: on a spruce top at 139; logs 131..137, leaves 135..138, snow layer at 131? no - trunk stands on dirt at 130
        Map<Integer, Cell> m = new HashMap<>();
        for (int y = 1; y <= 130; y++) m.put(y, Cell.SOLID);
        for (int y = 131; y <= 137; y++) m.put(y, Cell.LOG);
        m.put(138, Cell.LEAVES);
        assertEquals(131, SurfaceRule.groundFeet(col(m), 139, -64));
    }

    @Test
    void snowLayerAndPlantsAreSkippedButFloorsStay() {
        Map<Integer, Cell> m = new HashMap<>();
        for (int y = 1; y <= 60; y++) m.put(y, Cell.SOLID);
        m.put(61, Cell.SNOW_LAYER);
        assertEquals(61, SurfaceRule.groundFeet(col(m), 61, -64), "standing in the snow layer: stays");
        assertEquals(61, SurfaceRule.groundFeet(col(m), 70, -64), "from the air down to the snow");
        m.put(62, Cell.PLANT);
        assertEquals(61, SurfaceRule.groundFeet(col(m), 63, -64));
        // a log floor (a cabin) the bot is not in a tree over: the log counts as ground
        Map<Integer, Cell> cabin = new HashMap<>();
        cabin.put(10, Cell.LOG);
        assertEquals(11, SurfaceRule.groundFeet(col(cabin), 11, -64));
        // water stops the walk
        Map<Integer, Cell> lake = new HashMap<>();
        lake.put(5, Cell.FLUID);
        assertEquals(6, SurfaceRule.groundFeet(col(lake), 9, -64));
    }

    @Test
    void airTargetsAreSpotted() {
        Map<Integer, Cell> m = new HashMap<>();
        assertTrue(SurfaceRule.inAir(col(m), 10));
        assertFalse(SurfaceRule.inAir(col(m), 1), "standing on the ground at 0");
        m.put(9, Cell.LEAVES);
        assertFalse(SurfaceRule.inAir(col(m), 10), "on leaves Baritone can stand");
        assertEquals("the mark was in the air, walking to the ground at 1 2 3", SurfaceRule.airNote(1, 2, 3));
    }

    // ---- F: junk ----

    @Test
    void snowballsAreJunkAndBuildBlocksStayWhileWanted() {
        assertTrue(JunkRules.DEFAULT.contains("minecraft:snowball"));
        Set<String> junk = Set.copyOf(JunkRules.DEFAULT);
        Map<String, Integer> dep = Map.of("minecraft:cobblestone", 64, "minecraft:snowball", 0, "minecraft:dirt", 0);
        Map<String, Integer> inv = Map.of("minecraft:cobblestone", 170, "minecraft:snowball", 63, "minecraft:dirt", 20);
        Map<String, Integer> none = JunkRules.plan(junk, dep, inv, "", JunkRules.wanted(List.of("chopping 6")));
        assertEquals(106, none.get("minecraft:cobblestone"));
        assertEquals(63, none.get("minecraft:snowball"));
        Set<String> want = JunkRules.wanted(List.of("building shell of cobblestone", "build roof cobblestone house"));
        assertTrue(want.contains("minecraft:cobblestone"));
        Map<String, Integer> keep = JunkRules.plan(junk, dep, inv, "", want);
        assertNull(keep.get("minecraft:cobblestone"), "a build keeps its cobblestone");
        assertNull(keep.get("minecraft:dirt"));
        assertEquals(63, keep.get("minecraft:snowball"));
        assertTrue(JunkRules.wanted(List.of("shelter")).contains("*_planks"));
        assertTrue(JunkRules.wanted(null).isEmpty());
    }

    // ---- G: build counts ----

    @Test
    void buildCounts() {
        // a 5x5 house 3 high (the trial's 7x7 area 5 high below)
        assertEquals(25, BuildCount.need("floor", 0, 0, 0, 4, 2, 4));
        assertEquals(75, BuildCount.need("fill", 0, 0, 0, 4, 2, 4));
        assertEquals(48, BuildCount.need("walls", 0, 0, 0, 4, 2, 4));
        assertEquals(66, BuildCount.need("shell", 4, 2, 4, 0, 0, 0));
        assertEquals(7 * 7 * 5 - 5 * 3 * 5, BuildCount.need("shell", 0, 129, 0, 6, 133, 6));
        assertEquals(-1, BuildCount.need("roof", 0, 0, 0, 1, 1, 1));
        assertEquals("(needs 170 cobblestone, you have 40 - gather cobblestone 130 first)", BuildCount.note(170, 40, "cobblestone"));
        assertEquals("(needs 50 cobblestone, you have 64)", BuildCount.note(50, 64, "cobblestone"));
        assertNull(BuildCount.refusal(170, 17, "cobblestone"));
        assertEquals("error: that needs 170 cobblestone and I have 16 - next: gather cobblestone 154, then build again",
                BuildCount.refusal(170, 16, "cobblestone"));
    }

    // ---- D: shovel ----

    @Test
    void snowBreaksByHandAndBootstrapMakesAShovelInSnow() {
        assertTrue(Tools.okWithout(true, "snow_block"));
        assertTrue(Tools.okWithout(true, "minecraft:snow"));
        assertFalse(Tools.okWithout(true, "diamond_ore"));
        assertEquals("stopped: snow_block needs a shovel - next: craft wooden_shovel 1", Tools.shovelStop("minecraft:snow_block"));
        int[] f = {0, 64, 0};
        BootstrapPlan.Plan snowy = BootstrapPlan.plan(new BootstrapPlan.Facts(Map.of(), false, false, false, false,
                f, new int[]{-2, 64, 0}, new int[]{-2, 64, 1}, new int[]{-2, 64, -1}, new int[]{1, 0}, true));
        List<String> s = snowy.steps();
        int place = s.indexOf("place crafting_table -2 64 0"), shovel = s.indexOf("craft wooden_shovel 1");
        assertTrue(place >= 0 && shovel == place + 1, s.toString());
        BootstrapPlan.Plan plain = BootstrapPlan.plan(new BootstrapPlan.Facts(Map.of(), false, false, false, false,
                f, new int[]{-2, 64, 0}, new int[]{-2, 64, 1}, new int[]{-2, 64, -1}, new int[]{1, 0}));
        assertFalse(plain.steps().contains("craft wooden_shovel 1"));
        BootstrapPlan.Plan has = BootstrapPlan.plan(new BootstrapPlan.Facts(Map.of("minecraft:wooden_shovel", 1), false, false, false, false,
                f, new int[]{-2, 64, 0}, new int[]{-2, 64, 1}, new int[]{-2, 64, -1}, new int[]{1, 0}, true));
        assertFalse(has.steps().contains("craft wooden_shovel 1"));
    }
}
