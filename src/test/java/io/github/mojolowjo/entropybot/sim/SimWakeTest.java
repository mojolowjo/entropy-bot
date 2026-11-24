package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * stop-wake on the simulator (docs repo to-do/stop-wake-plan.md, build order 5): the REAL brain, order-end pass and wake
 * check on a fake world. Cloud tester run 11 (2026-11-21): both orders stopped on "blocked by water", the bot walked 40
 * blocks to the chest and stood there 26 minutes under open sky.
 */
class SimWakeTest {
    static final String WATER = "stopped at step 1 (dig -282 49 -76 -282 49 -76): blocked by water at -283 49 -76";

    @AfterEach
    void clear() {
        TestData.clear();
        io.github.mojolowjo.entropybot.aa.brain.Tuning.reset();
    }

    /** Run 11's first half: an order stopped where it stood; the bot then stands 30 blocks away (the deposit walk): it runs again and ends done. */
    @Test
    void aStoppedOrderRunsAgainAfterTheBotMoved() {
        SimGame g = SimGameTest.treeMap(6);
        Goals.Goal log = g.order("spruce_log", 4, null, false);
        assertTrue(Goals.park(g.bb, log, WATER));          // stopped before any run of it starts
        for (int i = 0; i < 5; i++) { g.clock.advance(SimGame.IDLE_TICK_MS); g.tick(); }
        assertNotNull(log.parked, "nothing changed: it stays stopped\n" + g.timelineText());
        g.feet[0] = -30;                                  // the walk another job made (run 11: the line's deposit)
        String end = g.play(600);
        System.out.println(g.timelineText());
        assertTrue(g.timelineText().contains("order spruce_log runs again: moved 30 blocks from where it stopped (0 64 0)"), g.timelineText());
        assertEquals("done", end, g.timelineText());
        assertTrue(g.bag.getOrDefault("minecraft:spruce_log", 0) >= 4, g.bag.toString());
    }

    /** Run 11's second half: every order stopped under open sky and nothing moves the bot - one look-around leg, then the order is priced and done. */
    @Test
    void everyOrderStoppedUnderOpenSky_theBotLooksAround() {
        // a land with no edge (the explore's actor walks it), trees and stone scattered; the senses see 40 blocks
        SimWorld w = SimWorld.endless(3, 63, 0.15, 0.05);
        SimGame g = new SimGame(w, new int[]{0, w.standY(0, 64, 0), 0});
        g.sight = 40;
        Goals.Goal log = g.order("spruce_log", 4, null, false);
        assertTrue(Goals.park(g.bb, log, WATER));          // stopped before any run of it starts
        String end = g.play(900);
        System.out.println(g.timelineText());
        String tl = g.timelineText();
        assertTrue(tl.contains("order spruce_log looks around:"), tl);
        int parked = tl.indexOf("every order is parked"), looks = tl.indexOf("looks around:");
        assertTrue(parked >= 0 && parked < looks, "the all-parked line first, the leg after goals.lookAroundAfterS:\n" + tl);
        assertEquals("done", end, tl);
        assertTrue(g.bag.getOrDefault("minecraft:spruce_log", 0) >= 4, g.bag.toString());
    }

    /**
     * Cloud tester run 43 (2026-11-22): an order met inside its batch's run leaves the goals while that run carries on
     * for the next order - the kept -d amounts are still held from that run (Goals.keptForRun); a run that is no
     * order's (the deposit itself) may use the bag as it is.
     */
    @Test
    void aCarriedRunStillKeepsTheDepositAmounts() {
        SimGame g = SimGameTest.treeMap(6);
        g.give("minecraft:cobblestone", 32);
        g.order("cobblestone", 32, "b1", true);
        Goals.Goal log = g.order("spruce_log", 4, "b1", true);
        g.tick();                                        // cobblestone is met at once: it leaves the goals, its 32 are due
        assertNull(Goals.find(g.bb, "cobblestone"), g.timelineText());
        assertEquals(32, Goals.keptForRun(g.bb, log.id(), true).getOrDefault("cobblestone", 0), "held from the other order's run");
        assertEquals(32, Goals.keptForRun(g.bb, "goal.cobblestone", true).getOrDefault("cobblestone", 0), "held from the met order's carried run too (was: nothing)");
        assertTrue(Goals.keptForRun(g.bb, null, false).isEmpty(), "no order's run (the deposit): the bag as it is");
    }

    /**
     * reserves-once (b), run 3 w064: the running order's OWN -d amount was free to its trip's furnace craft (8 of its 32
     * cobblestone) and a smelt's fuel. Asked what the run may use up, the one reader holds every -d amount, its own too;
     * asked what its steps may count as theirs, it still holds only the others'.
     */
    @Test
    void theRunningOrdersOwnKeptAmountIsNotUsedUp() {
        SimGame g = SimGameTest.treeMap(6);
        g.give("minecraft:cobblestone", 20);
        Goals.Goal cob = g.order("cobblestone", 32, "b1", true);
        g.tick();                                        // the senses read the bag
        assertNotNull(Goals.find(g.bb, "cobblestone"), g.timelineText());
        int own = Goals.kept(g.bb, null, null).getOrDefault("cobblestone", 0);
        assertTrue(own > 0, "the order's own 20 are kept for its deposit: " + Goals.kept(g.bb, null, null));
        assertEquals(0, Goals.keptForRun(g.bb, cob.id(), true).getOrDefault("cobblestone", 0), "its steps count its own");
        assertEquals(own, Goals.keptForRun(g.bb, cob.id(), true, true).getOrDefault("cobblestone", 0), "a craft or a smelt may not use them up");
        assertTrue(Goals.keptForRun(g.bb, null, false, true).isEmpty(), "no order's run: as before");
    }

    /**
     * job-review-leftovers review: the runner's start bag (AaRuntime.typedStartBag = liveBag(), the simulator's runStartBag)
     * is keyed by full id; the -d counts are by path. Read by full id, the start held nothing and the run's own 20 were free
     * to its crafts and smelts again (w064).
     */
    @Test
    void theOwnKeptAmountReadsAFullIdStartBag() {
        SimGame g = SimGameTest.treeMap(6);
        g.give("minecraft:cobblestone", 20);
        Goals.Goal cob = g.order("cobblestone", 32, "b1", true);
        g.tick();
        int own = Goals.kept(g.bb, null, null).getOrDefault("cobblestone", 0);
        assertTrue(own > 0, Goals.kept(g.bb, null, null).toString());
        var start = java.util.Map.of("minecraft:cobblestone", 20);
        assertEquals(own, Goals.keptForRun(g.bb, cob.id(), true, true, start).getOrDefault("cobblestone", 0), "held from a full-id start bag");
        assertEquals(0, Goals.keptForRun(g.bb, cob.id(), true, true, java.util.Map.of()).getOrDefault("cobblestone", 0), "an empty start holds none");
    }
}