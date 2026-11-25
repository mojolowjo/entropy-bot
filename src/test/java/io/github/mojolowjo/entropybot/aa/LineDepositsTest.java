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
        s.deposits.putStarted();
        assertTrue(s.deposits.dueNow().isEmpty(), "started: not due any more");
        assertEquals(32, s.deposits.kept(s.bag, List.of(), null, null).get("cobblestone"), "still kept until the run ends");
        log.parked = null;
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_OPEN, woke.kind(), woke.text());
        assertTrue(woke.text().contains("is on its way") && woke.text().contains("the rest of its amount (5)") && !woke.text().contains("waits for it"), woke.text());
        assertEquals(5, log.deposit, "the 11 went with the line's deposit");
        s.deposits.gone(true);
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
        s.deposits.putStarted();
        s.bag.put("stick", 4);
        assertEquals(Kind.DUE, s.deposits.weigh(stick, "done", s.bag, AT).kind());
        s.deposits.gone(true);
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
        s.deposits.putStarted();
        s.bag.put("cobblestone", 32);                        // the run's way up dug through stone
        cob.parked = null;
        LineDeposits.Out again = s.deposits.weigh(cob, "done", s.bag, AT);
        assertEquals(Kind.DUE, again.kind(), again.text());
        assertEquals(Map.of("cobblestone", 12), again.items(), "20 are on their way: the rest of the 32");
        assertEquals(Map.of("cobblestone", 12), s.deposits.dueNow());
        assertEquals(Map.of("cobblestone", 32, "oak_log", 16), s.deposits.kept(s.bag, List.of(), null, null), "the 20 on their way and the 12 due are both kept");
        s.deposits.gone(true);                                   // the first run ended
        s.bag.put("cobblestone", 12);
        s.bag.remove("oak_log");
        assertEquals(Map.of("cobblestone", 12), s.deposits.dueNow(), "the remainder is still due: the -d amount goes whole");
        assertEquals(Map.of("cobblestone", 12), s.deposits.kept(s.bag, List.of(), null, null), "and still kept");
        s.deposits.putStarted();
        assertTrue(s.deposits.dueNow().isEmpty());
        s.deposits.gone(true);
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
        s.deposits.putStarted();
        s.bag.put("stick", 4);
        assertEquals(Kind.DUE, s.deposits.weigh(stick, "done", s.bag, AT).kind());
        s.deposits.gone(true);
        assertEquals(Map.of("stick", 4), s.deposits.dueNow());
    }

    /** A due deposit that could not start (no run on its way) is no longer due or kept. */
    @Test
    void aDepositThatCouldNotStartIsNoLongerDue() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        due(s, cob, log, 11);
        s.deposits.gone(false);
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

    // ---- parked-holds (owner 2026-11-23): a parked order that can still wake holds its line's deposit ----

    /** A park that keeps facts it waits on (every park but the owner's-word one): it can wake. */
    private static void parkWithWake(Goals.Goal g, String why) {
        g.parked = why;
        g.park = new Goals.Park(Goals.ParkKind.PLACE, null, 0, null, null);
    }

    /** tester-1 w001: cobblestone 32 done, the torch order parked on a furnace click. The line does not go due on the park. */
    @Test
    void aParkedOrderThatCanWakeHoldsTheLinesDeposit() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", 3);
        assertEquals(Kind.NOTED, s.deposits.weigh(cob, "done", s.bag, AT).kind());
        parkWithWake(log, "clicked 3 times, nothing opened");
        assertTrue(Goals.canWake(log));
        LineDeposits.Out held = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.HELD, held.kind(), held.text());
        assertTrue(held.text().contains("oak_log stopped: the line's deposit waits for it") && held.text().contains("cobblestone 32") && held.text().contains("aa cancel oak_log"), held.text());
        assertNull(s.deposits.sweep(log, s.bag, AT), "said once per hold");
        assertTrue(s.deposits.dueNow().isEmpty(), "nothing is due while it holds");
        assertFalse(log.depositWeighed, "not weighed: it has not ended");
        assertEquals(32, s.deposits.kept(s.bag, List.of(log), null, null).get("cobblestone"), "the finished amount stays kept for the line");
        List<String> lines = s.deposits.held(List.of(log), s.bag);
        assertEquals(1, lines.size(), String.valueOf(lines));
        assertTrue(lines.get(0).contains("waits for oak_log") && lines.get(0).contains("cobblestone 32"), lines.get(0));
        // it wakes, runs and is met: the line's ONE deposit goes whole
        log.parked = null;
        log.park = null;
        assertNull(s.deposits.sweep(log, s.bag, AT), "it was never weighed: nothing to re-open");
        assertFalse(log.depositHeld);
        assertTrue(s.deposits.held(List.of(log), s.bag).isEmpty());
        s.bag.put("oak_log", 16);
        LineDeposits.Out end = s.deposits.weigh(log, "done", s.bag, AT);
        assertEquals(Kind.DUE, end.kind(), end.text());
        assertEquals(Map.of("cobblestone", 32, "oak_log", 16), end.items(), "the -d amounts reach the chest whole");
    }

    /** The owner's way out when the holding order never wakes: aa cancel ends the line, the finished orders deposit. */
    @Test
    void droppingTheHoldingOrderEndsTheLine() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 32);
        s.deposits.weigh(cob, "done", s.bag, AT);
        parkWithWake(log, "no source in reach");
        assertEquals(Kind.HELD, s.deposits.sweep(log, s.bag, AT).kind());
        LineDeposits.Out out = s.deposits.dropped(log, s.bag, AT);
        assertEquals(Kind.DUE, out.kind(), out.text());
        assertEquals(Map.of("cobblestone", 32), out.items());
    }

    /** A hold whose park loses its wake (only the owner's word is left) ends the line as before: its bag goes to the chest. */
    @Test
    void aHeldOrderWithNoWakeLeftEndsTheLine() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", 5);
        s.deposits.weigh(cob, "done", s.bag, AT);
        parkWithWake(log, "no source in reach");
        assertEquals(Kind.HELD, s.deposits.sweep(log, s.bag, AT).kind());
        log.park = null;                                     // stopped for good
        assertFalse(Goals.canWake(log));
        LineDeposits.Out out = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.DUE, out.kind(), out.text());
        assertEquals(Map.of("cobblestone", 32, "oak_log", 5), out.items());
        assertFalse(log.depositHeld);
        assertTrue(s.deposits.held(List.of(log), s.bag).isEmpty());
    }

    /** An order with no deposit word holds nothing: its stop is said as before. */
    @Test
    void aParkedOrderWithNoDepositWordHoldsNothing() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal log = s.order("oak_log", 16, "b1", false);
        parkWithWake(log, "no source in reach");
        assertEquals(Kind.NO_WORD, s.deposits.sweep(log, s.bag, AT).kind());
        assertFalse(log.depositHeld);
    }

    /** The real pass: every order of the line parked with a wake on the surface - each hold said once, nothing due. */
    @Test
    void thePassHoldsTheDepositOfParkedOrdersThatCanWake() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 11);
        parkWithWake(cob, "stopped");
        parkWithWake(log, "stopped");
        List<LineDeposits.Out> outs = pass(s, false);
        assertEquals(List.of(Kind.HELD, Kind.HELD), outs.stream().map(LineDeposits.Out::kind).toList(), String.valueOf(outs));
        assertTrue(pass(s, false).isEmpty(), "said once");
        assertTrue(s.deposits.dueNow().isEmpty());
    }

    // ---- merge review 2026-11-23 ----

    /**
     * The review of parked-holds: `aa want oak_log 16 -d, cobblestone 32 -d`, oak_log done (noted), cobblestone stopped with
     * a wake (held); the owner follows the held line's advice and types aa want cobblestone 32 -d. The old order's key
     * stayed among line b1's open orders for ever: the 16 logs never went to the chest and nothing was said. Wanted: the
     * replaced order leaves its line, the line ends now and the logs go; the new order deposits with its own line.
     */
    @Test
    void anOrderOrderedAgainLeavesItsLine_theFinishedOrdersDeposit() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal log = s.order("oak_log", 16, "b1", true), cob = s.order("cobblestone", 32, "b1", true);
        s.bag.put("oak_log", 16);
        assertEquals(Kind.NOTED, s.deposits.weigh(log, "done", s.bag, AT).kind());
        parkWithWake(cob, "no source in reach");
        assertEquals(Kind.HELD, s.deposits.sweep(cob, s.bag, AT).kind());
        Goals.Goal again = s.order("cobblestone", 32, null, true);
        assertNotSame(cob, again);
        assertEquals(List.of(Map.of("oak_log", 16)), s.due, "line b1 ended without the replaced order: " + s.said);
        assertTrue(s.said.get(s.said.size() - 1).contains("cobblestone ordered again"), String.valueOf(s.said));
        assertEquals(Map.of("oak_log", 16), s.deposits.dueNow());
        assertTrue(s.deposits.held(List.of(again), s.bag).isEmpty(), "nothing is held any more");
        s.depositRuns();
        s.bag.remove("oak_log");
        s.bag.put("cobblestone", 32);
        LineDeposits.Out end = s.deposits.weigh(again, "done", s.bag, AT);
        assertEquals(Kind.DUE, end.kind(), end.text());
        assertEquals(Map.of("cobblestone", 32), end.items(), "the new order's own line");
    }

    /**
     * The merge review's sequence A: cobblestone 32 -d stopped for good with 17 in the bag (weighed, noted; torch... here
     * oak_log still open), then ordered again. The old goal stayed among line b1's ended orders: its 17 were kept from the
     * new order (met only at 49) and both lines deposited cobblestone. Wanted: the old order is out of its line.
     */
    @Test
    void aWeighedOrderOrderedAgainIsNotKeptOrDepositedTwice() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 17);
        cob.parked = "stopped";                              // no wake: weighed as stopped
        assertEquals(Kind.NOTED, s.deposits.sweep(cob, s.bag, AT).kind());
        Goals.Goal again = s.order("cobblestone", 32, null, true);
        assertTrue(s.deposits.kept(s.bag, List.of(log, again), again, null).isEmpty(), "nothing of the old order is kept from the new one");
        s.bag.put("oak_log", 16);
        LineDeposits.Out end = s.deposits.weigh(log, "done", s.bag, AT);
        assertEquals(Kind.DUE, end.kind(), end.text());
        assertEquals(Map.of("oak_log", 16), end.items(), "line b1 deposits the logs only");
    }

    /**
     * The merge review: a deposit run that FAILED (or was refused, or a line that ended with no main chest set) left its
     * orders' notes as "taken by the deposit"; a later wake cut the order's amount by items still in the bag and said the
     * deposit went already. Wanted: nothing is taken off, and the line says the deposit did not reach the chest.
     */
    @Test
    void aWakeAfterADepositThatDidNotReachTheChestTakesNothingOff() {
        for (String how : new String[] {"failed", "refused", "no chest"}) {
            OrderLineSim s = new OrderLineSim();
            Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
            s.bag.put("cobblestone", 32);
            s.bag.put("oak_log", 16);
            assertEquals(Kind.NOTED, s.deposits.weigh(cob, "done", s.bag, AT).kind());
            log.parked = "the explore from here froze";
            LineDeposits.Out out = s.deposits.sweep(log, s.bag, how.equals("no chest") ? null : AT);
            assertEquals(how.equals("no chest") ? Kind.NO_CHEST : Kind.DUE, out.kind(), out.text());
            if (how.equals("failed")) { s.deposits.putStarted(); s.deposits.gone(false); }
            if (how.equals("refused")) s.deposits.gone(false);
            log.parked = null;                               // it woke; the 16 logs are still in the bag
            LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
            assertEquals(Kind.WOKE_OPEN, woke.kind(), how + ": " + woke.text());
            assertEquals(16, log.deposit, how + ": nothing reached the chest, nothing is taken off its amount");
            assertTrue(woke.text().contains("did not reach the chest") && !woke.text().contains("went already"), woke.text());
            LineDeposits.Out end = s.deposits.weigh(log, "done", s.bag, AT);
            assertEquals(Kind.DUE, end.kind(), how + ": " + end.text());
            assertEquals(Map.of("oak_log", 16), end.items(), how);
        }
    }

    /** A deposit run that reached the chest still takes its amounts off a woken order (as before). */
    @Test
    void aWakeAfterADepositThatReachedTheChestStillTakesItOff() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        due(s, cob, log, 11);
        s.deposits.putStarted();
        s.deposits.gone(true);
        log.parked = null;
        assertEquals(Kind.WOKE_OPEN, s.deposits.sweep(log, s.bag, AT).kind());
        assertEquals(5, log.deposit);
    }

    /**
     * The merge review: a modded -d item. The weighing counts by the item's path (the bag's key); the deposit step was sent
     * that path, the chest click looked for minecraft:&lt;path&gt;, found nothing and the run ended "none of it in the bag".
     * Wanted: the step is sent the order's own full id, which the chest click finds.
     */
    @Test
    void aModdedItemsDepositIsSentByItsFullId() {
        String osmium = "mekanism:ingot_osmium";
        LineDeposits d = new LineDeposits();
        Goals.Goal g = new Goals.Goal(new io.github.mojolowjo.entropybot.aa.brain.Thing.Result(osmium, osmium, false, List.of(osmium), List.of("ingot_osmium"), 0, null, null, null), 2, 0, 0);
        d.add(g, null, true);
        Map<String, Integer> bag = Map.of("ingot_osmium", 2);
        assertEquals(Kind.DUE, d.weigh(g, "done", bag, AT).kind());
        assertEquals(Map.of("ingot_osmium", 2), d.dueNow(), "the weighing counts by the bag's key");
        assertEquals(Map.of(osmium, 2), d.owed(), "the step is sent the full id (owed, the one reader)");
        var stacks = List.of(new io.github.mojolowjo.entropybot.aa.brain.DepositRule.Item(osmium, 2, false, false, false, false));
        assertEquals(Map.of(osmium, 2), io.github.mojolowjo.entropybot.aa.brain.DepositRule.onlyToPut(stacks, d.owed()), "the chest click finds the stack");
        assertTrue(io.github.mojolowjo.entropybot.aa.brain.DepositRule.onlyToPut(stacks, d.dueNow()).isEmpty(), "the bare path names minecraft:ingot_osmium: the fault");
        // a vanilla item's full id is the minecraft one
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, null, true);
        s.bag.put("cobblestone", 32);
        assertEquals(Kind.DUE, s.deposits.weigh(cob, "done", s.bag, AT).kind());
        assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed());
    }

    /** A park with no wake (only the owner's word changes it) still ends its line: its bag goes to the chest as before. */
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

    // ---- deposit-goal plan, stage 1: the owed store (piece 2) ----

    private static String id(Goals.Goal g, String counted) { return LineDeposits.fullId(g, counted); }

    @Test
    void owedFollowsTheDepositThroughDuePutAndEnd() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        assertTrue(s.deposits.owed().isEmpty(), "nothing owed before the line ends");
        due(s, cob, log, 11);
        Map<String, Integer> all = Map.of(id(cob, "cobblestone"), 32, id(log, "oak_log"), 11);
        assertEquals(all, s.deposits.owed(), "due: owed");
        s.deposits.putStarted();
        assertEquals(all, s.deposits.owed(), "on its way: still owed");
        assertTrue(s.deposits.dueNow().isEmpty());
        s.deposits.putEnded(false);
        assertEquals(all, s.deposits.owed(), "failed: owed again");
        assertFalse(cob.depositMissed || log.depositMissed, "a failed put marks no order missed");
        assertEquals(Map.of("cobblestone", 32, "oak_log", 11), s.deposits.dueNow(), "due again, as before the put");
        assertEquals(32, s.deposits.kept(s.bag, List.of(), null, null).get("cobblestone"), "the amounts still kept");
        log.parked = null;                                   // a later take-back still works
        LineDeposits.Out woke = s.deposits.sweep(log, s.bag, AT);
        assertEquals(Kind.WOKE_TAKEN_BACK, woke.kind(), woke.text());
        assertEquals(16, log.deposit, "nothing taken off its amount");
        log.parked = "stopped again";
        assertEquals(Kind.DUE, s.deposits.sweep(log, s.bag, AT).kind());
        s.deposits.putStarted();
        s.deposits.putEnded(true);
        assertTrue(s.deposits.owed().isEmpty(), "reached: nothing owed");
        assertTrue(s.deposits.owedOrders().isEmpty());
    }

    @Test
    void aWakeBeforeThePutTakesTheOwedDepositBack() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b2", true);
        due(s, cob, log, 11);
        s.bag.put("stick", 4);
        assertEquals(Kind.DUE, s.deposits.weigh(stick, "done", s.bag, AT).kind());
        log.parked = null;
        assertEquals(Kind.WOKE_TAKEN_BACK, s.deposits.sweep(log, s.bag, AT).kind());
        assertEquals(Map.of(id(stick, "stick"), 4), s.deposits.owed(), "only the other line is owed now");
    }

    @Test
    void aLineDueWhileThePutRunsStaysOwedAfterItReaches() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b2", true);
        due(s, cob, log, 11);
        s.deposits.putStarted();
        s.bag.put("stick", 4);
        assertEquals(Kind.DUE, s.deposits.weigh(stick, "done", s.bag, AT).kind());
        s.deposits.putEnded(true);
        assertEquals(Map.of(id(stick, "stick"), 4), s.deposits.owed());
        assertEquals(Map.of("stick", 4), s.deposits.dueNow());
    }

    @Test
    void aFailedPutJoinsALineThatCameDueMeanwhile() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b2", true);
        due(s, cob, log, 11);
        s.deposits.putStarted();
        s.bag.put("stick", 4);
        assertEquals(Kind.DUE, s.deposits.weigh(stick, "done", s.bag, AT).kind());
        s.deposits.failed();
        assertEquals(Map.of("cobblestone", 32, "oak_log", 11, "stick", 4), s.deposits.dueNow());
    }

    @Test
    void owedOrdersCountTheOrdersThatNotedNothing() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        due(s, cob, log, 0);
        assertEquals(Map.of(id(cob, "cobblestone"), 32), s.deposits.owed());
        var orders = s.deposits.owedOrders();
        assertEquals(2, orders.size());
        assertTrue(orders.contains(cob) && orders.contains(log), "oak_log noted nothing, it is still behind the deposit");
        s.deposits.putStarted();
        assertEquals(2, s.deposits.owedOrders().size(), "on its way: the same orders");
    }
}
