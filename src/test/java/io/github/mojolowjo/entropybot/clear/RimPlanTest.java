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
        assertEquals(RimPlan.Why.NO_SUPPORT, RimPlan.whyLeft(w, hang, Map.of()));
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
