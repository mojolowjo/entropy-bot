package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b (D12, to-do/j6b-caving-step.md): the caving step's decisions on the record, the cave way priced from the
 * record, and item 5 - the explore's surface goal and the walk yielding to a credited opening.
 */
class J6bCaveStepTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static CaveRecord.Facts facts(boolean loaded, boolean done) {
        return new CaveRecord.Facts(0, 40, 0, 200, 80, 0.6, 2, done, Map.of(), 0, Double.NaN, 0, loaded, "test");
    }

    static final int[] FEET = {10, 40, 0};

    @Test
    void theStepsMoves_inOrder() {
        List<CaveRecord.Group> two = List.of(new CaveRecord.Group(Sightings.cell(40, 40, 0), 1, 40), new CaveRecord.Group(Sightings.cell(100, 40, 0), 1, 40));
        assertEquals(CaveWalk.End.NEED_MET, ((CaveWalk.Stop) CaveWalk.next(3, 3, 1, 10, facts(true, false), two, List.of(), FEET, 4.5)).end());
        assertEquals(CaveWalk.End.MINUTES, ((CaveWalk.Stop) CaveWalk.next(1, 3, 10, 10, facts(true, false), two, List.of(), FEET, 4.5)).end());
        var mine = CaveWalk.next(0, 3, 1, 10, facts(true, false), two, List.of(new int[]{12, 41, 0}, new int[]{60, 40, 1}), FEET, 4.5);
        assertArrayEquals(new int[]{12, 41, 0}, ((CaveWalk.Mine) mine).cell(), "a seen ore in reach: mined");
        var toOre = CaveWalk.next(0, 3, 1, 10, facts(true, false), two, List.of(new int[]{60, 40, 1}), FEET, 4.5);
        assertTrue(((CaveWalk.Goto) toOre).why().startsWith("the seen ore at 60 40 1"), "a seen ore farther in: walked to before a branch");
        var branch = CaveWalk.next(0, 3, 1, 10, facts(true, false), two, List.of(), FEET, 4.5);
        assertArrayEquals(new int[]{40, 40, 0}, ((CaveWalk.Goto) branch).cell(), "the nearest branch (ranked rough, by straight distance)");
        assertTrue(((CaveWalk.Goto) branch).why().contains("40 cells behind"));
        var nook = CaveWalk.next(0, 3, 1, 10, facts(true, true), List.of(new CaveRecord.Group(Sightings.cell(40, 40, 0), 1, CaveRecord.BRANCH_MIN)), List.of(), FEET, 4.5);
        assertEquals(CaveWalk.End.DONE, ((CaveWalk.Stop) nook).end(), "a nook is no branch: done");
        assertEquals(CaveWalk.End.NOT_LOADED, ((CaveWalk.Stop) CaveWalk.next(0, 3, 1, 10, facts(false, false), List.of(), List.of(), FEET, 4.5)).end());
    }

    @Test
    void theEndLineSaysWhatTheRunMeasures() {
        String l = CaveWalk.endLine("0 40 0", CaveWalk.End.DONE, "explored", 2.42, 40, facts(true, true), 4, 3);
        assertEquals("cave: the caving step at 0 40 0 ended - DONE (explored): 2.4 min inside, cells seen 40 -> 120 of 200 (explored 60%), ore seen 4 / mined 3, branches left 2", l);
    }

    /** The cave way from the record: ore seen and left is mined first; the rest at the measured yield in what the unseen part holds. */
    @Test
    void theCaveWayIsPricedFromTheRecord() {
        CaveWay left = new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 0.5, true, CaveIndex.Size.SMALL, 0.2, 3);
        Chain c = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(left));
        assertTrue(Z77CaveWayTest.caveMined(c), "3 seen and left there: a way though its unseen part holds 0.2 min - " + c.why());
        Step.Cave k = (Step.Cave) c.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertEquals(1, k.minutes(), "at least a minute for the walk to the left ore");
        CaveWay little = new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 0.5, true, CaveIndex.Size.LARGE, 0.2, 0);
        Chain d = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(little));
        assertFalse(Z77CaveWayTest.caveMined(d));
        assertTrue(d.why().contains("min its unseen part holds"), "the record's unseen part, not the size bucket: " + d.why());
        CaveWay measured = new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 3, true, CaveIndex.Size.LARGE, 8, 0);
        Chain e = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(measured));
        assertTrue(Z77CaveWayTest.caveMined(e), e.why());
        assertTrue(e.why().contains("yield ~3.00 ore/min (measured)"), e.why());
    }

    /** Item 5 (a): an unexplored chunk is walked to as its surface cell; not loaded yet: the column goal. */
    @Test
    void noCreditedOpening_anUnexploredSurfaceCell() {
        assertArrayEquals(new int[]{40, 71, -8}, ExploreRules.surfaceCell(40, -8, 71));
        assertNull(ExploreRules.surfaceCell(40, -8, io.github.mojolowjo.entropybot.world.SurfaceHeight.NOT_KNOWN), "not loaded: not known (rule 15)");
    }

    /** Item 5 (b): a rim credit mid-walk - the chunk walk yields to the opening at once; a visited one is passed over. */
    @Test
    void aRimCreditMidWalk_theTargetSwitchesAtOnce() {
        List<int[]> credited = List.of(new int[]{5, 64, 5}, new int[]{9, 64, 9});
        assertArrayEquals(new int[]{5, 64, 5}, ExploreRules.yieldTo(true, credited, null));
        assertArrayEquals(new int[]{9, 64, 9}, ExploreRules.yieldTo(true, credited, c -> c[0] == 5), "the visited one passed over");
        assertNull(ExploreRules.yieldTo(true, List.of(), null), "none credited: the walk goes on");
        assertNull(ExploreRules.yieldTo(false, credited, null), "a walk toward an opening never yields");
    }
}
