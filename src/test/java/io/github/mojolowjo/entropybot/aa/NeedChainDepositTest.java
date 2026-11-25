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
    static final MainChest.Answer CHEST = new MainChest.Known(100, 70, 0, 0);
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
    void anOwedKeptBlockIsLentOnlyByAPricedLendWay() {
        Map<String, Integer> owedDirt = Map.of("minecraft:dirt", 30);
        Chain c = NeedChain.deposit(owedDirt, CHEST, world(Map.of("minecraft:dirt", 30), under(), PathPrice.Answer.unreachable("no path"), priced(10), UP_HELD));
        assertTrue(c.ok(), c.why());
        assertFalse(c.why().contains("climb with spare blocks") || c.why().contains("30 spare blocks of"), "the owed dirt is never spare: " + c.why());
        assertTrue(c.why().contains("kept dirt lent"), c.why());
        List<String> kinds = new ArrayList<>();
        for (Step s : c.steps()) kinds.add(s.getClass().getSimpleName());
        assertTrue(kinds.contains("Climb"), kinds.toString());
        // the same dirt owed to nothing: spare blocks
        Chain free = NeedChain.deposit(OWED, CHEST, world(Map.of("minecraft:dirt", 30), under(), PathPrice.Answer.unreachable("no path"), priced(10), UP_HELD));
        assertTrue(free.ok(), free.why());
        assertTrue(free.why().contains("with 30 spare blocks"), free.why());
    }
}
