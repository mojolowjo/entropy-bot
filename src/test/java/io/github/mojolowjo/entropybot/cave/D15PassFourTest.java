package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D15 pass four (run JAM 02:13:33: the lip's flood held 2 cells of a record promising 38 open passages, NO_BRANCH twice in
 * one second from the same feet; run JAK: one cave space, two records): a pocket lip asks Baritone down; no end without a
 * move or a new flood; overlapping records merge and their done holds for both entries. No game: hand-built cells.
 */
public class D15PassFourTest {
    static final String D = "minecraft:overworld";

    @AfterEach
    void clear() { Tuning.reset(); }

    static Sightings cave(int ex, int ey, int ez) {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(ex, ey, ez, CaveIndex.Size.LARGE, CaveIndex.Depth.STONE, List.of(Sightings.cell(ex, ey, ez))), 1));
        return s;
    }

    static List<Long> tunnel(int x0, int x1, int y) {
        List<Long> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) { out.add(Sightings.cell(x, y, 0)); out.add(Sightings.cell(x, y + 1, 0)); }
        return out;
    }

    /** The body at y 40 (from the entry), a pocket ledge at y 45 - 5 above the body, no walkable way between. */
    static Sightings pocketCave() {
        Sightings s = cave(0, 40, 0);
        List<Long> cells = new ArrayList<>(tunnel(-20, 20, 40));
        cells.addAll(tunnel(30, 31, 45));
        s.caveCells(D, 0, 40, 0, cells);
        return s;
    }

    static CaveWalk.Next stepAt(Sightings s, int[] feet, Set<Long> dropped, boolean moved) {
        var found = s.cavePassages(D, 0, 40, 0, feet, Set.of(), 3, 3, 4096, 8, 5);
        Set<Long> flood = new HashSet<>();
        for (var p : found.passages()) flood.addAll(p.cells());
        var pr = s.cavePromise(D, 0, 40, 0, feet, flood, Tuning.i("cave.pocketTries"));
        var pk = new CaveWalk.Pocket(found.start() == null ? feet : found.start(), flood.size(), pr.cells(), pr.targets(), moved);
        return CaveWalk.next(0, 3, 0.1, 10, s.caveFacts(D, 0, 40, 0), found.passages(), List.of(), feet, 10, new int[]{0, 40, 0}, dropped, found.capped(), Set.of(), pk);
    }

    @Test
    void aPocketLip_withTheBodyFiveBelow_asksBaritoneDown_notNoBranch() {
        Sightings s = pocketCave();
        var next = stepAt(s, new int[]{30, 45, 0}, Set.of(), false);
        assertTrue(next instanceof CaveWalk.Goto g && g.isDescent(), "a descent, not NO_BRANCH: " + next);
        var g = (CaveWalk.Goto) next;
        assertEquals(40, g.cell()[1], "the body's cell 5 below: " + g.why());
        assertTrue(g.why().contains("is a pocket (") && g.why().contains("by Baritone"), g.why());
        // Baritone finds no path to any target: the end says so, with the cells tried
        var pr = s.cavePromise(D, 0, 40, 0, new int[]{30, 45, 0}, Set.of(), Tuning.i("cave.pocketTries"));
        Set<Long> all = new HashSet<>();
        for (int[] t : pr.targets()) all.add(Sightings.cell(t[0], t[1], t[2]));
        var end = stepAt(s, new int[]{30, 45, 0}, all, false);
        assertTrue(end instanceof CaveWalk.Stop st && st.end() == CaveWalk.End.NO_BRANCH && st.why().contains("no path") && st.why().contains("tried:"), "" + end);
    }

    @Test
    void noNoBranchEnd_withoutAMoveOrANewFlood() {
        // the flood is not a pocket (no promise past it): only the targets the record holds off the flood
        var target = new int[]{50, 40, 0};
        var pk = new CaveWalk.Pocket(new int[]{0, 40, 0}, 100, 100, List.of(target), false);
        Set<Long> mouthDropped = Set.of(Sightings.cell(0, 40, 0));
        var next = CaveWalk.next(0, 3, 0.1, 10, null, List.of(), List.of(), new int[]{0, 40, 0}, 10, new int[]{0, 40, 0}, mouthDropped, false, Set.of(), pk);
        assertTrue(next instanceof CaveWalk.Goto g && g.isDescent() && g.cell()[0] == 50, "the next target before an end: " + next);
        var moved = new CaveWalk.Pocket(new int[]{0, 40, 0}, 100, 100, List.of(target), true);
        var end = CaveWalk.next(0, 3, 0.1, 10, null, List.of(), List.of(), new int[]{0, 40, 0}, 10, new int[]{0, 40, 0}, mouthDropped, false, Set.of(), moved);
        assertTrue(end instanceof CaveWalk.Stop st && st.end() == CaveWalk.End.NO_BRANCH, "moved: the end may come - " + end);
        Set<Long> both = Set.of(Sightings.cell(0, 40, 0), Sightings.cell(50, 40, 0));
        var tried = CaveWalk.next(0, 3, 0.1, 10, null, List.of(), List.of(), new int[]{0, 40, 0}, 10, new int[]{0, 40, 0}, both, false, Set.of(), pk);
        assertTrue(tried instanceof CaveWalk.Stop st && st.why().contains("50 40 0"), "every target tried: the end names them - " + tried);
    }

    static Sightings twoEntries() {
        Sightings s = cave(0, 40, 0);
        assertNotNull(s.knowCave(D, new CaveIndex.Known(30, 40, 0, CaveIndex.Size.LARGE, CaveIndex.Depth.STONE, List.of(Sightings.cell(30, 40, 0))), 2));
        s.caveCells(D, 0, 40, 0, tunnel(-10, 20, 40));
        s.caveCells(D, 30, 40, 0, tunnel(15, 40, 40));                       // shares x 15..20 with the first
        return s;
    }

    @Test
    void twoOverlappingRecords_mergeIntoTheOlder_keepingBothLips() {
        Sightings s = twoEntries();
        List<int[]> lips = s.caveLips(D, 0, 40, 0);
        assertEquals(1, lips.size());
        assertArrayEquals(new int[]{30, 40, 0}, lips.get(0));
        CaveRecord.Facts a = s.caveFacts(D, 0, 40, 0), b = s.caveFacts(D, 30, 40, 0);
        assertEquals(2 * 51, a.cellCount(), "the cells united: x -10..40, two high");
        assertEquals(a.x(), b.x(), "the second entry reads the older record");
    }

    @Test
    void aMergedRecordsDone_holdsForBothEntries() {
        Sightings s = twoEntries();
        s.caveDone(D, 30, 40, 0, "every passage walked");
        assertTrue(s.caveFacts(D, 0, 40, 0).done());
        assertTrue(s.caveFacts(D, 30, 40, 0).done());
        assertFalse(s.caveFacts(D, 0, 40, 0).offeredFor("iron"));
        assertFalse(s.caveFacts(D, 30, 40, 0).offeredFor("iron"));
    }
}
