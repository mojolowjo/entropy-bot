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

    @Test void theWestRimStandsOnTheInsidePlanksNotTheHill() {
        FakeWorld w = hillside();
        w.fill(5, 9, 53, 53, -2, 2, "oak_planks");          // Baritone placed the inside
        Pos rim = new Pos(4, 53, 0);
        Pos s = RimPlan.standFor(w, rim, 7.5, 54, 0.5);
        assertNotNull(s);
        assertEquals(54, s.y());
        assertEquals(5, s.x());
    }

    @Test void nothingPlacedYetMeansNowhereToStandOnTheHillSide() {
        FakeWorld w = hillside();
        // the hill is solid at 54 west of the cell; inside cells are air at 53: no stand spot
        assertNull(RimPlan.standFor(w, new Pos(4, 53, 0), 7.5, 54, 0.5));
    }

    @Test void nextIsTheNearestSupportedCellOfTheLowestLayer() {
        FakeWorld w = hillside();
        w.fill(5, 9, 53, 53, -2, 2, "oak_planks");
        List<Pos> miss = RimPlan.missing(w, RimPlan.shapeCells("floor", HOUSE));
        RimPlan.Pick pk = RimPlan.next(w, miss, new HashMap<>(), 9.5, 54, 2.5);
        assertNotNull(pk);
        assertTrue(pk.cell().equals(new Pos(9, 53, 3)) || pk.cell().equals(new Pos(10, 53, 2)), pk.cell().key());
        assertNull(pk.why());
        assertNull(pk.stand(), "standing on the corner's neighbour plank, the cell is in reach: no walk");
    }

    @Test void aFromHereCellThatFailedOnceWalksOnItsSecondTry() {
        FakeWorld w = hillside();
        w.fill(5, 9, 53, 53, -2, 2, "oak_planks");
        Pos c = new Pos(10, 53, 2);
        Map<Pos, Integer> tries = new HashMap<>();
        RimPlan.Pick first = RimPlan.next(w, List.of(c), tries, 9.5, 54, 2.5);
        assertNull(first.stand(), "first try: from where I stand");
        tries.put(c, 1);
        RimPlan.Pick second = RimPlan.next(w, List.of(c), tries, 9.5, 54, 2.5);
        assertNotNull(second.stand(), "second try: a stand spot, not the same click again");
        tries.put(c, 2);
        assertNull(RimPlan.next(w, List.of(c), tries, 9.5, 54, 2.5), "two tries used up");
    }

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

    @Test void aFarCellNeedsAWalk() {
        FakeWorld w = hillside();
        w.fill(5, 9, 53, 53, -2, 2, "oak_planks");
        RimPlan.Pick pk = RimPlan.next(w, List.of(new Pos(4, 53, 0)), new HashMap<>(), 9.5, 54, 2.5);
        assertNotNull(pk.stand());
        assertEquals(5, pk.stand().x());
        assertEquals(54, pk.stand().y());
        assertEquals(1, pk.stand().z(), "the nearest of the plank neighbours (a diagonal one is fine)");
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

    @Test void aWallCellOnTheFloorRowStandsOnTheFloorBesideIt() {
        FakeWorld w = floorRow();
        Pos cell = new Pos(3, 54, 0);
        assertNull(RimPlan.standFor(w, cell, 20.5, 54, 0.5), "nothing at the cell's level to stand on top of");
        RimPlan.Pick pk = RimPlan.next(w, List.of(cell), new HashMap<>(), 20.5, 54, 0.5);
        assertNotNull(pk);
        assertNull(pk.why());
        assertNotNull(pk.stand());
        assertEquals(54, pk.stand().y(), "feet on the floor row");
        assertFalse(PlaceRules.bodyIn(pk.stand().x(), pk.stand().y(), pk.stand().z(), cell));
        assertNotNull(pk.side());
        assertEquals(-1, pk.side().dy(), "the floor plank under the cell");
        double ex = pk.stand().x() + 0.5, ey = pk.stand().y() + 1.62, ez = pk.stand().z() + 0.5;
        assertTrue(RimPlan.sees(w, cell, pk.side(), ex, ey, ez, RimPlan.REACH));
    }

    @Test void aWallCellOverAHoleIsPlacedAgainstTheWallBlockBesideIt() {
        FakeWorld w = floorRow();
        w.set(3, 53, 0, "air");                                  // no floor under the cell
        w.set(2, 54, 0, "oak_planks");                           // the wall block west of it
        Pos cell = new Pos(3, 54, 0);
        assertFalse(RimPlan.supported(w, cell));
        assertTrue(RimPlan.anchored(w, cell));
        RimPlan.Pick pk = RimPlan.next(w, List.of(cell), new HashMap<>(), 20.5, 54, 0.5);
        assertNotNull(pk.stand());
        assertFalse(pk.filler());
        assertEquals(-1, pk.side().dx(), "the clicked block is the wall block at x 2");
        assertEquals(ClearWorld.Face.EAST, pk.side().face(), "its east face borders the cell: the block goes into x 3");
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

    @Test void theTopStanceIsPreferredWhenBothWork() {
        FakeWorld w = floorRow();
        w.set(2, 54, 0, "oak_planks");                           // a wall block to stand on, at the cell's level
        Pos cell = new Pos(3, 54, 0);
        assertNotNull(RimPlan.sideStand(w, cell, 20.5, 54, 0.5), "the side stance works too");
        RimPlan.Pick pk = RimPlan.next(w, List.of(cell), new HashMap<>(), 20.5, 54, 0.5);
        assertEquals(new Pos(2, 55, 0), pk.stand());
        assertNull(pk.side(), "top stance: placeAt picks the floor face");
    }

    @Test void aBlockedFaceIsNotSeen() {
        FakeWorld w = floorRow();
        Pos cell = new Pos(3, 54, 0);
        PlaceRules.Side below = PlaceRules.SIDES.get(0);
        assertTrue(RimPlan.sees(w, cell, below, 5.5, 55.62, 0.5, RimPlan.REACH));
        w.fill(4, 4, 54, 56, -1, 1, "stone");                     // a pillar between the bot and the cell
        assertFalse(RimPlan.sees(w, cell, below, 5.5, 55.62, 0.5, RimPlan.REACH));
        assertFalse(RimPlan.sees(w, cell, below, 3.5, 52.0, 0.5, RimPlan.REACH), "behind the face");
    }

    @Test void capsUnchanged() {
        assertEquals(2, RimPlan.MAX_TRIES);
        assertEquals(90 * 20, RimPlan.LAYER_CAP_TICKS);
        assertEquals(PlaceRules.STAND_REACH, RimPlan.REACH);
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
