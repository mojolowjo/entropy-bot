package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.cave.CaveRules;
import io.github.mojolowjo.entropybot.cave.Caves;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z51 items 1-3 from run L on z50 (docs/runs/night-2026-10-23.md): the caving step enters the confirmed mouth's
 * pocket (stored cells, no dark-corner test of its own), a mouth confirms once (a stored fact, kept across the merge),
 * and an explore whose until-source was known at its start holds instead of ending at once. Fact states of rule 15:
 * loaded (a fact), empty (none known), not loaded (no sightings: the step's own failure text).
 */
class Z51CaveMouthTest {
    static final String DIM = "minecraft:overworld";

    /** Run L's mouth 33 63 -61 with a 3-cell pocket. */
    static List<Long> pocket() { return List.of(Sightings.cell(33, 52, -61), Sightings.cell(34, 52, -61), Sightings.cell(35, 51, -60)); }

    @Test
    void aMouthConfirmsOnceAndTheFactIsKept() {
        Sightings sg = new Sightings();
        assertTrue(sg.confirmedMouths(DIM, 0, 64, 0).isEmpty(), "empty: no confirmed mouth known");
        assertNull(sg.mouthFact(DIM, 33, 63, -61));
        var f = sg.confirmMouth(DIM, 33, 63, -61, 1000, 33, 52, -61, pocket());
        assertNotNull(f, "the first confirmation is stored");
        assertNull(sg.confirmMouth(DIM, 33, 63, -61, 1200, 33, 52, -61, pocket()), "the same mouth: not stored (nor said) again");
        // z50's merge moved the candidate (the lowest point within cave.mergeM kept): the stored fact still answers
        int m = Sightings.factMerge();
        assertSame(f, sg.mouthFact(DIM, 33 + m / 2, 60, -61), "a merged candidate reads the stored fact");
        assertNull(sg.confirmMouth(DIM, 33 + m / 2, 60, -61, 1300, 0, 0, 0, List.of()), "merged: the fact is kept as stored");
        assertEquals(1000, sg.mouthFact(DIM, 33, 63, -61).confirmedAt());
        assertEquals(3, sg.mouthFact(DIM, 33, 63, -61).pocket().size());
        // the old exhaust (a caving run with no ore) leaves the confirmation alone: the rays never re-run it
        sg.sawMouth(DIM, 33, 63, -61, 24, 1);
        sg.exhaust(DIM, 33, 52, -61, 5);
        assertNotNull(sg.mouthFact(DIM, 33, 63, -61));
    }

    @Test
    void theFactSurvivesTheFileAndAWalkedPocketIsOfferedNoMore() {
        Sightings sg = new Sightings();
        sg.confirmMouth(DIM, 33, 63, -61, 1000, 33, 52, -61, pocket());
        int[] skipped = {0};
        Sightings back = Sightings.fromJson(sg.toJson(), skipped);
        assertEquals(0, skipped[0]);
        var f = back.mouthFact(DIM, 33, 63, -61);
        assertNotNull(f);
        assertEquals(pocket(), f.pocket());
        assertArrayEquals(new int[]{35, 51, -60}, Sightings.cellOf(f.pocket().get(2)));
        assertArrayEquals(new int[]{-120, 62, -184}, Sightings.cellOf(Sightings.cell(-120, 62, -184)), "negative cells round-trip");
        assertEquals(1, back.confirmedMouths(DIM, 0, 64, 0).size());
        assertTrue(back.walkedMouth(DIM, 33, 63, -61, 99));
        assertTrue(back.confirmedMouths(DIM, 0, 64, 0, 100).isEmpty(), "walked with no ore: held (cave.walkedHoldS)");
        assertNotNull(back.mouthFact(DIM, 33, 63, -61), "the fact stays: no re-confirmation");
    }

    /**
     * z53 review 3-4: a walked mouth that yielded ore stays offered while the order needs ore; one walked with 0 ore is held
     * cave.walkedHoldS, then offered again; closed for good never; the walk facts survive the file.
     */
    @Test
    void aWalkedMouthIsHeldOnlyWhenItGaveNoOre() {
        long hold = Sightings.walkedHoldMs();
        assertTrue(hold > 0);
        Sightings sg = new Sightings();
        sg.confirmMouth(DIM, 33, 63, -61, 1000, 33, 52, -61, pocket());
        sg.confirmMouth(DIM, 200, 63, 200, 1000, 200, 52, 200, pocket());
        sg.confirmMouth(DIM, -200, 63, -200, 1000, -200, 52, -200, pocket());
        long t = 10_000;
        assertTrue(sg.walkedMouth(DIM, 33, 63, -61, t, 3));
        assertTrue(sg.walkedMouth(DIM, 200, 63, 200, t, 0));
        assertTrue(sg.walkedMouth(DIM, -200, 63, -200, t, Sightings.MouthFact.CLOSED));
        Sightings back = Sightings.fromJson(sg.toJson(), new int[]{0});
        assertEquals(3, back.mouthFact(DIM, 33, 63, -61).oreOnWalk());
        var now = back.confirmedMouths(DIM, 0, 64, 0, t + 1);
        assertEquals(1, now.size(), "only the one that yielded ore: " + now);
        assertEquals(33, now.get(0).x());
        var later = back.confirmedMouths(DIM, 0, 64, 0, t + hold);
        assertEquals(2, later.size(), "the 0-ore mouth is offered again after the hold: " + later);
        assertTrue(later.stream().noneMatch(f -> f.x() == -200), "closed for good: never");
        // a second walk of the ore mouth with 0 ore holds it too
        assertTrue(back.walkedMouth(DIM, 33, 63, -61, t + hold, 0));
        assertTrue(back.confirmedMouths(DIM, 0, 64, 0, t + hold + 1).stream().noneMatch(f -> f.x() == 33));
    }

    /** The pocket cells are the fill's own dark cells (the count the z50 verdict used). */
    @Test
    void thePocketCellsAreTheFillsDarkCells() {
        // a floor cell at y 50 with a dark air tunnel along +x for 4 cells; everything else solid
        CavePatterns.Cells g = new CavePatterns.Cells() {
            public boolean air(int x, int y, int z) { return y == 50 && z == 0 && x >= 0 && x <= 4; }
            public boolean water(int x, int y, int z) { return false; }
            public int sky(int x, int y, int z) { return x == 0 ? 15 : 0; }
        };
        List<Long> cells = CavePatterns.pocketCells(0, 50, 0, 60, g);
        assertEquals(CavePatterns.pocket(0, 50, 0, 60, g)[0], cells.size());
        assertEquals(4, cells.size());
        assertTrue(cells.contains(Sightings.cell(4, 50, 0)));
    }

    /** Item 1: the step walks the stored pocket by nearest cell; the walked and refused ones drop; null = walked. */
    @Test
    void theStepWalksIntoThePocketCells() {
        List<int[]> pk = new ArrayList<>(List.of(new int[]{33, 52, -61}, new int[]{40, 52, -61}, new int[]{36, 52, -61}, new int[]{50, 50, -61}));
        int[] next = CaveRules.nextPocketCell(pk, new int[]{33, 53, -61}, q -> q[0] == 50, 2);
        assertArrayEquals(new int[]{36, 52, -61}, next, "the floor cell is reached, the refused one dropped, the nearest next");
        assertEquals(2, pk.size());
        assertArrayEquals(new int[]{40, 52, -61}, CaveRules.nextPocketCell(pk, new int[]{36, 52, -61}, null, 2));
        assertNull(CaveRules.nextPocketCell(pk, new int[]{40, 52, -61}, null, 2), "the pocket is walked");
        assertEquals("no confirmed cave mouth", CaveRules.NO_MOUTH, "the step's named failure");
    }

    /** Item 1: a cave_N name only at a confirmed mouth; a mouth within cave.mergeM is the same cave. */
    @Test
    void aCaveIsNamedOnlyAtTheConfirmedMouth() {
        Caves caves = new Caves();
        Caves.Cave a = caves.atMouth(DIM, 33, 52, -61, 8, 0, 0);
        assertEquals("cave_1", a.name);
        assertSame(a, caves.atMouth(DIM, 36, 50, -60, 8, 0, 0), "the same mouth (merged): the same cave");
        assertEquals("cave_2", caves.atMouth(DIM, -120, 62, -184, 8, 0, 0).name);
    }

    /** Item 3: a source known at the explore's start holds replan.unchangedHoldS; a new one ends it at once. */
    @Test
    void anUnchangedSourceHoldsTheExplore() {
        String atStart = "a source of #stone_tool_materials is known: stone at -11 48 -61 (15 away); the chain is priced again";
        String moved = "a source of #stone_tool_materials is known: stone at -11 48 -61 (9 away); the chain is priced again";
        String fresh = "a source of #stone_tool_materials is known: stone at 4 60 2 (3 away); the chain is priced again";
        double hold = io.github.mojolowjo.entropybot.aa.brain.Tuning.get("replan.unchangedHoldS");
        assertFalse(ExploreRules.untilEnds(atStart, atStart, 0), "run L: ended at once 66 times - now it holds");
        assertFalse(ExploreRules.untilEnds(moved, atStart, hold - 1), "the same source a few blocks nearer is no change");
        assertTrue(ExploreRules.untilEnds(moved, atStart, hold), "the hold is over: the chain prices again");
        assertTrue(ExploreRules.untilEnds(fresh, atStart, 1), "a new source ends it at once");
        assertTrue(ExploreRules.untilEnds(fresh, null, 0), "none known at the start: a source ends it at once");
        assertFalse(ExploreRules.untilEnds(null, atStart, hold * 10), "no source known: no end on it");
    }
}
