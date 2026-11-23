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

    /**
     * Review round 2: the SAME line comes due again while its own earlier deposit is on its way. cobblestone 32 -d parked
     * with 20 in the bag, the line went due and its run started; the run's climb through stone filled the bag, the order
     * was met and weighed again: 12 more are due. The end of the first run takes off what it was sent with, never the 12.
     */
    @Test
    void theEndOfARunLeavesItsOwnLineThatCameDueAgain() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("oak_log", 16);
        assertEquals(Kind.NOTED, s.deposits.weigh(log, "done", s.bag, AT).kind());
        s.bag.put("cobblestone", 20);
        cob.parked = "no source in reach";
        LineDeposits.Out first = s.deposits.sweep(cob, s.bag, AT);
        assertEquals(Kind.DUE, first.kind(), first.text());
        assertEquals(Map.of("cobblestone", 20, "oak_log", 16), first.items());
        s.deposits.started();
        s.bag.put("cobblestone", 32);                        // the run's way up dug through stone
        cob.parked = null;
        LineDeposits.Out again = s.deposits.weigh(cob, "done", s.bag, AT);
        assertEquals(Kind.DUE, again.kind(), again.text());
        assertEquals(Map.of("cobblestone", 12), again.items(), "20 are on their way: the rest of the 32");
        assertEquals(Map.of("cobblestone", 12), s.deposits.dueNow());
        assertEquals(Map.of("cobblestone", 32, "oak_log", 16), s.deposits.kept(s.bag, List.of(), null, null), "the 20 on their way and the 12 due are both kept");
        s.deposits.gone();                                   // the first run ended
        s.bag.put("cobblestone", 12);
        s.bag.remove("oak_log");
        assertEquals(Map.of("cobblestone", 12), s.deposits.dueNow(), "the remainder is still due: the -d amount goes whole");
        assertEquals(Map.of("cobblestone", 12), s.deposits.kept(s.bag, List.of(), null, null), "and still kept");
        s.deposits.started();
        assertTrue(s.deposits.dueNow().isEmpty());
        s.deposits.gone();
        assertTrue(s.deposits.kept(s.bag, List.of(), null, null).isEmpty(), "the second run ended: nothing left behind");
        // nothing of the line leaked: a later wake of an order of it finds no deposit to take back
        log.parked = null;
        assertNotEquals(Kind.WOKE_TAKEN_BACK, s.deposits.reopen(log).kind());
    }

    /** A run sent with nothing noted in the store (started with no line due) still ends as a run: what came due meanwhile stays. */
    @Test
    void theEndOfARunSentWithNothingLeavesWhatCameDueMeanwhile() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal stick = s.order("stick", 4, "b2", true);
        s.deposits.started();
        s.bag.put("stick", 4);
        assertEquals(Kind.DUE, s.deposits.weigh(stick, "done", s.bag, AT).kind());
        s.deposits.gone();
        assertEquals(Map.of("stick", 4), s.deposits.dueNow());
    }

    /** A due deposit that could not start (no run on its way) is no longer due or kept. */
    @Test
    void aDepositThatCouldNotStartIsNoLongerDue() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        due(s, cob, log, 11);
        s.deposits.gone();
        assertTrue(s.deposits.dueNow().isEmpty());
        assertTrue(s.deposits.kept(s.bag, List.of(), null, null).isEmpty());
    }

    /**
     * Review 1: a line of three -d orders. The first deposit went with {@code logs} of oak_log (parked) and nothing of stick
     * (parked); stick woke, was met and the line is DUE again on stick alone. Answers the oak_log order, still parked.
     */
    private static Goals.Goal secondDepositDue(OrderLineSim s, int logs) {
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true), stick = s.order("stick", 4, "b1", true);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", logs);
        assertEquals(Kind.NOTED, s.deposits.weigh(cob, "done", s.bag, AT).kind());
        log.parked = "the explore from here froze";
        assertEquals(Kind.NOTED, s.deposits.sweep(log, s.bag, AT).kind());
        stick.parked = "no source known";
        LineDeposits.Out first = s.deposits.sweep(stick, s.bag, AT);
        assertEquals(Kind.DUE, first.kind(), first.text());
        assertEquals(Map.of("cobblestone", 32, "oak_log", logs), first.items());
        s.depositRuns();                                     // the first deposit went: the chest holds them
        s.bag.remove("cobblestone");
        s.bag.remove("oak_log");
        stick.parked = null;
        assertEquals(Kind.WOKE_OPEN, s.deposits.sweep(stick, s.bag, AT).kind());
        s.bag.put("stick", 4);
        LineDeposits.Out second = s.deposits.weigh(stick, "done", s.bag, AT);
        assertEquals(Kind.DUE, second.kind(), second.text());
        assertEquals(Map.of("stick", 4), second.items());
        return log;
    }

    /**
     * Review 1: the woken order is of the due line but not one of the due deposit's orders (its 11 went with the line's
     * first deposit). The due deposit is taken back, and the 11 are taken off its amount: 5 more go, never 16 again.
     */
    @Test
    void aWakeOfAnOrderOutsideTheDueSetKeepsWhatWentAlready() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal log = secondDepositDue(s, 11);
        log.parked = null;
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_TAKEN_BACK, woke.kind(), woke.text());
        assertTrue(woke.text().contains("stick 4") && woke.text().contains("oak_log 11") && woke.text().contains("the rest of its amount, 5"), woke.text());
        assertEquals(5, log.deposit, "the 11 in the chest are not deposited again");
        assertFalse(log.depositWeighed);
        assertTrue(s.deposits.dueNow().isEmpty(), "the due deposit waits for the woken order");
        s.bag.put("oak_log", 16);
        assertEquals(Map.of("oak_log", 5, "stick", 4), s.deposits.kept(s.bag, List.of(log), null, null), "5 kept from the planner's bag, not 16");
        LineDeposits.Out end = s.deposits.weigh(log, "done", s.bag, AT);
        assertEquals(Kind.DUE, end.kind(), end.text());
        assertEquals(Map.of("oak_log", 5, "stick", 4), end.items(), "16 for an order of 16: 11 before, 5 now");
    }

    /** Review 1: the whole amount of the woken order went with the first deposit: nothing more of it, the due deposit stays due. */
    @Test
    void aWakeOfAnOrderOutsideTheDueSetWithNothingLeftLeavesTheDueDeposit() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal log = secondDepositDue(s, 16);
        log.parked = null;
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_GONE, woke.kind(), woke.text());
        assertTrue(woke.text().contains("oak_log 16") && woke.text().contains("stick 4 stays due"), woke.text());
        assertEquals(0, log.deposit);
        assertEquals(Map.of("stick", 4), s.deposits.dueNow(), "the due deposit is not this order's: it still starts");
        assertNull(s.deposits.sweep(log, s.bag, AT), "said once");
    }

    /**
     * Review 3: an order stopped earlier and met since is weighed again by its "done"; a due deposit of its line that had
     * not started is taken back by that weighing. Whatever the weighing answers, the line says so and nothing is due in
     * the store any more (the runtime starts a queued deposit only from dueNow()).
     */
    @Test
    void aTakeBackThroughAWeighingIsSaidAndNothingStaysDue() {
        for (String mainAt : new String[] {AT, null}) {
            OrderLineSim s = new OrderLineSim();
            Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
            due(s, cob, log, 0);
            if (mainAt != null) s.bag.clear();               // the bag holds nothing of the line now: NOTHING
            else s.bag.put("oak_log", 16);                   // no main chest set any more: NO_CHEST
            LineDeposits.Out out = s.deposits.weigh(log, "done", s.bag, mainAt);
            assertEquals(mainAt != null ? Kind.NOTHING : Kind.NO_CHEST, out.kind(), out.text());
            assertTrue(out.text().contains("the line's deposit that had not started is taken back"), out.text());
            assertTrue(s.deposits.dueNow().isEmpty(), "the taken-back deposit is not due: " + s.deposits.dueNow());
        }
    }

    /** Review 3: the same weighing with the line's items in the bag answers DUE, the take-back said in it, the store as the answer. */
    @Test
    void aTakeBackThroughAWeighingThatEndsTheLineIsDueAgain() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        due(s, cob, log, 0);
        s.bag.put("oak_log", 16);
        LineDeposits.Out out = s.deposits.weigh(log, "done", s.bag, AT);
        assertEquals(Kind.DUE, out.kind(), out.text());
        assertTrue(out.text().contains("taken back"), out.text());
        assertEquals(Map.of("cobblestone", 32, "oak_log", 16), out.items());
        assertEquals(out.items(), s.deposits.dueNow());
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
