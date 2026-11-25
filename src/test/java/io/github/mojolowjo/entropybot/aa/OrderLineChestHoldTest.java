package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.MainChest;
import io.github.mojolowjo.entropybot.aa.brain.MainChestHold;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * deposit-goal review 4 (point 1): a full or gone main chest is a fact that holds while it is true (MainChestHold), not a
 * place park that every move wakes. On the order line with no game ({@link OrderLineSim}). Hand numbers: the main chest
 * is the sim's -53 107 -8; 40 blocks is a move past the wake distance.
 */
class OrderLineChestHoldTest {

    @AfterEach
    void clear() {
        Only.set("off");
        FactChange.clear();
        TestData.clear();
        Tuning.reset();
    }

    static final String FULL = "stopped at step 1 (deposit): deposit: 10 of 48 items went in at -53 107 -8: chest at -53 107 -8 full: 38 items left";
    static final String GONE = "stopped at step 1 (deposit): deposit: chest at -53 107 -8 gone";

    static Goals.Goal refused(OrderLineSim s, String why) {
        s.depositFails();
        s.failRun(why);
        s.run(30, 5000);                         // past the brain's cooling after a failed run
        Goals.Goal dg = s.depositGoal();
        assertNotNull(dg, s.said.toString());
        assertNotNull(dg.parked, "parked: " + s.said);
        assertTrue(dg.chestHeld, dg.parked);
        return dg;
    }

    @Test
    void aFullChest_holds_noMoveWakesIt_saidOnce() {
        OrderLineSim s = OrderLineDepositGoalTest.lineDue();
        Goals.Goal dg = refused(s, FULL);
        assertInstanceOf(MainChest.Held.class, MainChest.of(s.bb));
        assertNull(dg.park, "no place park");
        assertTrue(dg.parked.contains("full"), dg.parked);
        int starts = s.starts();
        s.feet[0] += 40;
        s.run(10, 5000);
        s.feet[2] += 40;
        s.run(10, 5000);
        assertNotNull(dg.parked, "no move wakes it: " + s.said);
        assertEquals(starts, s.starts(), "no walk back to the full chest");
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:oak_log", 16), s.deposits.owed(), "still owed, whole");
    }

    @Test
    void aFullChest_theOwnersWord_runsItAgain() {
        OrderLineSim s = OrderLineDepositGoalTest.lineDue();
        Goals.Goal dg = refused(s, FULL);
        assertNotNull(s.chestHold.lift("the owner set the main chest"));
        s.run(1, 5000);
        assertNull(dg.parked, s.said.toString());
        assertFalse(MainChest.of(s.bb) instanceof MainChest.Held);
    }

    @Test
    void aFullChest_aNewMainChest_liftsIt() {
        OrderLineSim s = OrderLineDepositGoalTest.lineDue();
        Goals.Goal dg = refused(s, FULL);
        s.mainAt = "-50 107 -8";
        s.run(1, 5000);
        assertNull(dg.parked, s.said.toString());
        assertNull(s.chestHold.hold());
    }

    @Test
    void aFullChest_openedShowingRoom_liftsIt() {
        MainChestHold h = new MainChestHold();
        assertNotNull(h.refused(FULL));
        assertNull(h.refused(FULL), "the same hold is said once");
        assertNull(h.roomSeen(-53, 107, -8, 0), "no room: still held");
        assertNull(h.roomSeen(1, 2, 3, 5), "another chest");
        assertNotNull(h.roomSeen(-53, 107, -8, 5));
        assertNull(h.hold());
    }

    @Test
    void aGoneChest_holds_untilItsBlockIsAContainerAgain() {
        OrderLineSim s = OrderLineDepositGoalTest.lineDue();
        s.chestThere = false;
        Goals.Goal dg = refused(s, GONE);
        assertTrue(dg.parked.contains("gone"), dg.parked);
        s.feet[0] += 40;
        s.run(10, 5000);
        assertNotNull(dg.parked, "no move wakes it");
        s.chestThere = true;                    // the block read again as a container
        s.run(1, 5000);
        assertNull(dg.parked, s.said.toString());
    }

    @Test
    void theReader_aLoadedCellThatIsNoContainer_isNotKnown() {
        assertInstanceOf(MainChest.Held.class, MainChest.ofText("1 2 3", null, c -> false));
        assertEquals(new MainChest.Known(1, 2, 3), MainChest.ofText("1 2 3", null, c -> true));
        assertEquals(new MainChest.NotLoaded(1, 2, 3), MainChest.ofText("1 2 3", c -> false, c -> false), "not loaded: not read");
    }

    @Test
    void theWords_areTheDepositLegs() {
        var f = MainChestHold.refusal("deposit: " + io.github.mojolowjo.entropybot.aa.brain.DepositRule.full(new int[]{1, -2, 3}, 1));
        assertEquals(MainChestHold.Kind.FULL, f.kind());
        assertEquals(-2, f.y());
        assertEquals(MainChestHold.Kind.GONE, MainChestHold.refusal(io.github.mojolowjo.entropybot.aa.brain.DepositRule.gone(new int[]{1, 2, 3})).kind());
        assertNull(MainChestHold.refusal("deposit: the chest at 1 2 3 was not opened"));
    }
}
