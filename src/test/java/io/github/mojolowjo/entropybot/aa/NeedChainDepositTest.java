package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.MainChest;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * deposit-goal plan, stage 2: NeedChain.deposit - the main chest walk and the put, the walk up and the climb as options
 * for the chooser. Hand numbers: the climb's row is depth x rungOptions.climbBlock 3 + climbFix 10 walk-blocks (as
 * Z77ClimbDigUpTest); the walks are the fake path's seconds.
 */
class NeedChainDepositTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int[] FEET = {0, 40, 0};
    static final MainChest.Answer CHEST = new MainChest.Known(100, 70, 0);
    static final Map<String, Integer> OWED = Map.of("minecraft:raw_iron", 5);

    /** Under ground: 30 blocks over the feet, the surface cell over them at 0 70 0. */
    static NeedChain.Surface under() { return new NeedChain.Surface(30, 0, 70, 0, 30); }

    /** On the surface: the feet are the ring's top. */
    static NeedChain.Surface top() { return new NeedChain.Surface(0, 0, 40, 0, 0); }

    static PathPrice.Answer priced(double s) { return PathPrice.Answer.priced(new PathPrice.Walk(s)); }

    static NeedChain.World world(Map<String, Integer> bag, NeedChain.Surface s, PathPrice.Answer chest, PathPrice.Answer up, List<String> held) {
        ScenariosTest.world("wooden", "stone");
        return ScenariosTest.w(bag, true, false, Map.of("dirt", List.of(new NeedChain.Spot(20, 40, 0, 20, "minecraft:dirt", 64, null, 0, false, 0, null))))
                .withMobs(x -> List.of(), Z77ExploreTest.book()).withStrip(word -> null)
                .withWhere(new NeedChain.WhereFacts(Z77ExploreTest::where, () -> s, () -> FEET))
                .withPath((x, y, z, d) -> x == 100 ? chest : up)
                .withHeld(held);
    }

    static double climbS(int depth) { return (depth * 3 + 10) / Unit.WALK_BPS; }

    @Test
    void onTheSurface_aPricedWalkGivesToStationAndDeposit() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of(), top(), priced(20), priced(5), List.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(2, c.steps().size(), c.steps().toString());
        assertEquals(new Step.ToStation("container", "main chest", 100, 70, 0), c.steps().get(0));
        Step.Deposit d = assertInstanceOf(Step.Deposit.class, c.steps().get(1));
        assertArrayEquals(new int[]{100, 70, 0}, d.chest());
        assertEquals(OWED, d.only());
        assertEquals(20, c.seconds(), 0.1, c.why());
    }

    @Test
    void underGround_theWalkUpBeatsTheClimb() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of("minecraft:dirt", 64), under(), priced(200), priced(10), List.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(List.of(new Step.ToSurface(0, 70, 0)), c.steps(), "the way out is one leg: " + c.why());
        assertEquals(10, c.seconds(), 0.1, c.why());
    }

    @Test
    void underGround_theWalkUpHeldGivesTheClimb() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of("minecraft:dirt", 64), under(), PathPrice.Answer.unreachable("no path"), priced(10),
                List.of(NeedChain.surfaceKey(0, 70, 0) + ": the walk up failed")));
        assertTrue(c.ok(), c.why());
        assertEquals(1, c.steps().size(), c.steps().toString());
        assertInstanceOf(Step.Climb.class, c.steps().get(0));
        assertEquals(climbS(30), c.seconds(), 0.1, c.why());
    }

    @Test
    void underGround_walkUpAndClimbBothHeld_oneTextNamesBoth() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of("minecraft:dirt", 64), under(), PathPrice.Answer.unreachable("no path"), priced(10),
                List.of(NeedChain.surfaceKey(0, 70, 0) + ": the walk up failed", NeedChain.CLIMB_KEY + ": the climb ended short")));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().contains("the walk up failed"), c.missing());
        assertTrue(c.missing().contains("the climb ended short"), c.missing());
        assertTrue(c.missing().startsWith("no way to the main chest"), c.missing());
    }

    @Test
    void aWalkStillComputingIsTheWait() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of(), top(), PathPrice.Answer.computing("queued"), priced(5), List.of()));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().startsWith(PathPrice.NOT_KNOWN), c.missing());
        assertTrue(c.steps().isEmpty(), "no step that moves: " + c.steps());
    }

    @Test
    void theChestAnswers() {
        var w = world(Map.of(), top(), priced(20), priced(5), List.of());
        Chain none = NeedChain.deposit(OWED, new MainChest.None("none set (aa deposit x y z)"), w);
        assertFalse(none.ok());
        assertTrue(none.missing().startsWith("no main chest set (none set"), none.missing());
        Chain nl = NeedChain.deposit(OWED, new MainChest.NotLoaded(100, 70, 0), w);
        assertTrue(nl.ok(), nl.why());
        assertEquals(new Step.ToStation("container", "main chest", 100, 70, 0), nl.steps().get(0));
        assertTrue(NeedChain.deposit(OWED, CHEST, w).ok());
    }

    @Test
    void underGround_anEstimateToTheChestIsNotOffered_said() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of("minecraft:dirt", 64), under(), PathPrice.Answer.estimated(new PathPrice.Walk(3), "guess"), priced(10), List.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(List.of(new Step.ToSurface(0, 70, 0)), c.steps());
        assertTrue(c.why().contains(NeedChain.CHEST_NOT_PRICED), c.why());
        // the same estimate on the surface is ranked
        Chain top = NeedChain.deposit(OWED, CHEST, world(Map.of(), top(), PathPrice.Answer.estimated(new PathPrice.Walk(3), "guess"), priced(10), List.of()));
        assertTrue(top.ok(), top.why());
        assertInstanceOf(Step.Deposit.class, top.steps().get(top.steps().size() - 1));
    }

    @Test
    void theSurfaceNotKnown_said() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of(), null, PathPrice.Answer.estimated(new PathPrice.Walk(3), "guess"), priced(10), List.of()));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().contains("the surface over the bot is not known"), c.missing());
        assertTrue(c.missing().contains(NeedChain.CHEST_NOT_PRICED), c.missing());
    }

    @Test
    void theChestsStationKeyHeld_noWalk_said() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of(), top(), priced(20), priced(5), List.of(NeedChain.stationKey(100, 70, 0) + ": no chest block there")));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().contains("no chest block there"), c.missing());
        assertTrue(c.steps().isEmpty());
        assertEquals(NeedChain.stationKey(100, 70, 0), NeedChain.sourceKey(new Step.Deposit(null, OWED, new int[]{100, 70, 0})), "the put's faults hold the chest cell");
        assertNull(NeedChain.sourceKey(new Step.Deposit()), "a put with no chest cell: no key, as before");
    }

    static final List<String> UP_HELD = List.of(NeedChain.surfaceKey(0, 70, 0) + ": the walk up failed");

    @Test
    void noPickaxeNoSpareBlocks_makeAPickaxeFirstIsPriced() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2), new NeedChain.Surface(10, 0, 50, 0, 10), PathPrice.Answer.unreachable("no path"), priced(10), UP_HELD));
        assertTrue(c.ok(), c.why());
        assertTrue(c.why().contains("then climb up 10"), c.why());
        assertInstanceOf(Step.Climb.class, c.steps().get(c.steps().size() - 1));
    }

    @Test
    void neitherPossible_theFailHoldsEveryReason() {
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of(), under(), PathPrice.Answer.unreachable("no path"), priced(10), UP_HELD));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().contains("the walk up failed"), c.missing());
        assertTrue(c.missing().contains("no climb either"), c.missing());
        assertTrue(c.missing().contains("walk to the main chest"), c.missing());
    }

    @Test
    void theOwedAmountsAreOffThePlannersBag_theWorldIsUsedAsGiven() {
        // the planner's world (Goals.keptOff): the owed 30 dirt are already off the bag; the 30 left are spare. Owing dirt or
        // raw iron gives the same climb: the deposit neither reserves nor lends nor crafts the owed amounts a second time
        var w = world(Map.of("minecraft:dirt", 30), under(), PathPrice.Answer.unreachable("no path"), priced(10), UP_HELD);
        Chain dirt = NeedChain.deposit(Map.of("minecraft:dirt", 30), CHEST, w);
        Chain iron = NeedChain.deposit(OWED, CHEST, w);
        assertTrue(dirt.ok(), dirt.why());
        assertTrue(dirt.why().contains("with 30 spare blocks"), dirt.why());
        assertFalse(dirt.why().contains("lent"), dirt.why());
        assertEquals(iron.seconds(), dirt.seconds(), 0.1, dirt.why() + " / " + iron.why());
        assertEquals(iron.steps(), dirt.steps());
    }

    @Test
    void aFinishedOrdersReserve_isCountedOnce() {
        // review fault 2: 42 dirt in the planner's bag, a finished order's 10 reserved; owing 32 dirt adds nothing on top
        var w = world(Map.of("minecraft:dirt", 42), under(), PathPrice.Answer.unreachable("no path"), priced(10), UP_HELD)
                .withReserves(List.of(io.github.mojolowjo.entropybot.aa.moves.Reservations.order("dirt (finished)", List.of("minecraft:dirt"), 10)));
        Chain c = NeedChain.deposit(Map.of("minecraft:dirt", 32), CHEST, w);
        assertTrue(c.ok(), c.why());
        assertTrue(c.why().contains("with 30 spare blocks of 42"), c.why());
    }

    @Test
    void aHeldChest_hasNoWay_itsReasonSaid() {
        // review 4 (point 1; the bonus field that was always 0 is gone): a full or gone chest is no way, with its reason
        Chain c = NeedChain.deposit(OWED, new MainChest.Held(100, 70, 0, "the main chest at 100 70 0 is full (3 left out of the last put)"), world(Map.of(), top(), priced(20), priced(5), List.of()));
        assertFalse(c.ok(), c.why());
        assertTrue(c.why().contains("is full"), c.why());
        assertTrue(c.steps().isEmpty(), c.why());
    }

    @Test
    void surfaceNotKnown_anEstimateIsNotOffered_saidWithThatReason() {
        PathPrice.Answer est = PathPrice.Answer.estimated(new PathPrice.Walk(20), "a heuristic");
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of(), null, est, priced(5), List.of()));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().contains("not offered while the surface is not known"), c.missing());
        assertFalse(c.missing().contains("from under ground"), c.missing());
    }
    @Test
    void onTheSurface_chunksNotLoadedIsRankedOnBaritonesEstimate() {
        ScenariosTest.world("wooden", "stone");
        PathPrice pp = new PathPrice() {
            @Override public Answer price(int x, int y, int z, double d) { return x == 100 ? Answer.chunks("not loaded") : priced(5); }
            @Override public double estimate(int x, int y, int z) { return x == 100 ? 33 : Double.NaN; }
        };
        var w = world(Map.of(), top(), priced(1), priced(5), List.of()).withPath(pp);
        Chain c = NeedChain.deposit(OWED, CHEST, w);
        assertTrue(c.ok(), c.why());
        assertEquals(33, c.seconds(), 0.1, c.why());
        assertTrue(c.why().contains("Baritone's estimate"), c.why());
    }

    @Test
    void underGround_aChestWalkStillComputing_nextToAPricedWalkUp() {
        // choose(): the priced walk up wins at once; the chest walk's pending answer is not the wait here (stage 3 settles it)
        Chain c = NeedChain.deposit(OWED, CHEST, world(Map.of("minecraft:dirt", 64), under(), PathPrice.Answer.computing("queued"), priced(10), List.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(List.of(new Step.ToSurface(0, 70, 0)), c.steps(), c.why());
    }

    @Test
    void aNearbyCellsHeldKey_doesNotHoldTheChest() {
        Chain c = NeedChain.deposit(OWED, new MainChest.Known(100, 70, 3), world(Map.of(), top(), priced(20), priced(5), List.of(NeedChain.stationKey(100, 70, 30) + ": no chest block there")));
        assertTrue(c.ok(), c.why());
    }
}
