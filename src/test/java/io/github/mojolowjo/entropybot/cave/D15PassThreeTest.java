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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D15 pass three (run JAK, 2026-11-08: DONE at 12% explored, 234 cells "seen" by a 3 s walk, one ore seen and none mined):
 * the capped flood goes on from its farthest ends and is not DONE; seen is looked at (the eyes' cone); a seen ore within
 * the mine's sight pauses the walk; a DONE record is not offered again. No game: hand-built cells.
 */
public class D15PassThreeTest {
    static final String D = "minecraft:overworld";

    @AfterEach
    void clear() { Tuning.reset(); }

    /** The record now: {@code cells} cells, {@code unseen} of them not seen. */
    static CaveRecord.Facts facts(int cells, int unseen) {
        return new CaveRecord.Facts(0, 40, 0, cells, unseen, (cells - unseen) / (double) cells, 0, false, Map.of(), 0, Double.NaN, 0, true, "x");
    }

    static Passages.Passage capped(int id, int[] end, int unseen) {
        return new Passages.Passage(id, -1, new int[]{0, 40, 0}, end, 30, 1, 0, List.of(), unseen, 0, Passages.EndKind.CAPPED);
    }

    static Passages.Passage walked(int id, int[] end) {
        return new Passages.Passage(id, -1, new int[]{0, 40, 0}, end, 10, 1, 0, List.of(), 0, 0, Passages.EndKind.DEAD_END);
    }

    @Test
    void theCappedFlood_goesOnFromItsEnd_andIsNotDone() {
        // every cell of the flood seen, the flood capped, 40000 of 45000 cells unseen past it (run JAK)
        var ps = List.of(walked(0, new int[]{-5, 40, 0}), capped(1, new int[]{30, 40, 0}, 0));
        var next = CaveWalk.next(0, 3, 1, 10, facts(45000, 40000), ps, List.of(), new int[]{0, 40, 0}, 10, null, Set.of(), true, Set.of());
        assertTrue(next instanceof CaveWalk.Goto g && g.isPassage() && g.cell()[0] == 30, "the capped end, priced by the cells past it: " + next);
        assertTrue(((CaveWalk.Goto) next).why().contains("the flood's cap"), ((CaveWalk.Goto) next).why());
        // stood at that end and flooded again: spent; explored under cave.doneExplored - not DONE
        Set<Long> again = Set.of(Sightings.cell(31, 40, 0));
        var under = CaveWalk.next(0, 3, 2, 10, facts(45000, 40000), ps, List.of(), new int[]{30, 40, 0}, 10, null, Set.of(), true, again);
        assertTrue(under instanceof CaveWalk.Stop s && s.end() == CaveWalk.End.NO_BRANCH && s.why().contains("under cave.doneExplored"), "not done at 11%: " + under);
        var over = CaveWalk.next(0, 3, 2, 10, facts(45000, 1000), ps, List.of(), new int[]{30, 40, 0}, 10, null, Set.of(), true, again);
        assertTrue(over instanceof CaveWalk.Stop s && s.end() == CaveWalk.End.DONE, "explored past cave.doneExplored: done - " + over);
    }

    @Test
    void aWholeFlood_withCellsOffIt_isDone_withTheReasonSaid() {
        var ps = List.of(walked(0, new int[]{-5, 40, 0}), walked(1, new int[]{5, 40, 0}));
        var next = CaveWalk.next(0, 3, 1, 10, facts(45000, 40000), ps, List.of(), new int[]{0, 40, 0}, 10, null, Set.of(), false, Set.of());
        assertTrue(next instanceof CaveWalk.Stop s && s.end() == CaveWalk.End.DONE, "" + next);
        assertTrue(((CaveWalk.Stop) next).why().contains("40000 unseen cell(s)") && ((CaveWalk.Stop) next).why().contains("unreachable by walking"), ((CaveWalk.Stop) next).why());
        var all = CaveWalk.next(0, 3, 1, 10, facts(45000, 0), ps, List.of(), new int[]{0, 40, 0}, 10, null, Set.of(), false, Set.of());
        assertTrue(all instanceof CaveWalk.Stop s && s.end() == CaveWalk.End.DONE && s.why().contains("every cell of the cave is seen"), "" + all);
    }

    static Sightings tunnel(int len) {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(0, 40, 0, CaveIndex.Size.LARGE, CaveIndex.Depth.STONE, List.of(Sightings.cell(0, 40, 0))), 1));
        List<Long> cells = new ArrayList<>();
        for (int x = -len; x <= len; x++) { cells.add(Sightings.cell(x, 40, 0)); cells.add(Sightings.cell(x, 41, 0)); }
        s.caveCells(D, 0, 40, 0, cells);
        return s;
    }

    @Test
    void seenIsLookedAt_notWalkedPast() {
        Sightings all = tunnel(20);
        int stood = all.caveStood(D, 0, 40, 0, 0, 40, 0, 1);
        Sightings cone = tunnel(20);
        int looked = cone.caveStood(D, 0, 40, 0, 0, 40, 0, 1, (x, y, z) -> x >= 0);   // the eyes toward +x
        assertTrue(looked > 0 && looked < stood, "only the cells in the eyes' cone: " + looked + " of " + stood);
        assertFalse(cone.caveFacts(D, 0, 40, 0).unseen() == all.caveFacts(D, 0, 40, 0).unseen());
        List<Long> behind = List.of(Sightings.cell(-3, 40, 0), Sightings.cell(-3, 41, 0));
        assertEquals(0, cone.caveSeenOf(D, 0, 40, 0, behind), "the cells behind the back are walked past, not seen");
        assertEquals(2, all.caveSeenOf(D, 0, 40, 0, behind));
    }

    @Test
    void aSeenOreFace_withinTheMinesSight_pausesTheWalk() {
        Set<Long> air = new HashSet<>();
        for (int x = 0; x <= 30; x++) { air.add(Sightings.cell(x, 40, 0)); air.add(Sightings.cell(x, 41, 0)); }
        int[] ore = {20, 40, 1};
        var f = Passages.build((x, y, z) -> air.contains(Sightings.cell(x, y, z)), c -> false, new int[]{0, 40, 0}, Set.of(Sightings.cell(20, 40, 1)), 3, 3, 4096);
        assertEquals(1, f.passages().get(0).oreFaces(), "the one face on its wall");
        assertNull(CaveWalk.oreToMine(List.of(ore), new int[]{0, 40, 0}, 10), "20 blocks off: the walk goes on");
        assertArrayEquals(ore, CaveWalk.oreToMine(List.of(ore), new int[]{11, 40, 0}, 10), "within the mine's sight: paused for it");
        var next = CaveWalk.next(0, 3, 1, 10, null, f.passages(), List.of(ore), new int[]{11, 40, 0}, 10);
        assertTrue(next instanceof CaveWalk.Mine m && m.cell()[0] == 20, "the mine takes it: " + next);
    }

    @Test
    void aDoneRecord_isNotOfferedAgain_untilItGrows() {
        Sightings s = tunnel(20);
        assertFalse(s.caveFacts(D, 0, 40, 0).done());
        s.caveDone(D, 0, 40, 0, "every passage walked");
        var f = s.caveFacts(D, 0, 40, 0);
        assertTrue(f.done());
        assertFalse(f.offeredFor("iron"), "done, no iron left: not offered");
        s.caveCells(D, 0, 40, 0, List.of(Sightings.cell(0, 40, 5), Sightings.cell(0, 41, 5)));
        assertFalse(s.caveFacts(D, 0, 40, 0).done(), "more of the cave known: offered again");
    }
}
