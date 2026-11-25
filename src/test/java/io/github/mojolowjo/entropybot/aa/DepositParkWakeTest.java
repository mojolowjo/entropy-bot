package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.MainChest;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.StationHolds;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-deposit-park-wake (tester pilot on c606aca8, w005 09:51:26-09:55:44): the main chest at 30 74 -42 stood on a log
 * column 9 above the ground; the walk to it ended short below ("couldn't get there (9 blocks away, stopped at 30 65 -42)"),
 * the hold left the deposit goal no way, every order parked and the bot stood 253 s with the whole line in the bag.
 * (a) the short end is a fact about the walk's last blocks: its finish is priced once from there (the walk again with
 * blocks to place, or N more blocks got first); a second short end from there is no finish. (b) the deposit goal parked on
 * that held walk with every order stopped takes one move away (review 1: Baritone's run-away goal from where the walk failed,
 * ended by the distance the hold lifts at - no untargeted explore, rule 22), then is priced from where it ends (a walk from
 * another side); after it, the wake words say no wake by itself. Hand numbers: the chest 9 over
 * the feet, as w005; the walks are the fake path's seconds.
 */
class DepositParkWakeTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        Only.set("off");
        StationHolds.INSTANCE.reset();
    }

    static final int[] FEET = NeedChainDepositTest.FEET;                    // 0 40 0
    static final MainChest.Answer CHEST = new MainChest.Known(100, 49, 0);  // w005: 9 over the feet
    static final Map<String, Integer> OWED = Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16, "minecraft:torch", 4);
    static final String SHORT_REASON = "main chest at 100 49 0 out for this order: the walk to it ended short: couldn't get there (9 blocks away, stopped at 100 40 0)";

    static PathPrice.Answer priced(double s) { return PathPrice.Answer.priced(new PathPrice.Walk(s)); }

    static List<String> held(boolean again) {
        return List.of(NeedChain.stationKey(100, 49, 0) + ": held for deposit: " + SHORT_REASON + "; " + (again ? StationHolds.AGAIN : StationHolds.SHORT));
    }

    // ---- the one store: a short walk, then a short walk again from there ----

    @Test
    void theStore_aShortWalkThenAgainFromThere_saysSoInItsWords() {
        int[] from = {30, 65, -42};
        StationHolds.INSTANCE.walkFailed("deposit", 30, 74, -42, SHORT_REASON, from, true);
        String first = StationHolds.INSTANCE.held(30, 74, -42, from, null);
        assertTrue(first.endsWith(StationHolds.SHORT), first);
        StationHolds.INSTANCE.walkFailed("deposit", 30, 74, -42, SHORT_REASON, new int[]{28, 65, -44}, true);
        String again = StationHolds.INSTANCE.held(30, 74, -42, from, null);
        assertTrue(again.endsWith(StationHolds.AGAIN), "within failures.moved of the first stop: again - " + again);
        // a short end far from there is a new question: SHORT again
        int far = (int) Math.ceil(Failures.movedV() + 20);
        StationHolds.INSTANCE.walkFailed("deposit", 30, 74, -42, SHORT_REASON, new int[]{30 + far, 65, -42}, true);
        assertTrue(StationHolds.INSTANCE.held(30, 74, -42, new int[]{30 + far, 65, -42}, null).endsWith(StationHolds.SHORT));
        // review 1: whether a second short end is AGAIN is the two stops alone, not the lift state (which only a price moves):
        // away and back with no price between, a short end at the first stop is AGAIN
        StationHolds.INSTANCE.reset();
        StationHolds.INSTANCE.walkFailed("deposit", 30, 74, -42, SHORT_REASON, from, true);
        assertNull(StationHolds.INSTANCE.held(30, 74, -42, new int[]{30 + far, 65, -42}, null), "lifted far away");
        StationHolds.INSTANCE.walkFailed("deposit", 30, 74, -42, SHORT_REASON, from.clone(), true);
        assertTrue(StationHolds.INSTANCE.held(30, 74, -42, from, null).endsWith(StationHolds.AGAIN), "the same stop: again");
        assertArrayEquals(from, StationHolds.INSTANCE.walkFrom("deposit"), "where the walk failed, for the move away");
        assertNull(StationHolds.INSTANCE.walkFrom("other"));
        // a put's fault at the chest (not the walk) carries neither
        StationHolds.INSTANCE.walkFailed("deposit", 1, 2, 3, "no chest block there", from);
        String put = StationHolds.INSTANCE.held(1, 2, 3, from, null);
        assertFalse(put.contains(StationHolds.SHORT) || put.contains(StationHolds.AGAIN), put);
    }

    // ---- (a) the finish of the short walk, priced ----

    @Test
    void w005_spareShort_moreBlocksFirstThenTheWalkAgain() {
        // the planner's bag in w005: dirt 3, oak_planks 1 and 2 spare logs; the owed amounts are off it. Here 6 dirt: 3 short
        Chain c = NeedChain.deposit(OWED, CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 6), NeedChainDepositTest.top(), priced(150), priced(5), held(false)));
        assertTrue(c.ok(), c.why());
        assertTrue(c.why().contains("3 more"), c.why());
        assertEquals(new Step.ToStation("container", "main chest", 100, 49, 0), c.steps().get(c.steps().size() - 2), c.steps().toString());
        Step.Deposit d = assertInstanceOf(Step.Deposit.class, c.steps().get(c.steps().size() - 1));
        assertEquals(OWED, d.only(), "the put is the owed amounts whole: the blocks got first are spare");
        assertTrue(c.steps().size() > 2, "the blocks got first: " + c.steps());
    }

    @Test
    void spareEnough_theWalkAgainFromHere() {
        Chain c = NeedChain.deposit(OWED, CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 20), NeedChainDepositTest.top(), priced(150), priced(5), held(false)));
        assertTrue(c.ok(), c.why());
        assertEquals(2, c.steps().size(), c.steps().toString());
        assertEquals(new Step.ToStation("container", "main chest", 100, 49, 0), c.steps().get(0));
        assertArrayEquals(new int[]{100, 49, 0}, assertInstanceOf(Step.Deposit.class, c.steps().get(1)).chest());
        assertTrue(c.why().contains("again from here"), c.why());
        assertEquals(150, c.seconds(), 0.1, c.why());
    }

    @Test
    void theFinishIsWalkedOnAPricedPathOnly() {
        Chain c = NeedChain.deposit(OWED, CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 20), NeedChainDepositTest.top(),
                PathPrice.Answer.estimated(new PathPrice.Walk(3), "a heuristic"), priced(5), held(false)));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().contains(NeedChain.FINISH_PRICED_ONLY), c.missing());
    }

    @Test
    void shortAgainFromHere_noFinish_everyReasonSaid() {
        Chain c = NeedChain.deposit(OWED, CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 20), NeedChainDepositTest.top(), priced(150), priced(5), held(true)));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().startsWith("no way to the main chest"), c.missing());
        assertTrue(c.missing().contains(StationHolds.AGAIN), c.missing());
        assertTrue(c.steps().isEmpty(), c.steps().toString());
    }

    @Test
    void underGround_theFinishCompetesWithTheWalkUp() {
        // w005 09:51:28.958: the stop at 30 65 -42 was under the log column (11 under ground); the walk up of 0.5 s wins,
        // then the finish is priced from the surface cell (the hold still holds there)
        Chain c = NeedChain.deposit(OWED, CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 20), NeedChainDepositTest.under(), priced(150), priced(0.5), held(false)));
        assertTrue(c.ok(), c.why());
        assertEquals(List.of(new Step.ToSurface(0, 70, 0)), c.steps(), c.why());
    }

    // ---- (a) and (b) on the order line: OrderEnds and the real brain ----

    /** The line's deposit due with the main chest 9 over the feet (w005), every walk priced. */
    static OrderLineSim w005Line(int dirt) {
        OrderLineSim s = OrderLineDepositGoalTest.lineDue();
        s.mainAt = "0 73 0";                     // 9 over the feet at 0 64 0
        s.bag.put("dirt", dirt);
        s.path = (x, y, z, d) -> priced(15);
        return s;
    }

    static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine walkEndedShort(OrderLineSim s, Goals.Goal dg, Step walk) {
        FactChange.stationWalk("main chest", 0, 73, 0, "the walk to it ended short: couldn't get there (9 blocks away, stopped at " + s.feet[0] + " " + s.feet[1] + " " + s.feet[2] + ")");
        return Goals.replanner(s.bb, dg).replan(walk, FactChange.take(), "the walk to it ended short");
    }

    @Test
    void w005_onTheLine_theShortWalksFinishIsTheNextWay_thenNoneFromThere() {
        OrderLineSim s = w005Line(3);
        Goals.Goal dg = s.depositGoal();
        assertNotNull(dg, s.said.toString());
        var first = Goals.chain(s.bb, dg);
        assertTrue(first.ok(), first.why());
        Step walk = first.steps().get(0);
        assertInstanceOf(Step.ToStation.class, walk, first.why());
        var rt = walkEndedShort(s, dg, walk);
        assertNotNull(rt.steps(), "the finish is a way: " + rt.why());
        assertInstanceOf(Step.Deposit.class, rt.steps().get(rt.steps().size() - 1), rt.steps().toString());
        assertTrue(rt.steps().stream().anyMatch(st -> st instanceof Step.ToStation), rt.steps().toString());
        // the finish ended short again from the same place: no finish from here
        var again = walkEndedShort(s, dg, rt.steps().stream().filter(st -> st instanceof Step.ToStation).findFirst().orElseThrow());
        assertNull(again.steps(), "no way from here: " + again.why());
        assertTrue(again.why().contains(StationHolds.AGAIN), again.why());
    }

    @Test
    void w005_onTheLine_everyOrderParked_theDepositLooksAround_thenIsPricedFromThere() {
        OrderLineSim s = w005Line(30);
        Goals.Goal dg = s.depositGoal();
        var first = Goals.chain(s.bb, dg);
        Step walk = first.steps().get(0);
        var rt = walkEndedShort(s, dg, walk);
        walkEndedShort(s, dg, rt.steps().stream().filter(st -> st instanceof Step.ToStation).findFirst().orElseThrow());
        s.failRun("busy: the test ends the running walk");
        s.run(30, 5000);                         // past the brain's cooling: the deposit goal parks on its held walk
        assertNotNull(dg.parked, "parked: " + s.said + " / " + s.brain.whyLines(4));
        assertTrue(dg.parked.contains(NeedChain.CHEST_WALK_HELD), dg.parked);
        assertNotNull(Goals.stillChanges(Goals.of(s.bb), dg, false, false, false), "a wake that can come with every order parked");
        // every order parked on the surface: after goals.lookAroundAfterS the deposit takes its one look-around leg
        int before = s.starts();
        s.run((int) Math.ceil(Tuning.get("goals.lookAroundAfterS") / 5) + 4, 5000);
        assertTrue(s.port.told.stream().anyMatch(l -> l.startsWith("the line's deposit moves away")), s.port.told.toString());
        assertTrue(s.starts() > before, "a run started");
        var spec = s.port.started.get(s.port.started.size() - 1);
        assertEquals(1, spec.steps().size(), spec.steps().toString());
        // review 1 (rule 22: no explore without a target): Baritone's run-away goal from where the walk failed, as far as
        // the hold lifts - the leg ends on that fact, never on a minute timer
        assertTrue(spec.steps().stream().noneMatch(st -> st instanceof Step.Explore), "no untargeted explore: " + spec.steps());
        Step.MoveAway away = assertInstanceOf(Step.MoveAway.class, spec.steps().get(0));
        assertArrayEquals(s.feet, new int[]{away.x(), away.y(), away.z()}, "from where the walk ended short");
        assertTrue(away.dist() > Failures.movedV() + 8, "past failures.moved + WorldFacts.MARGIN: " + away);
        // the leg ends where Baritone's goal is met: the hold lifted by distance, the deposit is priced from there
        s.feet[0] += away.dist();
        s.endRun("ok: arrived near " + s.feet[0] + " " + s.feet[1] + " " + s.feet[2]);
        s.run(2, 5000);
        var from = Goals.chain(s.bb, dg);
        assertTrue(from.ok(), from.why());
        assertInstanceOf(Step.ToStation.class, from.steps().get(0), from.why());
        assertFalse(from.why().contains("is held"), from.why());
    }

    @Test
    void afterItsLookAround_theDepositSaysNoWakeByItself() {
        Goals.Goal g = Goals.draft(io.github.mojolowjo.entropybot.aa.brain.Thing.resolve("cobblestone", OrderLineSim.CATALOG), 1, 0, 0);
        g.depositGoal = true;
        g.parked = "no way to get it: deposit: no way to the main chest at 0 73 0 (" + NeedChain.CHEST_WALK_HELD + "0 73 0 is held (x))";
        g.park = new Goals.Park(Goals.ParkKind.PLACE, new int[]{0, 64, 0}, 0, java.util.Set.of(), java.util.Set.of());
        assertNotNull(Goals.stillChanges(List.of(g), g, false, false, false), "its look-around is due");
        g.looked = true;
        assertNull(Goals.stillChanges(List.of(g), g, false, false, false), "taken: nothing moves it by itself");
        assertTrue(Goals.wake(g).startsWith("it looked around once already"), Goals.wake(g));
        // a deposit parked on no main chest: no look-around (the owner's word, not a place)
        g.looked = false;
        g.parked = "no way to get it: deposit: no main chest set (none)";
        assertNull(Goals.stillChanges(List.of(g), g, false, false, false));
    }
}
