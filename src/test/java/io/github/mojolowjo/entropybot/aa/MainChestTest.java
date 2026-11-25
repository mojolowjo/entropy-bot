package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.MainChest;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** deposit-goal plan stage 1: the one main chest reader (three answers, rule 15) and the deposit step with a chest. */
class MainChestTest {

    @org.junit.jupiter.api.AfterEach
    void clear() {
        io.github.mojolowjo.entropybot.aa.brain.Only.set("off");
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        io.github.mojolowjo.entropybot.aa.brain.TestData.clear();
        io.github.mojolowjo.entropybot.aa.brain.Tuning.reset();
    }

    @Test
    void theThreeAnswers() {
        assertInstanceOf(MainChest.None.class, MainChest.ofText(null, null));
        assertNull(MainChest.ofText(null, null).at());
        assertInstanceOf(MainChest.None.class, MainChest.ofText("1 two 3", null));
        assertEquals(new MainChest.NotLoaded(1, 2, 3), MainChest.ofText("1 2 3", c -> false));
        assertEquals("1 2 3", MainChest.ofText("1 2 3", c -> false).at());
        assertEquals(new MainChest.Known(-53, 107, -8), MainChest.ofText("-53 107 -8", null));
        assertEquals("-53 107 -8", MainChest.ofText("-53 107 -8", c -> true).at());
    }

    /**
     * Review 7 (point 1, rule 15): the one container reader's three states, as the main chest reader answers them. The
     * item data not loaded (join, a recipe reload) is NotLoaded with its reason - never gone, never a hold.
     */
    @Test
    void theContainerReader_threeStates_andTheMainChestNeverGoneWhileNotLoaded() {
        io.github.mojolowjo.entropybot.aa.brain.TestData.clear();
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Container.NOT_LOADED, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.containerOf("minecraft:chest"));
        MainChest.Answer early = MainChest.ofBlock(1, 2, 3, "minecraft:chest");
        assertTrue(early instanceof MainChest.NotLoaded n && n.why().contains("item data is not loaded"), String.valueOf(early));
        assertEquals(new MainChest.NotLoaded(1, 2, 3), MainChest.ofBlock(1, 2, 3, null), "the chunk not loaded");
        // the hold store does not record a not-loaded answer as gone
        var hold = new io.github.mojolowjo.entropybot.aa.brain.MainChestHold();
        var applied = hold.apply(early, null);
        assertEquals(early, applied.answer());
        assertNull(applied.line(), "nothing said: nothing is gone");
        assertNull(hold.hold());
        // review 8: said in the log once per change (the walk may go on it), and its end said
        String note = hold.notKnown(early);
        assertTrue(note != null && note.contains("item data is not loaded"), note);
        assertNull(hold.notKnown(early), "once");
        assertNull(hold.notKnown(new MainChest.NotLoaded(1, 2, 3)), "the chunk's own reason is no item-data note");
        assertTrue(hold.notKnown(new MainChest.Known(1, 2, 3)).contains("can be told now"));
        assertNull(hold.notKnown(new MainChest.Known(1, 2, 3)));
        // the deposit leg's check: not loaded is never gone (its station record is not forgotten)
        assertFalse(io.github.mojolowjo.entropybot.commands.DepositLegProbe.goneAt("minecraft:chest"));
        assertNotNull(io.github.mojolowjo.entropybot.commands.DepositLegProbe.notKnownWords("minecraft:chest"));
        assertTrue(io.github.mojolowjo.entropybot.clear.OwnStations.pickUp("chest", "chest", false, "chest", 0, 1, 2, 3).contains("item data is not loaded"),
                "an own chest is not picked up while it is not known whether it holds items");
        io.github.mojolowjo.entropybot.aa.brain.TestData.vanilla().install();
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Container.YES, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.containerOf("minecraft:chest"));
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.Container.NO, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.containerOf("minecraft:stone"));
        assertEquals(new MainChest.Known(1, 2, 3), MainChest.ofBlock(1, 2, 3, "minecraft:chest"));
        MainChest.Answer stone = MainChest.ofBlock(1, 2, 3, "minecraft:stone");
        assertTrue(stone instanceof MainChest.Held h && h.why().contains("is gone"), String.valueOf(stone));
        assertFalse(io.github.mojolowjo.entropybot.commands.DepositLegProbe.goneAt("minecraft:chest"));
        assertTrue(io.github.mojolowjo.entropybot.commands.DepositLegProbe.goneAt("minecraft:stone"));
        assertFalse(io.github.mojolowjo.entropybot.commands.DepositLegProbe.goneAt(null), "the chunk not loaded is never gone");
        assertNull(io.github.mojolowjo.entropybot.commands.DepositLegProbe.notKnownWords("minecraft:chest"));
    }

    @Test
    void theFactIsTheOneReader() {
        OrderLineSim s = new OrderLineSim();
        assertEquals("-53 107 -8", MainChest.of(s.bb).at(), "the sim installs the reader from its main chest cell");
        s.mainAt = null;
        assertInstanceOf(MainChest.None.class, MainChest.of(s.bb), "read each time");
        s.bb.fact(MainChest.FACT, null);
        MainChest.Answer none = MainChest.of(s.bb);
        assertTrue(none instanceof MainChest.None n && n.why().contains("no main chest reader"), String.valueOf(none));
        s.bb.fact(MainChest.FACT, (Supplier<MainChest.Answer>) () -> new MainChest.NotLoaded(4, 5, 6));
        assertEquals(new MainChest.NotLoaded(4, 5, 6), MainChest.of(s.bb));
    }

    @Test
    void aDepositWithAChestNamesItsCell() {
        Map<String, Integer> only = Map.of("minecraft:cobblestone", 32);
        Step.Deposit plain = new Step.Deposit(only), at = new Step.Deposit(only, new int[] {1, 2, 3});
        assertNull(plain.chest(), "the old constructors pass no chest");
        assertEquals("deposit cobblestone 32", plain.text());
        assertEquals("deposit cobblestone 32 to the chest at 1 2 3", at.text());
        assertNotEquals(StepId.of(plain), StepId.of(at));
        assertEquals(StepId.of(at), StepId.of(new Step.Deposit(only, new int[] {1, 2, 3})));
        assertEquals(at, new Step.Deposit(only, new int[] {1, 2, 3}), "equal by the cell, not the array");
        assertEquals(at.hashCode(), new Step.Deposit(only, new int[] {1, 2, 3}).hashCode());
        assertNotEquals(plain, at);
        assertEquals(new Step.Deposit(), new Step.Deposit());
    }
}
