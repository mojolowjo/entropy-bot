package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits.Kind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Merge review of job-d6 (2026-11-25), findings 2, 3/5 and 7: replays through the real brain, OrderEnds and LineDeposits
 * (OrderLineSim, whose pass asks the runtime's goals-tick test, OrderEnds.idle). Each holds the owner's rules: a -d amount
 * reaches the chest whole, once; a line's deposit is never stranded with no line said (rule 7).
 */
class OrderEndsReviewTest {

    /** aa cancel of one order, as the runtime's (AaRuntime: goals.remove, the brain's desire, LineDeposits.dropped). */
    static LineDeposits.Out cancel(OrderLineSim s, Goals.Goal g) {
        Goals.of(s.bb).remove(g);
        s.brain.remove(g.id(), "aa cancel");
        return s.deposits.dropped(g, s.bag, s.mainAt);
    }

    /**
     * Finding 2 (serious): cobblestone 32 -d and oak_log 4 -d on one line; cobblestone is met and leaves the goals; the owner
     * cancels oak_log, which leaves the goals empty and makes the line due. The goals pass must still reach depositSync: the
     * deposit goal is added and the 32 go to the chest (owner 2026-11-13: dropping the other order still deposits it).
     */
    @Test
    void cancellingTheLastOpenOrder_ofALineWithAMetOrder_stillAddsTheDepositGoal() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 4, "b1", true);
        s.bag.put("cobblestone", 32);
        s.tick();
        assertTrue(cob.met, "cobblestone met: " + s.said);
        assertFalse(Goals.of(s.bb).contains(cob));
        LineDeposits.Out o = cancel(s, log);
        assertEquals(Kind.DUE, o.kind(), o.text());
        assertTrue(Goals.of(s.bb).isEmpty(), "no goal left");
        s.tick();
        assertNotNull(s.depositGoal(), "the line's deposit is a goal: " + s.said);
        assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed());
    }

    /**
     * Findings 3 and 5 (serious, the same fault): cobblestone 32 -d is met (oak_log 4 -d still open on its line), the owner
     * orders cobblestone 16 (a goal of its own), the bag is lost. The old order cannot be among the goals again (the new
     * one holds the key), so it leaves its line, said; when oak_log ends, the line ends and its deposit is due - never
     * waiting on an order nothing can end.
     */
    @Test
    void aLostMetOrderWhoseKeyANewerOrderHolds_leavesItsLine_theLineStillEnds() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 4, "b1", true);
        s.bag.put("cobblestone", 32);
        s.tick();
        assertTrue(cob.met);
        Goals.Goal again = s.order("cobblestone", 16, null, false);
        assertNotSame(cob, again);
        s.bag.clear();                                       // a death: the bag is lost
        s.tick();
        assertFalse(Goals.of(s.bb).contains(cob), "the newer order holds the key");
        assertFalse(s.deposits.openOrders().contains("cobblestone"), "the old order left its line: " + s.deposits.openOrders());
        assertTrue(s.said.stream().anyMatch(t -> t.contains("is not put back and leaves its line")), String.join("\n", s.said));
        assertFalse(s.said.stream().anyMatch(t -> t.contains("aa cancel ends that line")), "no false way out said");
        s.bag.put("oak_log", 4);
        s.tick();
        assertTrue(log.met, String.join("\n", s.said));
        assertTrue(s.due.contains(Map.of("oak_log", 4)), "the line ended: its deposit is due: " + s.due + "\n" + String.join("\n", s.said));
        assertNotNull(s.depositGoal());
    }

    /**
     * Finding 5 (the due case): the line was due (cobblestone 32 and oak_log 4, not started) when the bag was lost and a
     * newer cobblestone order held the key. The take-back must not strand the line: oak_log is open again, the old
     * cobblestone leaves the line, and when oak_log is met again the line's deposit is due again.
     */
    @Test
    void aDueLineTakenBackByALoss_withTheKeyHeldByANewerOrder_endsAgain() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 4, "b1", true);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", 4);
        s.tick();
        assertTrue(cob.met && log.met, String.join("\n", s.said));
        assertFalse(s.deposits.owed().isEmpty(), "the line is due");
        s.order("cobblestone", 16, null, false);
        s.bag.clear();
        s.tick();
        assertTrue(Goals.of(s.bb).contains(log), "oak_log is open again");
        assertFalse(s.deposits.openOrders().contains("cobblestone"), String.valueOf(s.deposits.openOrders()));
        s.due.clear();
        s.bag.put("oak_log", 4);
        s.tick();
        assertTrue(s.due.contains(Map.of("oak_log", 4)), "due again: " + s.due + "\n" + String.join("\n", s.said));
    }

    /**
     * Finding 7: cobblestone 32 -d parks with no wake left; another order's digging fills the bag and done() meets it while
     * parked; the bag is lost. The reopened order must come back with no stop: never weighed "stopped" in the same pass on
     * the bag that lost its items, and priced like any open order.
     */
    @Test
    void aMetOrderReopenedAfterALoss_comesBackWithNoStop() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        s.order("oak_log", 4, "b1", true);
        cob.parked = "the oak_log is in another dimension";
        s.stoppedForGood(cob);
        cob.looked = true;
        cob.climbRetried = true;
        cob.tries.add("a try");
        s.bag.put("cobblestone", 32);
        s.tick();
        assertTrue(cob.met, String.join("\n", s.said));
        s.bag.clear();
        s.said.clear();
        s.tick();
        assertTrue(Goals.of(s.bb).contains(cob), "open again: " + String.join("\n", s.said));
        assertNull(cob.parked, "no stop kept from before it was met");
        assertFalse(cob.depositWeighed, "not weighed 'stopped' in the same pass: " + String.join("\n", s.said));
        assertFalse(cob.looked || cob.climbRetried || !cob.tries.isEmpty());
        assertFalse(s.said.stream().anyMatch(t -> t.contains("cobblestone stopped")), String.join("\n", s.said));
    }
}
