package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Package 4: the rim placer's decisions (trial 8: a floor cut into a hillside, Baritone stuck at the rim). */
class RimPlanTest {
    /** The trial 8 house floor, moved onto the fake world's ground (stone up to y 52): x 4..10, z -3..3, y 53. */
    static final ClearBox HOUSE = ClearBox.of(4, 53, -3, 10, 57, 3);

    /** The hillside: solid west of the house up to y 55 (the outside neighbour is solid at floor level and above). */
    static FakeWorld hillside() {
        FakeWorld w = new FakeWorld();
        w.fill(-2, 3, 53, 55, -6, 6, "dirt");
        return w;
    }

    @Test void shapeCellsCountAsBuildCountDoes() {
        assertEquals(49, RimPlan.shapeCells("floor", HOUSE).size());
        assertEquals(49, RimPlan.shapeCells("roof", HOUSE).size());
        assertTrue(RimPlan.shapeCells("roof", HOUSE).stream().allMatch(p -> p.y() == 57));
        assertEquals(24 * 5, RimPlan.shapeCells("walls", HOUSE).size());
        assertEquals(24 * 3 + 49 * 2, RimPlan.shapeCells("shell", HOUSE).size());
        assertEquals(49 * 5, RimPlan.shapeCells("fill", HOUSE).size());
    }

    @Test void missingIsWhatTakesABlock() {
        FakeWorld w = hillside();
        List<Pos> floor = RimPlan.shapeCells("floor", HOUSE);
        assertEquals(49, RimPlan.missing(w, floor).size());
        w.fill(5, 9, 53, 53, -2, 2, "oak_planks");
        assertEquals(49 - 25, RimPlan.missing(w, floor).size());
    }

    /** 0.30.4 (live, open terrain): an air cell with solid on all six sides is sealed: not missing, nothing can reach it. */
    @Test void aSealedCellIsNotMissing() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 2, 60, 62, 0, 2, "cobblestone");
        w.fill(1, 1, 61, 61, 1, 1, "air");
        Pos c = new Pos(1, 61, 1);
        assertTrue(RimPlan.sealed(w, c));
        assertTrue(RimPlan.missing(w, List.of(c)).isEmpty());
        w.fill(1, 1, 62, 62, 1, 1, "air");
        assertFalse(RimPlan.sealed(w, c), "open above");
        w.fill(1, 1, 62, 62, 1, 1, "cobblestone");
        w.unloaded.add("2 1");
        assertFalse(RimPlan.sealed(w, c), "an unloaded neighbour reads as unknown: not sealed");
        w.unloaded.clear();
        assertTrue(RimPlan.missing(w, List.of(c)).isEmpty());
    }

    /** 0.30.4 (live, shed 18 128 8): a corner wall cell of the lowest layer, walls beside it, the hill outside. */
    @Test void otherBlocksInTheShapeAreNotAFinishedFloor() {
        FakeWorld w = new FakeWorld();
        ClearBox b = ClearBox.of(0, 53, 0, 1, 53, 1);
        List<Pos> cells = RimPlan.shapeCells("floor", b);
        w.fill(0, 1, 53, 53, 0, 1, "oak_planks");
        w.set(0, 53, 0, "dirt");
        assertEquals(1, RimPlan.otherBlocks(w, cells, "minecraft:oak_planks"));
        assertEquals("done: nothing to place in the floor area (1 cell holds other blocks)", RimPlan.nothingText("floor", "oak_planks", 1));
        assertEquals("done: the floor of oak_planks already stands", RimPlan.nothingText("floor", "oak_planks", 0));
    }

    @Test void aCellTriedTwiceIsSkipped() {
        FakeWorld w = hillside();
        Pos a = new Pos(4, 53, 0), b = new Pos(10, 53, 0);
        Map<Pos, Integer> tries = new HashMap<>();
        tries.put(a, RimPlan.MAX_TRIES);
        assertEquals(b, RimPlan.next(w, List.of(a, b), tries, 4.5, 54, 0.5).cell());
        tries.put(b, RimPlan.MAX_TRIES);
        assertNull(RimPlan.next(w, List.of(a, b), tries, 4.5, 54, 0.5));
    }

    @Test void aCellWithNothingUnderItIsNeverPicked() {
        FakeWorld w = new FakeWorld();
        Pos hang = new Pos(0, 60, 0);                          // air under it
        assertNull(RimPlan.next(w, List.of(hang), new HashMap<>(), 0.5, 53, 0.5));
        assertEquals(RimPlan.Why.NO_NEIGHBOUR, RimPlan.whyLeft(w, hang, Map.of()));
    }

    // ---- 0.30.2 (package 5): the side stance for wall cells ----

    /** A placed floor row: planks at y 53 over x 0..6, z -2..2 (stone below). */
    static FakeWorld floorRow() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 6, 53, 53, -2, 2, "oak_planks");
        return w;
    }

    @Test void aCellWithNoSolidNeighbourIsLeftWithThatReason() {
        FakeWorld w = new FakeWorld();
        Pos cell = new Pos(0, 60, 0);
        w.set(1, 61, 0, "oak_planks");                           // above-side diagonal: not a neighbour
        w.set(0, 61, 0, "oak_planks");                           // above: never clicked
        assertFalse(RimPlan.anchored(w, cell));
        assertNull(RimPlan.next(w, List.of(cell), new HashMap<>(), 0.5, 53, 0.5));
        assertEquals(RimPlan.Why.NO_NEIGHBOUR, RimPlan.whyLeft(w, cell, Map.of()));
        String s = RimPlan.stoppedText("walls", "spruce_planks", 87, 0, List.of(cell), RimPlan.Why.NO_NEIGHBOUR, "house");
        assertTrue(s.contains("(no solid neighbour)"), s);
    }

    /** Trial 8 snow: grass (no collision) in the feet cells and in the wall cell itself: standable, missing, seen. */
    @Test void theDoorCellsAreNotPartOfTheWalls() {
        List<Pos> walls = RimPlan.shapeCells("walls", HOUSE);
        List<Pos> less = RimPlan.withoutDoor(walls, new int[]{7, 3}, 53);
        assertEquals(walls.size() - 2, less.size());
        assertFalse(less.contains(new Pos(7, 54, 3)));
        assertFalse(less.contains(new Pos(7, 55, 3)));
        assertTrue(less.contains(new Pos(7, 56, 3)));
        assertSame(walls, RimPlan.withoutDoor(walls, null, 53));
    }

    @Test void theRayNeverPassesATorch() {
        FakeWorld w = floorRow();
        w.set(4, 54, 0, "torch");                               // no collision, but built: never passed or broken
        w.set(5, 54, 0, "short_grass");
        w.set(6, 54, 0, "chest");
        assertFalse(RimPlan.replaceable(w, 4, 54, 0));
        assertTrue(RimPlan.replaceable(w, 5, 54, 0));
        assertFalse(RimPlan.replaceable(w, 6, 54, 0));
        assertFalse(RimPlan.replaceable(w, 3, 55, 0), "air is not a cover");
    }

    @Test void aCellOverAOneBlockHoleGetsAFillerFirst() {
        FakeWorld w = new FakeWorld();
        w.set(0, 52, 0, "air");                              // the hole under the floor cell 0 53 0
        Pos cell = new Pos(0, 53, 0);
        RimPlan.Pick pk = RimPlan.next(w, List.of(cell), new HashMap<>(), 1.5, 53, 0.5);
        assertNotNull(pk);
        assertTrue(pk.filler());
        assertEquals(new Pos(0, 52, 0), pk.cell());
        assertEquals("minecraft:cobblestone", RimPlan.filler(Map.of("minecraft:cobblestone", 3, "minecraft:dirt", 9), "minecraft:oak_planks"));
        assertEquals("minecraft:dirt", RimPlan.filler(Map.of("minecraft:dirt", 9), "minecraft:oak_planks"));
        assertEquals("minecraft:oak_planks", RimPlan.filler(Map.of("minecraft:oak_planks", 1), "minecraft:oak_planks"));
        assertNull(RimPlan.filler(Map.of(), "minecraft:oak_planks"));
    }

    @Test void lowestLayerFirst() {
        FakeWorld w = new FakeWorld();
        w.set(0, 53, 0, "stone");
        Pos up = new Pos(0, 54, 0), low = new Pos(3, 53, 0);
        assertEquals(low, RimPlan.next(w, List.of(up, low), new HashMap<>(), 0.5, 55, 0.5).cell());
    }

    @Test void endTexts() {
        assertEquals("done: floor of spruce_planks (Baritone placed 42, I placed 7)", RimPlan.doneText("floor", "spruce_planks", 42, 7));
        String s = RimPlan.stoppedText("floor", "spruce_planks", 42, 3, List.of(new Pos(4, 128, -3), new Pos(4, 128, -2)), RimPlan.Why.NO_STAND, "house");
        assertTrue(s.startsWith("stopped: floor of spruce_planks (Baritone placed 42, I placed 3): 2 cells left, e.g. 4 128 -3 (nowhere to stand) - next: "), s);
        assertTrue(RimPlan.stoppedText("floor", "spruce_planks", 0, 0, List.of(new Pos(1, 2, 3)), RimPlan.Why.NO_BLOCKS, "house").contains("1 cell left"));
        assertTrue(RimPlan.standsText("floor", "spruce_planks").startsWith("done: "));
    }
}
