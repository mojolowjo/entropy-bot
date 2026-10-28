package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run ES (docs/runs/runES-z78j6o; save runES-z78j6o.zip, r.-1.-1.mca): the chestplate started at 08:49:56 at
 * -51 17 -41 and at 08:52:37 at -52 17 -42 - iron_ore cells the earlier caving step mined at 08:47 (probe tool "stage 9 at
 * -51 17 -41 iron_ore"), beside the cave's air (the save: -51 17 -40, -51 18 -41 air). The cave index never takes in a dug
 * cell, so caveWay's "inside" (idx.volumeAt) said outside and the chain walked to the lip from deep inside: up a 21-block
 * open shaft (-50 36..56 -40), "no path 3 times", twice, the order stopped. The fix (rule 23 (2)): "in the cave" also reads
 * the record's own reader, the stand's - a cave cell within the eyes' reach of the feet (CaveRecord.cellInReach).
 */
class J6bEsTest {
    static final String D = "minecraft:overworld";

    /** ES's cave round the furnace pocket: the passage at y 17-18 beside it and the shaft's foot up at y 36 (cave air). */
    static Sightings cave() {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(-43, 67, -39, CaveIndex.Size.LARGE, CaveIndex.Depth.STONE, List.of(Sightings.cell(-43, 66, -39))), 1));
        List<Long> cells = new ArrayList<>();
        for (int x = -56; x <= -48; x++) for (int y = 17; y <= 18; y++) cells.add(Sightings.cell(x, y, -40));   // the passage (save: -51 17 -40 air)
        for (int y = 36; y <= 56; y++) cells.add(Sightings.cell(-50, y, -40));                                 // the shaft
        cells.add(Sightings.cell(-43, 66, -39));
        s.caveCells(D, -43, 67, -39, cells);
        return s;
    }

    @Test
    void theFeetInADugOreCellBesideTheCave_areInside() {
        Sightings s = cave();
        assertTrue(s.caveHolds(D, -43, 67, -39, -51, 17, -41, SEES, NO_INDEX), "the mined iron_ore cell -51 17 -41 (08:49:56): its passage in reach");
        assertTrue(s.caveHolds(D, -43, 67, -39, -52, 17, -42, SEES, NO_INDEX), "the second try's -52 17 -42 (08:52:37)");
        assertTrue(s.caveHolds(D, -43, 67, -39, -50, 36, -40, SEES, NO_INDEX), "the shaft's foot: a cell of the cave");
        assertFalse(s.caveHolds(D, -43, 67, -39, -10, 65, 69, SEES, NO_INDEX), "far off: not in it");
        assertFalse(new Sightings().caveHolds(D, -43, 67, -39, -51, 17, -41, SEES, () -> true), "no known cave: not in it");
    }

    /** Inside: its branches from here (the caving step), never the lip walk the chestplate ran into the shaft. */
    @Test
    void inside_itsBranchesFromHere_notALipWalk() {
        var over = new NeedChain.Surface(48, -51, 65, -41, 3, false);
        CaveWay in = new CaveWay(-43, 67, -39, 51, "minecraft:iron_ore", "iron", 7.86, true, CaveIndex.Size.LARGE, 30, List.of(), List.of(new int[]{-43, 67, -39})).withInside(true);
        Chain c = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? in : null));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Cave), "the caving step: " + c.steps());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ToCave), "no lip walk: " + c.steps());
        assertTrue(c.why().contains("the bot is inside it"), c.why());
    }
    /** Every cell in sight (an open pocket), and an index that says nothing (the record is fed: never asked). */
    static final java.util.function.Predicate<int[]> SEES = c -> true;
    static final java.util.function.BooleanSupplier NO_INDEX = () -> { throw new AssertionError("the index's volume is asked although the record is fed"); };

    /**
     * Review of 2394a868 (2): reach AND sight, as GoalReachBlock - a bot outside a wall with a cave cell within 4.5 of its
     * eyes is NOT inside; the lip walk is priced. The wall: every ray from the feet blocked.
     */
    @Test
    void aWallBetween_isNotInside_theLipWalkIsPriced() {
        Sightings s = cave();
        int[] outside = {-51, 17, -43};                                                   // 3 from the passage at z -40, rock between
        assertFalse(s.caveHolds(D, -43, 67, -39, outside[0], outside[1], outside[2], c -> false, NO_INDEX), "in reach, not in sight: outside");
        assertTrue(s.caveHolds(D, -43, 67, -39, outside[0], outside[1], outside[2], SEES, NO_INDEX), "the same cell in sight: inside");
        var over = new NeedChain.Surface(48, -51, 65, -41, 3, false);
        CaveWay out = new CaveWay(-43, 67, -39, 51, "minecraft:iron_ore", "iron", 7.86, true, CaveIndex.Size.LARGE, 30, List.of(), List.of(new int[]{-43, 67, -39})).withInside(false);
        Chain c = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? out : null));
        assertTrue(c.steps().stream().anyMatch(st -> st instanceof Step.ToCave), "not inside: the lip walk is priced: " + c.steps());
    }

    /** Review of 2394a868 (1, rule 23 (2)): the record is the one reader; the index's volume only while the record is unfed. */
    @Test
    void theRecordIsTheReader_theIndexOnlyWhileUnfed() {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(-43, 67, -39, CaveIndex.Size.LARGE, CaveIndex.Depth.STONE, List.of(Sightings.cell(-43, 66, -39))), 1));
        assertTrue(s.caveHolds(D, -43, 67, -39, -50, 36, -40, SEES, () -> true), "unfed: the index's volume says it");
        assertFalse(s.caveHolds(D, -43, 67, -39, -50, 36, -40, SEES, () -> false));
        Sightings fed = cave();
        assertFalse(fed.caveHolds(D, -43, 67, -39, -10, 65, 69, SEES, () -> true), "fed: the record alone - the index's yes is never read");
    }

    /** Review of 503b6d5d (3): the start cell's read warns once per cause, never per tick. */
    @Test
    void theStartCellsWarning_oncePerCause() {
        java.util.Set<String> said = new java.util.HashSet<>();
        assertTrue(io.github.mojolowjo.entropybot.commands.ProbeLines.firstCause(said, new NoSuchMethodError("pathStart")));
        assertFalse(io.github.mojolowjo.entropybot.commands.ProbeLines.firstCause(said, new NoSuchMethodError("pathStart")), "the same cause again: not said");
        assertTrue(io.github.mojolowjo.entropybot.commands.ProbeLines.firstCause(said, new ClassCastException()), "another cause: said once");
    }
}