package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * short-wake (pilot 2026-11-25, w057 and w061: the chest empty, the whole line in the bag): the line's deposit was owed
 * without a stopped order ("goes without the rest of it - short: oak_log 2 of 16"; "torch 0 of 4"), the deposit goal was
 * added, and the stopped order woke (w057 08:49:46.724 "a new source seen"; w061 09:17:02.795 "moved 54 blocks from where
 * it parked" - the deposit goal's own walk). The wake took the WHOLE due deposit back, nothing was owed and the deposit
 * goal ended. Wanted: only the woken order leaves the due deposit, the rest stays due and goes; the woken order's amount
 * reaches the chest whole, once, with a deposit of its own when it ends. Played on {@link OrderLineSim} (the real brain,
 * Goals, OrderEnds, LineDeposits). The puts are the sim's ({@link #put}): what is owed when the put starts goes in.
 */
class DepositShortWakeTest {

    @AfterEach
    void clear() {
        Only.set("off");
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        TestData.clear();
        Tuning.reset();
    }

    /** What each put carried to the chest (full id -> n), in order. */
    final List<Map<String, Integer>> puts = new ArrayList<>();

    /** The deposit goal's put, reached: what is owed now goes in and leaves the bag. */
    void put(OrderLineSim s) {
        Map<String, Integer> owed = new TreeMap<>(s.deposits.owed());
        puts.add(owed);
        s.depositRuns();
        for (var e : owed.entrySet()) s.bag.merge(e.getKey().replace("minecraft:", ""), -e.getValue(), Integer::sum);
        s.bag.values().removeIf(n -> n <= 0);
    }

    /** All the puts summed (full id -> n). */
    Map<String, Integer> chest() {
        Map<String, Integer> out = new TreeMap<>();
        for (var p : puts) for (var e : p.entrySet()) out.merge(e.getKey(), e.getValue(), Integer::sum);
        return out;
    }

    /**
     * The w057 line, cut to the sim's items: cobblestone 32 met at once (in the bag), oak_log 16 runs, 2 in the bag, and
     * stops on the honest loop at the surface; nothing it waits on can change, so the line's deposit is owed short.
     */
    static Goals.Goal[] owedShort(OrderLineSim s, String batch) { return owedShort(s, batch, 2); }

    /** As {@link #owedShort(OrderLineSim, String)}, the oak_log stopping with {@code n} in the bag (w061's torch: 0). */
    static Goals.Goal[] owedShort(OrderLineSim s, String batch, int n) {
        Goals.Goal cob = batch == null ? null : s.order("cobblestone", 32, batch, true);
        Goals.Goal log = s.order("oak_log", 16, batch, true);
        if (cob != null) s.bag.put("cobblestone", 32);
        parkShort(s, log, n);
        return new Goals.Goal[]{cob, log};
    }

    /** {@code log} runs and stops on the honest loop with 2 in the bag; the pass then owes the line short. */
    static void parkShort(OrderLineSim s, Goals.Goal log) { parkShort(s, log, 2); }

    /** {@code log} runs and stops on the honest loop with {@code n} in the bag (0: none); the pass then owes the line short. */
    static void parkShort(OrderLineSim s, Goals.Goal log, int n) {
        for (int i = 0; i < 60 && (log.parked == null || !log.depositWeighed); i++) {
            s.run(1, 5000);
            String r = s.running();
            if (log.id().equals(r)) {
                if (n > 0) s.bag.put("oak_log", n); else s.bag.remove("oak_log");
                log.loopAttempt = Z70StepCheckTest.cobbleTry();          // the run ends on the honest loop
                s.failRun(OrderLineScenariosTest.JBM_STAND_END);
            } else if (r != null && r.equals("goal." + Goals.DEPOSIT_KEY)) {
                s.failRun("busy: the test ends the running put");      // a passing end: no park of its own
            }
        }
        assertNotNull(log.parked, "oak_log is stopped: " + s.said);
        assertTrue(log.depositWeighed, "weighed: the line is owed short: " + s.said);
    }

    /** The wake w057 saw: a new oak tree comes into sight. */
    static void newTreeSeen(OrderLineSim s) {
        List<NeedChain.Spot> trees = new ArrayList<>(s.seen.get("oak_log"));
        trees.add(ScenariosTest.tree(30, 30));
        s.seen.put("oak_log", trees);
    }

    @Test
    void w057_aNewSourceWakesTheShortOrder_theRestOfTheLineStaysDue() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        Goals.Goal[] o = owedShort(s, "b1");
        assertTrue(s.said.stream().anyMatch(l -> l.contains("short: oak_log 2 of 16")), s.said.toString());
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 2), s.deposits.owed(), "owed short: " + s.said);
        assertNotNull(s.depositGoal(), "the deposit goal: " + s.said);
        newTreeSeen(s);
        s.run(1, 5000);
        assertNull(o[1].parked, "oak_log woke (a new source seen): " + s.port.log);
        assertTrue(s.port.log.stream().anyMatch(l -> l.contains("order oak_log runs again: a new source seen")), s.port.log.toString());
        assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed(), "only the woken order left the due deposit: " + s.said);
        assertNotNull(s.depositGoal(), "the deposit goal stays: " + s.said);
        assertTrue(s.said.stream().anyMatch(l -> l.contains("oak_log woke: the line's deposit owed without it stays due (cobblestone 32); its oak_log 2 in the bag stay its own; "
                + "its whole amount (16) follows with a deposit of its own when it ends")), s.said.toString());
        assertTrue(s.said.stream().noneMatch(l -> l.contains("taken back")), "nothing taken back: " + s.said);
        assertEquals(16, o[1].deposit, "nothing of it reached the chest: its amount is not cut");
        // (1) its own 2 in the bag are kept for its own amount, never put by the still-due deposit
        assertEquals(2, s.deposits.kept(s.bb.in.bag, Goals.of(s.bb), null, null).getOrDefault("oak_log", 0), "kept for its own amount");
        put(s);
        s.run(1, 5000);
        assertNull(s.depositGoal(), "the rest went: " + s.said);
        assertTrue(s.said.stream().anyMatch(l -> l.equals("the line's deposit goal ended: in the chest")), s.said.toString());
        // (2) it ends: its deposit is due on its own and goes, whole, once
        s.bag.put("oak_log", 16);
        s.run(3, 5000);
        assertTrue(s.ordersLeft().isEmpty(), "met: " + s.said);
        assertEquals(Map.of("minecraft:oak_log", 16), s.deposits.owed(), "its own deposit, whole: " + s.said);
        put(s);
        s.run(1, 5000);
        assertNull(s.depositGoal(), s.said.toString());
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), chest(), "each amount whole, once: " + puts);
    }

    @Test
    void w061_theDepositsOwnWalkWakesTheShortOrder_theRestStillGoes() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        Goals.Goal[] o = owedShort(s, "b1");
        s.run(1, 5000);
        assertEquals("goal." + Goals.DEPOSIT_KEY, s.running(), "the deposit goal runs (walking to the chest): " + s.brain.whyLines(4));
        s.feet[0] = 54;                                       // 09:17:02.795: the deposit's walk moved the bot 54 blocks
        s.run(1, 5000);
        assertNull(o[1].parked, "oak_log woke: " + s.port.log);
        assertTrue(s.port.log.stream().anyMatch(l -> l.contains("order oak_log runs again: moved 54 blocks from where it parked")), s.port.log.toString());
        assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed(), "the rest stays owed: " + s.said);
        assertNotNull(s.depositGoal(), "the deposit goal is not ended by its own walk: " + s.said);
        put(s);
        s.bag.put("oak_log", 16);
        s.run(3, 5000);
        assertEquals(Map.of("minecraft:oak_log", 16), s.deposits.owed(), s.said.toString());
        put(s);
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), chest(), "each amount whole, once: " + puts);
    }

    /**
     * w061 as it was (torch 0 of 4: nothing of the stopped order in the bag), and the review's circle: the deposit's walk
     * wakes it, it stops again with nothing in the bag while nothing runs (the deposit goal's run ended), and wakes again
     * (the deposit's walk once more). The re-stop joins the still-due rest of its line, so the second wake takes only itself
     * out: the rest stays due through every round and goes; nothing is taken back.
     */
    @Test
    void w061_noneInTheBag_theDepositsWalkWakesIt_itStopsAgainWithNone_wakesAgain_theRestStillGoes() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        Goals.Goal[] o = owedShort(s, "b1", 0);
        assertTrue(s.said.stream().anyMatch(l -> l.contains("short: oak_log 0 of 16")), s.said.toString());
        assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed(), "owed short with none of it: " + s.said);
        assertTrue(o[1].depositOwedShort, s.said.toString());
        for (int round = 0; round < 3; round++) {
            assertNotNull(s.depositGoal(), "round " + round + ": the deposit goal: " + s.said);
            s.feet[0] += 54;                                  // the deposit's walk moved the bot (w061 09:17:02.795)
            s.run(1, 5000);
            assertNull(o[1].parked, "round " + round + ": oak_log woke: " + s.port.log);
            assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed(), "round " + round + ": the rest stays owed: " + s.said);
            assertNotNull(s.depositGoal(), "round " + round + ": the deposit goal is not ended by its own walk: " + s.said);
            assertEquals(16, o[1].deposit, "round " + round + ": its amount whole");
            if (round == 2) break;
            parkShort(s, o[1], 0);                            // stops again with none in the bag
            assertTrue(o[1].depositOwedShort, "round " + round + ": the re-stop joined the still-due rest: " + s.said);
            assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed(), "round " + round + ": " + s.said);
            assertTrue(s.said.stream().anyMatch(l -> l.contains("nothing of its own deposit items in the bag; the rest of the line still due goes (cobblestone 32)")), s.said.toString());
        }
        assertTrue(s.said.stream().noneMatch(l -> l.contains("taken back")), "nothing taken back: " + s.said);
        assertTrue(s.said.stream().noneMatch(l -> l.contains("no deposit") && l.contains("oak_log")), "never a false 'no deposit' (rule 7): " + s.said);
        assertEquals(0, s.takenBack.size(), "nothing taken back: " + s.takenBack);
        put(s);
        s.run(1, 5000);
        assertNull(s.depositGoal(), "the rest went: " + s.said);
        s.bag.put("oak_log", 16);
        s.run(3, 5000);
        assertTrue(s.ordersLeft().isEmpty(), "met: " + s.said);
        assertEquals(Map.of("minecraft:oak_log", 16), s.deposits.owed(), "its own deposit, whole: " + s.said);
        put(s);
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), chest(), "each amount whole, once: " + puts);
    }

    /** (2) the woken order ends while the rest of its line is still due: its deposit joins the rest (merged, never put over). */
    @Test
    void theWokenOrderEndsBeforeTheRestWent_itsDepositJoinsTheRest() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        owedShort(s, "b1");
        newTreeSeen(s);
        s.run(1, 5000);
        assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed(), s.said.toString());
        s.bag.put("oak_log", 16);
        s.run(3, 5000);
        assertTrue(s.ordersLeft().isEmpty(), "met: " + s.said);
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), s.deposits.owed(), "the rest kept, its own joined: " + s.said);
        assertTrue(s.said.stream().anyMatch(l -> l.contains("(with the rest of the line still due: cobblestone 32, oak_log 16 in all)")), s.said.toString());
        put(s);
        s.run(1, 5000);
        assertNull(s.depositGoal(), s.said.toString());
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), chest(), "one put, each amount whole: " + puts);
    }

    /** (3) a wake while the put is on its way: the put carries what it was sent with; the order owes the rest of its amount. */
    @Test
    void aWakeWhileThePutIsOnItsWay_thePutGoes_theRestOfTheAmountFollows() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        Goals.Goal[] o = owedShort(s, "b1");
        Map<String, Integer> sent = new TreeMap<>(s.deposits.owed());
        s.deposits.putStarted();                              // the put step started: from here a wake leaves it alone
        newTreeSeen(s);
        s.run(1, 5000);
        assertNull(o[1].parked, "woke: " + s.port.log);
        assertEquals(sent, s.deposits.owed(), "the put on its way keeps what it was sent with: " + s.said);
        assertTrue(s.said.stream().anyMatch(l -> l.contains("oak_log woke: its deposit items are weighed again when it ends") && l.contains("is on its way; the rest of its amount (14) follows")), s.said.toString());
        s.deposits.putEnded(true);
        puts.add(sent);
        s.bag.remove("cobblestone");
        s.bag.put("oak_log", 16);                             // met: the 2 went in, 16 gathered anew
        s.run(3, 5000);
        assertTrue(s.ordersLeft().isEmpty(), "met: " + s.said);
        put(s);
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), chest(), "2 with the put, 14 later: whole, once: " + puts);
    }

    /** (4) the due deposit held only the woken order's items: nothing is owed, the goal ends, said without "nothing is owed now". */
    @Test
    void aWakeWhenTheDueDepositHeldOnlyItsOwnItems_theGoalEnds_theLineIsOpen() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        Goals.Goal[] o = owedShort(s, null);
        assertEquals(Map.of("minecraft:oak_log", 2), s.deposits.owed(), s.said.toString());
        newTreeSeen(s);
        s.run(1, 5000);
        assertNull(o[1].parked, "woke: " + s.port.log);
        assertTrue(s.deposits.owed().isEmpty(), s.said.toString());
        assertNull(s.depositGoal(), "nothing owed: the goal ends: " + s.said);
        assertTrue(s.said.stream().anyMatch(l -> l.contains("oak_log woke: the line's deposit owed without the rest of it held only its own items and had not started - nothing of the line is due now")), s.said.toString());
        assertTrue(s.said.stream().anyMatch(l -> l.equals("the line's deposit goal ended: taken back (the line is open again: its deposit follows when oak_log ends)")), s.said.toString());
        assertTrue(s.said.stream().noneMatch(l -> l.contains("nothing is owed now")), "the line is open again: " + s.said);
        s.bag.put("oak_log", 16);
        s.run(3, 5000);
        assertEquals(Map.of("minecraft:oak_log", 16), s.deposits.owed(), "its whole amount: " + s.said);
        put(s);
        assertEquals(Map.of("minecraft:oak_log", 16), chest(), puts.toString());
    }

    /** (5) the same order stops short and wakes three times: the rest stays due each time, its own amount is never doubled. */
    @Test
    void theSameOrderParksAndWakesSeveralTimes_theRestStaysDue_itsAmountOnce() { rounds(2); }

    /** (5) as above, every stop with none of it in the bag (w061's torch 0 of 4). */
    @Test
    void theSameOrderParksAndWakesSeveralTimes_noneInTheBag_theRestStaysDue() { rounds(0); }

    void rounds(int n) {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        Goals.Goal[] o = owedShort(s, "b1", n);
        Map<String, Integer> owedShort = new TreeMap<>(Map.of("minecraft:cobblestone", 32));
        if (n > 0) owedShort.put("minecraft:oak_log", n);
        for (int round = 0; round < 3; round++) {
            assertEquals(owedShort, s.deposits.owed(), "round " + round + ": owed short, once each: " + s.said);
            assertTrue(o[1].depositOwedShort, "round " + round);
            s.feet[0] += 20;                                   // a move wakes it (failures.moved 16)
            s.run(1, 5000);
            assertNull(o[1].parked, "round " + round + ": woke: " + s.port.log);
            assertEquals(Map.of("minecraft:cobblestone", 32), s.deposits.owed(), "round " + round + ": the rest stays due: " + s.said);
            assertEquals(16, o[1].deposit, "round " + round + ": its amount whole");
            if (round < 2) parkShort(s, o[1], n);
        }
        put(s);
        s.bag.put("oak_log", 16);
        s.run(3, 5000);
        assertTrue(s.ordersLeft().isEmpty(), "met: " + s.said);
        put(s);
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), chest(), "each amount whole, once: " + puts);
        assertEquals(0, s.takenBack.size(), "nothing taken back: " + s.takenBack);
    }

    /**
     * (6) the take-back the run 3 circle needs stays as it was for a stop that was not owed short (tester-1 w006: a line
     * that went due on stopped orders and woke takes the whole deposit back; the line waits for the woken order).
     */
    @Test
    void aWakeOfAnOrderNotOwedShort_stillTakesTheWholeDueDepositBack() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", 2);
        s.run(1, 5000);
        log.parked = "stopped at step 1 (cut 6 oak_log at 10 64 12): test";
        LineDeposits.Out end = s.deposits.weigh(log, "stopped", s.bb.in.bag, s.mainAt);
        assertEquals(LineDeposits.Kind.DUE, end.kind(), end.text());
        assertFalse(log.depositOwedShort, "a plain stop is not owed short");
        log.parked = null;
        LineDeposits.Out back = s.deposits.reopen(log);
        assertEquals(LineDeposits.Kind.WOKE_TAKEN_BACK, back.kind(), back.text());
        assertTrue(s.deposits.owed().isEmpty(), "the whole due deposit taken back: " + s.deposits.owed());
        assertNotNull(cob);
    }
}
