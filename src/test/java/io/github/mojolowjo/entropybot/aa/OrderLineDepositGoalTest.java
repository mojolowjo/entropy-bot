package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.WorldFacts;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * deposit-goal plan, stage 3 (pieces 4 and 6; owner 2026-11-23: the line's deposit is a planned, priced goal with its own
 * ways out of the ground): the deposit goal on the order line with no game ({@link OrderLineSim}: the real brain, Goals,
 * OrderEnds.depositSync, LineDeposits). Hand numbers: the walks are the fake path's seconds; the main chest is the sim's
 * -53 107 -8.
 */
class OrderLineDepositGoalTest {

    @AfterEach
    void clear() {
        Only.set("off");
        FactChange.clear();
        TestData.clear();
        Tuning.reset();
    }

    static PathPrice.Answer priced(double s) { return PathPrice.Answer.priced(new PathPrice.Walk(s)); }

    /** The line of two -d orders, both met: its deposit is owed (cobblestone 32, oak_log 16). */
    static OrderLineSim lineDue() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        s.bag.put("dirt", 64);
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true), log = s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", 16);
        s.run(3, 5000);
        assertTrue(s.ordersLeft().isEmpty(), "both met: " + s.said);
        assertNotNull(cob);
        assertNotNull(log);
        return s;
    }

    static long goals(OrderLineSim s) { return Goals.of(s.bb).stream().filter(g -> g.depositGoal).count(); }

    @Test
    void due_makesExactlyOneGoal_andNoGotLine() {
        OrderLineSim s = lineDue();
        assertEquals(1, goals(s), s.said.toString());
        s.run(5, 5000);
        assertEquals(1, goals(s), "still one: " + s.said);
        assertTrue(s.said.stream().anyMatch(l -> l.contains("is a goal now")), s.said.toString());
        assertTrue(s.said.stream().noneMatch(l -> l.contains("got 1 deposit")), s.said.toString());
        assertTrue(s.said.stream().noneMatch(l -> l.contains("deposit") && l.contains("no deposit word")), "the goal is never weighed: " + s.said);
        assertEquals("goal." + Goals.DEPOSIT_KEY, s.running(), "its desire runs: " + s.brain.whyLines(4));
    }

    @Test
    void aTakeBack_removesIt_said() {
        OrderLineSim s = lineDue();
        Goals.Goal cob = Goals.finished(s.bb).isEmpty() ? null : Goals.finished(s.bb).get(0).order;
        assertNotNull(cob);
        s.deposits.reopen(cob);                  // a wake of one of the line's orders before the put started
        s.run(1, 5000);
        assertEquals(0, goals(s), s.said.toString());
        assertTrue(s.said.stream().anyMatch(l -> l.contains("the line's deposit goal ended: taken back")), s.said.toString());
    }

    @Test
    void thePutReachesTheChest_theGoalEnds_said() {
        OrderLineSim s = lineDue();
        s.depositRuns();
        s.run(1, 5000);
        assertEquals(0, goals(s), s.said.toString());
        assertTrue(s.said.stream().anyMatch(l -> l.contains("the line's deposit goal ended: in the chest")), s.said.toString());
    }

    @Test
    void aFailedWalkUp_rePlansToTheClimb_heldWorldWide() {
        OrderLineSim s = lineDue();
        s.under(20);
        s.path = (x, y, z, d) -> x == -53 ? PathPrice.Answer.unreachable("no path from under ground") : priced(10);
        Goals.Goal dg = s.depositGoal();
        var first = Goals.chain(s.bb, dg);
        assertTrue(first.ok(), first.why());
        assertEquals(List.of(new Step.ToSurface(0, 84, 0)), first.steps(), first.why());
        FactChange.surface(0, 84, 0, "the walk up ended short");
        var rt = Goals.replanner(s.bb, dg).replan(first.steps().get(0), FactChange.take(), "the walk up ended short");
        assertNotNull(rt, "a way after the failed leg");
        assertInstanceOf(Step.Climb.class, rt.steps().get(0), rt.steps().toString());
        // the hold is a world fact: any goal's chain from here prices without the walk up
        assertTrue(WorldFacts.of(s.bb).heldFor(s.bb, java.util.Set.of()).containsKey(NeedChain.surfaceKey(0, 84, 0)), "held world-wide");
    }

    @Test
    void noWay_parksTheGoal_itemsKeptAndOwed_andMovingWakesIt() {
        OrderLineSim s = lineDue();
        s.mainAt = null;                         // no main chest: no way
        s.failRun("busy: the test ends the running put");        // a passing end: no park of its own
        s.run(30, 5000);                         // past the brain's cooling after a failed run
        Goals.Goal dg = s.depositGoal();
        assertNotNull(dg);
        assertNotNull(dg.parked, "parked: " + s.said + " / " + s.brain.whyLines(4));
        assertTrue(dg.parked.contains("no main chest set"), dg.parked);
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), s.deposits.owed());
        assertEquals(Map.of("cobblestone", 32, "oak_log", 16), Goals.kept(s.bb, null, null), "the items stay kept");
        assertNull(Goals.stillChanges(Goals.of(s.bb), dg, false, false, false), "no look-around for a deposit (rule 22)");
        s.mainAt = "-53 107 -8";
        s.feet[0] += 40;                          // moved (failures.moved and the band)
        s.run(2, 5000);
        assertNull(dg.parked, "moving woke it: " + s.said);
    }

    @Test
    void aParkedDeposit_holdsNoOrdersLine_andIsNotWeighed() {
        OrderLineSim s = lineDue();
        s.mainAt = null;
        s.failRun("busy: the test ends the running put");        // a passing end: no park of its own
        s.run(30, 5000);                         // past the brain's cooling after a failed run
        Goals.Goal dg = s.depositGoal();
        assertNotNull(dg.parked, s.said.toString());
        Goals.Goal stick = s.order("stick", 4, "b2", true);
        stick.parked = "stopped at step 1 (craft stick 4): test";
        stick.park = new Goals.Park(Goals.ParkKind.PLACE, s.feet, 0, java.util.Set.of(), java.util.Set.of());
        stick.looked = true;
        String c = Goals.stillChanges(Goals.of(s.bb), stick, false, false, false);
        assertTrue(c == null || !c.contains(Goals.DEPOSIT_KEY), "the parked deposit holds no order's line: " + c);
        // and the running (open) deposit goal is no "open order" either: its run counts by busy
        dg.parked = null;
        String c2 = Goals.stillChanges(Goals.of(s.bb), stick, false, false, false);
        assertTrue(c2 == null || !c2.contains("order " + Goals.DEPOSIT_KEY), c2);
        assertTrue(s.said.stream().noneMatch(l -> l.startsWith(Goals.DEPOSIT_KEY + " stopped")), "never weighed as a stopped order: " + s.said);
    }

    @Test
    void aDeathWhileTheGoalRuns_reopensTheOrder_owedShrinks() {
        OrderLineSim s = lineDue();
        assertEquals(1, goals(s));
        s.bag.remove("cobblestone");             // the bag lost the cobblestone (a death)
        s.run(2, 5000);
        assertTrue(s.ordersLeft().stream().anyMatch(g -> g.r.key().equals("cobblestone")), "the order is open again: " + s.said);
        assertFalse(s.deposits.owed().containsKey("minecraft:cobblestone"), "owed shrinks: " + s.deposits.owed());
    }

    @Test
    void aSecondLineDueDuringThePut_staysOwed() {
        OrderLineSim s = lineDue();
        s.deposits.putStarted();
        Goals.Goal stick = s.order("stick", 4, "b2", true);
        s.bag.put("stick", 4);
        s.run(2, 5000);
        s.deposits.putEnded(true);
        assertEquals(Map.of("minecraft:stick", 4), s.deposits.owed(), "the second line stays owed: " + s.said);
        s.run(1, 5000);
        assertEquals(1, goals(s), "its goal: " + s.said);
        assertNotNull(stick);
    }

    /**
     * w005 (worlds run 3): an order learned the surface hold under ground; the deposit's first plan has no walk up -
     * the hold is world-wide.
     */
    @Test
    void w005_anOrderLearnsTheSurfaceHold_theDepositsFirstPlanHasNoWalkUp() {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        s.bag.put("dirt", 64);
        s.under(20);
        s.path = (x, y, z, d) -> x == -53 ? PathPrice.Answer.unreachable("no path from under ground") : priced(10);
        s.order("cobblestone", 32, "b1", true);
        s.tick();
        WorldFacts.of(s.bb).learned(NeedChain.surfaceKey(0, 84, 0), "the walk up ended short", "cobblestone", s.bb.in);
        s.bag.put("cobblestone", 32);
        s.run(2, 5000);
        Goals.Goal dg = s.depositGoal();
        assertNotNull(dg, s.said.toString());
        var c = Goals.chain(s.bb, dg);
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().noneMatch(st -> st instanceof Step.ToSurface), "no walk up: " + c.why());
        assertInstanceOf(Step.Climb.class, c.steps().get(0), c.why());
    }

    /** w053-200-1: after a leg's progress the goal is priced again from the new feet (on the surface now: the chest walk). */
    @Test
    void w053_rePricedFromTheNewFeet() {
        OrderLineSim s = lineDue();
        s.under(20);
        s.path = (x, y, z, d) -> x == -53 ? PathPrice.Answer.unreachable("no path from under ground") : priced(10);
        Goals.Goal dg = s.depositGoal();
        assertInstanceOf(Step.ToSurface.class, Goals.chain(s.bb, dg).steps().get(0));
        s.feet[1] += 20;                          // the walk up ended at the surface
        s.surface = new NeedChain.Surface(0, s.feet[0], s.feet[1], s.feet[2], 0);
        s.underGround = false;
        s.path = (x, y, z, d) -> priced(15);
        var c = Goals.chain(s.bb, dg);
        assertTrue(c.ok(), c.why());
        assertInstanceOf(Step.ToStation.class, c.steps().get(0), c.why());
        assertInstanceOf(Step.Deposit.class, c.steps().get(c.steps().size() - 1), c.why());
    }

    /**
     * Stage 4 review 3 (point 2): the deposit goal's kept is the reverse of an order's - its run may use up what is owed only
     * by putting it in the chest. The runner holds every kept amount from the run's crafts (Goals.keptForRun with consume,
     * AaRuntime.keptFromUse, Crafting.planBag), so the chain may price no craft, place or lend of the owed items: under
     * ground with the walk up held, no pickaxe, no source known and nothing but the owed cobblestone and logs (and 2 sticks), there is no
     * way up - said - never a pickaxe from the owed logs or a pillar of the owed cobblestone. Its put still counts them.
     */
    @Test
    void theDepositChain_neverUsesWhatIsOwed_asTheRunnerHoldsItBack() {
        OrderLineSim s = lineDue();
        s.bag.remove("dirt");
        s.bag.remove("wooden_pickaxe");
        s.bag.put("stick", 2);
        s.seen.clear();                          // no tree or stone known: the owed items are the only material near
        s.under(20);
        s.path = (x, y, z, d) -> x == -53 ? PathPrice.Answer.unreachable("no path from under ground") : priced(10);
        s.tick();
        WorldFacts.of(s.bb).learned(NeedChain.surfaceKey(0, 84, 0), "the walk up ended short", "cobblestone", s.bb.in);
        Goals.Goal dg = s.depositGoal();
        assertNotNull(dg, s.said.toString());
        Map<String, Integer> held = Goals.keptForRun(s.bb, dg.id(), true, true);
        assertEquals(32, held.get("cobblestone"), "the runner holds the owed amounts from use: " + held);
        assertEquals(16, held.get("oak_log"), held.toString());
        assertTrue(Goals.keptForRun(s.bb, dg.id(), true, false).isEmpty(), "the put's own free bag counts them");
        var c = Goals.chain(s.bb, dg);
        for (Step st : c.steps()) {
            assertFalse(st instanceof Step.Craft, "no craft from the owed amounts: " + c.why());
            if (st instanceof Step.Climb k) assertNull(k.lendItem(), "no lend of the owed amounts: " + c.why());
        }
        assertFalse(c.ok(), "no way up but the owed items: " + c.why());
        assertTrue(c.missing().startsWith("no way to the main chest"), c.missing());
    }

    /**
     * Stage 4 review 3 (point 2, the worker path since job-own-aside-worker): the deposit goal's chain is priced on the
     * tick only (Goals.chain: no ask reaches the planner worker for it), and an order's ask on the worker - its trip's legs
     * or its own chain (SyncPlanner: the snapshot, PlannerWorker.resolve, the port's live check) - starts from a bag with
     * the owed amounts off, as the tick's: no planner drop.
     */
    @Test
    void theWorkerPath_theDepositIsPricedOnTheTick_andAnOrdersWorkerBagHoldsNoOwedAmount() {
        OrderLineSim s = lineDue();
        List<String> keys = new java.util.ArrayList<>();
        List<Map<String, Integer>> bags = new java.util.ArrayList<>();
        s.bb.fact(io.github.mojolowjo.entropybot.aa.brain.BatchWay.PLANNER_FACT, new io.github.mojolowjo.entropybot.aa.brain.BatchWay.Planner() {
            @Override public NeedChain.Legs legs(String key, Playbook pb, java.util.Set<String> keptFuel, NeedChain.World w) {
                keys.add(key);
                bags.add(NeedChain.liveBag(w));
                return s.sync.legs(key, pb, keptFuel, w);
            }

            @Override public boolean ready(String key) { return s.sync.ready(key); }

            @Override public NeedChain.Chain own(String key, io.github.mojolowjo.entropybot.aa.brain.BatchWay.OwnAsk ask, NeedChain.World w) {
                keys.add(key);
                bags.add(ask.bagLess(NeedChain.liveBag(w)));
                return s.sync.own(key, ask, w);
            }
        });
        Goals.Goal dg = s.depositGoal();
        var c = Goals.chain(s.bb, dg);
        assertTrue(c.ok(), c.why());
        assertTrue(keys.isEmpty(), "the deposit goal never asks the worker: " + keys);
        Goals.Goal stick = s.order("stick", 4, "b2", false);
        Goals.chain(s.bb, stick);
        assertFalse(keys.isEmpty(), "the order asks the worker");
        for (Map<String, Integer> b : bags) {
            assertEquals(0, b.getOrDefault("minecraft:oak_log", 0), "the owed logs are off the worker's bag: " + b);
            assertEquals(0, b.getOrDefault("minecraft:cobblestone", 0), "the owed cobblestone too: " + b);
        }
        assertTrue(s.sync.drops.isEmpty(), "no planner drop: " + s.sync.drops);
    }

    /** The deposit goal is skipped by every LineDeposits check (deposit 0, line null): nothing of it is kept or weighed. */
    @Test
    void theGoalIsNoDOrder() {
        OrderLineSim s = lineDue();
        Goals.Goal dg = s.depositGoal();
        assertEquals(0, dg.deposit);
        assertNull(dg.line);
        assertTrue(s.deposits.keptPer(s.bb.in.bag, List.of(dg), null, null).stream().noneMatch(k -> k.order() == dg));
        assertNull(s.deposits.dropped(dg, s.bb.in.bag, s.mainAt));
        assertFalse(s.deposits.owedOrders().contains(dg));
    }
}
