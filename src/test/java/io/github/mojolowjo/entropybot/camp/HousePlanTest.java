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
                "dig 0 64 0 6 68 6",                       // 0.30.4: the whole box, floor layer included, first
                "build under cobblestone house",           // 0.30.4: pillars under the floor
                "build floor cobblestone house",
                "build walls cobblestone house door 3 6",   // 0.30.2: the door's two cells stay open
                "build roof cobblestone house",
                "dig 3 65 6 3 66 6 force",                 // door south (no camp), middle of the side
                "goto 3 65 3",                             // 0.30.6: in through the door before the stations
                "place chest 2 65 1",                      // back wall (north) inside
                "place crafting_table 3 65 1",
                "place furnace 4 65 1",
                "place base 3 65 3",
                "place food 2 65 1",
                "scan base 4"), p.steps());
        assertTrue(p.summary().contains("needs about 168 cobblestone (you have 200)"), p.summary());
        assertTrue(p.summary().contains("door on the south side"));
        assertTrue(p.summary().contains(": 13 steps"), p.summary());
    }

    /** A world: the shell cells (less a south door at x 3) hold cobblestone when shell, the rest is clear unless listed. */
    private static HousePlan.Probe world(boolean floor, boolean walls, boolean roof, boolean doorOpen, java.util.Set<String> blocked) {
        return new HousePlan.Probe() {
            boolean shell(int x, int y, int z) {
                if (y == 64) return floor;
                if (y == 68) return roof;
                boolean edge = x == 0 || x == 6 || z == 0 || z == 6;
                if (!edge) return false;
                if (x == 3 && z == 6 && (y == 65 || y == 66)) return !doorOpen;
                return walls;
            }

            @Override public boolean hasBlock(int x, int y, int z) { return shell(x, y, z); }

            @Override public boolean clear(int x, int y, int z) { return !shell(x, y, z) && !blocked.contains(x + " " + y + " " + z); }
        };
    }

    private static HousePlan.Plan rerun(HousePlan.Probe pr, Map<String, Boolean> standing) {
        return HousePlan.plan(new HousePlan.Facts("house", new int[]{0, 64, 0, 6, 68, 6}, true, "minecraft:cobblestone",
                Map.of("minecraft:cobblestone", 5), standing, null, List.of(), pr));
    }

    @Test
    void rerunSkipsWhatStands() {
        // trial 11: everything but the stations stands: no dig, no build, no door dig; the stations still come
        HousePlan.Plan p = rerun(world(true, true, true, true, java.util.Set.of()), Map.of("shell", true, "chest", true));
        assertEquals(List.of("goto 3 65 3", "craft crafting_table 1", "place crafting_table 3 65 1", "craft furnace 1", "place furnace 4 65 1",
                "place base 3 65 3", "place food 2 65 1", "scan base 4"), p.steps());
        assertTrue(p.summary().contains("skips dig, under, floor, walls, roof, door, chest (already there)"), p.summary());
        assertTrue(p.summary().contains("the shell stands (no cobblestone needed)"), p.summary());
    }

    @Test
    void rerunResumesAtTheRoof() {
        // walls stand, the roof is short, one cobblestone left inside: dig the inside, then roof and door
        HousePlan.Plan p = rerun(world(true, true, false, false, java.util.Set.of("2 66 2")), Map.of("shell", true));
        assertEquals("dig 1 65 1 5 67 5", p.steps().get(0));
        assertEquals("build roof cobblestone house", p.steps().get(1));
        assertEquals("dig 3 65 6 3 66 6 force", p.steps().get(2));
        assertTrue(p.summary().contains("skips under, floor, walls (already there)"), p.summary());
    }

    @Test
    void noProbeRunsEveryStep() {
        HousePlan.Plan p = rerun(null, Map.of("shell", true));
        assertTrue(p.steps().contains("build floor cobblestone house"));
        assertFalse(p.summary().contains("skips"), p.summary());
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

    /** 0.30.8 (trial 10): a station standing in the area never becomes the house block. */
    @Test
    void placedStationsNeverDecideTheBlock() {
        assertEquals("minecraft:cobblestone", HousePlan.chooseBlock(null, Map.of("minecraft:cobblestone", 65, "minecraft:spruce_planks", 65),
                Map.of("visualworkbench:minecraft/crafting_table", 1)));
        assertEquals("minecraft:oak_planks", HousePlan.chooseBlock(null, Map.of("minecraft:cobblestone", 10),
                Map.of("minecraft:oak_planks", 40, "minecraft:chest", 1, "minecraft:furnace", 1, "minecraft:torch", 50)));
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
        assertEquals("dig 1 65 1 5 67 5", p.steps().get(0), p.steps().toString());
        assertEquals("build under cobblestone house", p.steps().get(1));
        HousePlan.Plan big = plan(new int[]{0, 64, 0, 20, 68, 20}, inv, Map.of(), null);
        assertEquals("dig 0 68 0 20 68 20", big.steps().get(0), "441 x 5 > 1000: top layer first");
        assertEquals("dig 0 64 0 20 64 20", big.steps().get(4), "the floor layer last");
        assertEquals(List.of("build under cobblestone house", "build floor cobblestone house"), big.steps().subList(5, 7));
        assertTrue(big.steps().get(7).startsWith("build walls"));
    }

    /** 0.30.4: on a rerun the floor layer's natural blocks are dug one by one before the fill, the shell stays. */
    @Test
    void rerunDigsForeignFloorCells() {
        HousePlan.Plan p = HousePlan.plan(new HousePlan.Facts("house", new int[]{0, 64, 0, 6, 68, 6}, true, "minecraft:cobblestone",
                Map.of("minecraft:cobblestone", 200), Map.of(HousePlan.SHELL, true), null, List.of(new int[]{2, 64, 3})));
        assertEquals(List.of("dig 1 65 1 5 67 5", "dig 2 64 3 2 64 3", "build under cobblestone house", "build floor cobblestone house"),
                p.steps().subList(0, 4));
        // review 7: a natural block on the wall ring next to the door (door 3 6, floor layer y 64) is dug too
        HousePlan.Plan d = HousePlan.plan(new HousePlan.Facts("house", new int[]{0, 64, 0, 6, 68, 6}, true, "minecraft:cobblestone",
                Map.of("minecraft:cobblestone", 200), Map.of(HousePlan.SHELL, true), null, List.of(new int[]{4, 64, 6})));
        assertEquals("dig 4 64 6 4 64 6", d.steps().get(1), d.steps().toString());
        assertTrue(d.steps().contains("build walls cobblestone house door 3 6"));
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
