package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * deposit-goal plan, stage 3 (piece 6): the deposit goal played by the simulator on the deep-stone lands with the far
 * chest (as SimGameTest.theOwnersLine_onDeepStoneAndAFarChest): its walk, its ways up, the put. A game ends with the line
 * whole in the chest, or with the deposit stopped and its reasons named - never a deposit owed and nothing said. Hand
 * numbers: the lands and the chest cell are SimGameTest's.
 */
class SimDepositGoalTest {
    static final int LANDS = 3;                  // sim: the deep lands played (SimGameTest plays all of them without the fault)

    @AfterEach
    void clear() {
        TestData.clear();
        io.github.mojolowjo.entropybot.aa.brain.Tuning.reset();
    }

    static SimGame land(int seed, int walkUpFailures) {
        java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L);       // structural: spreads the small seeds (as SimGameTest)
        double treeP = 0.05 + r.nextDouble() * 0.25, coalP = 0.005 + r.nextDouble() * 0.03;
        SimWorld w = SimWorld.deep(seed, 63, treeP, coalP, 24, 10);
        w.set(40, 64, 45, "minecraft:chest");
        SimGame g = new SimGame(w, new int[]{0, w.standY(0, 64, 0), 0});
        g.sight = 24;
        g.mainAt = "40 64 45";
        g.walkUpFailures = walkUpFailures;
        g.order("cobblestone", 32, "b1", true);
        g.order("spruce_log", 16, "b1", true);
        g.order("torch", 4, "b1", true);
        g.play(SimGameTest.TROUBLE_LIMIT_S);
        return g;
    }

    static void endsWholeOrSaid(String name, SimGame g) {
        String t = g.timelineText();
        if (g.end.equals("done")) {
            assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:spruce_log", 16, "minecraft:torch", 4), g.chest, name + ": the -d amounts reach the chest whole\n" + t);
            assertTrue(g.depositLegs.contains("put"), name + ": " + g.depositColumn());
        } else {
            assertTrue(g.end.startsWith("deposit parked: "), name + ": the end is the line whole or a stopped deposit with its reasons: " + g.end + "\n" + t);
            assertFalse(g.deposits.owed().isEmpty(), name + ": still owed: " + g.end);
        }
    }

    @Test
    void deepLandsFarChest_theDepositGoalEndsWholeOrSaid() {
        for (int seed = 1; seed <= LANDS; seed++) {
            clear();
            SimGame g = land(seed, 0);
            System.out.println("DEPOSIT land " + seed + ": " + g.end + " | " + g.depositColumn() + " | chest " + g.chest);
            endsWholeOrSaid("land " + seed, g);
        }
    }

    /** Review 4 (point 1): a chest that takes 10 items: one put, then the deposit holds on the full chest, no walk back. */
    @Test
    void aFullChest_onePut_thenHeld_noLoop() {
        boolean seen = false;
        for (int seed = 1; seed <= LANDS; seed++) {
            clear();
            java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L);       // structural: as land()
            double treeP = 0.05 + r.nextDouble() * 0.25, coalP = 0.005 + r.nextDouble() * 0.03;
            SimWorld w = SimWorld.deep(seed, 63, treeP, coalP, 24, 10);
            w.set(40, 64, 45, "minecraft:chest");
            SimGame g = new SimGame(w, new int[]{0, w.standY(0, 64, 0), 0});
            g.sight = 24;
            g.mainAt = "40 64 45";
            g.chestRoom = 10;                    // test: room for 10 of the 52
            g.order("cobblestone", 32, "b1", true);
            g.order("spruce_log", 16, "b1", true);
            g.order("torch", 4, "b1", true);
            g.play(SimGameTest.TROUBLE_LIMIT_S);
            System.out.println("DEPOSIT land " + seed + " (a chest for 10): " + g.end + " | " + g.depositColumn() + " | chest " + g.chest);
            long puts = g.depositLegs.stream().filter(x -> x.startsWith("put")).count();
            if (puts == 0) continue;
            seen = true;
            assertEquals(1, puts, "one put, never a walk back to the full chest: " + g.depositColumn() + "\n" + g.timelineText());
            assertTrue(g.end.startsWith("deposit parked: "), g.end);
            assertNotNull(g.chestHold.hold(), g.end);
            assertEquals(io.github.mojolowjo.entropybot.aa.brain.MainChestHold.Kind.FULL, g.chestHold.hold().kind());
            assertEquals(10, g.chest.values().stream().mapToInt(Integer::intValue).sum(), "what fit went in");
            assertEquals(42, g.deposits.owed().values().stream().mapToInt(Integer::intValue).sum(), "the rest is owed, once: " + g.deposits.owed());
        }
        assertTrue(seen, "no land reached the chest");
    }

    @Test
    void anInjectedWalkUpFailure_endsInAnotherWay() {
        boolean seen = false;
        for (int seed = 1; seed <= LANDS; seed++) {
            clear();
            SimGame g = land(seed, 1);
            System.out.println("DEPOSIT land " + seed + " (its first walk up fails): " + g.end + " | " + g.depositColumn() + " | chest " + g.chest);
            endsWholeOrSaid("land " + seed, g);
            int failed = g.depositLegs.indexOf("walk up!");
            if (failed < 0) continue;
            seen = true;
            assertTrue(failed < g.depositLegs.size() - 1 || g.end.startsWith("deposit parked"), "another way after the failed walk up: " + g.depositColumn());
        }
        assertTrue(seen, "no deposit walked up in the lands: the fault was never injected");
    }
}
