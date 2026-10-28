package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6d, the second cause (run EV's 10 minutes: "ore seen 36 / mined 0"; the diagnosis in j6b "## Run EV"). The
 * cells are from EV's save (run-saves/runEV-z78j6r.zip, region r.-1.-1, read after the run): coal_ore at -52 27 -37 and
 * -51 26 -36; the feet cell -55 25 -40 is air over stone at y 24, air at y 26; the cells on the eye's line to the first ore
 * (-54 26 -39, -53 26 -38, -53 27 -38, -54 27 -39) are air. The log: "at the seen ore at -52 27 -37 (feet -55 25 -40, in its
 * reach goal ...)" then "dropped: arrived, nothing new" - both seen-ore gotos dropped, then skipped by CaveWalk.next.
 */
class J6dEvTest {
    private static final int[] FEET = {-55, 25, -40};
    private static final int[] ORE_A = {-52, 27, -37};
    private static final int[] ORE_B = {-51, 26, -36};

    private static long key(int[] c) { return Sightings.cell(c[0], c[1], c[2]); }

    @Test
    void evTheFirstOreInReachIsMinedThoughItsGotoWasDropped() {
        assertTrue(ExploreRules.withinReach(FEET, ORE_A), "eyes to -52 27 -37: 4.33 <= 4.5");
        assertFalse(ExploreRules.withinReach(FEET, ORE_B));
        // the old drops (run EV): both seen-ore keys were in the dropped list
        var next = CaveWalk.next(0, 1, 1.0, 10, null, List.of(), List.of(ORE_B, ORE_A), FEET, ExploreRules.OPENING_REACH, null,
                Set.of(key(ORE_A), key(ORE_B)));
        assertInstanceOf(CaveWalk.Mine.class, next);
        assertArrayEquals(ORE_A, ((CaveWalk.Mine) next).cell());
        // the vein mined it: 1 of 1 - the step ends NEED_MET
        var end = CaveWalk.next(1, 1, 1.2, 10, null, List.of(), List.of(ORE_B), FEET, ExploreRules.OPENING_REACH, null, Set.of(key(ORE_A), key(ORE_B)));
        assertInstanceOf(CaveWalk.Stop.class, end);
        assertEquals(CaveWalk.End.NEED_MET, ((CaveWalk.Stop) end).end());
    }

    @Test
    void aSeenOreOutOfReachIsAnOreGotoAndItsArrivalIsNeverDropped() {
        int[] far = {-60, 25, -40};
        var next = CaveWalk.next(0, 1, 0, 10, null, List.of(), List.of(ORE_A), far, ExploreRules.OPENING_REACH, null, Set.of());
        CaveWalk.Goto g = assertInstanceOf(CaveWalk.Goto.class, next);
        assertTrue(g.ore());
        assertArrayEquals(ORE_A, g.cell());
        assertFalse(CaveWalk.dropsOnNothingNew(true, 40, 40), "a walk to a seen ore: never 'arrived, nothing new'");
        assertTrue(CaveWalk.dropsOnNothingNew(false, 40, 40), "a branch or unseen-cell walk that saw nothing new: dropped (run EO)");
        assertFalse(CaveWalk.dropsOnNothingNew(false, 40, 41));
    }

    @Test
    void theUnseenCellGotoIsNotAnOreGoto() {
        var next = CaveWalk.next(0, 1, 0, 10, null, List.of(), List.of(), FEET, ExploreRules.OPENING_REACH, new int[]{-50, 30, -40}, Set.of());
        assertFalse(assertInstanceOf(CaveWalk.Goto.class, next).ore());
    }

    /** The adapter: the drop reads the ore flag; "in sight from here" picks the next move in the same tick (no goal-less tick). */
    @Test
    void theAdapterDropsBranchesOnlyAndPicksTheNextMoveWhenInSight() throws Exception {
        String src = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        assertTrue(src.contains("CaveWalk.dropsOnNothingNew(ore, cv.seenAtGoal, seenNow)"));
        assertFalse(src.contains("\" in sight from here\")"), "in sight no longer returns a status with no goal set");
        assertTrue(src.contains("for (int move = 0; move < IN_SIGHT_PASSES; move++)"));
        assertTrue(src.contains("cv.goalOre = g.ore();"));
    }
}
