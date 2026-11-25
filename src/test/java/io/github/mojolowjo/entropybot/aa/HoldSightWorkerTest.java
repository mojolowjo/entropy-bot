package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.commands.PlannerPort;
import io.github.mojolowjo.entropybot.commands.PlannerWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-hold-sight review 3 (the reviewer, serious): w044 on the planner worker path, as the game prices it. The scan's
 * renewal finds the next tree (the sight moment passes the park), so the sight hold ends; the wake that renewal starts is a
 * price, asked of the planner worker (BatchWay own / trip, the port's stand), and the order stays parked while it resolves.
 * Before the fix the deposit went short in that pass, before the worker's answer landed. Wanted: the hold lasts while the
 * port has the order's price in flight (a fact, no timer); the answer lands, the order wakes, the deposit never goes short.
 * The real PlannerWorker and PlannerPort, the worker's answer held back by a gate.
 */
class HoldSightWorkerTest {
    @AfterEach
    void clear() { Tuning.reset(); }

    @Test
    void w044_theWakePricedOnTheWorker_holdsTheDeposit_untilItsAnswerLands() throws InterruptedException {
        Goals.Goal[] g = new Goals.Goal[2];
        OrderLineSim s = HoldSightTest.w044(g);
        Goals.Goal log = g[1];
        Semaphore gate = new Semaphore(0);
        PlannerWorker worker = new PlannerWorker(j -> {
            gate.acquireUninterruptibly();
            return PlannerWorker.resolve(j);
        });
        List<String> plannerLog = new CopyOnWriteArrayList<>();
        PlannerPort port = new PlannerPort(worker, () -> 0, plannerLog::add);
        s.bb.fact(BatchWay.PLANNER_FACT, port);

        s.run(1, 2);
        assertTrue(s.due.isEmpty(), "held: the scan behind the park: " + s.said);
        // 07:41:12.953: the renewal finds the next tree, the bot where it stood; the sight moment passes the park
        s.seen.put("oak_log", List.of(new Spot(-176, 71, -223, 3, "minecraft:oak_log", 6)));
        s.bb.in.sightAtMs = s.clock.now() + 3000;
        s.run(1, 3000);
        assertNotNull(log.parked, "the wake is a price on the worker: still stopped while it resolves: " + s.said);
        assertNotNull(log.wakeAsked, "the change is noted as asked");
        assertTrue(BatchWay.pricing(s.bb, log), "the port has its price in flight");
        String why = Goals.stillChanges(Goals.of(s.bb), log, false, false, false, s.bb.in.sightAtMs, o -> BatchWay.pricing(s.bb, o));
        assertNotNull(why, "held on the price in flight");
        assertTrue(why.contains("its answer has not landed"), why);
        assertTrue(s.due.isEmpty(), "the deposit does not go while the wake is priced: " + s.said);
        s.run(3, 50);
        assertTrue(s.due.isEmpty(), "still held while the worker thinks: " + s.said);

        // the worker answers (fetch rounds included): passes until the answer is taken
        gate.release(10_000);
        long until = System.nanoTime() + 30_000_000_000L;
        while (log.parked != null && System.nanoTime() < until) {
            Thread.sleep(5);
            s.run(1, 50);
        }
        assertNull(log.parked, "oak_log woke on the new source when the worker's answer landed: " + s.said + " | " + plannerLog);
        assertTrue(s.said.stream().noneMatch(l -> l.contains("short: oak_log")), "the line's deposit never went short: " + s.said);
        assertTrue(s.due.isEmpty(), "nothing went due: " + s.due);
    }

    /** Pure: a change noted as asked holds only while the port has it in flight; no noted change, no hold (no stale result holds). */
    @Test
    void thePriceHold_readsTheNotedChangeAndThePort() {
        Goals.Goal[] g = new Goals.Goal[2];
        OrderLineSim s = HoldSightTest.w044(g);
        Goals.Goal log = g[1];
        long sight = log.parkedAtMs;                             // the scan read at the park: no sight hold
        assertNull(Goals.stillChanges(List.of(log), log, false, false, false, sight, o -> true), "no change asked: nothing in flight for it");
        log.wakeAsked = "a new source seen: oak_log at -176 71 -223";
        assertNotNull(Goals.stillChanges(List.of(log), log, false, false, false, sight, o -> true), "asked and in flight");
        assertNull(Goals.stillChanges(List.of(log), log, false, false, false, sight, o -> false), "asked, the answer taken: no hold");
        assertNull(Goals.stillChanges(List.of(log), log, false, false, false, sight, null), "no planner worker: as before");
        assertFalse(BatchWay.pricing(s.bb, log), "no planner worker installed: nothing in flight");
    }
}
