package io.github.mojolowjo.entropybot.aa;

import baritone.api.process.Reserves;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
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

    /**
     * Pickaxe price (plan step 6, run 3 w070: "15 levels, 7 of 43 blocks spare: a staircase", then the collect, then "wooden_pickaxe
     * gone (broke)" under ground): a wooden pickaxe with 30 uses digs 16 cobblestone (14 left); the staircase's 45 breaks pass
     * them, no replacement can be had (before going down: no materials; under ground: never), the spot has no extra blocks,
     * and the 40 dirt another order keeps have no source to be collected again: no way up stands, said, no staircase planned.
     */
    @Test
    void theStaircasePastThePickaxesUsesIsNoWay_keptBlocksNotLostW070() {
        Spot sp = new Spot(5, 62, 0, 5, STONE, 16).covered(2, "minecraft:dirt");
        World w = world(sp, Map.of("minecraft:wooden_pickaxe", 1, "minecraft:dirt", 40))
                .withReserves(List.of(Reservations.order("dirt (finished)", List.of("minecraft:dirt"), 40)))
                .withUses(k -> k.endsWith("wooden_pickaxe") ? 30 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED);
        Chain c = NeedChain.resolve("cobblestone", 16, w);
        assertTrue(c.why().contains("a staircase: the staircase up 15 levels: 45" + NeedChain.PICK_SHORT + "14 left, no replacement under ground"), "the staircase is dropped with its reason: " + c.why());
        assertTrue(c.why().contains(NeedChain.WAY_BACK_UP + "15 levels: no way up stands"), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Climb), "no lend without its collect again: " + c.steps());
        assertFalse(c.why().contains(NeedChain.WAY_BACK_UP + "15 levels, 0 of 56 blocks spare:"), "no way back up taken: " + c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Collect k && k.item().equals("dirt")), "the kept dirt is never re-collected: " + c.steps());
        // unlimited uses: the staircase stands again (no spare block, no extra at the spot: the only way)
        Chain free = NeedChain.resolve("cobblestone", 16, w.withUses(k -> io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED));
        assertTrue(free.ok(), free.why());
        assertTrue(free.why().contains(NeedChain.WAY_BACK_UP + "15 levels, 0 of 56 blocks spare: a staircase "), free.why());
    }

    /**
     * Pickaxe price (c): the same w070 shape with an exposed dirt source - the 15 kept dirt are lent to the pillar (the Climb
     * carries the lend) and collected again right after the Climb, so the kept 40 are whole before any later step.
     */
    @Test
    void keptBlocksLentToThePillarAndCollectedAgain() {
        Spot sp = new Spot(5, 62, 0, 5, STONE, 16).covered(2, "minecraft:dirt");
        Spot dirt = new Spot(20, 64, 0, 15, "minecraft:dirt");
        World w = Z77StoneHereTest.world(word -> word.contains("dirt") ? List.of(dirt) : List.of(sp))
                .withBag(Map.of("minecraft:wooden_pickaxe", 1, "minecraft:dirt", 40))
                .withReserves(List.of(Reservations.order("dirt (finished)", List.of("minecraft:dirt"), 40)))
                .withUses(k -> k.endsWith("wooden_pickaxe") ? 30 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED);
        Chain c = NeedChain.resolve("cobblestone", 16, w);
        assertTrue(c.ok(), c.why());
        int climb = -1, again = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            if (c.steps().get(i) instanceof Step.Climb k && k.lend() == 15 && k.lendItem().endsWith("dirt")) climb = i;
            if (climb >= 0 && i > climb && c.steps().get(i) instanceof Step.Collect k && k.item().contains("dirt") && k.n() >= 15) { again = i; break; }
        }
        assertTrue(climb >= 0 && again > climb, "the lent dirt collected again after the climb: " + c.steps());
        assertTrue(c.why().contains("15 kept dirt lent"), c.why());
    }

    /** Pickaxe price (c): the Climb's lend lowers the order's reserve for that step only (the next step's table is whole). */
    @Test
    void theClimbsLendLowersTheReserveForItsStepOnly() {
        var orders = List.of(Reservations.order("dirt (finished)", List.of("minecraft:dirt"), 40));
        var steps = List.<Step>of(new Step.Climb("minecraft:dirt", 15), new Step.Collect("dirt", 15, null));
        Reservations at = Reservations.build(steps, 0, List.of(), orders);
        assertEquals(25, at.list().get(0).units, at.text());
        assertTrue(at.note().contains("climb lends 15 dirt"), at.note());
        Reservations after = Reservations.build(steps, 1, List.of(), orders);
        assertEquals(40, after.list().get(0).units, "the next step's table is whole: " + after.text());
    }

    /**
     * Pickaxe price (a), review 2: a wooden pickaxe with 10 uses, 8 cobblestone under 2 blocks of stone (2 + 8 + 4 extra
     * blocks = 14 uses; review 4: the extra blocks stand past the spot's count, the shaft's walls): the replacement is
     * priced BEFORE going down - its craft before the cover dig - and the climb then stands on the new one's uses.
     */
    @Test
    void theReplacementIsMadeBeforeGoingDown() {
        Spot sp = new Spot(5, 62, 0, 5, STONE, 8).covered(2, STONE);
        World w = world(sp, Map.of("minecraft:wooden_pickaxe", 1, "minecraft:oak_planks", 3, "minecraft:stick", 2, "minecraft:crafting_table", 1))
                .withUses(k -> k.endsWith("wooden_pickaxe") ? 10 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED);
        Chain c = NeedChain.resolve("cobblestone", 8, w);
        assertTrue(c.ok(), c.why());
        int craft = -1, dig = -1, climb = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (craft < 0 && s instanceof Step.Craft k && k.item().endsWith("_pickaxe")) craft = i;
            if (dig < 0 && s instanceof Step.Dig) dig = i;
            if (s instanceof Step.Climb) climb = i;
        }
        assertTrue(craft >= 0 && dig > craft && climb > dig, "the pickaxe made before the dig down: " + c.steps() + " | " + c.why());
    }

    /** Review: the staircase's wear counts only the levels the spare blocks do not pillar, the same in every climb way. */
    @Test
    void stairWearLeavesOutPillaredLevels() {
        int per = NeedChain.stairBreaksPerLevel();
        assertEquals(15 * per, NeedChain.stairWear(15, 0));
        assertEquals(8 * per, NeedChain.stairWear(15, 7));
        assertEquals(0, NeedChain.stairWear(15, 20));
        assertEquals(15 * per, NeedChain.stairWear(15, -3));
    }

    static int pickCrafts(Chain c, String kind) {
        int n = 0;
        for (Step s : c.steps()) if (s instanceof Step.Craft k && k.item().endsWith(kind)) n++;
        return n;
    }

    /**
     * Review 3 item 1 (serious; the w070 shape): 64 cobblestone under 2 blocks of dirt with a worn wooden pickaxe (40 uses):
     * the take 64 and the climb's 63 extra blocks are 127 uses, past one new wooden pickaxe (59). The better tier the bag
     * pays (a stone pickaxe, 131 new) and the held one with spares are priced ways - the collect never fails "no replacement
     * under ground".
     */
    @Test
    void wearAboveOneFreshPickaxe_theBetterTierStands() {
        Spot sp = new Spot(5, 62, 0, 5, STONE).covered(2, "minecraft:dirt");
        World w = world(sp, Map.of("minecraft:wooden_pickaxe", 1, "minecraft:oak_planks", 6, "minecraft:stick", 4, "minecraft:crafting_table", 1, COBBLE, 3))
                .withUses(k -> k.endsWith("wooden_pickaxe") ? 40 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED);
        Chain c = NeedChain.resolve("cobblestone", 64, w);
        assertTrue(c.ok(), c.why());
        assertTrue(c.why().contains("with stone_pickaxe") || c.why().contains("spare wooden_pickaxe"), "the replacement priced before going down: " + c.why());
        assertTrue(pickCrafts(c, "_pickaxe") >= 1, "a replacement made before going down: " + c.steps());
    }

    /**
     * job-own-aside-worker (worlds run 4, w006: "69 pickaxe uses needed, 11 left, no replacement under ground", every covered
     * spot dropped, the first plan 64 s where run 3 took 3.4 s): the same w070 shape with NO pickaxe in the bag - the
     * wooden pickaxe is made in the chain (59 uses new) and the take and the climb need more. Its uses are known, so the
     * collect's pickaxe check counts them (was: skipped for a pickaxe made in the chain) and prices the replacement before
     * going down; the covered spot stands.
     */
    @Test
    void aPickaxeMadeInTheChainIsCountedBeforeGoingDown_w006() {
        Spot sp = new Spot(5, 62, 0, 5, STONE).covered(2, "minecraft:dirt");
        World w = world(sp, Map.of("minecraft:oak_planks", 12, "minecraft:stick", 6, "minecraft:crafting_table", 1));
        // 48 under 2 dirt: the take 48 and the climb's 41 extra blocks are 89 uses, past the made one's 59 (before the fix:
        // the check was skipped, the way up ran past the uses with "no replacement under ground", the spot dropped: an explore)
        Chain c = NeedChain.resolve("cobblestone", 48, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Collect k && k.item().contains("cobblestone")), "the covered spot is taken: " + c.steps() + " | " + c.why());
        assertTrue(c.why().contains("then 1 spare wooden_pickaxe") || c.why().contains("then stone_pickaxe"), "the made pickaxe's uses are counted before going down: " + c.why());
        assertTrue(pickCrafts(c, "_pickaxe") >= 2, "the made pickaxe and its replacement (a spare or a better tier) before the dig: " + c.steps() + " | " + c.why());
    }

    /**
     * Review 3 item 1: the same wear with no stone to pay a better tier (the asked cobblestone is the only stone): the held
     * one's 50 uses are kept and a spare wooden pickaxe (59) added - 109 uses for 79 - instead of one new one (59) alone.
     */
    @Test
    void wearAboveOneFreshPickaxe_theHeldUsesKeptAndSparesAdded() {
        Spot sp = new Spot(5, 62, 0, 5, STONE).covered(2, "minecraft:dirt");
        World w = world(sp, Map.of("minecraft:wooden_pickaxe", 1, "minecraft:oak_planks", 12, "minecraft:stick", 4, "minecraft:crafting_table", 1))
                .withUses(k -> k.endsWith("wooden_pickaxe") ? 50 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED);
        Chain c = NeedChain.resolve("cobblestone", 40, w);
        assertTrue(c.ok(), c.why());
        assertEquals(1, pickCrafts(c, "wooden_pickaxe"), "one spare beside the held one: " + c.steps());
        assertTrue(c.why().contains("spare wooden_pickaxe"), c.why());
    }

    /**
     * Review 3 item 5: the held stone pickaxe has 10 uses, the bag's other wooden one 59 - the one reader counts both (17 uses
     * for 8 blocks, the cover and 7 extra): no new pickaxe is made before going down.
     */
    @Test
    void theBagsOtherPickaxeCountsBeforeGoingDown() {
        Spot sp = new Spot(5, 62, 0, 5, STONE).covered(2, STONE);
        World w = world(sp, Map.of("minecraft:stone_pickaxe", 1, "minecraft:wooden_pickaxe", 1, "minecraft:oak_planks", 3, "minecraft:stick", 2, "minecraft:crafting_table", 1))
                .withUses(k -> k.endsWith("stone_pickaxe") ? 10 : k.endsWith("wooden_pickaxe") ? 59 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED);
        Chain c = NeedChain.resolve("cobblestone", 8, w);
        assertTrue(c.ok(), c.why());
        assertEquals(0, pickCrafts(c, "_pickaxe"), "no pickaxe made: " + c.steps());
    }

    /**
     * Review 4 (serious, the iron-kit order's w070 class): iron ore (tier 2) under 2 blocks of stone, a stone pickaxe with 4
     * uses and a wooden one with 59 in the bag. The cover takes 2, the take 3 more: the wooden one cannot drop iron, so the
     * take's wear is counted against stone-tier uses only (2 left) and a new stone pickaxe is made before going down.
     */
    static World iron(World w) {
        return w.withMobs(x -> List.of(), new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, STONE, COBBLE, 1, 1, false, null),
                new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:iron_ore", "minecraft:raw_iron", 1, 1, false, null)), "test"));
    }

    @Test
    void aWoodenSpareDoesNotCoverAnIronTake() {
        Spot sp = new Spot(5, 62, 0, 5, "minecraft:iron_ore", 3).covered(2, STONE);
        World w = iron(world(sp, Map.of("minecraft:stone_pickaxe", 1, "minecraft:wooden_pickaxe", 1, COBBLE, 3, "minecraft:stick", 2, "minecraft:crafting_table", 1)))
                .withUses(k -> k.endsWith("stone_pickaxe") ? 4 : k.endsWith("wooden_pickaxe") ? 59 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED);
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(c.ok(), c.why());
        assertEquals(1, pickCrafts(c, "stone_pickaxe"), "a stone pickaxe made before going down: " + c.steps() + " | " + c.why());
        // the same with a stone spare: its uses cover the take, nothing made
        Chain spare = NeedChain.resolve("raw_iron", 3, iron(world(sp, Map.of("minecraft:stone_pickaxe", 2, COBBLE, 3, "minecraft:stick", 2, "minecraft:crafting_table", 1)))
                .withUses(k -> k.endsWith("stone_pickaxe") ? 4 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED));
        assertTrue(spare.ok(), spare.why());
        assertEquals(0, pickCrafts(spare, "_pickaxe"), "the bag's stone spare covers it: " + spare.steps());
    }

    /**
     * Review 4 (item 2; before this branch the extra blocks always stood): 16 dirt under dirt with no pickaxe and no spare
     * block, the take empties the counted spot - the extra blocks (the shaft's own dirt walls) and a pillar are still the
     * way up, no pickaxe needed: the near spot is planned, a Climb follows its collect.
     */
    @Test
    void noPickaxe_theExtraBlocksStillClimbOut() {
        Spot near = new Spot(5, 62, 0, 5, "minecraft:dirt", 16).covered(2, "minecraft:dirt");
        World w = Z77StoneHereTest.world(word -> List.of(near)).withBag(Map.of());
        Chain c = NeedChain.resolve("dirt", 16, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(1, climbAfterCollect(c), c.steps() + " | " + c.why());
        assertTrue(c.why().contains("15 extra blocks and a pillar"), c.why());
    }

    /** Review 3 item 3: a whole (not loaded) reserve never lends - the planner offers no lend of it (one answer with the climb). */
    @Test
    void aWholeReserveIsNoLend() {
        ScenariosTest.world("wooden", "stone");
        assertEquals(40, Reservations.lendable(List.of(Reservations.order("dirt", List.of("minecraft:dirt"), 40)), "dirt"));
        assertEquals(0, Reservations.lendable(List.of(Reserves.Reserve.whole("minecraft:dirt", "minecraft:dirt", "x")), "minecraft:dirt"));
        assertEquals(List.of(), NeedChain.lendItems(Map.of("minecraft:dirt", 40), List.of(Reserves.Reserve.whole("minecraft:dirt", "minecraft:dirt", "x")), 15, "cobblestone"));
        assertEquals(List.of("minecraft:dirt"), NeedChain.lendItems(Map.of("minecraft:dirt", 40), List.of(Reservations.order("dirt", List.of("minecraft:dirt"), 40)), 15, "cobblestone"));
    }

    /**
     * Review 3 item 4: two kept blocks may be lent - 50 andesite (no source to collect again) and 40 dirt (a source): one way
     * each, the scorer takes the one that stands (dirt), never the hand pick "most in the bag".
     */
    @Test
    void oneLendWayPerItem_theScorerChooses() {
        Spot sp = new Spot(5, 62, 0, 5, STONE, 16).covered(2, "minecraft:dirt");
        Spot dirt = new Spot(20, 64, 0, 15, "minecraft:dirt");
        World w = Z77StoneHereTest.world(word -> word.contains("dirt") ? List.of(dirt) : word.contains("andesite") ? List.of() : List.of(sp))
                .withBag(Map.of("minecraft:wooden_pickaxe", 1, "minecraft:dirt", 40, "minecraft:andesite", 50))
                .withReserves(List.of(Reservations.order("dirt (finished)", List.of("minecraft:dirt"), 40), Reservations.order("andesite (finished)", List.of("minecraft:andesite"), 50)))
                .withUses(k -> k.endsWith("wooden_pickaxe") ? 30 : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED);
        Chain c = NeedChain.resolve("cobblestone", 16, w);
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Climb k && k.lend() == 15 && k.lendItem().endsWith("dirt")), c.steps().toString());
    }

    /**
     * Review 4 (item 3): the replacement is sized on the ways that always stand - the extra blocks (their count only when the
     * block needs a pickaxe) or the staircase; a lend is not counted (its collect again may fail, and 0 would under-buy).
     */
    @Test
    void climbWearIsSizedWithoutTheLend() {
        ScenariosTest.world("wooden", "stone");
        var rs = List.of(Reservations.order("dirt (finished)", List.of("minecraft:dirt"), 40));
        assertEquals(15, NeedChain.climbWear(Map.of("minecraft:dirt", 40), rs, "cobblestone", 16, COBBLE, 16, 16, true), "kept dirt (a lend of 0 wear) is not counted: the 15 extra stone");
        assertEquals(0, NeedChain.climbWear(Map.of(), List.of(), "dirt", 16, "minecraft:dirt", 16, 16, false), "extra dirt needs no pickaxe: no wear");
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

    /**
     * Merge review 2026-11-23 (the table never reached the planner in a game): AaRuntime.needWorld sets the table and then
     * calls withWhere and withoutSources, which built their copy with no table. The World in the runtime's own call
     * order, and through every other copy an order makes of it, still carries the table - and the planner prices with it.
     */
    @Test
    void theTableReachesThePlannerThroughTheRuntimesCallOrder() {
        Spot sp = new Spot(5, 62, 0, 5, STONE).covered(2, "minecraft:dirt");
        List<Reserves.Reserve<String>> table = List.of(Reservations.order("dirt (finished)", List.of("minecraft:dirt"), 20));
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:dirt", 20);
        // needWorld: ...withRooms(..).withReserves(..).withWhere(..).withoutSources(the take holds)
        World live = world(sp, bag).withRooms(List.of(new Spot(0, 63, 0, 0, null))).withReserves(table)
                .withWhere(NeedChain.WhereFacts.NONE)
                .withoutSources(Map.of(NeedChain.stationKey(90, 64, 90), "a take short twice"));
        assertEquals(table, live.reserves(), "withWhere and withoutSources keep the table");
        // the order's own copies (Goals: the held sources, the place failures, the anchor)
        World order = live.withPlaceFailures(Map.of(NeedChain.placeKey(40, 64, 40), "no room"), new int[]{0, 64, 0})
                .without((word, s) -> false).withFeetAt(new int[]{0, 64, 0}).withHeld(List.of());
        assertEquals(table, order.reserves(), "withPlaceFailures, without and withFeetAt keep it");
        Chain c = NeedChain.resolve("cobblestone", 14, order);
        assertTrue(c.ok(), c.why());
        assertTrue(c.why().contains(NeedChain.WAY_BACK_UP + "13 levels, 0 of 34 blocks spare: "), "the planner reads the table: the 20 dirt are claimed: " + c.why());
        // the worker's frozen World carries the same list the live check compares with
        var snap = io.github.mojolowjo.entropybot.commands.PlannerSnapshot.take(order, List.of(), null, 1, 0);
        assertEquals(table, snap.world().reserves(), "the snapshot carries the table to the worker");
        assertEquals(order.reserves(), NeedChain.Facts.ask(snap.world(), null, new PlanNodes.Fact("reserves", null)));
    }

    /**
     * reserves-once (run 3 w070, log line 1000: "reserved: mangrove_log 16 (finished), cobblestone 32 (finished) | spare to
     * place: 4"; the planner said 0 spare): another order's -d amount came off the forecast bag (Goals.keptOff) AND off the
     * spare count again through the pushed ledger. The planner's reserves now come from Goals.plannerReserves: once.
     */
    @Test
    void keptAmountsAreCountedOnce_w070() {
        ScenariosTest.world("wooden", "stone");
        io.github.mojolowjo.entropybot.aa.brain.Goals.Goal cob = Z61ReservationsTest.goal("cobblestone", 32), iron = Z61ReservationsTest.goal("raw_iron", 3);
        List<io.github.mojolowjo.entropybot.aa.brain.LineDeposits.Kept> kept = List.of(new io.github.mojolowjo.entropybot.aa.brain.LineDeposits.Kept(cob, Map.of("cobblestone", 32)));
        java.util.function.Supplier<List<io.github.mojolowjo.entropybot.aa.brain.Goals.Finished>> fin = () -> new java.util.ArrayList<>(List.of(
                new io.github.mojolowjo.entropybot.aa.brain.Goals.Finished("cobblestone", List.of(COBBLE), 32, false, cob)));
        Map<String, Integer> live = Map.of("cobblestone", 35);
        // the ledger the run pushed (release: the orders only) holds the 32, and the lease is right with it on the LIVE bag
        var ledger = io.github.mojolowjo.entropybot.aa.brain.Goals.orderReserves(null, fin.get(), live, kept);
        assertEquals(3, Reserves.spareThrowaway(ledger, Map.of(COBBLE, 35), List.of(COBBLE)), "the lease: 3 of 35");
        // the fault: the planner's forecast bag has the 32 off already; the ledger took them off again
        assertEquals(0, NeedChain.spareBlocks(Map.of(COBBLE, 3), ledger, "raw_iron", 3), "counted twice: 0 spare");
        // the fix: one store, one reader for the planner
        List<io.github.mojolowjo.entropybot.aa.brain.Goals.Finished> f = fin.get();
        var planner = io.github.mojolowjo.entropybot.aa.brain.Goals.plannerReserves(iron, f, live, kept, List.of(iron));
        assertEquals(3, NeedChain.spareBlocks(Map.of(COBBLE, 3), planner, "raw_iron", 3), "counted once: 3 spare " + planner);
        assertEquals(32, f.get(0).n, "the finished list is not lowered by the planner");
        // the -d order planned itself: its own amount is not off its bag, it is its own target: 3 spare of 35
        var own = io.github.mojolowjo.entropybot.aa.brain.Goals.plannerReserves(cob, fin.get(), live, kept, List.of(cob));
        assertEquals(3, NeedChain.spareBlocks(Map.of(COBBLE, 35), own, "cobblestone", 32), own.toString());
        // a finished order with no deposit word is still held from placing (ruling 1 option c)
        var plain = io.github.mojolowjo.entropybot.aa.brain.Goals.plannerReserves(iron, fin.get(), live, List.of(), List.of(iron));
        assertEquals(3, NeedChain.spareBlocks(Map.of(COBBLE, 35), plain, "raw_iron", 3), plain.toString());
        // the id forms: a path-keyed bag against full-id reserves, and a modded reserve against its path
        assertEquals(3, NeedChain.spareBlocks(Map.of("cobblestone", 35), List.of(order(32)), null, 0));
        assertEquals(3, NeedChain.spareBlocks(Map.of("cobblestone", 35), List.of(), "minecraft:cobblestone", 32));
        // merge review 2026-11-25 finding 13 (rule 4): the bag keeps a modded item's namespace, so a reserve of another
        // mod's cobblestone is another item and holds none of the minecraft cobblestone (it once matched by the path alone)
        assertEquals(35, NeedChain.spareBlocks(Map.of("cobblestone", 35), List.of(Reservations.order("x", List.of("othermod:cobblestone"), 32)), null, 0));
    }

    /** reserves-once (b): the smelt burns the fuel its plan priced when the free bag covers it, else the pick stands, said. */
    @Test
    void theSmeltBurnsThePricedFuel() {
        var smelt = new io.github.mojolowjo.entropybot.craft.Crafter.Smelt("r", "minecraft:charcoal", 1, "minecraft:spruce_log", 1, "minecraft:spruce_planks", 1);
        var plan = new io.github.mojolowjo.entropybot.craft.Crafter.Plan(List.of(smelt), null);
        Map<String, Integer> fuels = Map.of("minecraft:coal", 1600, "minecraft:spruce_planks", 300);
        List<String> said = new java.util.ArrayList<>();
        var p = io.github.mojolowjo.entropybot.craft.CraftPlanner.withFuel(plan, "coal", Map.of("minecraft:coal", 2, "minecraft:spruce_planks", 4), fuels, 200, said::add);
        var s = (io.github.mojolowjo.entropybot.craft.Crafter.Smelt) p.steps().get(0);
        assertEquals("minecraft:coal", s.fuel());
        assertEquals(1, s.fuelCount());
        assertTrue(said.isEmpty(), said.toString());
        var q = io.github.mojolowjo.entropybot.craft.CraftPlanner.withFuel(plan, "coal", Map.of("minecraft:spruce_planks", 4), fuels, 200, said::add);
        assertEquals("minecraft:spruce_planks", ((io.github.mojolowjo.entropybot.craft.Crafter.Smelt) q.steps().get(0)).fuel());
        assertEquals(1, said.size(), "rule 7: the priced fuel not free is said");
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
