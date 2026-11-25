package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-deposit-park-wake (tester pilot on c606aca8, w005): the main chest on a log column 9 above the ground, played by the
 * simulator (Baritone's real search on the sim's blocks, placing only the bag's spare filler, as the game's lease). The
 * first walk ends short below the chest when the bag has no filler to spare; the deposit then finishes it (blocks got
 * first, the walk again) or, held, wakes by its look-around - never a deposit parked on a held walk while nothing moves.
 * A game ends with the line whole in the chest or the deposit stopped with its reasons named. Hand numbers: the lands are
 * SimDepositGoalTest's; the column at 6 0 next to the feet.
 */
class SimChestUpTest {
    static final int LANDS = 3;                  // sim: the deep lands played

    @AfterEach
    void clear() {
        TestData.clear();
        io.github.mojolowjo.entropybot.aa.brain.Tuning.reset();
    }

    static SimGame land(int seed) {
        java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L);       // structural: as SimDepositGoalTest
        double treeP = 0.05 + r.nextDouble() * 0.25, coalP = 0.005 + r.nextDouble() * 0.03;
        SimWorld w = SimWorld.deep(seed, 63, treeP, coalP, 24, 10);
        int ground = w.standY(6, 64, 0);
        for (int y = ground; y < ground + 9; y++) w.set(6, y, 0, "minecraft:oak_log");       // w005: the chest 9 above the ground
        w.set(6, ground + 9, 0, "minecraft:chest");
        SimGame g = new SimGame(w, new int[]{0, w.standY(0, 64, 0), 0});
        g.sight = 24;
        g.mainAt = "6 " + (ground + 9) + " 0";
        g.order("cobblestone", 32, "b1", true);
        g.order("spruce_log", 16, "b1", true);
        g.order("torch", 4, "b1", true);
        g.play(SimGameTest.TROUBLE_LIMIT_S);
        return g;
    }

    /**
     * The sim's station walk stands within 2 of the station (SimSteps.apply): Baritone's search finds no such stand by a
     * column with a chest on top (a probe of this branch: within 3 it pillars to 70, within 2 "no path" with 64 dirt to
     * spare), so the put is never reached here - what is checked is the wake: after the first short walk the deposit
     * prices its finish (the walk again from there), and parked with every order stopped it takes its look-around leg (an
     * explore) before the game ends on it, the reasons and the owed line said. Before this job the game ended at the first
     * park: "deposit parked" on the held walk, nothing after it.
     */
    @Test
    void aChestNineUp_theShortWalksFinish_thenTheLookAround_thenSaid() {
        for (int seed = 1; seed <= LANDS; seed++) {
            clear();
            SimGame g = land(seed);
            System.out.println("CHEST UP land " + seed + ": " + g.end + " | " + g.depositColumn() + " | chest " + g.chest);
            SimDepositGoalTest.endsWholeOrSaid("land " + seed, g);
            int failed = g.depositLegs.indexOf("walk!");
            if (failed < 0 || g.end.equals("done")) continue;
            assertTrue(g.depositLegs.subList(failed + 1, g.depositLegs.size()).contains("walk!"), "the finish was walked: " + g.depositColumn());
            assertTrue(g.timeline.stream().anyMatch(l -> l.contains("  explore ") && l.contains(" -> ")), "the look-around leg (an explore) before the end: " + g.depositColumn() + "\n" + g.end);
            assertTrue(g.timeline.stream().anyMatch(l -> l.contains("the line's deposit looks around")), "said");
            assertTrue(g.end.contains("owed"), "the end names what is owed: " + g.end);
        }
    }
}
