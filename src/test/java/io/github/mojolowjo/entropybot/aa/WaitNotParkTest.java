package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wait-not-park after the merge review of 2026-11-23 (read from the source, no run): the rule "an order whose chain waits
 * on an answer is not stopped by a run's end" applied to every failure reason, so a real failure was swallowed while the
 * chain waited; and an order waiting on an answer nothing holds it for (a source's structure fact not loaded) was neither
 * stopped nor held for ever. Narrowed to a run that ended ON the wait, and bounded by goals.waitEndedMaxS.
 */
class WaitNotParkTest {
    static final String STAND = "stopped at step 2 (" + new Step.AwaitPaths(30).text() + "): stood 30 s (re-plan: no way)";
    static final String STUCK = "stopped at step 3 (dig 4 60 2 4 60 2): stuck 40 s";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** One order of oak_log whose only known source's structure fact is not loaded: its chain waits (Z60ChainTest). */
    private static Goals.Goal waiting(Blackboard bb) {
        io.github.mojolowjo.entropybot.aa.brain.BatchWay.clear();
        ScenariosTest.world("wooden");
        Spot t = ScenariosTest.tree(10, 4).notLoaded();
        World w = ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(t))).withPath(new ScenariosTest.FakePath().asLine(t));
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) () -> w);
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{0, 64, 0};
        return Goals.add(bb, new Thing.Result("oak_log", "oak_log", false, List.of("minecraft:oak_log"), List.of("oak_log"), 0, null, "oak_log", null), 16, 0, 0);
    }

    @Test
    void onlyARunThatEndedOnTheWaitIsAWait() {
        String waits = "oak_log: tree 10 m: " + NeedChain.SOURCE_NOT_LOADED;
        assertTrue(Goals.waitEnd(false, STAND, waits), "the stand itself ended");
        assertTrue(Goals.waitEnd(false, "stopped at step 1 (x): no steps (re-plan: " + waits + ")", waits), "the reason is the wait");
        assertFalse(Goals.waitEnd(false, STUCK, waits), "a step failed for a reason of its own");
        assertFalse(Goals.waitEnd(true, STAND, waits), "the honest loop ended the run: its park keeps the try");
        assertFalse(Goals.waitEnd(false, STAND, null), "the chain does not wait");
        assertFalse(Goals.waitEnd(false, null, waits));
    }

    /** The review's finding: a step stuck while the chain priced after it waits on a source not loaded - the order was not stopped. */
    @Test
    void aRealFailureStopsTheOrderThoughItsChainWaits() {
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        Goals.Goal g = waiting(bb);
        assertTrue(Goals.runFailed(bb, g, STUCK), "stopped: " + port.log);
        assertEquals(STUCK, g.parked);
        assertNull(g.waitEnded);
    }

    /** The stand's end while the chain waits is still no stop (runs JBM-T1, JBN-T1), said once. */
    @Test
    void theStandsEndWhileTheChainWaitsIsNoStop() {
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        Goals.Goal g = waiting(bb);
        assertFalse(Goals.runFailed(bb, g, STAND));
        assertNull(g.parked);
        assertNotNull(g.waitEnded);
        assertTrue(g.waitEnded.endsWith(NeedChain.SOURCE_NOT_LOADED), g.waitEnded);
        assertTrue(port.log.stream().anyMatch(l -> l.contains("not stopped; it runs again when that lands")), port.log.toString());
    }

    /** The bound: the wait lasts past goals.waitEndedMaxS and nothing holds the order - it is stopped on the place, said once. */
    @Test
    void aWaitNothingHoldsIsStoppedAfterTheBound() {
        FakePort port = new FakePort();
        Clock.Fake clock = new Clock.Fake(0);
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        Goals.Goal g = waiting(bb);
        assertFalse(Goals.runFailed(bb, g, STAND));
        long max = (long) (Tuning.get("goals.waitEndedMaxS") * 1000);
        clock.advance(max - 1000);
        assertTrue(Goals.retryWaits(bb, null).isEmpty(), "inside the bound: it waits on");
        assertNull(g.parked);
        clock.advance(1000);
        List<String> said = Goals.retryWaits(bb, null);
        assertEquals(1, said.size(), said.toString());
        assertNotNull(g.parked);
        assertTrue(g.parked.contains(NeedChain.SOURCE_NOT_LOADED) && g.parked.contains("for an answer that did not land"), g.parked);
        assertNotNull(g.park, "a stop that can wake");
        assertEquals(Goals.ParkKind.PLACE, g.park.kind());
        assertNull(g.waitEnded, "a stopped order waits on its stop's facts only");
        assertTrue(Goals.retryWaits(bb, null).isEmpty(), "said once");
        assertEquals(1, port.told.stream().filter(l -> l.startsWith("order oak_log stopped")).count(), port.told.toString());
    }

    /** The bound through a run's end: another stand ends past the bound - that end stops the order. */
    @Test
    void aRunEndingOnTheWaitPastTheBoundStopsTheOrder() {
        FakePort port = new FakePort();
        Clock.Fake clock = new Clock.Fake(0);
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        Goals.Goal g = waiting(bb);
        assertFalse(Goals.runFailed(bb, g, STAND));
        clock.advance((long) (Tuning.get("goals.waitEndedMaxS") * 1000));
        assertTrue(Goals.runFailed(bb, g, STAND));
        assertTrue(g.parked.startsWith(STAND) && g.parked.contains("for an answer that did not land"), g.parked);
    }

    /** The review's minor: a stop clears the ended wait and the look-around flag, so a wake starts from the stop's facts only. */
    @Test
    void aStopClearsTheEndedWaitAndTheLookAroundFlag() {
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{0, 64, 0};
        Goals.Goal g = Goals.add(bb, new Thing.Result("oak_log", "oak_log", false, List.of("minecraft:oak_log"), List.of("oak_log"), 0, null, "oak_log", null), 16, 0, 0);
        g.waitEnded = "waiting for the loot book";
        g.lookAround = true;
        assertTrue(Goals.park(bb, g, "no way to get it here"));
        assertNull(g.waitEnded);
        assertFalse(g.lookAround);
        g.parked = null;                                     // it woke another way
        assertTrue(Goals.retryWaits(bb, null).isEmpty(), "no 'what it waited on landed' line for a wait that is not its");
    }
}
