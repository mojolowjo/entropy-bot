package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** job-silent-end-fact: run 3 w025 (a chop held under one log six times) and w005 (a place-near stalled 22 times), replayed. */
class SilentEndFactTest {
    @AfterEach
    void clear() { TestData.clear(); }

    /** w025: tree A's logs 276 64..68 262 (the plan's), tree B 273 63 271; the hold "cut at 276 64 262" (log 632). */
    @Test
    void w025_aStalledCutHoldsItsWholeTrunkSoTheNextPlanTakesAnotherTree() {
        ScenariosTest.world("wooden");
        String held = "cut at 276 64 262";
        List<Spot> logs = new java.util.ArrayList<>();
        for (int y = 64; y <= 68; y++) logs.add(new Spot(276, y, 262, 10, "minecraft:oak_log", 1));
        logs.add(new Spot(273, 63, 271, 12, "minecraft:oak_log", 1));
        var trunks = NeedChain.trunksHeld(NeedChain.heldCuts(List.of(held)), w -> logs);
        for (int y = 65; y <= 68; y++)
            assertTrue(NeedChain.spotHeld("oak_log", new Spot(276, y, 262, 10, "minecraft:oak_log", 1), held::equals, trunks),
                    "log 276 " + y + " 262 is of the held trunk (run 3 took it next, 740-1079)");
        assertFalse(NeedChain.spotHeld("oak_log", new Spot(273, 63, 271, 12, "minecraft:oak_log", 1), held::equals, trunks), "tree B stays open");
        assertFalse(NeedChain.spotHeld("cobblestone", new Spot(276, 65, 262, 10, "minecraft:stone", 1), held::equals, trunks), "not a log: no trunk");

        Map<String, List<Spot>> seen = Map.of("oak_log", List.of(new Spot(276, 65, 262, 10, "minecraft:oak_log", 4),
                new Spot(273, 63, 271, 14, "minecraft:oak_log", 6)));
        Chain c = NeedChain.resolve("oak_log", 3, ScenariosTest.w(Map.of(), false, false, seen).withoutSources(Map.of(held, "no progress 180 s at cut 6 oak_log at 276 64 262")));
        assertTrue(c.ok(), c.why());
        Step.ChopTree cut = (Step.ChopTree) c.steps().stream().filter(s -> s instanceof Step.ChopTree).findFirst().orElseThrow();
        assertArrayEquals(new int[]{273, 63, 271}, cut.at(), "the next plan takes tree B, not the next log of A: " + c.steps());
    }

    /** job-review-leftovers (2): a 2x2 trunk (dark oak) held at one column holds the adjacent columns of the same tree. */
    @Test
    void aStalledCutOfA2x2TrunkHoldsItsOtherColumns() {
        ScenariosTest.world("wooden");
        List<int[]> cuts = NeedChain.heldCuts(List.of("cut at 100 64 100", "not a cut"));
        assertEquals(1, cuts.size());
        List<Spot> logs = new java.util.ArrayList<>();
        for (int y = 64; y <= 66; y++)
            for (int[] d : new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}}) logs.add(new Spot(100 + d[0], y, 100 + d[1], 10, "minecraft:oak_log", 1));
        for (int y = 64; y <= 66; y++) logs.add(new Spot(103, y, 100, 10, "minecraft:oak_log", 1));   // a tree one column apart (102 free)
        var trunks = NeedChain.trunksHeld(cuts, w -> logs);
        for (int[] d : new int[][]{{1, 0}, {0, 1}, {1, 1}})
            assertTrue(NeedChain.spotHeld("oak_log", new Spot(100 + d[0], 66, 100 + d[1], 10, "minecraft:oak_log", 1), k -> false, trunks),
                    "column " + d[0] + " " + d[1] + " is of the same 2x2 trunk");
        assertFalse(NeedChain.spotHeld("oak_log", new Spot(103, 64, 100, 10, "minecraft:oak_log", 1), k -> false, trunks), "another tree stays open");
    }

    /** review sent back again (3): keyed by the tree, not a distance - a log one column off that does not touch the held tree's logs is open. */
    @Test
    void aTreeOneColumnOffThatDoesNotTouchIsOpen() {
        ScenariosTest.world("wooden");
        List<int[]> cuts = NeedChain.heldCuts(List.of("cut at 100 64 100"));
        List<Spot> logs = List.of(new Spot(100, 64, 100, 10, "minecraft:oak_log", 1), new Spot(100, 65, 100, 10, "minecraft:oak_log", 1),
                new Spot(101, 70, 100, 10, "minecraft:oak_log", 1), new Spot(101, 71, 100, 10, "minecraft:oak_log", 1));
        var trunks = NeedChain.trunksHeld(cuts, w -> logs);
        assertTrue(NeedChain.spotHeld("oak_log", logs.get(1), k -> false, trunks));
        assertFalse(NeedChain.spotHeld("oak_log", logs.get(2), k -> false, trunks), "the neighbour tree's logs do not touch the held one's");
        assertNull(NeedChain.trunksHeld(List.of(), w -> logs));
    }

    /** job-review-leftovers (1): the place stalled away from where it was asked holds the stall cell too, through placeKey. */
    @Test
    void w005_theStallCellIsHeldToo() {
        Step.PlaceNear pn = new Step.PlaceNear("crafting_table");
        FactChange.Fact f = StepMonitor.placeStall(pn, new int[]{20, 28, -10}, new int[]{20, 36, -10}, "no progress 45 s");
        assertEquals(NeedChain.placeKey(20, 36, -10), FactChange.stallPlaceKey(f));
        assertNull(FactChange.stallPlaceKey(StepMonitor.placeStall(pn, new int[]{1, 2, 3}, new int[]{1, 2, 3}, "x")), "the feet did not move: one fact");
        NeedChain.World w = ScenariosTest.w(Map.of(), false, false, Map.of());
        NeedChain.World both = w.withPlaceFailures(Map.of(NeedChain.placeKey(20, 28, -10), f.text(), FactChange.stallPlaceKey(f), f.text()), new int[]{20, 36, -10});
        assertNotNull(both.rooms().hereFailed(), "standing at the stall cell (8 m above the asked one): placing near me here is out at once");
    }

    /** w005: asked from the cell with room 20 28 -10, stalled at 20 36 -10 (lines 1079/1083): the held cell is the asked one. */
    @Test
    void w005_aStalledPlaceNearHoldsTheCellItWasAskedFrom() {
        Step.PlaceNear pn = new Step.PlaceNear("crafting_table");
        FactChange.Fact f = StepMonitor.placeStall(pn, new int[]{20, 28, -10}, new int[]{20, 36, -10}, "no progress 45 s");
        assertEquals(FactChange.noProgress(NeedChain.placeKey(20, 28, -10), "x").key(), f.key());
        assertTrue(f.text().contains("failed at 20 28 -10 (stalled at 20 36 -10)"), f.text());
        FactChange.Fact still = StepMonitor.placeStall(pn, new int[]{20, 36, -10}, new int[]{20, 36, -10}, "x");
        assertFalse(still.text().contains("stalled at"), still.text());
        assertEquals(FactChange.noProgress(NeedChain.placeKey(1, 2, 3), "x").key(), StepMonitor.placeStall(pn, null, new int[]{1, 2, 3}, "x").key(), "no start: the stall's feet");
        assertNull(StepMonitor.placeStall(pn, null, null, "x"), "nothing known: the plain step fact (rule 15)");
    }
}
