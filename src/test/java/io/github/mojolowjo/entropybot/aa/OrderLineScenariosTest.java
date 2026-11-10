package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sim (owner 2026-11-09): order lines played with no game ({@link OrderLineSim}). Each scenario is a stall a real run
 * showed, with that run's reasons; the rule they check: an order ends done or by the owner's word, never parked on
 * something that can still change, and everything ordered with a deposit word reaches the line's deposit.
 *
 * <p>{@link #open}: a case that is WRONG today (docs: to-do/order-stops-audit.md). It passes while the stall is there and
 * FAILS the day the stall is gone, so the fix's commit moves it to a plain assertion - the old stall cannot come back
 * unseen.
 */
class OrderLineScenariosTest {

    @AfterEach
    void clear() {
        Only.set("off");
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        TestData.clear();
        Tuning.reset();
    }

    /** A case known wrong today: green while it still fails, red once it passes (then it becomes a plain test). */
    static void open(String what, Executable wanted) {
        try {
            wanted.execute();
        } catch (AssertionError stillWrong) {
            System.out.println("OPEN (" + what + "): " + stillWrong.getMessage());
            return;
        } catch (Throwable t) {
            throw new AssertionError("open case " + what + " broke instead of failing its assertion: " + t, t);
        }
        fail("the open case now passes: " + what + " - make it a plain assertion (to-do/order-stops-audit.md)");
    }

    static OrderLineSim stoneAndTree() {
        OrderLineSim s = new OrderLineSim();
        s.bag.put("wooden_pickaxe", 1);
        s.seen.put("oak_log", List.of(ScenariosTest.tree(10, 12), ScenariosTest.tree(14, 12)));
        s.seen.put("cobblestone", List.of(new Spot(0, 60, 16, 16, "minecraft:stone", 40)));
        return s;
    }

    /** The harness is not vacuous: two orders of one line run to their ends and ONE deposit of both follows. */
    @Test
    void twoOrdersOfALineEndAndOneDepositFollows() {
        OrderLineSim s = stoneAndTree();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        for (int i = 0; i < 40 && !Goals.of(s.bb).isEmpty(); i++) {
            s.run(1, 5000);
            String r = s.running();
            if (r == null) continue;
            if (r.equals(cob.id())) { s.bag.put("cobblestone", 33); s.endRun("done: collected"); }
            else if (r.equals(log.id())) { s.bag.put("oak_log", 16); s.endRun("done: chopped"); }
        }
        s.run(3, 5000);
        assertTrue(Goals.of(s.bb).isEmpty(), "both orders done: " + s.said + " / " + s.brain.whyLines(6));
        assertEquals(List.of(Map.of("cobblestone", 32, "oak_log", 16)), s.due, "one deposit of exactly the ordered amounts: " + s.said);
    }

    /** Run JBM-T1's first run end, word for word (docs/runs/runJBM-T1, 21:42:11). */
    static final String JBM_STAND_END = "stopped at step 1 (stand while paths compute (up to 30 s)): stood 30 s (pathCost.firstAnswerWaitS) with the walks' "
            + "paths not known: the order is priced again, the walks still not known taken by Baritone's estimate (ranked, not priced; rule 18 as amended) "
            + "(re-plan: cobblestone: the mining data is not loaded yet (a known block may be diggable once it is; priced again then, rule 15))";

    /**
     * Run JBM-T1 (2026-11-09 21:41:41-21:49): cobblestone 32 was picked first, its stand ended after 30 s with "the mining
     * data is not loaded yet", the order parked ("no wake by itself") and the bot idled once the other orders were done.
     * Audit A1. Wanted: the order runs again by itself (here: within ten minutes of the park, the data long loaded).
     */
    @Test
    void anOrderStoppedOnDataStillLoadingRunsAgain() {
        OrderLineSim s = stoneAndTree();
        // the run's facts (JBN-T1: order 22:31:55, stopped 22:32:25, "mining tiers from data" 22:32:46): no mining data from
        // the world's start until 51 s after the order; the planner answers off the tick, so the order's first run is the
        // stand ("start EXPLORE stand while paths compute (up to 30 s)", "the planner waits on its answer (thinking: ...)")
        var tiers = io.github.mojolowjo.entropybot.aa.brain.MiningTiers.source();
        io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(null);
        s.plannerThinks();
        Goals.Goal cob = s.order("cobblestone", 32, null, false);
        s.run(1, 1000);
        assertEquals(1, s.starts(), "the order's first run starts: " + s.brain.whyLines(6) + " log " + s.port.log);
        assertTrue(s.port.started.get(0).steps().get(0) instanceof io.github.mojolowjo.entropybot.aa.moves.Step.AwaitPaths, "and it is the stand: " + s.port.started.get(0).steps());
        s.clock.advance(30_000);
        s.plannerAnswers.run();                              // the worker's answer: "the mining data is not loaded yet"
        s.failRun(JBM_STAND_END);
        s.run(4, 5000);                                      // 20 s more with no mining data
        var ch = Goals.chain(s.bb, cob);
        assertNull(cob.parked, "not parked while the data loads (chain: ok " + (ch == null ? null : ch.ok()) + ", waiting " + (ch == null ? null : ch.waiting())
                + ", missing " + (ch == null ? null : ch.missing()) + ", why " + (ch == null ? null : ch.why()) + "): " + cob.parked);
        assertEquals(1, s.starts(), "nothing starts while the data is not there");
        assertTrue(String.valueOf(Goals.blocked(s.bb, cob)).contains("mining data is not loaded yet"), "it waits, with the reason: " + Goals.blocked(s.bb, cob));
        io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(tiers);      // 51 s after the order: the data lands
        s.plannerOnTheTick();
        s.run(3, 5000);
        assertNull(cob.parked);
        assertEquals(2, s.starts(), "the order runs again within 15 s of the data landing: " + s.brain.whyLines(6) + " / " + s.port.log);
    }

    /**
     * Run JBO-T1 (2026-11-09 22:53:39.69-.85, feet -56 79 -11, a line of three orders): the collect ended, the trip was asked
     * of the planner worker again ("trip: stand (not priced yet: the planner waits on its answer (thinking: ...))"), that
     * stand ended twice within 160 ms and was counted as two path stands: "stood 2 times at -56 79 -11 ... no third stand
     * there, and with those walks dropped no way is left" - cobblestone and spruce_log parked, "no wake by itself". Audit A5.
     * Wanted: a trip's thinking stand is the planner's stand (as an order's own is since run EU): never counted, nothing
     * dropped, no park.
     */
    @Test
    void aLinesThinkingStandIsNeverCountedAsAPathStand() {
        OrderLineSim s = stoneAndTree();
        s.plannerThinks();
        s.order("cobblestone", 32, "b1", true);
        s.order("oak_log", 16, "b1", true);
        s.run(1, 1000);
        assertEquals(1, s.starts(), "the line's first run starts: " + s.brain.whyLines(6) + " log " + s.port.log);
        var stand = s.port.started.get(0).steps().get(0);
        assertTrue(stand instanceof io.github.mojolowjo.entropybot.aa.moves.Step.AwaitPaths, "and it is the trip's stand: " + s.port.started.get(0).steps() + " log " + s.port.log);
        // the step machine with that stand, as in the run: a path stand, then the trip's thinking stand again and again
        List<String> asked = new java.util.ArrayList<>();
        var run = J6cEuTest.standing(List.of(List.of(stand), List.of(stand), List.of(stand), List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Walk(-56, 107, -9, 1))), asked);
        for (int t = 1; t <= 8 && run.running(); t++) run.tick(t * 20L);
        assertTrue(asked.stream().noneMatch(k -> k.contains(NeedChain.STOOD_AT)), "no 'stood' drop on the planner's thinking: " + asked);
        assertTrue(run.running(), "and no stop: " + run.why());
    }

    /** The same world start with the planner on the tick: an order given before the mining data is there waits with the reason, then runs. */
    @Test
    void anOrderGivenBeforeTheDataLoadsWaitsAndThenRuns() {
        OrderLineSim s = stoneAndTree();
        var tiers = io.github.mojolowjo.entropybot.aa.brain.MiningTiers.source();
        io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(null);
        Goals.Goal cob = s.order("cobblestone", 32, null, false);
        s.run(6, 5000);
        assertEquals(0, s.starts(), "nothing starts on a missing fact: " + s.port.started);
        assertNull(cob.parked, "and nothing is stopped: " + cob.parked);
        assertTrue(String.valueOf(Goals.blocked(s.bb, cob)).contains("mining data is not loaded yet"), "it waits, with the reason: " + Goals.blocked(s.bb, cob));
        io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(tiers);
        s.run(2, 5000);
        assertEquals(1, s.starts(), "it runs once the data is there: " + s.brain.whyLines(6));
        assertTrue(s.port.started.get(0).steps().stream().anyMatch(st -> st instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Collect), "and its run is the dig: " + s.port.started.get(0).steps());
    }

    /**
     * Run JBK-T1 (2026-11-09 15:18-15:25): cobblestone 32 -d parked 30 s in with none in the bag ("its deposit items noted
     * ()"), stayed parked, and was counted done at 15:25:38 when the torch order's digging had filled the bag to 33. The
     * line's deposit took the logs and the torches and left the cobblestone. Wanted: the line's one deposit has all of it.
     * (Fix A of 2026-11-09 re-weighs an order that WOKE; this one never woke.)
     */
    @Test
    void aParkedOrderMetLaterIsInTheLinesDeposit() {
        OrderLineSim s = stoneAndTree();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        boolean cobFailed = false;
        for (int i = 0; i < 60 && !Goals.of(s.bb).isEmpty(); i++) {
            s.run(1, 5000);
            String r = s.running();
            if (r == null) continue;
            if (r.equals(cob.id()) && !cobFailed) { cobFailed = true; s.failRun(JBM_STAND_END); }
            else if (r.equals(log.id())) {
                s.run(2, 5000);                              // the park's weighing comes first, as in the run (15:18:55)
                s.bag.put("oak_log", 16);
                s.bag.put("cobblestone", 33);                // the other order's digging filled the bag
                s.endRun("done: chopped");
            }
        }
        s.run(3, 5000);
        assertTrue(cobFailed, "the cobblestone order ran and was stopped: " + s.brain.whyLines(6));
        assertTrue(Goals.of(s.bb).isEmpty(), "both orders met: " + s.said);
        assertEquals(List.of(Map.of("cobblestone", 32, "oak_log", 16)), s.due, "the line's one deposit has all of it: " + s.said);
    }

    /**
     * Run JBO-T1 (2026-11-09 22:53:47-22:54:30): cobblestone stopped with 17 in the bag ("its deposit items noted
     * (cobblestone 17)"); the torch order then used 8 of them for its furnace; the line's deposit asked for the 17 noted at
     * the stop ("the line's deposit of cobblestone 17, spruce_log 3, torch 4"). Wanted: the line's deposit is weighed on the
     * bag when the line ends - what is there of each order's amount then.
     */
    @Test
    void theLinesDepositIsWeighedOnTheBagWhenTheLineEnds() {
        OrderLineSim s = stoneAndTree();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        boolean cobFailed = false, logDone = false;
        for (int i = 0; i < 60 && !logDone; i++) {
            s.run(1, 5000);
            String r = s.running();
            if (r == null) continue;
            if (r.equals(cob.id()) && !cobFailed) { cobFailed = true; s.bag.put("cobblestone", 17); s.failRun("collect 32 cobblestone: Baritone's mine went away from the priced spot, got 17 of 32"); }
            else if (r.equals(log.id())) {
                s.run(2, 5000);                              // the stop's weighing: "cobblestone 17" noted
                s.bag.put("cobblestone", 9);                 // another order's furnace took 8
                s.bag.put("oak_log", 16);
                s.endRun("done: chopped");
                logDone = true;
            }
        }
        s.run(3, 5000);
        assertTrue(cobFailed && logDone, s.brain.whyLines(6).toString());
        assertEquals(List.of(Map.of("cobblestone", 9, "oak_log", 16)), s.due, "what the bag holds when the line ends: " + s.said);
    }

    /**
     * Values round the runs', swept (owner 2026-11-09: "slightly adjusting some values for possible other errors"): the
     * mining data landing 0 s to 10 min after the order - never a stop, nothing started on the missing fact, and the order
     * runs within 10 s of the landing.
     */
    @Test
    void sweptLoadDelays_theOrderWaitsAndRuns() {
        List<String> wrong = new java.util.ArrayList<>();
        for (int delayS : new int[]{0, 1, 5, 29, 30, 31, 51, 90, 600}) {
            OrderLineSim s = stoneAndTree();
            var tiers = io.github.mojolowjo.entropybot.aa.brain.MiningTiers.source();
            if (delayS > 0) io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(null);
            Goals.Goal cob = s.order("cobblestone", 32, null, false);
            for (int t = 0; t < delayS; t++) s.run(1, 1000);
            if (delayS > 0 && s.starts() != 0) wrong.add(delayS + " s: a run started on the missing data");
            io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(tiers);
            s.run(10, 1000);
            if (cob.parked != null) wrong.add(delayS + " s: stopped: " + cob.parked);
            if (s.starts() != 1) wrong.add(delayS + " s: " + s.starts() + " runs 10 s after the data landed " + s.brain.whyLines(3));
            clear();
        }
        assertTrue(wrong.isEmpty(), wrong.toString());
    }

    /**
     * The same for the deposit: the bag at the stop (0 to 40 cobblestone) and when the line ends (0 to 58) - the line's
     * deposit is always what the bag holds of the ordered 32 then, and the 16 logs; never more than the bag, never twice.
     */
    @Test
    void sweptBags_theLinesDepositIsTheBagAtItsEnd() {
        List<String> wrong = new java.util.ArrayList<>();
        for (int atStop : new int[]{0, 5, 17, 31})
            for (int atEnd : new int[]{0, 3, 9, 17, 31, 32, 58}) {
                OrderLineSim s = stoneAndTree();
                Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
                boolean cobFailed = false, logDone = false;
                for (int i = 0; i < 60 && !logDone; i++) {
                    s.run(1, 5000);
                    String r = s.running();
                    if (r == null) continue;
                    if (r.equals(cob.id()) && !cobFailed) { cobFailed = true; s.bag.put("cobblestone", atStop); s.failRun("collect 32 cobblestone: Baritone's mine went away from the priced spot, got " + atStop + " of 32"); }
                    else if (r.equals(log.id())) { s.run(2, 5000); s.bag.put("cobblestone", atEnd); s.bag.put("oak_log", 16); s.endRun("done: chopped"); logDone = true; }
                }
                s.run(3, 5000);
                Map<String, Integer> want = new java.util.TreeMap<>(Map.of("oak_log", 16));
                if (atEnd > 0) want.put("cobblestone", Math.min(32, atEnd));
                if (!s.due.equals(List.of(want))) wrong.add("stop " + atStop + ", end " + atEnd + ": " + s.due + " (wanted " + want + ") " + s.said);
                clear();
            }
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong, the first: " + wrong.stream().limit(2).toList());
    }

    /**
     * The same, the other way round in time: the line's deposit went (the other order ended) BEFORE the stopped order was
     * met. Wanted: a second deposit of what the first left out, nothing twice.
     */
    @Test
    void aParkedOrderMetAfterTheLinesDepositGetsItsOwn() {
        OrderLineSim s = stoneAndTree();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        boolean cobFailed = false, logDone = false;
        for (int i = 0; i < 60 && !logDone; i++) {
            s.run(1, 5000);
            String r = s.running();
            if (r == null) continue;
            if (r.equals(cob.id()) && !cobFailed) { cobFailed = true; s.failRun(JBM_STAND_END); }
            else if (r.equals(log.id())) { s.run(2, 5000); s.bag.put("oak_log", 16); s.endRun("done: chopped"); logDone = true; }
        }
        s.run(3, 5000);
        assertEquals(List.of(Map.of("oak_log", 16)), s.due, "the line's deposit went with the logs: " + s.said);
        assertNotNull(cob.parked, "the cobblestone order is still stopped");
        s.bag.remove("oak_log");                             // deposited
        s.bag.put("cobblestone", 33);                        // met later (the owner's own digging, another order)
        s.run(3, 5000);
        assertTrue(Goals.of(s.bb).isEmpty(), "met: " + s.said);
        assertEquals(List.of(Map.of("oak_log", 16), Map.of("cobblestone", 32)), s.due, "a second deposit of what the first left out: " + s.said);
    }
}
