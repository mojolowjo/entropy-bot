package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-deposit-park-wake (tester pilot on c606aca8, w005): the main chest on a log column 9 above the ground, played by the
 * simulator (Baritone's real search on the sim's blocks, placing only the bag's spare filler, as the game's lease). The
 * first walk ends short below the chest when the bag has no filler to spare; the deposit then finishes it (blocks got
 * first, the walk again) or, held, wakes by its move away - never a deposit parked on a held walk while nothing moves.
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
        // review 1, w005's bag at the deposit (dirt 3, oak_planks 1: no filler to spare for 9 up): as each of the deposit goal's
        // runs starts until its walk first ended short, every placeable block over its owed amount leaves the bag - so the
        // first walk to the chest cannot pillar (a walk up before it digs filler of its own)
        g.beforeRun = () -> {
            if (g.depositLegs.contains("walk!") || io.github.mojolowjo.entropybot.aa.brain.Goals.depositGoal(g.bb) == null) return;
            java.util.Map<String, Integer> owed = new java.util.HashMap<>();
            for (var e : g.deposits.owed().entrySet()) owed.put(SimSteps.full(e.getKey()), e.getValue());
            for (var e : g.bag.entrySet())
                if (io.github.mojolowjo.entropybot.aa.AaEscape.pillarBlock(e.getKey())) e.setValue(Math.min(e.getValue(), owed.getOrDefault(e.getKey(), 0)));
        };
        g.play(SimGameTest.TROUBLE_LIMIT_S);
        return g;
    }

    /**
     * Review 1 (the reviewer: no put was ever reached here, and a land with no short walk passed with nothing checked): the
     * sim's station walk is now the game's (SimSteps.toStation: done in reach from the eye, Baritone's search to the first
     * cell in reach). Every land: the first walk to the chest ends short (no filler to spare, so no pillar) - checked, never
     * skipped; then the deposit finishes it (the blocks got first, the walk again from there) and the line reaches the chest
     * whole, once. If a land ends parked instead, its wake was the move away (never an untargeted explore, rule 22) and the
     * end names what is owed.
     */
    @Test
    void aChestNineUp_theShortWalkIsFinished_theLineReachesTheChestWhole() {
        int put = 0;
        for (int seed = 1; seed <= LANDS; seed++) {
            clear();
            SimGame g = land(seed);
            System.out.println("CHEST UP land " + seed + ": " + g.end + " | " + g.depositColumn() + " | chest " + g.chest);
            SimDepositGoalTest.endsWholeOrSaid("land " + seed, g);
            int failed = g.depositLegs.indexOf("walk!");
            assertTrue(failed >= 0, "land " + seed + ": the first walk to the chest 9 up ends short: " + g.depositColumn());
            assertTrue(g.depositLegs.subList(failed + 1, g.depositLegs.size()).contains("walk"), "land " + seed + ": the finish was walked: " + g.depositColumn());
            assertTrue(g.timeline.stream().noneMatch(l -> l.contains("  explore ") && l.contains("deposit")), "land " + seed + ": no untargeted explore for the deposit");
            if (g.end.equals("done")) { put++; continue; }
            assertTrue(g.timeline.stream().anyMatch(l -> l.contains("the line's deposit moves away")), "land " + seed + ": its wake was the move away: " + g.end);
            assertTrue(g.end.contains("owed"), "land " + seed + ": the end names what is owed: " + g.end);
        }
        assertEquals(LANDS, put, "every land: the finish puts the line whole in the chest");
    }
}