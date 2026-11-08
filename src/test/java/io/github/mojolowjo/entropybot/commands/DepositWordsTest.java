package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.DepositRule;
import io.github.mojolowjo.entropybot.aa.brain.DepositRule.Item;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * deposit-words (owner 2026-11-08, to-do/deposit-words.md): -d / -deposit / a trailing deposit on the aa want line flag the
 * items that go into the main chest when the line ends (the ordered amount); aa deposit <item> <n>, ... puts exactly those
 * away and says what the bag holds first; aa want deposit is gone.
 */
class DepositWordsTest {
    static Item plain(String id, int n) { return new Item(id, n, false, false, false, false); }

    @Test
    void theWantWordsCarryTheDepositFlag() {
        Goals.Args a = Goals.parse("cobblestone 32 -d");
        assertEquals("cobblestone", a.word());
        assertEquals(32, a.n());
        assertTrue(a.deposit());
        assertTrue(Goals.parse("torch -deposit high").deposit());
        assertEquals(1, Goals.parse("torch -deposit high").prio());
        assertTrue(Goals.parse("torch 4 deposit").deposit(), "the trailing word on a single order");
        assertFalse(Goals.parse("torch 4").deposit());
        assertEquals(Goals.WANT_DEPOSIT_GONE, Goals.parse("deposit").err(), "the old one-shot is gone, said");
        assertEquals(Goals.WANT_DEPOSIT_GONE, Goals.parse("-d").err());
    }

    @Test
    void exactlyTheAskedCountsGoIn_wholeStacksFirstThenOneSplit() {
        List<Item> bag = List.of(plain("minecraft:cobblestone", 64), plain("minecraft:torch", 2), plain("minecraft:cobblestone", 40), plain("minecraft:oak_log", 16));
        int[] take = DepositRule.takeOnly(bag, Map.of("cobblestone", 70, "torch", 4));
        assertArrayEquals(new int[]{64, 2, 6, 0}, take, "64 whole, then 6 of the 40; 2 torches of 4 asked; the logs stay");
        assertEquals(Map.of("minecraft:cobblestone", 70, "minecraft:torch", 2), DepositRule.onlyToPut(bag, Map.of("cobblestone", 70, "torch", 4)));
        assertArrayEquals(new int[]{32, 0, 0, 0}, DepositRule.takeOnly(bag, Map.of("minecraft:cobblestone", 32)), "32 asked: no whole stack fits, one split of the first stack");
        assertEquals(104, DepositRule.onlyInBag(bag, Map.of("cobblestone", 1)));
        assertTrue(DepositRule.onlyToPut(bag, Map.of("iron_ingot", 3)).isEmpty());
    }

    @Test
    void theReportSaysWhatTheBagHolds() {
        List<Item> bag = List.of(plain("minecraft:cobblestone", 40), plain("minecraft:torch", 2));
        List<String> lines = DepositRule.onlyReport(bag, new java.util.TreeMap<>(Map.of("cobblestone", 32, "torch", 4, "iron_ingot", 3)));
        assertEquals(List.of("cobblestone: 32 of 40 in the bag: putting 32 away", "iron_ingot: none in the bag", "torch: 2 in the bag, 4 asked: putting 2 away"), lines);
    }

    @Test
    void theOneShotWordsParse() {
        String[] err = new String[1];
        assertEquals(Map.of("minecraft:cobblestone", 32, "minecraft:torch", 4), MainStationsLive.depositItemsOf("cobblestone 32, torch 4", err));
        assertNull(err[0]);
        assertNull(MainStationsLive.depositItemsOf("10 64 -20", err), "three integers: a position, as before");
        assertNull(err[0]);
        assertNull(MainStationsLive.depositItemsOf("", err));
        assertNull(MainStationsLive.depositItemsOf("cobblestone", err), "no amount: an error");
        assertTrue(err[0].contains("give each item an amount"), err[0]);
        err[0] = null;
        assertEquals(Map.of("minecraft:iron_ore", 5), MainStationsLive.depositItemsOf("iron ore 5", err), "words joined");
        assertEquals(Map.of("mekanism:osmium_ingot", 2), MainStationsLive.depositItemsOf("mekanism:osmium_ingot 2", err), "a namespace stays");
    }

    @Test
    void theStepNamesItsItems() {
        Step.Deposit d = new Step.Deposit(Map.of("minecraft:torch", 4, "minecraft:cobblestone", 32));
        assertEquals("deposit cobblestone 32, torch 4", d.text());
        assertNull(d.order());
        assertEquals("deposit", new Step.Deposit().text());
        assertNotEquals(StepId.of(d), StepId.of(new Step.Deposit()), "a plain deposit and an exact one are different steps");
        assertEquals(StepId.of(d), StepId.of(new Step.Deposit(Map.of("minecraft:cobblestone", 32, "minecraft:torch", 4))));
    }
}
