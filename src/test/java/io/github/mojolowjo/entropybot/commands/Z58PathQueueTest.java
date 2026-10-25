package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z58 (run Q on z57, 02:28-02:35: "in flight 4" all run long, "queued +16", then each second "searches started +1,
 * queued +8, kept dropped +1"; the crafting walks "path not known yet (computing)" while the bot explored, so "collect 4
 * torch" - the only priced way - won at 02:29:32 and failed at 02:33:13). The queue and the re-search wait are the fork's
 * (PathMemory; its JUnit PathQueueTest plays run Q's pattern); here the mod's half: the re-search wait is "computing" (rule
 * 15), the walk told to the fork is the running step's or the next one's, the load line's per-class part, the Tuning entries.
 */
class Z58PathQueueTest {

    private static final String QUEUED = baritone.api.pathing.calc.IPricedPath.QUEUED;
    private static final String WAIT = baritone.api.pathing.calc.IPricedPath.RESEARCH_WAIT;

    @Test
    void theReSearchWaitIsComputingWithItsReasonNeverAPriceOrNone() {
        PathPrice.Answer a = PricedAnswers.notYet(WAIT, QUEUED, WAIT, 4);
        assertEquals(PathPrice.Kind.COMPUTING, a.kind());
        assertEquals("re-search waiting (the bot moved off the last path) (pathCost.researchMinS)", a.reason());
        assertNull(a.walk());
    }

    @Test
    void theRunningWalkIsTheCurrentOrNextWalkStep() {
        List<Step> steps = List.of(new Step.Craft("minecraft:crafting_table", 1), new Step.Walk(3, 64, -9, 0),
                new Step.ToStrip(10, 12, 10, "iron"));
        assertArrayEquals(new int[]{3, 64, -9}, Steps.walkAhead(steps, 0));    // crafting now: the walk it does next
        assertArrayEquals(new int[]{3, 64, -9}, Steps.walkAhead(steps, 1));    // walking now
        assertArrayEquals(new int[]{10, 12, 10}, Steps.walkAhead(steps, 2));
        assertNull(Steps.walkAhead(steps, -1));                                // nothing runs
        assertNull(Steps.walkAhead(List.of(new Step.Collect("minecraft:torch", 4)), 0));   // run Q's pick: no priced walk
    }

    @Test
    void theLoadLineSaysTheQueueByClass() {
        long[] d = new long[24];
        d[0] = 9;
        d[12] = 1; d[13] = 2; d[14] = 3; d[15] = 0;
        d[16] = 0; d[17] = 1; d[18] = 4; d[19] = 0;
        d[20] = 1; d[21] = 0; d[22] = 5; d[23] = 2;
        assertTrue(ProbeLines.asks(d).endsWith(" | by class running step/first answer/re-search/rest: started +1/+2/+3/+0,"
                + " queued +0/+1/+4/+0, cancelled for priority re-search +1 rest +0, re-search waits +5, left the queue +2"));
        assertFalse(ProbeLines.asks(new long[12]).contains("by class"));         // a z57 fork: no queue part
    }

    // ---- Opus Design ruling: rule 18 stays exact; the explore while paths compute first waits in place ----

    /**
     * Fake hands as StepHands runs the explore while paths compute: FirstAnswerWait on a fake fork's pending first answers, a
     * fake clock and landed count; "explore started" recorded when the wait gives way (Baritone's explore goal).
     */
    private static final class WaitHands implements Steps.Hands {
        int pending;
        long clock, landed, seenLanded;
        long waitStart = -1;
        final List<String> started = new java.util.ArrayList<>();
        final List<String> exploreStarted = new java.util.ArrayList<>();
        int cancels;
        String lastWhy;

        @Override public String start(Step s) {
            started.add(s.text());
            if (s instanceof Step.Explore k && k.whilePaths() && k.waitS() > 0) {
                var d = io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(0, k.waitS(), pending, false);
                if (d.next() == io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.Next.EXPLORE) exploreStarted.add(s.text());
                else { waitStart = clock; seenLanded = landed; }
            }
            return null;
        }

        @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status(Step s) {
            if (s instanceof Step.Explore k && waitStart >= 0) {
                boolean since = landed != seenLanded;
                seenLanded = landed;
                var d = io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(clock - waitStart, k.waitS(), pending, since);
                lastWhy = d.why();
                if (d.next() == io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.Next.EXPLORE) { waitStart = -1; exploreStarted.add(s.text()); }
            }
            return io.github.mojolowjo.entropybot.aa.moves.Car.Status.running(s.text());
        }

        @Override public void cancel(String why) { cancels++; waitStart = -1; }
    }

    @Test
    void theWaitDecision() {
        var W = io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.Next.WAIT;
        var E = io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.Next.EXPLORE;
        assertEquals(W, io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(0, 4, 3, false).next());
        assertEquals("waiting up to 4 s while 3 paths compute (1.5 s so far; Baritone idle, priced from this cell)",
                io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(30, 4, 3, false).why());
        assertEquals(E, io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(80, 4, 3, false).next());   // the wait is over
        assertEquals(E, io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(0, 4, 0, false).next());      // none computing
        assertEquals(W, io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(16, 4, 0, true).next());     // the last one just landed
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(0, 4, -1, false).why().contains("does not count"));  // rule 15: said
        assertEquals(E, io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait.of(0, 4, -1, false).next());
    }

    @Test
    void runQsStartTheBotStandsStillTheFirstAnswersLandAndTheCraftWayIsPickedWithNoExplore() {
        // 02:28: no way priced, 3 first asks (the crafting walks) queued in the fork: "explore 5 while paths compute" waits
        WaitHands h = new WaitHands();
        Steps run = new Steps(h);
        int waitS = io.github.mojolowjo.entropybot.aa.NeedChain.firstAnswerWaitS();
        Step ex = new Step.Explore(5, null, true, waitS);
        Step craft = new Step.Craft("minecraft:torch", 4);
        h.pending = 3;
        run.start("goal.torch", List.of(ex), 0, (failed, fact, why) -> new Steps.Routine(List.of(new Step.Walk(3, 64, -9, 0), craft), null));
        for (int t = 1; t <= 4; t++) {                     // 1 s of AA ticks: the searches run, the bot stands
            h.clock += 5;
            run.tick(t);
        }
        assertTrue(h.exploreStarted.isEmpty(), "standing still while the first answers compute: " + h.lastWhy);
        assertTrue(h.lastWhy.startsWith("waiting up to " + waitS + " s while 3 paths compute"), h.lastWhy);
        // 1.5 s: the three land from the standing cell (exact answers), the mod re-prices: the craft way is priced
        h.clock += 10;
        h.pending = 0;
        h.landed += 3;
        run.tick(5);                                       // the hands' look: the last one just landed - still waiting
        assertTrue(h.exploreStarted.isEmpty(), h.lastWhy);
        run.repriceAtNextStep(io.github.mojolowjo.entropybot.aa.moves.FactChange.priced("walk to 3 64 -9: 12 s, complete"));
        run.tick(6);
        assertTrue(h.exploreStarted.isEmpty(), "no explore ever started");
        assertEquals(1, h.cancels, "the wait ended for the priced way");
        assertEquals(new Step.Walk(3, 64, -9, 0).text(), h.started.get(h.started.size() - 1));
    }

    @Test
    void nothingLandedAfterTheWaitTheExploreStarts() {
        WaitHands h = new WaitHands();
        Steps run = new Steps(h);
        Step ex = new Step.Explore(5, null, true, 4);
        h.pending = 2;
        run.start("goal.torch", List.of(ex), 0, (failed, fact, why) -> new Steps.Routine(null, "no way priced yet"));
        for (int t = 1; t <= 15; t++) {                    // 3.75 s: still waiting
            h.clock += 5;
            run.tick(t);
        }
        assertTrue(h.exploreStarted.isEmpty(), h.lastWhy);
        h.clock += 5;                                  // 4 s, nothing landed
        run.tick(16);
        assertEquals(List.of(ex.text()), h.exploreStarted, "the wait is over: the explore as before");
        assertEquals("waited 4 s, 2 paths still computing: exploring meanwhile", h.lastWhy);
        assertTrue(ex.text().endsWith("(first waiting up to 4 s in place while first answers compute)"), ex.text());
    }

    @Test
    void aPausedGameDoesNotBurnTheWait() {
        // review 7: the wait counts the level's game ticks; paused, they stand still (the step machine's own ticks go on)
        WaitHands h = new WaitHands();
        Steps run = new Steps(h);
        h.pending = 1;
        run.start("goal.torch", List.of(new Step.Explore(5, null, true, 4)), 0, (failed, fact, why) -> new Steps.Routine(null, "none"));
        for (int t = 1; t <= 200; t++) run.tick(t);       // 200 step-machine ticks, game time frozen
        assertTrue(h.exploreStarted.isEmpty(), h.lastWhy);
        assertTrue(h.lastWhy.contains("(0.0 s so far"), h.lastWhy);
    }

    /** Hands that finish each step when told (done = its text). */
    private static final class DoneHands implements Steps.Hands {
        String done;
        @Override public String start(Step s) { return null; }
        @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status(Step s) {
            return s.text().equals(done) ? io.github.mojolowjo.entropybot.aa.moves.Car.Status.done("ok")
                    : io.github.mojolowjo.entropybot.aa.moves.Car.Status.running(s.text());
        }
        @Override public void cancel(String why) {}
    }

    @Test
    void theRunningWalkFollowsTheRunAndTwoOrdersAreTwoRuns() {
        // review 6: walkAhead on a real step run - before the walk (crafting), on it, after it, at the order's end; review 2:
        // each run started is a new order for the fork (runId moves on every start)
        DoneHands h = new DoneHands();
        Steps run = new Steps(h);
        Step craft = new Step.Craft("minecraft:crafting_table", 1), walk = new Step.Walk(3, 64, -9, 0), collect = new Step.Collect("minecraft:torch", 4);
        run.start("goal.torch", List.of(craft, walk, collect), 0);
        long first = run.runId();
        assertArrayEquals(new int[]{3, 64, -9}, Steps.walkAhead(run.steps(), run.index()), "crafting: the walk it does next");
        h.done = craft.text();
        run.tick(1);
        assertArrayEquals(new int[]{3, 64, -9}, Steps.walkAhead(run.steps(), run.index()), "walking now");
        h.done = walk.text();
        run.tick(2);
        assertNull(Steps.walkAhead(run.steps(), run.index()), "past the walk: Baritone's mine needs no priced walk");
        h.done = collect.text();
        run.tick(3);
        assertEquals(Steps.Phase.DONE, run.phase());
        assertNull(Steps.walkAhead(run.steps(), run.index()), "the order's end: none");
        run.start("goal.furnace", List.of(new Step.ToStrip(10, 12, 10, "iron")), 4);
        assertNotEquals(first, run.runId(), "the next order is a new run: the fork is told orderStarted");
        assertArrayEquals(new int[]{10, 12, 10}, Steps.walkAhead(run.steps(), run.index()));
    }

    @Test
    void theQueueNumbersAreTuningEntries() {
        assertEquals(4, Tuning.get("pathCost.firstAnswerWaitS"), 1e-9);
        assertEquals(1, Tuning.get("pathCost.reserveForRunning"), 1e-9);
        assertEquals(3, Tuning.get("pathCost.researchMinS"), 1e-9);
        assertEquals(30, Tuning.get("pathCost.queueKeepS"), 1e-9);
        assertEquals(4, Tuning.get("pathCost.maxPending"), 1e-9);              // still the total
        assertTrue(String.join("\n", Tuning.lines("pathCost.researchMinS")).contains("run Q"));
    }
}
