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
        Goals.Goal cob = s.order("cobblestone", 32, null, false);
        s.run(1, 1000);
        assertEquals(1, s.starts(), "the order runs: " + s.brain.whyLines(6));
        s.clock.advance(30_000);
        s.failRun(JBM_STAND_END);
        s.run(120, 5000);                                    // ten minutes; the game's data loads within the first one
        open("audit A1 (run JBM): an order stopped on 'not loaded yet' is parked for good", () -> {
            assertNull(cob.parked, "still parked after ten minutes: " + cob.parked);
            assertTrue(s.starts() >= 2, "never started again: " + s.starts() + " start(s); " + Goals.wake(cob));
        });
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
        open("run JBK-T1: a parked order met later is left out of its line's deposit", () ->
                assertEquals(List.of(Map.of("cobblestone", 32, "oak_log", 16)), s.due, "the line's deposit: " + s.said));
    }
}
