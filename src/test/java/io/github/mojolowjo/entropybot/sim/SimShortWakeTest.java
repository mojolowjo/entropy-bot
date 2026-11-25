package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * short-wake, played in the simulator (pilot w061 09:17:02.795: "order torch runs again: moved 54 blocks from where it
 * parked" - the deposit goal's own walk moved the bot - and the wake took the whole due deposit back; the game ended with
 * the chest empty). The line's spruce_log order is stopped with 3 in the bag and nothing it waits on can change, so the
 * deposit is owed short; the main chest is 30 blocks off, so the deposit's walk wakes it. Wanted: the cobblestone reaches
 * the chest, the spruce_log follows with its own amount whole, nothing goes twice.
 */
class SimShortWakeTest {
    @AfterEach
    void clear() {
        io.github.mojolowjo.entropybot.aa.brain.Only.set("off");
        io.github.mojolowjo.entropybot.aa.brain.BatchWay.clear();
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        TestData.clear();
        io.github.mojolowjo.entropybot.aa.brain.Tuning.reset();
    }

    /**
     * The line, the chest 30 blocks off, the spruce_log order stopped with 3 in the bag (as SimGameTest's hold-bound case:
     * the stop as the game's park leaves it, a PLACE park on the start cell, its look-around and climb try taken).
     */
    static SimGame lineOwedShort() {
        SimWorld w = SimWorld.flat(63, "minecraft:grass_block", 40);
        for (int i = 0; i < 4; i++) w.column(10 + 2 * i, 64, 3, 6, "minecraft:spruce_log");
        w.set(2, 64, -30, "minecraft:chest");
        SimGame g = new SimGame(w, new int[]{0, 64, 0});
        g.mainAt = "2 64 -30";
        g.give("minecraft:cobblestone", 32);
        g.give("minecraft:spruce_log", 3);
        g.order("cobblestone", 32, "b1", true);
        var log = g.order("spruce_log", 16, "b1", true);
        // the trees in sight are known to the park (a wake needs a new one); the try that stopped it was at another tree
        log.parked = "stopped at step 1 (cut 6 spruce_log at 30 64 30): no way from here";
        java.util.Set<String> known = new java.util.TreeSet<>();
        for (int i = 0; i < 4; i++) known.add("spruce_log at " + (10 + 2 * i) + " 64 3");
        log.park = new Goals.Park(Goals.ParkKind.PLACE, new int[]{0, 64, 0}, 35, known, java.util.Set.of());
        log.looked = true;
        log.climbRetried = true;
        return g;
    }

    /**
     * (3) in the simulator: the deposit's walk moves the bot 28 blocks, and the sim's put step starts as the walk ends, so the
     * wake (w061's "moved ... from where it parked") finds the put on its way: the put carries what it was sent with, the
     * order owes the rest of its amount and deposits it when it ends - 3 + 13, whole, once.
     */
    @Test
    void w061_theDepositsWalkWakesTheShortOrder_thePutOnItsWayGoes_theRestFollows() {
        SimGame g = lineOwedShort();
        String end = g.play(900);
        String t = g.timelineText();
        assertTrue(t.contains("short: spruce_log 3 of 16"), "owed short: " + t);
        assertTrue(t.contains("runs again: moved 28 blocks from where it stopped"), "the deposit's walk woke it: " + t);
        assertTrue(t.contains("the rest of its amount (13) follows when it ends"), t);
        assertFalse(t.contains("taken back"), "nothing taken back: " + t);
        assertEquals("done", end, t);
        assertEquals(32, g.chest.get("minecraft:cobblestone"), g.chest + "\n" + t);
        assertEquals(16, g.chest.get("minecraft:spruce_log"), "its amount whole, once: " + g.chest + "\n" + t);
    }

    /**
     * (1), (2) in the simulator (w057's wake: a new source seen after the line was owed short, before the put started): a
     * tree grows into sight one pass after the deposit goal was added. Only the spruce_log leaves the due deposit; the put
     * planned with its 3 is refused (the owed changed) and planned again with the cobblestone alone; the spruce_log's own
     * amount goes with a deposit of its own, whole.
     */
    @Test
    void w057_aNewSourceWakesTheShortOrder_beforeThePut_theRestGoes_itsOwnFollowsWhole() {
        SimGame g = lineOwedShort();
        g.tick();
        assertTrue(g.timelineText().contains("short: spruce_log 3 of 16"), "owed short: " + g.timelineText());
        assertTrue(g.timelineText().contains("is a goal now"), g.timelineText());
        g.world.column(-6, 64, 6, 6, "minecraft:spruce_log");     // a tree comes into sight
        String end = g.play(900);
        String t = g.timelineText();
        System.out.println(t);
        assertTrue(t.contains("spruce_log woke: the line's deposit owed without it stays due (cobblestone 32); its spruce_log 3 in the bag stay its own; its whole amount (16) follows"), t);
        assertFalse(t.contains("taken back"), "nothing taken back: " + t);
        // (1) the put planned with its 3 does not start (the game's StepHands check, mirrored in SimSteps); the next carries the rest
        assertTrue(t.contains("ok: no put - the deposit owed changed since the plan (planned {minecraft:cobblestone=32, minecraft:spruce_log=3}, owed now {minecraft:cobblestone=32})"), t);
        assertTrue(t.contains("deposit at 2 64 -30: [32 minecraft:cobblestone]"), "the rest alone: " + t);
        assertEquals("done", end, t);
        assertEquals(32, g.chest.get("minecraft:cobblestone"), g.chest + "\n" + t);
        assertEquals(16, g.chest.get("minecraft:spruce_log"), "its amount whole, once: " + g.chest + "\n" + t);
    }
}
