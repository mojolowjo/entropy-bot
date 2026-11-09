package io.github.mojolowjo.entropybot.aa;

import baritone.api.process.Reserves;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Reservations;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * forecast (owner 2026-11-08; run JBJ-T2 13:21: "collect cobblestone 14" under 2 blocks of dirt dug a 1x1 shaft 13 levels down,
 * the 32 cobblestone in the bag were the order's own, Baritone's lease had no block to spare, every way up "no path"): the
 * chain's forecast bag knows the reserves - a covered spot's collect that digs down prices its way back up against the bag
 * AFTER the collect (the orders' and the chain's claims are no spare block) and a Climb step follows; the climb-up way counts
 * spare blocks the same way.
 */
class ForecastBagTest {
    static final String STONE = "minecraft:stone", COBBLE = "minecraft:cobblestone";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static Reserves.Reserve<String> order(int n) { return Reservations.order("cobblestone", List.of(COBBLE), n); }

    /** The spare count is the fork's math on the forecast bag: the order's own target and the table's claims are no spare. */
    @Test
    void spareBlocksArePure() {
        ScenariosTest.world("wooden", "stone");
        assertEquals(32, NeedChain.spareBlocks(Map.of(COBBLE, 32), List.of(), null, 0), "nothing claimed: every block spare");
        assertEquals(0, NeedChain.spareBlocks(Map.of(COBBLE, 32), List.of(order(32)), null, 0), "the table's order claims them all");
        assertEquals(0, NeedChain.spareBlocks(Map.of(COBBLE, 32), List.of(), "cobblestone", 32), "the chain's own asked item is an ORDER reserve");
        assertEquals(3, NeedChain.spareBlocks(Map.of(COBBLE, 35), List.of(), "cobblestone", 32));
        assertEquals(3, NeedChain.spareBlocks(Map.of(COBBLE, 35), List.of(order(32)), "cobblestone", 32), "the table names the order already: not counted twice");
        assertEquals(0, NeedChain.spareBlocks(Map.of("minecraft:torch", 8), List.of(), null, 0), "a torch is no placeable block");
        assertEquals(8, NeedChain.placeableBlocks(Map.of(COBBLE, 8, "minecraft:torch", 4)));
    }

    /** The levels a collect digs past its spot: one block a level by default, the Tuning entry changes it. */
    @Test
    void digDownLevelsArePure() {
        assertEquals(0, NeedChain.digDownLevels(0));
        assertEquals(0, NeedChain.digDownLevels(1), "the spot itself");
        assertEquals(1, NeedChain.digDownLevels(2));
        assertEquals(13, NeedChain.digDownLevels(14), "run JBJ-T2: 13 levels for 14");
        Tuning.set("forecast.digBlocksPerLevel", 2);
        assertEquals(6, NeedChain.digDownLevels(14));
    }

    static World world(Spot sp, Map<String, Integer> bag) {
        return Z77StoneHereTest.world(word -> List.of(sp)).withBag(bag);
    }

    static int climbAfterCollect(Chain c) {
        int collect = -1, climb = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            if (c.steps().get(i) instanceof Step.Collect) collect = i;
            if (c.steps().get(i) instanceof Step.Climb && climb < 0) climb = i;
        }
        assertTrue(collect >= 0, c.steps().toString());
        return climb < 0 ? -1 : climb - collect;
    }

    /** Run JBJ-T2's shape: 14 cobblestone under 2 blocks, a stone pickaxe and nothing else: the 14 are the order's own, 0 spare, a staircase (or extra blocks) and a Climb step. */
    @Test
    void aCoveredCollectThatDigsDownPricesItsWayBackUp() {
        Spot sp = new Spot(5, 62, 0, 5, STONE).covered(2, "minecraft:dirt");
        Chain c = NeedChain.resolve("cobblestone", 14, world(sp, Map.of("minecraft:stone_pickaxe", 1)));
        assertTrue(c.ok(), c.why());
        assertEquals(1, climbAfterCollect(c), "the Climb step right after the collect: " + c.steps());
        assertTrue(c.why().contains(NeedChain.WAY_BACK_UP + "13 levels, 0 of 14 blocks spare: "), "the collected 14 are the order's own: " + c.why());
        assertFalse(c.why().contains("blocks spare: a pillar "), "no spare block: never a plain pillar: " + c.why());
        Chain same = NeedChain.resolve("cobblestone", 14, world(sp.covered(0, null), Map.of("minecraft:stone_pickaxe", 1)));
        assertTrue(same.ok(), same.why());
        assertEquals(-1, climbAfterCollect(same), "an exposed spot digs no shaft: no climb: " + same.steps());
        assertTrue(c.seconds() > same.seconds(), "the way back up is a price: " + c.seconds() + " vs " + same.seconds());
    }

    /** Spare blocks beyond the order's own (20 dirt): the pillar is priced with them when it is the cheaper way (else the staircase); either way one Climb step. */
    @Test
    void spareBlocksBeyondTheOrderPriceAPillar() {
        Spot sp = new Spot(5, 62, 0, 5, STONE).covered(2, "minecraft:dirt");
        Chain c = NeedChain.resolve("cobblestone", 14, world(sp, Map.of("minecraft:stone_pickaxe", 1, "minecraft:dirt", 20)));
        assertTrue(c.ok(), c.why());
        assertEquals(1, climbAfterCollect(c), c.steps().toString());
        assertTrue(c.why().contains(NeedChain.WAY_BACK_UP + "13 levels, 20 of 34 blocks spare: "), c.why());
        double pillar = Unit.seconds(13 * RungOptions.pillarBlock() + RungOptions.pillarFix());
        double stairs = Unit.seconds(13 * RungOptions.climbBlock() + RungOptions.climbFix());
        if (pillar <= stairs) assertTrue(c.why().contains("blocks spare: a pillar "), c.why());
        else assertTrue(c.why().contains("blocks spare: a staircase "), c.why());
        // the table claims those 20 for another order: 0 spare again
        Chain claimed = NeedChain.resolve("cobblestone", 14, world(sp, Map.of("minecraft:stone_pickaxe", 1, "minecraft:dirt", 20)).withReserves(List.of(Reservations.order("dirt (finished)", List.of("minecraft:dirt"), 20))));
        assertTrue(claimed.ok(), claimed.why());
        assertTrue(claimed.why().contains(NeedChain.WAY_BACK_UP + "13 levels, 0 of 34 blocks spare: "), claimed.why());
    }

    /** Fewer levels than forecast.climbFromLevels: no climb step (a jump out). */
    @Test
    void aShallowDigNeedsNoClimb() {
        Spot sp = new Spot(5, 62, 0, 5, STONE).covered(1, "minecraft:dirt");
        Chain c = NeedChain.resolve("cobblestone", 2, world(sp, Map.of("minecraft:stone_pickaxe", 1)));
        assertTrue(c.ok(), c.why());
        assertEquals(-1, climbAfterCollect(c), c.steps().toString());
        assertFalse(c.why().contains(NeedChain.WAY_BACK_UP), c.why());
    }

    /** The World carries the table; the snapshot's fact replay knows it. */
    @Test
    void theWorldCarriesTheReserveTable() {
        World w = Z77StoneHereTest.world(word -> List.of());
        assertTrue(w.reserves().isEmpty());
        World r = w.withReserves(List.of(order(4)));
        assertEquals(1, r.reserves().size());
        assertEquals(r.reserves(), NeedChain.Facts.ask(r, null, new PlanNodes.Fact("reserves", null)));
    }
}
