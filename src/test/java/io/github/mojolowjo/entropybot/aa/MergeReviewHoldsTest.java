package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.commands.PlannerPort;
import io.github.mojolowjo.entropybot.commands.PlannerWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Merge review of job-d6, 2026-11-25 (to-do/merge-review-2026-11-25.md), the holds and the planner worker: findings 4
 * (two parked orders of one batch priced their wakes under the batch's one key), 6 (a group order's chain priced from a
 * plain -d sibling's held amount the runner holds) and 8 (the deposit goal's chain resolved on the client tick). The real
 * brain, Goals and LineDeposits ({@link OrderLineSim}); the real PlannerWorker and PlannerPort where the finding is the
 * worker path.
 */
class MergeReviewHoldsTest {
    @AfterEach
    void clear() {
        Only.set("off");
        FactChange.clear();
        TestData.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /**
     * Finding 4: cobblestone 32 -d and oak_log 16 -d parked on PLACE, stick 4 -d of the same line open; the bot moves away
     * from both park cells, so both wakes are asked in the same pass. Each wake's price must land (wakeAsked cleared: it woke
     * or parked again on a fresh snapshot), never replaced by its sibling's ask under one key for ever.
     */
    @Test
    void f4_twoParkedOrdersOfOneBatch_eachWakePriceLands() throws InterruptedException {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        s.feet[0] = 40;
        s.surface = new NeedChain.Surface(0, 40, 64, 0, 0);
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        Goals.Goal log = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b1", true);
        s.bb.in.pos = s.feet.clone();
        s.bb.in.bag = Map.copyOf(s.bag);
        String stop = "stopped at step 1 (stand while paths compute (up to 30 s)): the walks' answer landed (walk to 43 64 3: 1 s, complete)";
        assertTrue(Goals.park(s.bb, cob, stop));
        assertTrue(Goals.park(s.bb, log, stop));
        assertNotNull(cob.park, "a place park");
        assertNotNull(log.park, "a place park");
        List<String> plannerLog = new CopyOnWriteArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, plannerLog::add);
        s.bb.fact(BatchWay.PLANNER_FACT, port);
        // stick runs and moves the bot well past failures.moved from both park cells
        s.feet[0] = 0;
        s.surface = new NeedChain.Surface(0, 0, 64, 0, 0);
        boolean[] asked = {false, false}, answered = {false, false};
        long until = System.nanoTime() + 30_000_000_000L;
        // the sibling stick stays open meanwhile (its run stands on the planner; the no-progress stop ends it after 40 s):
        // the answers must land while it is open - the contention the review found is exactly while a sibling runs
        while ((!answered[0] || !answered[1]) && stick.parked == null && System.nanoTime() < until) {
            s.run(1, 50);
            Goals.Goal[] two = {cob, log};
            for (int i = 0; i < 2; i++) {
                if (two[i].wakeAsked != null) asked[i] = true;
                else if (asked[i] || two[i].parked == null) answered[i] = true;
            }
            Thread.sleep(5);
        }
        assertTrue(asked[0] && asked[1], "both wakes were priced on the worker: " + s.said + " | " + plannerLog);
        assertTrue(answered[0] && answered[1], "both landed while stick was open (stick: " + stick.parked + ")");
        assertTrue(answered[0], "cobblestone's wake price landed: wakeAsked " + cob.wakeAsked + " | " + plannerLog);
        assertTrue(answered[1], "oak_log's wake price landed: wakeAsked " + log.wakeAsked + " | " + plannerLog);
    }

    /**
     * Finding 6: one line - cobblestone 32 -d (plain, open, 20 in the bag) and the group stone tools - in one batch. The runner
     * (keptForRun with consume, Goals.keptFromUse) holds the 20 from the group's crafts; the group's chain must price the
     * same: whatever cobblestone its crafts use, a step of the chain gets first, never the held 20 from the bag.
     */
    @Test
    void f6_aGroupBesideAnOpenPlainDepositOrder_neverPricesFromItsHeldAmount() {
        OrderLineSim s = new OrderLineSim();
        s.table = true;
        s.bag.put("cobblestone", 20);
        s.bag.put("stick", 16);
        Goals.Goal p = s.order("cobblestone", 32, "b1", true);
        Goals.Goal g = s.order("stone tools", 1, "b1", false);
        assertTrue(g.r.group(), g.r.text());
        s.bb.in.pos = s.feet.clone();
        s.bb.in.bag = Map.copyOf(s.bag);
        Map<String, Integer> runner = Goals.keptForRun(s.bb, g.id(), true, true, null);
        assertEquals(20, runner.getOrDefault("cobblestone", 0), "the runner holds the sibling's 20: " + runner);
        NeedChain.Chain ch = Goals.chain(s.bb, g);
        assertNotNull(ch);
        int used = 0, got = 0;
        for (int i = 0; i < ch.steps().size(); i++) {
            Step st = ch.steps().get(i);
            Map<String, Integer> u = i < ch.uses().size() ? ch.uses().get(i) : null;
            if (u != null) for (var e : u.entrySet()) if (e.getKey() != null && e.getKey().endsWith("cobblestone") && e.getValue() != null) used += e.getValue();
            Map<String, Integer> made = st instanceof Step.Craft ? null : DryRun.produces(st);
            if (made != null) for (var e : made.entrySet()) if (e.getKey().endsWith("cobblestone") && e.getValue() != null) got += e.getValue();
        }
        assertTrue(used == 0 || got >= used || ch.explore(), "the crafts' cobblestone (" + used + ") comes from a step of the chain (" + got + "), never the held 20: "
                + ch.why() + " " + ch.steps());
        assertNotNull(p);
    }

    /**
     * Finding 8: the deposit goal's chain under ground (its ways: the chest walk, the walk up, the climb) is asked of the
     * planner worker, never resolved on the client tick: the thinking stand while the worker holds it, then the worker's
     * answer - the same chain the tick resolves.
     */
    @Test
    void f8_theDepositGoalsChain_isResolvedOnTheWorker() throws InterruptedException {
        OrderLineSim s = OrderLineDepositGoalTest.lineDue();
        s.under(20);
        Goals.Goal dg = s.depositGoal();
        assertNotNull(dg, s.said.toString());
        NeedChain.Chain onTick = Goals.chain(s.bb, dg);
        assertTrue(onTick.ok(), onTick.why());
        Semaphore gate = new Semaphore(0);
        List<PlannerWorker.Job> jobs = new CopyOnWriteArrayList<>();
        PlannerWorker worker = new PlannerWorker(j -> {
            jobs.add(j);
            gate.acquireUninterruptibly();
            return PlannerWorker.resolve(j);
        });
        List<String> plannerLog = new CopyOnWriteArrayList<>();
        PlannerPort port = new PlannerPort(worker, () -> 0, plannerLog::add);
        s.bb.fact(BatchWay.PLANNER_FACT, port);
        NeedChain.Chain first = Goals.chain(s.bb, dg);
        assertFalse(first.steps().isEmpty(), first.why());
        assertTrue(first.steps().get(0) instanceof Step.AwaitPaths a && a.planner(), "the planner's stand while the worker resolves: " + first.why());
        long until = System.nanoTime() + 10_000_000_000L;
        while (jobs.isEmpty() && System.nanoTime() < until) Thread.sleep(2);
        assertFalse(jobs.isEmpty(), "a request went to the worker");
        assertEquals(BatchWay.OWN_KEY + dg.id(), jobs.get(0).key());
        assertNotNull(jobs.get(0).own().deposit(), "the deposit request: " + jobs.get(0).own());
        gate.release(10_000);
        NeedChain.Chain c = first;
        until = System.nanoTime() + 30_000_000_000L;
        while (NeedChain.thinking(c) || c.steps().size() == 1 && c.steps().get(0) instanceof Step.AwaitPaths a && a.planner()) {
            if (System.nanoTime() > until) fail("no worker answer within 30 s: " + plannerLog);
            Thread.sleep(5);
            c = Goals.chain(s.bb, dg);
        }
        assertEquals(onTick.steps(), c.steps(), "the worker's chain is the tick's: " + c.why() + " | " + onTick.why());
        assertEquals(onTick.why(), c.why());
    }
}
