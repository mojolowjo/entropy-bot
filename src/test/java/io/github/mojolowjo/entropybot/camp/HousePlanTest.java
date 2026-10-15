package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HousePlanTest {
    private static HousePlan.Plan plan(int[] box, Map<String, Integer> inv, Map<String, Boolean> standing, int[] camp) {
        return HousePlan.plan(new HousePlan.Facts("house", box, true, "minecraft:cobblestone", inv, standing, camp));
    }

    @Test
    void sevenBySevenFiveHigh() {
        HousePlan.Plan p = plan(new int[]{0, 64, 0, 6, 68, 6}, Map.of("minecraft:cobblestone", 200, "minecraft:chest", 1,
                "minecraft:crafting_table", 1, "minecraft:furnace", 1), Map.of(), null);
        assertNull(p.err());
        assertEquals(List.of(
                "build floor cobblestone house",
                "dig 0 65 0 6 68 6",                       // 0.30.2: the whole box above the floor, before the walls
                "build walls cobblestone house door 3 6",   // 0.30.2: the door's two cells stay open
                "build roof cobblestone house",
                "dig 3 65 6 3 66 6 force",                 // door south (no camp), middle of the side
                "place chest 2 65 1",                      // back wall (north) inside
                "place crafting_table 3 65 1",
                "place furnace 4 65 1",
                "place base 3 65 3",
                "place food 2 65 1",
                "scan base"), p.steps());
        assertTrue(p.summary().contains("needs about 168 cobblestone (you have 200)"), p.summary());
        assertTrue(p.summary().contains("door on the south side"));
        assertTrue(p.summary().contains(": 11 steps"), p.summary());
    }

    @Test
    void counts() {
        assertEquals(170, BuildCount.need("shell", 0, 64, 0, 6, 68, 6));
        assertEquals(168, HousePlan.need(0, 64, 0, 6, 68, 6));
        assertEquals(120, BuildCount.need("walls", 0, 64, 0, 6, 68, 6));
        assertEquals(49, BuildCount.need("roof", 0, 64, 0, 6, 68, 6));
    }

    @Test
    void doorFacesTheCampAndStationsGoOpposite() {
        HousePlan.Plan p = plan(new int[]{6, 68, 6, 0, 64, 0}, Map.of(), Map.of(), new int[]{40, 64, 2});
        assertTrue(p.steps().contains("dig 6 65 3 6 66 3 force"), p.steps().toString());
        assertTrue(p.steps().contains("place chest 1 65 2"), p.steps().toString());
        assertTrue(p.summary().contains("east"));
        assertTrue(p.summary().contains("have 0) - gather cobblestone 168"));
    }

    @Test
    void craftsMissingStationsAndSkipsStanding() {
        HousePlan.Plan p = plan(new int[]{0, 64, 0, 6, 68, 6}, Map.of(), Map.of("chest", true), null);
        assertFalse(p.steps().stream().anyMatch(s -> s.startsWith("place chest")));
        int c = p.steps().indexOf("craft crafting_table 1");
        assertTrue(c >= 0 && p.steps().get(c + 1).equals("place crafting_table 3 65 1"));
        assertTrue(p.steps().contains("craft furnace 1"));
        assertTrue(p.summary().contains("crafts crafting_table, furnace first"));
    }

    @Test
    void refusesSmallOrFlat() {
        assertEquals(HousePlan.TOO_SMALL, plan(new int[]{0, 64, 0, 5, 68, 6}, Map.of(), Map.of(), null).err());
        assertEquals(HousePlan.TOO_SMALL, plan(new int[]{0, 64, 0, 6, 67, 6}, Map.of(), Map.of(), null).err());
        assertEquals(HousePlan.TOO_SMALL, HousePlan.plan(new HousePlan.Facts("h", null, false, "minecraft:cobblestone", Map.of(), Map.of(), null)).err());
    }

    @Test
    void blockChoice() {
        assertEquals("minecraft:cobblestone", HousePlan.chooseBlock(null, Map.of("minecraft:cobblestone", 10)));
        assertEquals("minecraft:oak_planks", HousePlan.chooseBlock(null, Map.of("minecraft:cobblestone", 10, "minecraft:oak_planks", 64)));
        assertEquals("minecraft:stone_bricks", HousePlan.chooseBlock("stone_bricks", Map.of()));
    }

    /** 0.30.2 review c: a rerun digs only the inside (a cobblestone shell stays); a big box goes per layer. */
    @Test
    void rerunDigsOnlyTheInsideAndBigBoxesGoPerLayer() {
        Map<String, Integer> inv = Map.of("minecraft:cobblestone", 200, "minecraft:chest", 1, "minecraft:crafting_table", 1, "minecraft:furnace", 1);
        HousePlan.Plan p = plan(new int[]{0, 64, 0, 6, 68, 6}, inv, Map.of(HousePlan.SHELL, true), null);
        assertEquals("dig 1 65 1 5 67 5", p.steps().get(1), p.steps().toString());
        HousePlan.Plan big = plan(new int[]{0, 64, 0, 20, 68, 20}, inv, Map.of(), null);
        assertEquals("dig 0 68 0 20 68 20", big.steps().get(1), "441 x 4 > 1000: top layer first");
        assertEquals("dig 0 65 0 20 65 20", big.steps().get(4));
        assertTrue(big.steps().get(5).startsWith("build walls"));
    }

    @Test
    void stationsAndBuildingBlocks() {
        assertFalse(HousePlan.isStation("minecraft:ender_chest", "chest"));
        assertTrue(HousePlan.buildingBlock("minecraft:spruce_planks"));
        assertTrue(HousePlan.buildingBlock("minecraft:cobblestone"));
        for (String s : new String[]{"minecraft:torch", "minecraft:oak_door", "minecraft:glass", "minecraft:ladder", "minecraft:oak_sign", "minecraft:oak_stairs"})
            assertFalse(HousePlan.buildingBlock(s), s);
    }

    /** 0.30.2 (trial 8): 87 plank wall cells stand; the bag now holds more cobblestone: the planks are kept. */
    @Test
    void theBlockThatStandsIsKept() {
        Map<String, Integer> bag = Map.of("minecraft:cobblestone", 49, "minecraft:spruce_planks", 36);
        assertEquals("minecraft:cobblestone", HousePlan.chooseBlock(null, bag, Map.of()));
        assertEquals("minecraft:spruce_planks", HousePlan.chooseBlock(null, bag, Map.of("minecraft:spruce_planks", 87, "minecraft:cobblestone", 3)));
        assertEquals("minecraft:cobblestone", HousePlan.chooseBlock(null, bag, Map.of("minecraft:spruce_planks", 4, "minecraft:cobblestone", 4)), "a tie: the bag's choice");
        assertEquals("minecraft:oak_planks", HousePlan.chooseBlock("oak_planks", bag, Map.of("minecraft:spruce_planks", 87)), "asked wins");
    }

    @Test
    void doorFacesTheCamp() {
        assertEquals("south", HousePlan.sideName(HousePlan.doorSide(10, 10, new int[]{10, 0, 40})));
        assertEquals("north", HousePlan.sideName(HousePlan.doorSide(10, 10, new int[]{10, 0, -40})));
        assertEquals("east", HousePlan.sideName(HousePlan.doorSide(10, 10, new int[]{40, 0, 10})));
        assertEquals("west", HousePlan.sideName(HousePlan.doorSide(10, 10, new int[]{-40, 0, 10})));
    }

    @Test
    void doorDefaultsSouth() {
        assertEquals("south", HousePlan.sideName(HousePlan.doorSide(10, 10, null)));
    }

    @Test
    void doorOnTheDiagonalPicksTheLongerAxis() {
        assertEquals("east", HousePlan.sideName(HousePlan.doorSide(10, 10, new int[]{40, 0, 12}))); // |dx|=30 > |dz|=2
        assertEquals("south", HousePlan.sideName(HousePlan.doorSide(10, 10, new int[]{12, 0, 40}))); // |dz|=30 > |dx|=2
    }

    @Test
    void needLeavesOutTheDoor() {
        // shell for 7x7x5 box (0,64,0)-(6,68,6): shell = 7*7*5 - 5*5*3 = 170; need = shell - 2 door cells = 168
        assertEquals(170, BuildCount.need("shell", 0, 64, 0, 6, 68, 6));
        assertEquals(168, HousePlan.need(0, 64, 0, 6, 68, 6));
    }
}
