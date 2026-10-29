package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 bagat (run BY 15:27:03: "step 15 (smelt iron_ingot 3 here) needs 2 of oak_planks; the bag will hold 0 after step
 * 14" with "table <- craft: 4 acacia_planks ... + burn 2 oak_planks (in the bag, the cheapest burn by worth)"; the re-plan gave
 * the same chain, "the same way failed twice", the pickaxe order parked): every pick reads the bag as it will be at its step.
 * A recipe slot that takes any member (the table's #planks) is taken off the chain's bag as the step's own check takes it
 * (DryRun: the need's token first, then the bag's members as they came in) - the 2 planks the bag held go into the table, so
 * the burn after it sees the made ones that are left, never the consumed ones. Hand numbers: a craft batch 0.3 s, a place
 * 0.15 s, a smelt 10 s.
 */
class Z77BagAtTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final String OAK = "minecraft:oak_planks", ACACIA = "minecraft:acacia_planks";

    /** Two woods the vanilla way, the table from any 4 #planks, the furnace from 8 cobblestone, iron from raw iron; the fuel table. */
    static void data() {
        TestData d = new TestData();
        ScenariosTest.wood(d, "minecraft", "oak");
        ScenariosTest.wood(d, "minecraft", "acacia");
        d.recipe("crafting_table", 1, "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks");
        d.recipe("furnace", 1, Collections.nCopies(8, "cobblestone").toArray(new String[0]));
        d.tableItems.add(TestData.id("furnace"));
        d.smelt("iron_ingot", "raw_iron");
        d.fuel("coal", 1600).fuel("crafting_table", 300);
        for (String w : List.of("oak", "acacia")) d.fuel(w + "_log", 300).fuel(w + "_planks", 300);
        d.install();
    }

    /** The bag in its order (the live bag's slots: what was there first comes first). */
    static Map<String, Integer> bag(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Integer) kv[i + 1]);
        return out;
    }

    static int smeltAt(Chain c) {
        for (int i = 0; i < c.steps().size(); i++) if (c.steps().get(i) instanceof Step.Smelt) return i;
        return -1;
    }

    /** Every step's needs with the plan's own counts (what Steps hands the dry run). */
    static List<StepNeeds> needs(Chain c) {
        List<StepNeeds> all = new ArrayList<>();
        for (int i = 0; i < c.steps().size(); i++) all.add(StepNeeds.of(c.steps().get(i)).withUses(c.uses().get(i)));
        return all;
    }

    /**
     * Run BY's shape: 3 raw_iron, 8 cobblestone, 2 oak_planks and 1 acacia_log in the bag, no table or furnace near. The table
     * takes 4 #planks: the bag's 2 oak + 2 of the 4 acacia its log makes (as the dry run and the craft take them), so 2
     * acacia_planks are left when the smelt's fuel is picked: burn 2 acacia_planks (600 ticks for 3 smelts of 200), never the
     * oak the table used. The dry run of the whole chain on that bag finds no shortage (was: "step 6 (smelt iron_ingot 3)
     * needs 2 of oak_planks; the bag will hold 0 after step 5"). Seconds: crafts acacia_planks, table, furnace 3 x 0.3 = 0.9;
     * places 2 x 0.15 = 0.3; smelts 3 x 10 = 30: 31.2 s (the burn from the bag is free).
     */
    @Test
    void by_theBurnNeverPicksPlanksAnEarlierStepUses() {
        data();
        Map<String, Integer> bag = bag("minecraft:raw_iron", 3, "minecraft:cobblestone", 8, OAK, 2, "minecraft:acacia_log", 1);
        Chain c = NeedChain.resolve("iron_ingot", 3, ScenariosTest.w(bag, false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("burn 2 acacia_planks (in the bag, the cheapest burn by worth)"), c.why());
        assertFalse(c.why().contains("burn 2 oak_planks"), "the oak went into the table: " + c.why());
        int at = smeltAt(c);
        assertTrue(at > 0, c.steps().toString());
        StepNeeds smelt = StepNeeds.of(c.steps().get(at)).withUses(c.uses().get(at));
        assertEquals(Map.of("minecraft:raw_iron", 3, ACACIA, 2), smelt.consumes(), smelt.text());
        DryRun.Outcome dry = DryRun.look(c.steps(), 0, needs(c), bag, true);
        assertNull(dry.shortage(), "the plan runs on its bag: " + dry.shortage() + " | " + c.why());
        assertEquals(34.2, c.seconds(), 0.01, c.why());      // owner 2026-10-29: + the table taken along after the furnace's craft (its break 3.75) - the walk back it replaced
    }

    /**
     * No planks left for it: 2 oak + 2 acacia planks and 1 coal in the bag, no table near. The table takes all 4 planks, so
     * the burn is another fuel, the coal (1 = 1600 ticks for 600), never the planks the table used. The dry run finds no
     * shortage.
     */
    @Test
    void by_allPlanksUsedTheBurnIsAnotherFuel() {
        data();
        Map<String, Integer> bag = bag("minecraft:raw_iron", 3, "minecraft:cobblestone", 8, OAK, 2, ACACIA, 2, "minecraft:coal", 1);
        Chain c = NeedChain.resolve("iron_ingot", 3, ScenariosTest.w(bag, false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("burn 1 coal (in the bag"), c.why());
        assertNull(DryRun.look(c.steps(), 0, needs(c), bag, true).shortage(), c.why());
    }

    /**
     * Control: a table near, so no step before the smelt uses the planks - the bag's 2 oak_planks are burnt as before (the
     * smelt's counted fuel), and the dry run finds no shortage. Seconds: the furnace 0.3 + placed 0.15 + 3 smelts 30 = 30.45
     * s plus the walk to the table and back (FakePaths), so only the burn and the dry run are asserted.
     */
    @Test
    void control_planksNoStepUsesAreBurntAsBefore() {
        data();
        Map<String, Integer> bag = bag("minecraft:raw_iron", 3, "minecraft:cobblestone", 8, OAK, 2);
        Chain c = NeedChain.resolve("iron_ingot", 3, ScenariosTest.w(bag, true, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("burn 2 oak_planks (in the bag, the cheapest burn by worth)"), c.why());
        int at = smeltAt(c);
        StepNeeds smelt = StepNeeds.of(c.steps().get(at)).withUses(c.uses().get(at));
        assertEquals(Map.of("minecraft:raw_iron", 3, OAK, 2), smelt.consumes(), smelt.text());
        assertNull(DryRun.look(c.steps(), 0, needs(c), bag, true).shortage(), c.why());
    }

    /**
     * The slot take is the dry run's: a table from #planks with 2 oak in the bag and a log of acacia - the craft's step uses
     * "#planks 4", and what the chain's bag holds after it is what the dry run's bag holds (2 acacia_planks, 0 oak).
     */
    @Test
    void theChainsBagIsTheDryRunsBag() {
        data();
        Map<String, Integer> bag = bag(OAK, 2, "minecraft:acacia_log", 1);
        Chain c = NeedChain.resolve("crafting_table", 1, ScenariosTest.w(bag, false, false, Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        int table = -1;
        for (int i = 0; i < c.steps().size(); i++) if (c.steps().get(i) instanceof Step.Craft k && k.item().equals("crafting_table")) table = i;
        assertTrue(table > 0, c.steps().toString());
        assertEquals(Map.of("#minecraft:planks", 4), c.uses().get(table), "the step's count by its slot word");
        // the dry run up to the table, then the burn of 2 acacia_planks (600 ticks) is covered; 2 oak_planks would not be
        List<Step> steps = new ArrayList<>(c.steps().subList(0, table + 1));
        List<StepNeeds> ns = new ArrayList<>(needs(c).subList(0, table + 1));
        steps.add(new Step.Smelt("iron_ingot", 3, true));
        ns.add(new StepNeeds(null, 0, Map.of(ACACIA, 2), null));
        assertNull(DryRun.check(steps, 0, ns, bag), "2 acacia_planks are left after the table");
        ns.set(ns.size() - 1, new StepNeeds(null, 0, Map.of(OAK, 2), null));
        assertNotNull(DryRun.check(steps, 0, ns, bag), "the oak went into the table");
    }
}
