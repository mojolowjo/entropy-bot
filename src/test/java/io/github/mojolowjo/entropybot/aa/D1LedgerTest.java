package io.github.mojolowjo.entropybot.aa;

import baritone.api.process.Reserves;
import io.github.mojolowjo.entropybot.aa.brain.DepositRule;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Reservations;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D1 ledger (job-d1, owner 2026-10-25 "counts, not keep lists"): the run's reserves in one ledger on the board (rule 23),
 * rebuilt from the plan at every push; a deposit leaves the reserved counts in the bag; an Obtain's sub-chain is planned on
 * the bag less the later steps' counts; the probe's "reserved: item N for step" words. Hand-computed; no game.
 */
class D1LedgerTest {
    static final String COBBLE = "minecraft:cobblestone", CRAFTING = "#minecraft:stone_crafting_materials";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    /** Fake hands that also write the board's ledger, as StepHands does. */
    static class Hands extends Z61ReservationsTest.Hands {
        final Blackboard bb = new Blackboard(() -> 0L, null, null);
        Reservations later;

        @Override public void ledger(Reservations table) { bb.reserved(table); }

        @Override public Steps.Routine routine(Step.Obtain o, Reservations l) {
            later = l;
            return new Steps.Routine(List.of(new Step.Collect("cobblestone", o.n())), null);
        }
    }

    /** A craft reserves its inputs: the furnace's 8 of the stone crafting tag, said "for craft furnace 1". */
    @Test
    void aCraftReservesItsInputs() {
        Z61ReservationsTest.stoneGame();
        List<Step> steps = List.of(new Step.Craft("furnace", 1));
        Reservations r = Reservations.build(steps, 0, Z61ReservationsTest.needs(steps), List.of());
        assertEquals(1, r.list().size());
        assertEquals(CRAFTING, r.list().get(0).word);
        assertEquals(8, r.list().get(0).units);
        assertEquals("reserved: #stone_crafting_materials 8 for craft furnace 1", r.ledgerText());
    }

    /** By hand: cobblestone 64 + 10, dirt 20, the furnace reserves 8: the 64 stack and the dirt go in, the 10 stack stays (8 needed). */
    @Test
    void aDepositLeavesTheReservedCountInTheBag() {
        Z61ReservationsTest.stoneGame();
        List<Step> steps = List.of(new Step.Craft("furnace", 1));
        var rs = Reservations.build(steps, 0, Z61ReservationsTest.needs(steps), List.of()).chain();
        List<DepositRule.Item> bag = List.of(new DepositRule.Item(COBBLE, 64, false, false, false, false),
                new DepositRule.Item(COBBLE, 10, false, false, false, false), new DepositRule.Item("minecraft:dirt", 20, false, false, false, false));
        assertArrayEquals(new boolean[]{true, false, true}, DepositRule.goesIn(bag, rs));
        assertEquals(Map.of(COBBLE, 64, "minecraft:dirt", 20), DepositRule.toPut(bag, rs));
        assertEquals(Map.of(COBBLE, 10), DepositRule.heldBack(bag, rs));
        // only 6 in the bag: short already, nothing of it goes in
        List<DepositRule.Item> few = List.of(new DepositRule.Item(COBBLE, 6, false, false, false, false));
        assertEquals(Map.of(), DepositRule.toPut(few, rs));
        assertEquals(Map.of(COBBLE, 6), DepositRule.toPut(few, List.of()), "no ledger: the old rule");
    }

    /** Two furnace crafts: 8 + 8 reserved; a bag of 20 frees 4 for anything else, and 16 are held. */
    @Test
    void twoStepsNeedingTheSameItemSumTheirReservations() {
        Z61ReservationsTest.stoneGame();
        List<Step> steps = List.of(new Step.Craft("furnace", 1), new Step.Craft("furnace", 1));
        var rs = Reservations.build(steps, 0, Z61ReservationsTest.needs(steps), List.of()).list();
        int sum = 0;
        for (var r : rs) if (r.word.equals(CRAFTING)) sum += r.units;
        assertEquals(16, sum);
        assertEquals(4, Reserves.spare(rs, Map.of(COBBLE, 20), COBBLE));
        assertEquals(Map.of("cobblestone", 4), Reservations.free(rs, Map.of("cobblestone", 20)), "bag keys by path read the same");
        assertEquals(Map.of("cobblestone", 16), Reservations.held(rs, Map.of("cobblestone", 20)));
    }

    /** Shared members are freed once: S-R3's tags, cobblestone 10 + blackstone 2 against 3 of either and 8 of cobblestone: 1 free in all. */
    @Test
    void freeNeverCountsASharedMemberTwice() {
        var a = Reserves.Reserve.of("a", List.of(COBBLE, "minecraft:blackstone"), 3, Reserves.Kind.CONSUME, 0, "x");
        var b = Reserves.Reserve.of(COBBLE, List.of(COBBLE), 8, Reserves.Kind.CONSUME, 1, "y");
        Map<String, Integer> f = Reservations.free(List.of(a, b), new java.util.LinkedHashMap<>(Map.of(COBBLE, 10, "minecraft:blackstone", 2)));
        assertEquals(1, f.values().stream().mapToInt(Integer::intValue).sum(), f.toString());
    }

    /** A re-plan rebuilds the ledger from the new plan: the old chain's claim is gone, the new one's is there (never both). */
    @Test
    void aReplanRebuildsTheLedger() {
        Z61ReservationsTest.stoneGame();
        Hands h = new Hands();
        h.bag.putAll(Map.of(COBBLE, 20, "minecraft:stick", 2));
        Steps run = new Steps(h);
        run.start("owner goal", List.of(new Step.Craft("furnace", 1)), 0, (failed, fact, why) ->
                new Steps.Routine(List.of(new Step.Craft("stone_pickaxe", 1)), null));
        assertEquals("reserved: #stone_crafting_materials 8 for craft furnace 1", h.bb.reserved().ledgerText());
        h.status = Car.Status.failed("ingredients ran out");
        h.fact = new FactChange.Fact("bag cobblestone", "cobblestone: 2 of 8 in the bag");
        run.tick(1);
        String t = h.bb.reserved().ledgerText();
        assertFalse(t.contains("furnace"), t);
        assertTrue(t.contains("#stone_tool_materials 3 for craft stone_pickaxe 1") && t.contains("stick 2 for craft stone_pickaxe 1"), t);
    }

    /** An Obtain's sub-chain is asked with the later steps' reserves (not the orders'); the run's end empties the ledger. */
    @Test
    void anObtainIsPlannedAroundTheLaterStepsAndTheEndClearsTheLedger() {
        Z61ReservationsTest.stoneGame();
        Hands h = new Hands();
        h.orders = List.of(Reservations.order("furnace", List.of("minecraft:furnace"), 1));
        Steps run = new Steps(h);
        run.start("owner goal", List.of(new Step.Obtain("minecraft:cobblestone", 8), new Step.Craft("furnace", 1)), 0);
        assertNotNull(h.later);
        assertEquals("reserved: #stone_crafting_materials 8 for craft furnace 1", h.later.ledgerText(), "the order's own target is not held from the sub-chain");
        run.cancel("test");
        assertEquals("reserved: nothing", h.bb.reserved().ledgerText());
    }

    /** The probe's words for each reserve, fuel in ticks, an order's. */
    @Test
    void theLineText() {
        var fuel = new Reserves.Reserve<>(Reservations.FUEL, List.of("minecraft:coal"), Map.of("minecraft:coal", 1600), 600, Reserves.Kind.CONSUME, 2, "smelt iron_ingot 3 here");
        assertEquals("fuel 600 ticks for smelt iron_ingot 3 here", Reservations.forText(fuel));
        assertEquals("cobblestone 64 for order cobblestone", Reservations.forText(Reservations.order("cobblestone", List.of("cobblestone"), 64)));
        assertEquals("reserved: nothing", Reservations.NONE.ledgerText());
        List<String> words = new ArrayList<>();
        for (var r : List.of(fuel)) words.add(Reservations.forText(r));
        assertEquals(List.of("fuel 600 ticks for smelt iron_ingot 3 here"), words);
    }
}
