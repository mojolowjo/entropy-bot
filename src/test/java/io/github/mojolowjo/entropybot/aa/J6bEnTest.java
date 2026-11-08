package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run EN (04:34:02-04:34:10, 04:41:53-04:42:00: both caving steps ended NO_PROGRESS on "Baritone found no
 * path 3 times" walking to a branch whose first cell was mid-air, the goal GoalNear(cell, 1)): a branch is reached like the
 * lip - a reach goal over its nearest frontier cells, Baritone picks the stand, arrival is the goal; an empty goal or no
 * path drops the branch with its reason and the next is walked; none left - "no reachable branch".
 */
class J6bEnTest {
    /** EN's save round -60 59 69 (src/test/resources/runEN-branch-60-59-69.txt: S solid, F fluid; the rest open). */
    static Set<Long> enSolid() throws java.io.IOException {
        Set<Long> s = new HashSet<>();
        try (var in = J6bEnTest.class.getResourceAsStream("/runEN-branch-60-59-69.txt")) {
            assertNotNull(in, "the EN save's cells");
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.trim().split(" ");
                if (p[0].equals("S")) s.add(Sightings.cell(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
            }
        }
        return s;
    }

    private static boolean standable(Set<Long> solid, int x, int y, int z) {
        return !solid.contains(Sightings.cell(x, y, z)) && !solid.contains(Sightings.cell(x, y + 1, z)) && solid.contains(Sightings.cell(x, y - 1, z));
    }

    /**
     * EN's -60 59 69 from the save: air with air under it down to the floor at 54. The step's old goal GoalNear(cell, 1)
     * held only the cell and its six neighbours - none of them standable, so Baritone found no path. The reach goal over
     * the cell finds the stands on the cave floor below it (and one beside the bot), so the branch is walked to.
     */
    @Test
    void ensBranchCell_midAir_theOldGoalHadNoStand_theReachGoalHas() throws java.io.IOException {
        Set<Long> solid = enSolid();
        int[] b = {-60, 59, 69};
        assertFalse(solid.contains(Sightings.cell(-60, 59, 69)) || solid.contains(Sightings.cell(-60, 58, 69)), "mid-air: air, air under it");
        int nearStands = 0;
        for (int[] d : List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{-1, 0, 0}, new int[]{0, 1, 0}, new int[]{0, -1, 0}, new int[]{0, 0, 1}, new int[]{0, 0, -1}))
            if (standable(solid, b[0] + d[0], b[1] + d[1], b[2] + d[2])) nearStands++;
        assertEquals(0, nearStands, "GoalNear(cell, 1): no cell a player can stand in - EN's 3 no-paths");
        var goal = J6bCaveStepTest.reachGoal(b, solid);
        assertFalse(goal.isEmpty(), "the reach goal finds stands from which the cell is in reach and sight");
        assertTrue(goal.isInGoal(-60, 55, 69), "the floor under it (grass at 54), 2.9 from the eye");
        assertFalse(goal.isInGoal(-60, 59, 69), "never the mid-air cell itself");
    }

    /** A branch whose only cell is sealed in rock (no stand sees it): its reach goal is empty - dropped; the next branch is walked. */
    @Test
    void anEmptyReachGoal_dropsTheBranch_theNextIsWalked_noneLeftSaysNoReachableBranch() {
        int[] sealed = {0, 60, 0};
        Set<Long> solid = new HashSet<>();
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) for (int y = 45; y < 65; y++) solid.add(Sightings.cell(x, y, z));
        solid.remove(Sightings.cell(0, 60, 0));
        assertTrue(J6bCaveStepTest.reachGoal(sealed, solid).isEmpty(), "no stand sees it");

        var facts = new CaveRecord.Facts(0, 40, 0, 200, 80, 0.6, 2, false, Map.of(), 0, Double.NaN, 0, true, "t");
        long a = Sightings.cell(0, 60, 0), bk = Sightings.cell(30, 40, 0);
        var groups = List.of(io.github.mojolowjo.entropybot.cave.D15PassagesTest.passage(0, Sightings.cellOf(a), 2, 20), io.github.mojolowjo.entropybot.cave.D15PassagesTest.passage(1, Sightings.cellOf(bk), 30, 20));
        int[] feet = {2, 60, 2};
        var first = CaveWalk.next(0, 3, 1, 10, facts, groups, List.of(), feet, 4.5, null, Set.of());
        assertEquals(a, ((CaveWalk.Goto) first).key(), "the cheaper passage first");
        var next = CaveWalk.next(0, 3, 1, 10, facts, groups, List.of(), feet, 4.5, null, Set.of(a));
        assertTrue(next instanceof CaveWalk.Goto g && g.key() == bk, "dropped: the next passage - " + next);
        var none = CaveWalk.next(0, 3, 1, 10, facts, groups, List.of(), feet, 4.5, null, Set.of(a, bk));
        assertEquals(CaveWalk.End.NO_BRANCH, ((CaveWalk.Stop) none).end());
        assertEquals("no reachable passage (2 dropped)", ((CaveWalk.Stop) none).why());
    }

    /** The goal is built over the REACH_CELLS cells nearest the feet (D15 pass two: the frontier groups are deleted). */
    @Test
    void theNearestFewCells() {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < 10; x++) cells.add(new int[]{x, 40, 0});
        List<int[]> n = CaveWalk.nearest(cells, new int[]{9, 40, 0}, CaveWalk.REACH_CELLS);
        assertEquals(CaveWalk.REACH_CELLS, n.size());
        assertEquals(9, n.get(0)[0]);
        assertEquals(6, n.get(3)[0]);
    }

    /**
     * d0682da0 review (rule 17): two branch cells in a tunnel, A 2 blocks from the feet, B 6 (out of reach): the composite
     * goal holds the feet (A's part does), and only A is seen by this arrival - never B by assumption.
     */
    @Test
    void anArrival_seesOnlyTheCellsWhoseOwnGoalHoldsTheFeet() {
        Set<Long> solid = new HashSet<>();
        for (int x = -10; x <= 20; x++) for (int z = -10; z <= 10; z++) for (int y = 45; y < 65; y++) solid.add(Sightings.cell(x, y, z));
        for (int x = 0; x <= 9; x++) { solid.remove(Sightings.cell(x, 60, 0)); solid.remove(Sightings.cell(x, 61, 0)); }     // a 2-high tunnel
        int[] a = {2, 60, 0}, b = {6, 60, 0}, feet = {0, 60, 0};
        var ga = J6bCaveStepTest.reachGoal(a, solid);
        var gb = J6bCaveStepTest.reachGoal(b, solid);
        assertTrue(ga.isInGoal(0, 60, 0) && !gb.isEmpty() && !gb.isInGoal(0, 60, 0), "A in reach and sight from the feet, B not");
        List<int[]> seen = CaveWalk.seenFrom(List.of(ga, gb), List.of(a, b), feet);
        assertEquals(1, seen.size());
        assertArrayEquals(a, seen.get(0), "only A is seen");
    }

    /** The walk probe's last-calc part (the fork's lastCalc for any walk's "no path"; the lip and the caving walks now probed). */
    @Test
    void theWalkProbesLastCalc() throws java.io.IOException {
        assertEquals(" | last calc none (no 'no path' end in this walk)", io.github.mojolowjo.entropybot.commands.ProbeLines.lastCalc(null));
        assertEquals(" | last calc calc FAILURE, nodes 120, best -59 62 72", io.github.mojolowjo.entropybot.commands.ProbeLines.lastCalc("calc FAILURE, nodes 120, best -59 62 72"));
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        assertTrue(src.contains("|| s instanceof Step.ToCave || s instanceof Step.Cave || s instanceof Step.PlaceNear ? s.text() : null"), "the walk probe covers the lip and the caving step (and run EQ's place step)");
    }

    /** One arrived reader: the caving step sets no GoalNear and reads no eye-reach "reached" (rule 23 (2)). */
    @Test
    void theCavingStepHasNoGoalNear_andNoReachedByReach() throws java.io.IOException {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        int from = src.indexOf("private Car.Status caveStatus("), to = src.indexOf("private Car.Status caveEnd(");   // GT fix: besideVolume is gone (the record's cells decide)
        assertTrue(from > 0 && to > from);
        String cave = src.substring(from, to);
        // D15: one GoalNear, at a passage's far end only (a walkable cell of the flood, never a mid-air frontier cell, run EN)
        int gn = cave.indexOf("new GoalNear("), pc = cave.indexOf("g.isPassage()");
        assertTrue(gn > pc && pc > 0 && cave.indexOf("new GoalNear(", gn + 1) < 0, "a GoalNear only in the passage case");
        assertTrue(cave.indexOf("case io.github.mojolowjo.entropybot.cave.CaveWalk.Goto g ->", pc) > gn, "the other gotos keep their reach goals");
        assertFalse(cave.contains("case REACHED"), "no eye-reach arrival");
        assertTrue(cave.contains("cv.reach.isInGoal(me[0], me[1], me[2])"), "arrival read from the goal");
        assertTrue(cave.contains("ExploreRules.NEVER_REACHED"), "the hold only fails or holds");
        assertEquals(0, ExploreRules.NEVER_REACHED.length);
        assertFalse(ExploreRules.withinReach(new int[]{0, 64, 0}, ExploreRules.NEVER_REACHED));
    }
}
