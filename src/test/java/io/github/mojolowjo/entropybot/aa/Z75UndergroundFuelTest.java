package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z75 (runs AE on z73, AF on z74): one resource model - (1) where each leg leaves the bot: a walk after the strip leg
 * to another level is not priced from the feet, and a need there is met there or before going down; (2) the smelt's fuel
 * is a counted need of its step, one kind that covers it (the furnace job's rule), and the fuel explore includes the
 * tag's own sources (coal ore); under ground in coal's band the feet's level is a strip Y; (3) what a need counted from the
 * bag is set aside for its inner needs. Hand numbers: the walk is the straight line x 1.3 at the walk speed (FakePaths).
 */
class Z75UndergroundFuelTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final String TABLE = "minecraft:crafting_table";
    /** The table the bot placed 1 m from the feet (y 76, the strip's band peak 60 below). */
    static final Spot TABLE_1M = new Spot(1, 76, 0, 1, TABLE);

    static StripBand ironAt16From76() {
        List<OreBand.Feature> fs = Z43StripWayTest.iron();
        return new StripBand(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), -64, 384, 16, OreBand.perSlab(fs, 16), 0, 0, 76, "east", "minecraft:stone");
    }

    /** The surface world: the table 1 m away (known, near, a block to take), iron only by the strip at y 16. */
    static World surface(Map<String, Integer> bag) {
        return ScenariosTest.w(bag, true, false, Map.of(TABLE, List.of(TABLE_1M)))
                .withStations(k -> ItemWorth.CRAFT.equals(k) ? TABLE_1M : null)
                .withStrip(w -> w.contains("raw_iron") || w.contains("iron_ore") ? ironAt16From76() : null);
    }

    static int index(Chain c, Class<?> kind) {
        for (int i = 0; i < c.steps().size(); i++) if (kind.isInstance(c.steps().get(i))) return i;
        return -1;
    }

    static int lastIndex(Chain c, java.util.function.Predicate<Step> p) {
        int at = -1;
        for (int i = 0; i < c.steps().size(); i++) if (p.test(c.steps().get(i))) at = i;
        return at;
    }

    // ---- 1. the table left on the surface ----

    /**
     * Run AE 17:29:44: iron 3 by the strip at y 16 (60 down), then the iron pickaxe at a table. The table 1 m away up there
     * is not 1 m from y 16 any more: its climb back is not priced (dropped, said); the chain that stands takes the table
     * along before going down (walk 1 m 0.30 s, break, back 0.30 s) and places it near the bot down there (0.15 s), so the
     * craft runs where the bot is. The bag: a stone pickaxe, 2 sticks, a furnace, 1 coal.
     */
    @Test
    void i1_theTableGoesDownWithTheBot() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("iron_pickaxe", 1, surface(Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 2,
                "minecraft:furnace", 1, "minecraft:coal", 1)));
        assertTrue(c.ok() && !c.explore(), c.why());
        int take = index(c, Step.TakeStation.class), down = index(c, Step.ToStrip.class), smelt = index(c, Step.Smelt.class);
        int placeTable = lastIndex(c, s -> s instanceof Step.PlaceNear p && p.item().contains("crafting_table"));
        int craft = lastIndex(c, s -> s instanceof Step.Craft k && k.item().equals("iron_pickaxe"));
        assertTrue(take >= 0 && down > take, "the table is taken before the strip leg: " + c.steps());
        assertTrue(smelt > down && placeTable > smelt && craft > placeTable, "placed down there, then the craft: " + c.steps());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ToStation), "no walk to the table up there: " + c.steps());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("the table up there: " + NeedChain.FROM_BELOW + "the strip leg to y 16")), c.dropped().toString());
        assertTrue(c.why().contains(NeedChain.BEFORE_DOWN + " (the strip leg to y 16): take the crafting_table 1 m"), c.why());
    }

    /**
     * The same order with the table in the bag already: the take before going down is the only difference - the two chains
     * differ by the walk there and back (2 x 0.30 s) and the table's break, nothing else (the strip, the smelt, the
     * places, the craft the same).
     */
    @Test
    void i1_theTakeAlongCostsItsWalkAndBreakOnly() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 2, "minecraft:furnace", 1, "minecraft:coal", 1);
        Chain taken = NeedChain.resolve("iron_pickaxe", 1, surface(bag));
        Map<String, Integer> withTable = new java.util.HashMap<>(bag);
        withTable.put(TABLE, 1);
        Chain carried = NeedChain.resolve("iron_pickaxe", 1, surface(withTable));
        assertTrue(carried.ok() && !carried.explore(), carried.why());
        assertTrue(carried.steps().stream().noneMatch(s -> s instanceof Step.TakeStation), carried.steps().toString());
        double walk = FakePaths.walkS(1);
        // the table by hand: hardness 2.5 x 1.5 = 3.75 s (the game's formula, no tool asked); walk 1 m = 1.3 / 4.317 = 0.30 s
        assertEquals(2 * walk + 3.75, taken.seconds() - carried.seconds(), 0.06, taken.why() + " vs " + carried.why());
        assertEquals(carried.steps().size() + 2, taken.steps().size(), "the walk to it and the take: " + taken.steps() + " vs " + carried.steps());
    }

    /** A chain with no leg to another level is unchanged: the iron pickaxe from the bag's ingots at the table 1 m away = walk 0.30 + craft 0.3. */
    @Test
    void i1_noDescentNoChange() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("iron_pickaxe", 1, surface(Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2)));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(FakePaths.walkS(1) + 0.3, c.seconds(), 0.01, c.why());
        assertEquals(List.of(new Step.ToStation(ItemWorth.CRAFT, "crafting_table", 1, 76, 0), new Step.Craft("iron_pickaxe", 1)), c.steps());
        assertFalse(c.why().contains(NeedChain.BEFORE_DOWN), c.why());
    }

    /** Run AF 18:12:45: a tree seen up there is no source from down there - the logs for the sticks are cut before going down. */
    @Test
    void i1_theLogsAreCutBeforeGoingDown() {
        ScenariosTest.world("wooden", "stone");
        World w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1, "minecraft:furnace", 1, "minecraft:coal", 1, TABLE, 1), true, false,
                        Map.of("oak_log", List.of(ScenariosTest.tree(10, 4))))
                .withStrip(x -> x.contains("raw_iron") || x.contains("iron_ore") ? ironAt16From76() : null);
        Chain c = NeedChain.resolve("iron_pickaxe", 1, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        int cut = index(c, Step.ChopTree.class), down = index(c, Step.ToStrip.class);
        assertTrue(cut >= 0 && down > cut, "the tree is cut before the strip leg: " + c.steps());
        assertTrue(c.why().contains(NeedChain.BEFORE_DOWN), c.why());
    }

    // ---- 2. fuel ----

    static final String PLANKS = "minecraft:oak_planks";

    /**
     * Two orders, one fuel unit (1 oak plank, 300 ticks), both smelt one raw iron (200 ticks). The second planned from the
     * same bag counts the plank for its smelt - as a counted need of the smelt step (the plan's fuel, a consume): once the
     * first has burned it, the dry run of the second finds the plank gone before its smelt runs (a bag fact: the order
     * re-plans with a fuel way), and the smelt's own start check says the same - never "no fuel" as a stop.
     */
    @Test
    void i2_theSecondOrdersSmeltRePlansOnItsFuel() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:raw_iron", 2, PLANKS, 1);
        Chain second = NeedChain.resolve("iron_ingot", 1, ScenariosTest.w(bag, true, true, Map.of()));
        assertTrue(second.ok() && !second.explore(), second.why());
        int at = index(second, Step.Smelt.class);
        StepNeeds n = StepNeeds.of(second.steps().get(at)).withUses(second.uses().get(at));
        assertEquals(Map.of("minecraft:raw_iron", 1, PLANKS, 1), n.consumes(), "the plank is the smelt's counted need: " + n.text());
        assertEquals(0, n.fuelTicks(), "the plan's fuel replaces the generic burn ticks");
        Map<String, Integer> afterFirst = Map.of("minecraft:raw_iron", 1);
        DryRun.Shortage dry = DryRun.check(second.steps(), at, List.of(n), afterFirst);
        assertNotNull(dry, "the dry run says it before the smelt runs");
        assertEquals("bag oak_planks", dry.key());
        StepNeeds.Bag left = bagOf(afterFirst);
        assertNotNull(n.itemShort(left, "smelt"), "the step's own start check: a bag fact, not a stop");
    }

    /**
     * Run AF 18:21:43-46: the bag's mix of the 300-tick class (1 plank + 1 crafting table) summed to the 600 ticks of 3
     * smelts, but the furnace job burns ONE kind: the generic burn check (a step the plan did not count) says it short, at
     * the dry run and at the step's start; one kind of 2 planks covers it.
     */
    @Test
    void i2_aMixOfFuelsIsNoBurn() {
        ScenariosTest.world("wooden", "stone");
        StepNeeds n = StepNeeds.of(new Step.Smelt("iron_ingot", 3, true));
        assertEquals(600, n.fuelTicks());
        Map<String, Integer> mix = Map.of("minecraft:raw_iron", 3, PLANKS, 1, TABLE, 1);
        assertNotNull(DryRun.check(List.of(new Step.Smelt("iron_ingot", 3, true)), 0, List.of(n), mix), "a mix is short");
        assertNotNull(n.itemShort(bagOf(mix), "smelt"));
        Map<String, Integer> one = Map.of("minecraft:raw_iron", 3, PLANKS, 2);
        assertNull(DryRun.check(List.of(new Step.Smelt("iron_ingot", 3, true)), 0, List.of(n), one), "one kind covers it");
        assertNull(n.itemShort(bagOf(one), "smelt"));
    }

    /**
     * The chain's burn counts the same rule (z75 review 2, by hand). 3 smelts = 600 ticks; the bag holds 1 oak plank + 1
     * crafting table (both 300 ticks: 600 mixed, 300 of one kind) and no coal; a tree 10 m (4 logs). No kind in the bag covers
     * it (pickFuel: 2 of one kind), so the 300-tick class - the least wood, 2 x 1 plank - is made anew as one kind: the
     * tree, walk 10 m 3.01 + 4 logs x 3.0 = 15.01 s, the walk back to the furnace 3.01 s; the smelts 3 x 10 s = 30 s.
     * 0.39.z77 fuel: the logs (4 planks of wood a piece, 300 ticks like a plank) are out of the class's word, so 1 log is
     * crafted to 4 planks (0.3 s) and 2 oak_planks burn (counted on the smelt step), never 2 logs (8 planks of wood): 48.32 s.
     * The dry run of the plan on that bag finds no shortage.
     */
    @Test
    void i2_theChainBurnsOneKind() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:raw_iron", 3, PLANKS, 1, TABLE, 1);
        Chain c = NeedChain.resolve("iron_ingot", 3, ScenariosTest.w(bag, true, true, Map.of("oak_log", List.of(ScenariosTest.tree(10, 4)))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of(new Step.ChopTree(new int[]{10, 64, 0}, 1, null).text(), "craft oak_planks 4", new Step.Smelt("iron_ingot", 3, true).text()),
                c.steps().stream().map(Step::text).toList(), c.why());
        assertEquals(15.01 + 0.3 + 3.01 + 30, c.seconds(), 0.05, c.why());
        int at = index(c, Step.Smelt.class);
        StepNeeds n = StepNeeds.of(c.steps().get(at)).withUses(c.uses().get(at));
        assertEquals(Map.of("minecraft:raw_iron", 3, PLANKS, 2), n.consumes(), n.text());
        assertEquals(0, n.fuelTicks());
        List<StepNeeds> all = new java.util.ArrayList<>();
        for (int i = 0; i < c.steps().size(); i++) all.add(StepNeeds.of(c.steps().get(i)).withUses(c.uses().get(i)));
        assertNull(DryRun.check(c.steps(), 0, all, bag), "the plan runs on its bag: " + c.why());
    }

    /**
     * z75 review 1: a legitimate two-level use of the bag is unchanged from z74 (the set-aside only touches a recipe way's
     * OWN need). Wooden pickaxe from 3 logs, no table: 3 planks <- 1 log (0.3); 2 sticks <- 2 planks, the bag's 1 left + 1
     * log (0.3) -> 4 sticks (0.3); the table <- 4 planks, the bag's 3 + 1 log (0.3), the table (0.3), placed (0.15); the
     * pickaxe (0.3): 1.95 s, 7 steps.
     */
    @Test
    void review1_sticksAndTableFromTheSameLogs() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, ScenariosTest.w(Map.of("minecraft:oak_log", 3), false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(1.95, c.seconds(), 0.01, c.why());
        assertEquals(List.of(new Step.Craft("oak_planks", 4), new Step.Craft("oak_planks", 4), new Step.Craft("stick", 4), new Step.Craft("oak_planks", 4),
                new Step.Craft("crafting_table", 1), new Step.PlaceNear("crafting_table"), new Step.Craft("wooden_pickaxe", 1)), c.steps());
    }

    /** z75 review 1: a partial bag plus a recipe - 6 planks with 2 in the bag: one craft from the log (4), 0.3 s; 6 sticks with 2: one craft, 0.3 s. */
    @Test
    void review1_aPartialBagPlusARecipe() {
        ScenariosTest.world("wooden", "stone");
        Chain p = NeedChain.resolve("oak_planks", 6, ScenariosTest.w(Map.of(PLANKS, 2, "minecraft:oak_log", 1), false, false, Map.of()));
        assertEquals(List.of(new Step.Craft("oak_planks", 4)), p.steps(), p.why());
        assertEquals(0.3, p.seconds(), 0.01, p.why());
        Chain s = NeedChain.resolve("stick", 6, ScenariosTest.w(Map.of("minecraft:stick", 2, PLANKS, 2), false, false, Map.of()));
        assertEquals(List.of(new Step.Craft("stick", 4)), s.steps(), s.why());
        assertEquals(0.3, s.seconds(), 0.01, s.why());
    }

    /** The fuel explore (nothing burns, nothing known): its until words include #coals (coal ore ends it), not only charcoal's logs. */
    @Test
    void i2_theFuelExploreEndsOnCoalToo() {
        ScenariosTest.world("wooden", "stone");
        LootBook book = new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:coal_ore", "minecraft:coal", 1, 1, false, null),
                new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:oak_log", "minecraft:oak_log", 1, 1, false, null)), "test");
        World w = ScenariosTest.w(Map.of("minecraft:raw_iron", 3), true, true, Map.of()).withMobs(x -> List.of(), book);
        Chain c = NeedChain.resolve("iron_ingot", 3, w);
        assertTrue(c.explore(), c.why());
        Step.Explore e = (Step.Explore) c.steps().stream().filter(s -> s instanceof Step.Explore x && x.until() != null).findFirst().orElseThrow();
        List<String> until = List.of(e.until().split("\\|"));
        boolean coal = false, charcoal = false;
        for (String u : until) {
            coal |= NeedChain.gives(u, "minecraft:coal");
            charcoal |= NeedChain.gives(u, "minecraft:charcoal") || NeedChain.gives(u, "minecraft:oak_log");
        }
        assertTrue(coal && charcoal, "coal and charcoal (its logs) end the explore: " + e.until());
    }

    /**
     * Under ground at y 16, inside coal's band (0..192, one peak at y 96 above the feet): the feet's own level is a strip Y
     * (the slab's ore there > 0), so the chain prices a coal way at y 16 - a tunnel from the feet, no walk; on the surface the
     * peaks only (as before: the lowest above).
     */
    @Test
    void i2_underGroundInCoalsBandTheChainPricesACoalWay() {
        OreBand.Feature lower = new OreBand.Feature(0, 192, 0, 30, 17);
        List<OreBand.Feature> fs = List.of(lower);
        assertEquals(96, OreBand.bestY(fs, 16), "on the surface rule: the peak above");
        assertEquals(16, OreBand.bestY(fs, 16, true), "in the rock: the feet's level");
        assertEquals(96, OreBand.bestY(fs, -11, true), "below the band: the peak above, as before");
        ScenariosTest.world("wooden", "stone");
        StripBand coal = new StripBand(List.of("minecraft:coal_ore"), 0, 192, 16, OreBand.perSlab(fs, 16), 0, 0, 16, "east", "minecraft:stone");
        World w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1, "minecraft:raw_iron", 3, "minecraft:furnace", 1), true, false, Map.of())
                .withStrip(x -> x.contains("coal") ? coal : null);
        Chain c = NeedChain.resolve("iron_ingot", 3, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        Step.Strip st = (Step.Strip) c.steps().stream().filter(s -> s instanceof Step.Strip).findFirst().orElseThrow();
        assertEquals(16, st.y());
        assertTrue(st.ores().contains("minecraft:coal_ore"), st.toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ToStrip), "at the feet: no walk: " + c.steps());
        // z75 review 3, by hand: 1 coal (1600 ticks >= 600) = 1 ore block; the band's blocks per ore at y 16 (the slab's ore)
        // x 1 ore = the tunnel cells, each 2 stone breaks at 0.6 s (hardness 1.5 x 1.5 / 4, the stone pickaxe, in ticks), the
        // ore 1.15 s (3 x 1.5 / 4); the walk 0 s (the feet); the furnace placed 0.15 s; the smelts 3 x 10 s
        int dug = (int) Math.ceil(Math.min(Tuning.get("strip.maxBlocksPerOre"), OreBand.blocksPerOre(OreBand.perSlab(fs, 16))) - 1e-9);
        assertEquals(dug * 2 * 0.6 + 1.15 + 0.15 + 30, c.seconds(), 0.05, "dug " + dug + ": " + c.why());
    }

    /**
     * z75 review 3: a second strip while the chain is down. Iron 3 by the strip from y 76 to 16, the smelt's fuel only by a
     * coal strip at y 60 (no coal, no tree, nothing that burns in the bag): that strip's walk is from where the first leg
     * leaves the bot - not known (dropped, said), and no strip is offered before going down (one leg to another level). The
     * chain's answer: the fuel explore from down there (no band walk, no walk up), the iron strip kept before it.
     */
    @Test
    void review3_aSecondStripWhileDownIsNotKnown() {
        ScenariosTest.world("wooden", "stone");
        StripBand coal = new StripBand(List.of("minecraft:coal_ore"), 0, 192, 60, 1.0, 0, 0, 76, "east", "minecraft:stone");
        World w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1, "minecraft:furnace", 1), true, false, Map.of())
                .withStrip(x -> x.contains("coal") ? coal : x.contains("raw_iron") || x.contains("iron_ore") ? ironAt16From76() : null);
        Chain c = NeedChain.resolve("iron_ingot", 3, w);
        assertTrue(c.explore(), c.why());
        assertEquals(1, c.steps().stream().filter(s -> s instanceof Step.ToStrip).count(), "the iron leg only: " + c.steps());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("strip at y 60: " + NeedChain.FROM_BELOW + "the strip leg to y 16")), c.dropped().toString());
        int down = index(c, Step.ToStrip.class), ex = index(c, Step.Explore.class);
        assertTrue(ex > down, c.steps().toString());
        assertTrue(c.why().contains("from where the strip leg to y 16 leaves the bot"), c.why());
    }

    // ---- metric fix (run AH on z75, seed S1 peaceful) ----

    /**
     * Run AH 19:46:59: "strip at y 67 ... x 0 ore = 0 blocks ... -> 0.0 s" for #coals: the tag word has no loot entry of its
     * own, the yield read NaN and the ore count (int) NaN = 0 - a way that digs nothing, priced 0 s, won, then Job D's repair
     * made the same gap 3 times. With the book's coal_ore -> coal (1 a block): 1 ore for 1 coal, priced by its dig; with no
     * entry for the ore at all: no strip way (said), never one of 0 ore.
     */
    @Test
    void fixA_aTagsStripDigsTheOreItsMemberComesFrom() {
        OreBand.Feature lower = new OreBand.Feature(0, 192, 0, 30, 17);
        List<OreBand.Feature> fs = List.of(lower);
        ScenariosTest.world("wooden", "stone");
        StripBand coal = new StripBand(List.of("minecraft:coal_ore"), 0, 192, 16, OreBand.perSlab(fs, 16), 0, 0, 16, "east", "minecraft:stone");
        LootBook book = new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:coal_ore", "minecraft:coal", 1, 1, false, null)), "test");
        World w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1, "minecraft:raw_iron", 3, "minecraft:furnace", 1), true, false, Map.of())
                .withStrip(x -> x.contains("coal") ? coal : null).withMobs(x -> List.of(), book);
        Chain c = NeedChain.resolve("iron_ingot", 3, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        Step.Strip st = (Step.Strip) c.steps().stream().filter(s -> s instanceof Step.Strip).findFirst().orElseThrow();
        assertEquals(Map.of("coal", 1), st.amounts(), "1 coal ore for the 1 coal the burn needs: " + c.why());
        int dug = (int) Math.ceil(Math.min(Tuning.get("strip.maxBlocksPerOre"), OreBand.blocksPerOre(OreBand.perSlab(fs, 16))) - 1e-9);
        assertEquals(dug * 2 * 0.6 + 1.15 + 0.15 + 30, c.seconds(), 0.05, c.why());

        LootBook none = new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:stone", "minecraft:cobblestone", 1, 1, false, null)), "test");
        Chain n = NeedChain.resolve("iron_ingot", 3, w.withMobs(x -> List.of(), none));
        assertTrue(n.steps().stream().noneMatch(s -> s instanceof Step.Strip), "no strip of unknown yield: " + n.steps());
        assertTrue(n.dropped().stream().anyMatch(d -> d.startsWith("strip at y 16: ") && d.contains("from coal_ore is not known (no loot entry; rule 15)")), n.dropped().toString());
    }

    /**
     * Run AH 19:45:33.781 - 19:46:32.222: the loot book landed, the mining tiers were dropped for a re-read a minute later;
     * meanwhile each stone dig failed "the pickaxe tier ... not known (no mining data loaded yet)" and the chain explored
     * "until #stone_tool_materials", which ended at once on every stone seen (14 times in 30 s). Rule 15: with the tiers not
     * read, the chain waits (no explore step, Chain.waiting) and is priced again when they are; read, the dig is priced.
     */
    @Test
    void fixB_noMiningDataWaitsInsteadOfExploring() {
        TestData d = ScenariosTest.world("wooden", "stone");
        Map<String, List<Spot>> seen = Map.of("cobblestone", List.of(ScenariosTest.STONE_12));
        io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(null);
        Chain waiting = NeedChain.resolve("cobblestone", 3, ScenariosTest.w(Map.of("minecraft:oak_log", 3), false, false, seen));
        assertTrue(waiting.waiting(), waiting.why());
        assertEquals(NeedChain.MINING_WAIT, waiting.missing());
        assertTrue(waiting.steps().stream().noneMatch(s -> s instanceof Step.Explore), waiting.steps().toString());
        d.install();
        Chain priced = NeedChain.resolve("cobblestone", 3, ScenariosTest.w(Map.of("minecraft:oak_log", 3), false, false, seen));
        assertTrue(priced.ok() && !priced.explore(), priced.why());
        assertTrue(priced.steps().stream().anyMatch(s -> s instanceof Step.Collect), priced.steps().toString());
    }

    /**
     * Run AH 19:46:05-33, the explore's done-test is the planner's: a found source re-prices the order where the explore
     * runs (FactChange.found); the same explore picked again - it runs on (no end, no restart); a pick of other steps - the
     * explore is cancelled there and the new steps start.
     */
    @Test
    void fixB_aFoundSourceEndsTheExploreOnlyWhenThePlanUsesIt() {
        Z48ScenarioTest.Hands h = new Z48ScenarioTest.Hands();
        io.github.mojolowjo.entropybot.aa.moves.Steps run = new io.github.mojolowjo.entropybot.aa.moves.Steps(h);
        Step ex = new Step.Explore(5, "#stone_tool_materials|#coals"), craft = new Step.Craft("minecraft:torch", 4);
        Step dig = new Step.Collect("cobblestone", 8);
        List<List<Step>> answers = new java.util.ArrayList<>(List.of(List.of(ex, craft), List.of(dig, craft)));
        run.start("goal.torch", List.of(ex, craft), 0, (failed, fact, why) -> new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(answers.remove(0), null));
        run.repriceAtNextStep(FactChange.found("a source of #stone_tool_materials is known: stone at -37 64 -60 (8 away)"));
        run.tick(1);
        assertEquals(0, h.cancels, "the same explore again: it runs on");
        assertEquals(List.of(ex.text()), h.started, "never restarted");
        run.repriceAtNextStep(FactChange.found("a source of #stone_tool_materials is known: stone at -41 64 -60 (8 away)"));
        run.tick(2);
        assertEquals(1, h.cancels, "the plan uses the stone now: the explore ends");
        assertEquals(dig.text(), h.started.get(h.started.size() - 1));
        assertTrue(io.github.mojolowjo.entropybot.cave.ExploreRules.askAgain("a source of x is known: stone at 1 2 3 (8 away)", null));
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.askAgain("a source of x is known: stone at 1 2 3 (5 away)", "a source of x is known: stone at 1 2 3 (8 away)"), "the same source: asked once");
    }

    /** Project manager: a need of 0 is "have it" before any way; no chain ever carries a way of count 0 (the AH strip "for coal 0"). */
    @Test
    void fixA_noWayOfCountZero() {
        ScenariosTest.world("wooden", "stone");
        Chain none = NeedChain.resolve("coal", 0, ScenariosTest.w(Map.of(), true, true, Map.of()));
        assertTrue(none.ok() && none.steps().isEmpty(), none.why());
        assertNotNull(NeedChain.zeroStep(List.of(new Step.Strip(List.of("minecraft:coal_ore"), Map.of("coal", 0), 67, "north", 0, null))));
        assertNotNull(NeedChain.zeroStep(List.of(new Step.Collect("coal", 0))));
        assertNull(NeedChain.zeroStep(List.of(new Step.Collect("coal", 1), new Step.Craft("minecraft:torch", 4))));
    }

    // ---- take fix and the short rule (run AI on z75) ----

    /**
     * Run AI 20:06:10: the fork's break of the table ended at once ("cluster 1 block: picked up 0 of 1"), the table still
     * standing - the take ends SHORT (its typed fact follows), never a plain stop; a broken table not in the bag is
     * Baritone's pickup and the step waits for the bag; the table in the bag - DONE.
     */
    @Test
    void take_endsOnTheBagNeverOnTheBreak() {
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.Verdict.SHORT, io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(0, false, true, 100, -1), "AI 20:06:10: still stands");
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.shortText("take crafting_table at -34 70 -55", true, "cluster 1 block: picked up 0 of 1", false).contains("short by 1: the station still stands"));
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.Verdict.RUNNING, io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(0, true, true, 100, -1), "the break runs");
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.Verdict.PICKUP, io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(0, false, false, 100, -1), "broken, not in the bag: Baritone's pickup");
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.Verdict.RUNNING, io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(0, false, false, 150, 300), "waiting for the bag");
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.Verdict.SHORT, io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(0, false, false, 300, 300), "the wait is over");
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.Verdict.DONE, io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(1, false, false, 160, 300), "picked up: done");
    }

    /**
     * The take's fact ("take at x y z" held for the order) re-plans with another way: with 2 logs in the bag and no other
     * table, the table is made and placed (planks 0.3 + table 0.3 + place 0.15) before the pickaxe's craft (0.3) - not the
     * same take again.
     */
    @Test
    void take_aFailedTakeRePlansWithANewTable() {
        ScenariosTest.world("wooden", "stone");
        World w = ScenariosTest.w(Map.of("minecraft:oak_log", 2, "minecraft:cobblestone", 3, "minecraft:stick", 2), false, false, Map.of(TABLE, List.of(TABLE_1M)));
        Chain held = NeedChain.resolve("stone_pickaxe", 1, w.withoutSources(Map.of("take at 1 76 0", "take crafting_table at 1 76 0: short by 1: the station still stands")));
        assertTrue(held.ok() && !held.explore(), held.why());
        assertTrue(held.steps().stream().noneMatch(s -> s instanceof Step.TakeStation), held.steps().toString());
        assertEquals(List.of(new Step.Craft("oak_planks", 4), new Step.Craft("crafting_table", 1), new Step.PlaceNear("crafting_table"), new Step.Craft("stone_pickaxe", 1)), held.steps());
        assertEquals(0.3 + 0.3 + 0.15 + 0.3, held.seconds(), 0.01, held.why());
    }

    /**
     * Run AI 20:06:47: the helmet's one table refused ("every known one is refused"), a table in sight still read "near":
     * no more - a refused station is not near, so the chain prices making and placing a new one (the table lost -> a new one).
     */
    @Test
    void take_aRefusedTableIsNotNearAndANewOneIsMade() {
        // 0.39.z78 J5 slice 1: "near" is the lookup alone (the by-name sight and the camp latch are gone, audit D6/B3)
        assertFalse(io.github.mojolowjo.entropybot.aa.StationFact.near(new io.github.mojolowjo.entropybot.aa.StationFact.None(io.github.mojolowjo.entropybot.aa.StationFact.REFUSED_ALL)));
        assertFalse(io.github.mojolowjo.entropybot.aa.StationFact.near(new io.github.mojolowjo.entropybot.aa.StationFact.None("no station placed or scanned")));
        assertTrue(io.github.mojolowjo.entropybot.aa.StationFact.near(new io.github.mojolowjo.entropybot.aa.StationFact.Known(List.of(TABLE_1M))));
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("furnace", 1, ScenariosTest.w(Map.of("minecraft:oak_log", 1, "minecraft:cobblestone", 8), false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of(new Step.Craft("oak_planks", 4), new Step.Craft("crafting_table", 1), new Step.PlaceNear("crafting_table"), new Step.Craft("furnace", 1)), c.steps());
    }

    /**
     * The short rule (rule 7), one case per step kind that can end short: the bag counts what arrived, the fact is "short by
     * N" in the bag slot of what it was for. AI 20:10:05: collect 2 raw_iron, 1 arrived -> short by 1.
     */
    @Test
    void short_everyCountedStepEndsWithATypedFact() {
        ScenariosTest.world("wooden", "stone");
        record Case(Step step, String word, int start, int now, String key, int by) {}
        List<Case> cases = List.of(
                new Case(new Step.Collect("raw_iron", 2), "minecraft:raw_iron", 0, 1, "bag raw_iron", 1),
                new Case(new Step.TakeStation("crafting_table", 1, 76, 0), "minecraft:crafting_table", 0, 0, "bag crafting_table", 1),
                new Case(new Step.ChopTree(new int[]{10, 64, 0}, 4, null), "#" + NeedChain.LOGS_TAG, 0, 2, "bag logs", 2),
                new Case(new Step.Craft("stick", 4), "minecraft:stick", 0, 0, "bag stick", 4),
                new Case(new Step.Smelt("iron_ingot", 3, true), "minecraft:iron_ingot", 1, 2, "bag iron_ingot", 2),
                new Case(new Step.HuntAt("minecraft:cow", 2, new int[]{0, 0}, "beef", 3), "minecraft:beef", 0, 1, "bag beef", 2));
        for (Case k : cases) {
            Map<String, Integer> wants = io.github.mojolowjo.entropybot.aa.moves.StepsShort.wants(k.step());
            assertNotNull(wants, k.step().text());
            FactChange.Fact f = io.github.mojolowjo.entropybot.aa.moves.StepsShort.of(wants, Map.of(k.word(), k.start()), Map.of(k.word(), k.now()), "the hand's end");
            assertNotNull(f, k.step().text());
            assertEquals(k.key(), f.key(), k.step().text());
            assertTrue(f.text().startsWith("short by " + k.by() + " "), f.text());
            assertNull(io.github.mojolowjo.entropybot.aa.moves.StepsShort.of(wants, Map.of(k.word(), 0), Map.of(k.word(), 99), "x"), "all arrived: not short");
        }
        assertNull(io.github.mojolowjo.entropybot.aa.moves.StepsShort.wants(new Step.Walk(0, 0, 0, 0)), "a walk has no count");
    }

    /**
     * AI 20:10:05 end to end (review 2): a collect that ends short at the cluster (feet 3 16 -160) re-plans with the short
     * fact as the item's source NEAR there; fed through the planner like the order does (its own source key held), the
     * second plan does not dig the same cluster (still listed 1 m away, the stale scan) but the next known iron ore 30 m
     * off - walk 30 x 1.3 / 4.317 = 9.03 s + 1 ore by the stone pickaxe - and says the near cluster dropped.
     */
    @Test
    void short_aShortCollectRePlansWithAnotherSource() {
        ScenariosTest.world("wooden", "stone");
        Spot cluster = new Spot(3, 16, -159, 1, "minecraft:iron_ore", 2), far = new Spot(33, 16, -160, 30, "minecraft:iron_ore", 3);
        World w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1, "minecraft:raw_iron", 1), true, true, Map.of("raw_iron", List.of(cluster, far)));
        Chain first = NeedChain.resolve("raw_iron", 2, w);
        assertTrue(first.why().contains("seen 1 m"), "first: the near cluster: " + first.why());
        java.util.Map<String, Integer> bag = new java.util.HashMap<>(Map.of("minecraft:raw_iron", 0));
        Step collect = new Step.Collect("raw_iron", 2);
        List<FactChange.Fact> asked = new java.util.ArrayList<>();
        List<Chain> second = new java.util.ArrayList<>();
        io.github.mojolowjo.entropybot.aa.moves.Steps.Hands h = new io.github.mojolowjo.entropybot.aa.moves.Steps.Hands() {
            int t;
            @Override public String start(Step s) { return null; }
            @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status(Step s) {
                if (s == collect && ++t >= 2) { bag.put("minecraft:raw_iron", 1); return io.github.mojolowjo.entropybot.aa.moves.Car.Status.failed("collect 2 raw_iron: cluster 2 blocks: picked up 1 of 2 (1 drop left, unreachable) (got 1 of 2)"); }
                return io.github.mojolowjo.entropybot.aa.moves.Car.Status.running("running");
            }
            @Override public void cancel(String why) {}
            @Override public FactChange.Fact lastFact() { return null; }
            @Override public StepNeeds.Bag bag() { return bagOf(bag); }
            @Override public int[] feet() { return new int[]{3, 16, -160}; }
        };
        io.github.mojolowjo.entropybot.aa.moves.Steps run = new io.github.mojolowjo.entropybot.aa.moves.Steps(h);
        run.start("goal.iron", List.of(collect), 0, (failed, fact, why) -> {
            asked.add(fact);
            String own = FactChange.ownSourceKey(fact);                 // the order's charge (Goals): the fact's own source key
            Chain c = NeedChain.resolve("raw_iron", 2, w.withoutSources(Map.of(own, fact.text())));
            second.add(c);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(c.steps(), null);
        });
        for (int i = 1; i <= 4; i++) run.tick(i);
        assertEquals(1, asked.size(), "re-planned once: " + run.why());
        assertEquals("source " + NeedChain.nearKey("raw_iron", new int[]{3, 16, -160}), asked.get(0).key());
        assertTrue(asked.get(0).text().startsWith("short by 1 of 2 raw_iron (1 arrived)"), asked.get(0).text());
        Chain c = second.get(0);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("seen 30 m") && !c.why().contains("seen 1 m"), "another source: " + c.why());
    }

    /**
     * Run AI 20:11:05-20:14:16: a frozen walk/explore at the monitor's no-progress verdict writes its facts (Baritone's
     * process, the path and its index, the goal, the feet, fluid, ticks since the last move) as one probe line behind
     * debug.stepProbe; off: no line.
     */
    @Test
    void freeze_theStallFactsLine() {
        for (int probe : new int[]{1, 0}) {
            Tuning.set("debug.stepProbe", probe);
            List<String> lines = new java.util.ArrayList<>();
            int[] second = {0};
            io.github.mojolowjo.entropybot.aa.moves.Steps.Hands h = new io.github.mojolowjo.entropybot.aa.moves.Steps.Hands() {
                @Override public String start(Step s) { return null; }
                @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status(Step s) { return io.github.mojolowjo.entropybot.aa.moves.Car.Status.running("on it"); }
                @Override public void cancel(String why) {}
                @Override public int[] feet() { return new int[]{3, 16, -160}; }
                @Override public io.github.mojolowjo.entropybot.aa.moves.StepSignals signals(Step s) {
                    int k = second[0];
                    return new io.github.mojolowjo.entropybot.aa.moves.StepSignals(true, null, new int[]{3, 16, -160}, 220.6, "GoalXZ", false, false, false, k >= 7 ? 3 : k >= 4 ? 2 : k >= 2 ? 1 : 0, -1, -1, null, 0, -1, null, true);
                }
                @Override public void probe(String line) { lines.add(line); }
                @Override public String stallFacts(Step s) { return "process Custom Goal (active) | pathing true, path 40 moves, at 0 | goal GoalXZ{x=200,z=-160} | feet 3 16 -160 | in fluid no | 600 ticks since the last move"; }
            };
            io.github.mojolowjo.entropybot.aa.moves.Steps run = new io.github.mojolowjo.entropybot.aa.moves.Steps(h);
            boolean[] replanned = {false};
            run.start("k", List.of(new Step.Walk(200, 16, -160, 0)), 0, (f, fact, w) -> { replanned[0] = true; return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(List.of(new Step.Wait(5)), null); });
            for (int s = 1; s <= 20 && !replanned[0]; s++) { second[0] = s; run.tick(s * 20L); }
            assertTrue(replanned[0], run.why());
            long stall = lines.stream().filter(l -> l.startsWith("probe stall: ")).count();
            assertEquals(probe, stall, lines.toString());
            if (probe == 1) assertTrue(lines.stream().anyMatch(l -> l.contains("600 ticks since the last move")), lines.toString());
        }
    }

    /**
     * Run AI: the target an explore froze on, held by position: from the feet it froze at (3 16 -160) the next explore
     * passes over that target's chunk; once the bot stands failures.moved away it may try it again.
     */
    @Test
    void freeze_theFrozenTargetIsPassedOverFromThere() {
        var frozen = List.of(new io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.Failed(200, -160, new int[]{3, 16, -160}, "frozen: no progress 30 s"));
        double moved = io.github.mojolowjo.entropybot.aa.brain.Failures.movedV();
        assertTrue(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.skip(frozen, 200 >> 4, -160 >> 4, new int[]{3, 16, -160}, moved), "the same target from the same spot: passed over");
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.skip(frozen, 180 >> 4, -160 >> 4, new int[]{3, 16, -160}, moved), "another target: open");
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.skip(frozen, 200 >> 4, -160 >> 4, new int[]{3 + (int) Math.ceil(moved), 16, -160}, moved), "moved away: open again");
    }

    /**
     * Review 2: the short fact by step kind with the feet known - a collect: its item's source near the feet (z67 style), a
     * cut: its tree's source (the run charges "cut at x y z"), a craft: the bag slot; a take whose break is not active on
     * its first ticks is not short yet; a held frozen target is released once the bot stands failures.moved away.
     */
    @Test
    void review2_shortKeysTakeGraceAndFrozenRelease() {
        ScenariosTest.world("wooden", "stone");
        int[] feet = {3, 16, -160};
        Step collect = new Step.Collect("raw_iron", 2), cut = new Step.ChopTree(new int[]{10, 64, 0}, 4, null), craft = new Step.Craft("stick", 4);
        assertEquals("source near collect raw_iron at 3 16 -160", io.github.mojolowjo.entropybot.aa.moves.StepsShort.of(collect, Map.of("minecraft:raw_iron", 2), Map.of("minecraft:raw_iron", 0), Map.of("minecraft:raw_iron", 1), feet, "x").key());
        assertEquals("source cut short", io.github.mojolowjo.entropybot.aa.moves.StepsShort.of(cut, io.github.mojolowjo.entropybot.aa.moves.StepsShort.wants(cut), Map.of(), Map.of(), feet, "x").key());
        assertEquals("bag stick", io.github.mojolowjo.entropybot.aa.moves.StepsShort.of(craft, Map.of("minecraft:stick", 4), Map.of(), Map.of(), feet, "x").key());
        assertEquals("near collect raw_iron at 3 16 -160", FactChange.ownSourceKey(new FactChange.Fact("source near collect raw_iron at 3 16 -160", "t")));
        // the take: its break not active on the first second is no end
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.Verdict.RUNNING, io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(0, false, true, 101, -1, 1), "first tick, not active yet");
        assertEquals(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.Verdict.SHORT, io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(0, false, true, 140, -1, 40), "inactive after a second: short");
        // the frozen target's release
        var f = new io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.Failed(200, -160, feet, "frozen");
        double moved = io.github.mojolowjo.entropybot.aa.brain.Failures.movedV();
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.released(f, feet, moved));
        assertTrue(io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.released(f, new int[]{3 + (int) Math.ceil(moved), 16, -160}, moved));
    }

    /**
     * Take 2 (run AJ 20:23:41 torch, 20:24:55 iron_pickaxe): two shorts at one cell hold "take at x y z" for EVERY order -
     * the next order's chain (here a stone pickaxe, 2 logs in the bag, the table 1 m away) makes and places a new table
     * (planks 0.3 + table 0.3 + place 0.15 + the pickaxe 0.3 = 1.05 s), no TakeStation; one short holds nothing for the
     * others (the order's own hold covers it); the bot moved failures.moved, or the table gone: released.
     */
    @Test
    void take2_twoShortsAtOneCellHoldTheTakeForEveryOrder() {
        ScenariosTest.world("wooden", "stone");
        var holds = new io.github.mojolowjo.entropybot.aa.moves.TakeHolds();
        int[] feet = {0, 76, 0};
        double moved = io.github.mojolowjo.entropybot.aa.brain.Failures.movedV();
        io.github.mojolowjo.entropybot.aa.moves.TakeHolds.Cells stands = (x, y, z, item) -> true;
        holds.shortAt(1, 76, 0, TABLE, feet, "short by 1: the station still stands");
        assertTrue(holds.held(feet, stands, moved).isEmpty(), "one short: no hold for every order");
        holds.shortAt(1, 76, 0, TABLE, feet, "short by 1: the station still stands");
        Map<String, String> held = holds.held(feet, stands, moved);
        assertEquals(java.util.Set.of("take at 1 76 0"), held.keySet());
        World w = ScenariosTest.w(Map.of("minecraft:oak_log", 2, "minecraft:cobblestone", 3, "minecraft:stick", 2), false, false, Map.of(TABLE, List.of(TABLE_1M)));
        Chain next = NeedChain.resolve("stone_pickaxe", 1, w.withoutSources(held));
        assertTrue(next.ok() && !next.explore(), next.why());
        assertTrue(next.steps().stream().noneMatch(s -> s instanceof Step.TakeStation), next.steps().toString());
        assertEquals(List.of(new Step.Craft("oak_planks", 4), new Step.Craft("crafting_table", 1), new Step.PlaceNear("crafting_table"), new Step.Craft("stone_pickaxe", 1)), next.steps());
        assertEquals(1.05, next.seconds(), 0.01, next.why());
        assertTrue(holds.held(new int[]{(int) Math.ceil(moved), 76, 0}, stands, moved).isEmpty(), "moved failures.moved: released");
        holds.shortAt(1, 76, 0, TABLE, feet, "x");
        holds.shortAt(1, 76, 0, TABLE, feet, "x");
        assertTrue(holds.held(feet, (x, y, z, item) -> false, moved).isEmpty(), "the table gone: released");
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.TakeEnd.shortText("take crafting_table at 1 76 0", true, "cluster 1 block: picked up 0 of 1", false,
                "on the ground at the start no, eyes 1.6 m from it, beside/above it no liquid or falling block, Baritone's last words: chop: no path to part of the current tree")
                .endsWith("[on the ground at the start no, eyes 1.6 m from it, beside/above it no liquid or falling block, Baritone's last words: chop: no path to part of the current tree]"));
    }

    // ---- 3. counted once ----

    /**
     * Run AE 17:31:10: the helmet takes 5 ingots, the bag holds 3. "5 iron_ingot <- craft: 18 iron_nugget <- craft: 2
     * iron_ingot" counted the bag's 3 twice (2 of them went into the nuggets). Set aside for the helmet, the 3 are not "had"
     * by the nuggets' need: the round trip is a loop, dropped; the 2 missing ingots come from the smelt: 2 x 10 s (200 ticks
     * each) + the helmet's craft 0.3 s at the table in reach (the furnace in reach, the coal in the bag) = 20.3 s.
     */
    @Test
    void i3_theBagIsCountedOnce() {
        TestData d = ScenariosTest.world("wooden", "stone");
        d.recipe("iron_nugget", 9, "iron_ingot");
        d.recipe("iron_ingot", 1, java.util.Collections.nCopies(9, "iron_nugget").toArray(new String[0]));
        d.install();
        Chain c = NeedChain.resolve("iron_helmet", 1, ScenariosTest.w(Map.of("minecraft:iron_ingot", 3, "minecraft:raw_iron", 2, "minecraft:coal", 1), true, true, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && k.item().equals("iron_nugget")), "no nugget round trip: " + c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Smelt k && k.item().equals("iron_ingot") && k.n() == 2), c.steps().toString());
        assertTrue(c.dropped().stream().anyMatch(d2 -> d2.contains("loop: iron_ingot needs itself")), c.dropped().toString());
        assertEquals(20.3, c.seconds(), 0.05, c.why());
    }

    static StepNeeds.Bag bagOf(Map<String, Integer> m) {
        return new StepNeeds.Bag() {
            @Override public int bestTier(String kind) { return 9; }       // any pickaxe the steps ask for
            @Override public String bestTool(String kind) { return null; }
            @Override public int usesLeft(String id) { return 0; }
            @Override public int count(String id) { return m.getOrDefault(id, 0); }
            @Override public Map<String, Integer> items() { return new java.util.HashMap<>(m); }
        };
    }
}
