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
