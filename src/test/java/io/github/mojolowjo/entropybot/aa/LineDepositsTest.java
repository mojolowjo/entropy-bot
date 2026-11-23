package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits.Kind;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.OrderEnds;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deposit faults of 2026-11-22 (to-do/deposit-faults-2026-11-23.md, "done-with-less" parts 2 and 3), with no game:
 * a wake of an order whose line's deposit is DUE and has not started takes that deposit back (tester-1 w006: "spruce_log
 * woke ... the line's deposit waits for it" at 04:34:11.245, the stale deposit of spruce_log 11 started at .413), keyed on
 * the line (an order that noted nothing takes it back too: tester-2 w003-3 birch_log); a deposit on its way is left alone
 * and said so; and the order-end pass sweeps after its un-parks (w006: weighed 04:34:10.244, un-parked 04:34:10.249).
 * The goals come from {@link OrderLineSim} (the real Goals and catalog); the weighings are called by hand.
 */
class LineDepositsTest {

    @AfterEach
    void clear() {
        Only.set("off");
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        TestData.clear();
        Tuning.reset();
    }

    static final String AT = "15 72 0";

    /** A line of cobblestone 32 -d (done, 32 in the bag) and oak_log 16 -d (stopped with {@code logs} in the bag): DUE. */
    private static LineDeposits.Out due(OrderLineSim s, Goals.Goal cob, Goals.Goal log, int logs) {
        s.bag.put("cobblestone", 32);
        if (logs > 0) s.bag.put("oak_log", logs);
        assertEquals(Kind.NOTED, s.deposits.weigh(cob, "done", s.bag, AT).kind());
        log.parked = "the explore from here froze";
        LineDeposits.Out out = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.DUE, out.kind(), out.text());
        return out;
    }

    @Test
    void aWakeBeforeTheDepositStartsTakesItBack() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        assertEquals(Map.of("cobblestone", 32, "oak_log", 11), due(s, cob, log, 11).items());
        assertEquals(Map.of("cobblestone", 32, "oak_log", 11), s.deposits.dueNow());
        log.parked = null;                                   // it woke (the climb retry, a new source)
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_TAKEN_BACK, woke.kind(), woke.text());
        assertTrue(woke.text().contains("taken back") && woke.text().contains("oak_log 11") && !woke.text().contains("waits for it"), woke.text());
        assertNull(woke.items(), "nothing else is due");
        assertTrue(s.deposits.dueNow().isEmpty(), "no deposit is due any more");
        assertEquals(16, log.deposit, "nothing was taken off its amount (nothing went to the chest)");
        assertFalse(log.depositWeighed, "weighed again at its end");
        // the finished order's amount is still kept for the line's deposit (deposit-kept), now through the open line
        assertEquals(32, s.deposits.kept(s.bag, List.of(log), null, null).get("cobblestone"));
        assertNull(s.deposits.sweep(log, s.bag, AT), "running and not weighed: nothing to do");
        s.bag.put("oak_log", 16);
        LineDeposits.Out end = s.deposits.weigh(log, "done", s.bag, AT);
        assertEquals(Kind.DUE, end.kind(), end.text());
        assertEquals(Map.of("cobblestone", 32, "oak_log", 16), end.items(), "the line's one deposit, weighed on the bag at its end");
    }

    /** tester-2 w003-3: birch_log stopped with 0 in the bag ("its stop noted nothing"): it is not among the due items, yet it is of the due line. */
    @Test
    void aWakeOfAnOrderThatNotedNothingTakesTheDueDepositBack() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        assertEquals(Map.of("cobblestone", 32), due(s, cob, log, 0).items());
        log.parked = null;
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_TAKEN_BACK, woke.kind(), woke.text());
        assertTrue(woke.text().contains("cobblestone 32"), woke.text());
        assertTrue(s.deposits.dueNow().isEmpty());
        log.parked = "stopped again";                        // w006 04:34:14.248: parked again with the retry used up
        LineDeposits.Out again = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.DUE, again.kind(), again.text());
        assertEquals(Map.of("cobblestone", 32), again.items(), "the same items go, when the line really ends");
    }

    @Test
    void aDepositOnItsWayIsLeftAloneAndSaidSo() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        due(s, cob, log, 11);
        s.deposits.started();
        assertTrue(s.deposits.dueNow().isEmpty(), "started: not due any more");
        assertEquals(32, s.deposits.kept(s.bag, List.of(), null, null).get("cobblestone"), "still kept until the run ends");
        log.parked = null;
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_OPEN, woke.kind(), woke.text());
        assertTrue(woke.text().contains("is on its way") && woke.text().contains("the rest of its amount (5)") && !woke.text().contains("waits for it"), woke.text());
        assertEquals(5, log.deposit, "the 11 went with the line's deposit");
        s.deposits.gone();
        assertTrue(s.deposits.kept(s.bag, List.of(), null, null).isEmpty(), "the run ended: nothing kept for it");
    }

    @Test
    void aWakeAfterTheDepositWentSaysSo() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        due(s, cob, log, 0);
        s.depositRuns();
        log.parked = null;
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_OPEN, woke.kind(), woke.text());
        assertTrue(woke.text().contains("the line's deposit went already") && !woke.text().contains("waits for it"), woke.text());
        assertEquals(16, log.deposit);
    }

    @Test
    void aWakeWhileTheLineIsOpenWaitsForIt() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 7);
        cob.parked = "stopped";
        assertEquals(Kind.NOTED, s.deposits.sweep(cob, s.bag, AT).kind());
        cob.parked = null;
        LineDeposits.Out woke = s.deposits.sweep(cob, s.bag, AT);
        assertEquals(Kind.WOKE_OPEN, woke.kind());
        assertTrue(woke.text().contains("the line's deposit waits for it"), woke.text());
    }

    /** Two lines due at once: the wake takes back its own line's deposit only; the other stays due (its items in the answer). */
    @Test
    void aTakeBackLeavesAnotherLinesDueDeposit() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b2", true);
        due(s, cob, log, 11);
        s.bag.put("stick", 4);
        assertEquals(Kind.DUE, s.deposits.weigh(stick, "done", s.bag, AT).kind());
        log.parked = null;
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_TAKEN_BACK, woke.kind());
        assertEquals(Map.of("stick", 4), woke.items(), woke.text());
        assertEquals(Map.of("stick", 4), s.deposits.dueNow());
    }

    /** A line that came due while another's run was on its way stays due and kept when that run ends. */
    @Test
    void theEndOfARunLeavesALineThatCameDueMeanwhile() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b2", true);
        due(s, cob, log, 11);
        s.deposits.started();
        s.bag.put("stick", 4);
        assertEquals(Kind.DUE, s.deposits.weigh(stick, "done", s.bag, AT).kind());
        s.deposits.gone();
        assertEquals(Map.of("stick", 4), s.deposits.dueNow());
        assertEquals(Map.of("stick", 4), s.deposits.kept(s.bag, List.of(), null, null));
    }

    private static List<LineDeposits.Out> pass(OrderLineSim s, boolean underGround) {
        List<LineDeposits.Out> outs = new ArrayList<>();
        s.bb.in.bag = Map.copyOf(s.bag);
        OrderEnds.tick(s.bb, s.brain, s.deposits, AT, underGround, "goal reached", new OrderEnds.Hooks() {
            @Override public boolean carriesOn(Goals.Goal g) { return false; }

            @Override public void got(String msg) { }

            @Override public void deposit(LineDeposits.Out out) { if (out != null && out.text() != null) outs.add(out); }
        });
        return outs;
    }

    /**
     * w006 04:34:10.244 -> .249: every order parked under ground; the pass weighed torch "stopped" (the line went due) and
     * 5 ms later, in the same pass, the climb retry un-parked both orders. Wanted: an order un-parked in the pass is not
     * weighed as stopped, so no deposit comes due in it.
     */
    @Test
    void anOrderUnParkedInThePassIsNotWeighedAsStopped() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 11);
        for (Goals.Goal g : List.of(cob, log)) {
            g.parked = "the explore from here froze";
            g.park = new Goals.Park(Goals.ParkKind.BAG, null, 0, null, null);
        }
        List<LineDeposits.Out> outs = pass(s, true);
        assertNull(cob.parked, "the climb retry un-parked it in this pass");
        assertNull(log.parked);
        assertTrue(outs.stream().noneMatch(o -> o.kind() == Kind.DUE || o.kind() == Kind.NOTED), "nothing weighed as stopped: " + outs);
        assertTrue(s.deposits.dueNow().isEmpty());
    }

    /** A park that nothing in the pass wakes still ends its line (the owner's rule of 2026-11-02 stands; part 1 waits for him). */
    @Test
    void aParkThePassDoesNotWakeStillEndsTheLine() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 11);
        cob.parked = "stopped";
        log.parked = "stopped";
        List<LineDeposits.Out> outs = pass(s, false);
        assertNotNull(cob.parked);
        assertEquals(List.of(Kind.NOTED, Kind.DUE), outs.stream().map(LineDeposits.Out::kind).toList(), String.valueOf(outs));
        assertEquals(Map.of("cobblestone", 11), outs.get(1).items());
    }
}
