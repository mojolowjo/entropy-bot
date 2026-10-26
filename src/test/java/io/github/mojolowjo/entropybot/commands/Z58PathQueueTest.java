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

    // ---- 0.39.z76 send-back 3 (rule 22): the bot STANDS while its walks compute (was: z58's explore while paths compute) ----

    /**
     * Fake hands as StepHands runs the standing step: StandWait on a fake game clock; past its waitS the step ends with the
     * price fact (the order priced again, the walks still not known taken by the estimate). Every step started is recorded;
     * a walk or an explore started is a move.
     */
    private static final class WaitHands implements Steps.Hands {
        long clock;
        final io.github.mojolowjo.entropybot.aa.moves.StandWait stand = new io.github.mojolowjo.entropybot.aa.moves.StandWait();
        final List<String> started = new java.util.ArrayList<>(), moves = new java.util.ArrayList<>();
        int cancels;
        String lastWhy;

        @Override public String start(Step s) {
            started.add(s.text());
            if (s instanceof Step.Walk || s instanceof Step.Explore || s instanceof Step.ToStrip) moves.add(s.text());
            if (s instanceof Step.AwaitPaths) stand.begin(clock);
            return null;
        }

        @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status(Step s) {
            if (s instanceof Step.AwaitPaths a) {
                if (stand.over(clock, a.waitS())) {
                    lastWhy = io.github.mojolowjo.entropybot.aa.moves.StandWait.overWords(a.waitS());
                    stand.end();
                    io.github.mojolowjo.entropybot.aa.moves.FactChange.repriceAfterStand(lastWhy);
                    return io.github.mojolowjo.entropybot.aa.moves.Car.Status.failed(lastWhy);
                }
                lastWhy = stand.words(clock, a.waitS(), 3);
            }
            return io.github.mojolowjo.entropybot.aa.moves.Car.Status.running(s.text());
        }

        @Override public void cancel(String why) { cancels++; stand.end(); }
    }

    @Test
    void theStandsClock() {
        var w = new io.github.mojolowjo.entropybot.aa.moves.StandWait();
        assertFalse(w.standing());
        w.begin(100);
        assertEquals("standing 1.5 s of 4 while 3 walks compute from here (Baritone idle, no move; rule 22)", w.words(130, 4, 3));
        assertFalse(w.over(179, 4), "3.95 s: still standing");
        assertTrue(w.over(180, 4), "4 s of game time: over");
        assertTrue(w.sayOver());
        assertFalse(w.sayOver(), "said once");
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.StandWait.overWords(30).contains("ranked, not priced"));
    }

    @Test
    void runQsStartTheBotStandsTheFirstAnswersLandAndTheCraftWayIsPickedWithNoExplore() {
        // 02:28: no way priced, 3 first asks queued in the fork: the chain's step is the stand (rule 22)
        WaitHands h = new WaitHands();
        Steps run = new Steps(h);
        int waitS = io.github.mojolowjo.entropybot.aa.NeedChain.firstAnswerWaitS();
        Step stand = new Step.AwaitPaths(waitS);
        Step craft = new Step.Craft("minecraft:torch", 4);
        run.start("goal.torch", List.of(stand), 0, (failed, fact, why) -> new Steps.Routine(List.of(new Step.Walk(3, 64, -9, 0), craft), null));
        for (int t = 1; t <= 4; t++) {                     // 1 s of AA ticks: the searches run, the bot stands
            h.clock += 5;
            run.tick(t);
        }
        assertTrue(h.moves.isEmpty(), "standing while the first answers compute: " + h.lastWhy);
        assertTrue(h.lastWhy.startsWith("standing 1.0 s of " + waitS), h.lastWhy);
        // 1.5 s: they land from the standing cell, the mod re-prices: the craft way is priced, its walk is the first move
        h.clock += 10;
        run.repriceAtNextStep(io.github.mojolowjo.entropybot.aa.moves.FactChange.priced("walk to 3 64 -9: 12 s, complete"));
        run.tick(5);
        assertEquals(1, h.cancels, "the stand ended for the priced way");
        assertEquals(List.of(new Step.Walk(3, 64, -9, 0).text()), h.moves, "the first move is the priced walk; no explore ever");
    }

    @Test
    void nothingLandedAfterTheWaitTheOrderIsPricedAgainOnTheEstimate() {
        WaitHands h = new WaitHands();
        Steps run = new Steps(h);
        Step stand = new Step.AwaitPaths(4);
        List<io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact> facts = new java.util.ArrayList<>();
        run.start("goal.torch", List.of(stand), 0, (failed, fact, why) -> {
            facts.add(fact);
            return new Steps.Routine(List.of(new Step.Walk(3, 64, -9, 0)), null);   // the walk taken by the estimate
        });
        for (int t = 1; t <= 15; t++) {                    // 3.75 s: still standing
            h.clock += 5;
            run.tick(t);
        }
        assertTrue(h.moves.isEmpty() && facts.isEmpty(), h.lastWhy);
        h.clock += 5;                                      // 4 s, nothing landed
        run.tick(16);
        assertEquals(1, facts.size(), "the order is priced again where it stands");
        assertTrue(facts.get(0).price(), "a price fact: no cap counted: " + facts.get(0));
        assertTrue(h.lastWhy.startsWith("stood 4 s (pathCost.firstAnswerWaitS) with the walks' paths not known"), h.lastWhy);
        assertEquals(List.of(new Step.Walk(3, 64, -9, 0).text()), h.moves, "then it walks; it never explored");
    }

    @Test
    void aPausedGameDoesNotBurnTheWait() {
        // the stand counts the level's game ticks; paused, they stand still (the step machine's own ticks go on)
        WaitHands h = new WaitHands();
        Steps run = new Steps(h);
        run.start("goal.torch", List.of(new Step.AwaitPaths(4)), 0, (failed, fact, why) -> new Steps.Routine(null, "none"));
        for (int t = 1; t <= 200; t++) run.tick(t);       // 200 step-machine ticks, game time frozen
        assertTrue(h.moves.isEmpty() && run.running(), h.lastWhy);
        assertTrue(h.lastWhy.startsWith("standing 0.0 s of 4"), h.lastWhy);
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
        assertEquals(30, Tuning.get("pathCost.firstAnswerWaitS"), 1e-9);     // 0.39.z60 item 1: the cap (was 4)
        assertEquals(1, Tuning.get("pathCost.reserveForRunning"), 1e-9);
        assertEquals(3, Tuning.get("pathCost.researchMinS"), 1e-9);
        assertEquals(30, Tuning.get("pathCost.queueKeepS"), 1e-9);
        assertEquals(4, Tuning.get("pathCost.maxPending"), 1e-9);              // still the total
        assertTrue(String.join("\n", Tuning.lines("pathCost.researchMinS")).contains("run Q"));
    }
}
