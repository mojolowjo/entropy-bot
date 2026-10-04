package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.craft.CraftPlanner.AllPlan;
import io.github.mojolowjo.entropybot.craft.CraftPlanner.Target;
import io.github.mojolowjo.entropybot.craft.Crafter.Craft;
import io.github.mojolowjo.entropybot.craft.Crafter.Plan;
import io.github.mojolowjo.entropybot.craft.Crafter.Smelt;
import io.github.mojolowjo.entropybot.craft.Crafter.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.mojolowjo.entropybot.craft.FakeRecipes.alts;
import static io.github.mojolowjo.entropybot.craft.FakeRecipes.m;
import static org.junit.jupiter.api.Assertions.*;

/** Package D: the planner fixes (TO-LOOK-AT-LATER 15 and the DEVNOTES "planner quirk"). */
class PlannerFixesTest {
    static final String MA = "mysticalagriculture:";
    static final String CRYSTAL = MA + "infusion_crystal";
    static final String[] TIERS = {"inferium", "prudentium", "tertium", "imperium", "supremium"};

    static String ess(String tier) {
        return MA + tier + "_essence";
    }

    /** Mystical Agriculture's tiers: 4 lower + a crystal (kept) in cells 2/4/6/8 + 5 -> 1 higher, and each one back apart. */
    static FakeRecipes essences() {
        FakeRecipes f = new FakeRecipes();
        f.withCatalysts(CRYSTAL);
        for (int i = 1; i < TIERS.length; i++) {
            String lower = ess(TIERS[i - 1]), higher = ess(TIERS[i]);
            f.shaped(MA + "essence/" + TIERS[i], higher, 1, new String[] {".e.", "ece", ".e."}, 'e', alts(lower), 'c', alts(CRYSTAL));
            f.shapeless(MA + "essence/" + TIERS[i - 1] + "_uncraft", lower, 4, alts(higher));
        }
        return f;
    }

    static Map<String, Integer> counts(Object... kv) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) c.put(m((String) kv[i]), (Integer) kv[i + 1]);
        return c;
    }

    final CraftPlanner ma = new CraftPlanner(essences());

    @Test
    void breakdownsAreTold() {
        FakeRecipes f = essences();
        assertTrue(ma.isBreakdown(f.recipe(MA + "essence/tertium_uncraft")), "imperium -> 4 tertium");
        assertFalse(ma.isBreakdown(f.recipe(MA + "essence/tertium")), "4 prudentium + crystal -> tertium builds up");
        CraftPlanner vanilla = new CraftPlanner(CraftPlannerTest.world());
        FakeRecipes w = CraftPlannerTest.world();
        assertTrue(vanilla.isBreakdown(w.recipe("minecraft:iron_ingot_from_iron_block")), "by its shape: a block taken apart, the block made of ingots");
        assertFalse(vanilla.isBreakdown(w.recipe("minecraft:iron_block")));
        assertFalse(vanilla.isBreakdown(w.recipe("minecraft:oak_planks")), "a log is not made of planks: not a breakdown");
        assertEquals(List.of(new Crafter.Need(List.of(ess("prudentium")), 4)), ma.usedNeeds(f.recipe(MA + "essence/tertium")), "the crystal is not used up");
    }

    @Test
    void anUncraftIsRefusedWhileTheLowerTierIsInStock() {
        Map<String, Integer> c = counts(ess("prudentium"), 2, ess("imperium"), 1, CRYSTAL, 1);
        Map<String, Integer> before = new LinkedHashMap<>(c);
        Plan p = ma.plan(ess("tertium"), 1, c);
        assertFalse(p.ok(), "the owner's lost supremium: never break a higher tier down while the lower tier is there");
        assertEquals("missing 2 " + ess("prudentium") + " (not breaking down " + ess("imperium") + " for it: I have " + ess("prudentium") + ", the lower tier)", p.error());
        assertEquals(before, c, "nothing used");
        // with enough of the lower tier the up recipe is the one, the higher tier is left alone
        c = counts(ess("prudentium"), 4, ess("imperium"), 1, CRYSTAL, 1);
        p = ma.plan(ess("tertium"), 1, c);
        assertEquals(List.of(new Craft(MA + "essence/tertium", ess("tertium"), 1, 1, true)), p.steps());
        assertEquals(1, c.get(ess("imperium")));
        // with none of the lower tier anywhere a breakdown is still allowed (the owner's rule names the lower tier)
        c = counts(ess("imperium"), 1);
        p = ma.plan(ess("tertium"), 4, c);
        assertTrue(p.ok(), p.error());
        assertEquals(MA + "essence/tertium_uncraft", ((Craft) p.steps().get(0)).recipeId());
    }

    @Test
    void theCatalystIsKeptNotConsumed() {
        Map<String, Integer> c = counts(ess("inferium"), 64, CRYSTAL, 1);
        AllPlan r = ma.planAll(List.of(new Target(ess("tertium"), 4)), c);
        assertTrue(r.ok(), r.error());
        assertEquals("16 " + ess("prudentium") + " -> 4 " + ess("tertium"), CraftPlanner.describeSteps(r.steps()));
        assertEquals(1, r.counts().get(CRYSTAL), "the crystal comes back from the grid");
        assertEquals(0, r.counts().get(ess("inferium")));
        assertEquals(Set.of(CRYSTAL), r.catalysts());
        assertEquals(16, ((Craft) r.steps().get(0)).times(), "16 crafts with one crystal");
        // the crystal in storage only: the fetch takes one along (a plain difference would miss it)
        Map<String, Integer> inv = counts(ess("inferium"), 64), storage = counts(CRYSTAL, 2);
        Map<String, Integer> combined = CraftTexts.combine(inv, storage);
        AllPlan r2 = ma.planAll(List.of(new Target(ess("tertium"), 4)), new LinkedHashMap<>(combined));
        assertTrue(r2.ok(), r2.error());
        assertEquals(Map.of(CRYSTAL, 1), CraftTexts.fromStorage(combined, r2.counts(), inv, r2.catalysts()));
        assertTrue(CraftTexts.needReply(ma, ess("tertium") + " 4", inv, storage, true).endsWith("from storage: 1 " + CRYSTAL + " (kept, not used up)"));
        assertEquals("missing 1 " + CRYSTAL + " (not breaking down " + ess("tertium") + " for it: I have " + ess("inferium") + ", the lower tier)",
                ma.plan(ess("prudentium"), 1, counts(ess("inferium"), 4)).error(), "no crystal anywhere: it says so");
    }

    @Test
    void theTierClimbIsPlannedBottomUp() {
        // package E's "upgrade": 256 inferium -> 64 prudentium -> 16 tertium -> 4 imperium -> 1 supremium
        Map<String, Integer> c = counts(ess("inferium"), 256, CRYSTAL, 1);
        Plan p = ma.plan(ess("supremium"), 1, c);
        assertTrue(p.ok(), p.error());
        assertEquals("64 " + ess("prudentium") + " -> 16 " + ess("tertium") + " -> 4 " + ess("imperium") + " -> 1 " + ess("supremium"),
                CraftPlanner.describeSteps(p.steps()));
        assertEquals(counts(ess("inferium"), 0, CRYSTAL, 1, ess("prudentium"), 0, ess("tertium"), 0, ess("imperium"), 0, ess("supremium"), 1), c);
    }

    @Test
    void theCircularImperiumPlanIsRefused() {
        // DEVNOTES: "need imperium_essence 4" printed "4 imperium -> 16 tertium -> ... -> 4 imperium" using a supremium
        Map<String, Integer> c = counts(ess("supremium"), 1, CRYSTAL, 1);
        Plan p = ma.plan(ess("imperium"), 4, c);
        assertTrue(p.ok(), p.error());
        for (Step s : p.steps()) assertNotEquals(ess("tertium"), s.item(), "never imperium -> tertium -> imperium");
        assertEquals(List.of(new Craft(MA + "essence/imperium_uncraft", ess("imperium"), 4, 1, false)), p.steps(), "the supremium taken apart, once");
        // tertium in stock (the lower tier): the supremium stays whole, and the answer says why
        c = counts(ess("supremium"), 1, CRYSTAL, 1, ess("tertium"), 8);
        p = ma.plan(ess("imperium"), 4, c);
        assertFalse(p.ok());
        assertEquals("missing 8 " + ess("tertium") + " (not breaking down " + ess("supremium") + " for it: I have " + ess("tertium") + ", the lower tier)", p.error());
        // the item itself is never an ingredient further down the path
        Map<String, Integer> only = counts(ess("imperium"), 1);
        assertFalse(ma.plan(ess("imperium"), 2, only).ok(), "2 imperium from 1 imperium: no circle through tertium");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // a deep Refined Storage chain (the 16k disk), planned bottom-up
    // ---------------------------------------------------------------------------------------------------------------

    static final String RS = "refinedstorage:";

    static FakeRecipes rs() {
        FakeRecipes f = new FakeRecipes();
        f.smelting(RS + "silicon", RS + "silicon", 1, alts("quartz"));
        f.smelting("glass", "glass", 1, alts("sand"));
        f.shapeless(RS + "raw_basic_processor", RS + "raw_basic_processor", 1, alts("iron_ingot"), alts(RS + "silicon"), alts(RS + "processor_binding"));
        f.smelting(RS + "basic_processor", RS + "basic_processor", 1, alts(RS + "raw_basic_processor"));
        f.shapeless(RS + "raw_improved_processor", RS + "raw_improved_processor", 1, alts("gold_ingot"), alts(RS + "silicon"), alts(RS + "processor_binding"));
        f.smelting(RS + "improved_processor", RS + "improved_processor", 1, alts(RS + "raw_improved_processor"));
        f.shaped(RS + "1k_storage_part", RS + "1k_storage_part", 1, new String[] {"srs", "rpr", "srs"},
                's', alts(RS + "silicon"), 'r', alts("redstone"), 'p', alts(RS + "basic_processor"));
        f.shaped(RS + "4k_storage_part", RS + "4k_storage_part", 1, new String[] {"bkb", "kgk", "bkb"},
                'b', alts(RS + "basic_processor"), 'k', alts(RS + "1k_storage_part"), 'g', alts("glass"));
        f.shaped(RS + "16k_storage_part", RS + "16k_storage_part", 1, new String[] {"iki", "kgk", "iki"},
                'i', alts(RS + "improved_processor"), 'k', alts(RS + "4k_storage_part"), 'g', alts("glass"));
        f.shaped(RS + "16k_storage_disk", RS + "16k_storage_disk", 1, new String[] {"grg", "rpr", "iii"},
                'g', alts("glass"), 'r', alts("redstone"), 'p', alts(RS + "16k_storage_part"), 'i', alts("iron_ingot"));
        f.item("coal");
        return f;
    }

    @Test
    void aDeepRsChainIsPlannedBottomUp() {
        FakeRecipes f = rs();
        CraftPlanner p = new CraftPlanner(f);
        Map<String, Integer> c = counts("quartz", 200, "sand", 40, "iron_ingot", 64, "gold_ingot", 16, "redstone", 200,
                RS + "processor_binding", 64, "coal", 64);
        Plan plan = p.plan(RS + "16k_storage_disk", 1, c);
        assertTrue(plan.ok(), plan.error());
        assertEquals(1, c.get(RS + "16k_storage_disk"));
        // bottom-up: every ingredient a step uses that the plan makes is made by an earlier step
        Map<String, Integer> made = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (Step s : plan.steps()) {
            List<String> uses = new ArrayList<>();
            if (s instanceof Smelt sm) uses.add(sm.input());
            else for (List<String> cell : f.recipe(((Craft) s).recipeId()).cells()) uses.addAll(cell);
            for (String u : uses) {
                boolean madeInPlan = false;
                for (Step t : plan.steps()) madeInPlan |= t.item().equals(u);
                if (madeInPlan) assertTrue(made.containsKey(u), s.item() + " uses " + u + " before a step made it");
            }
            made.merge(s.item(), s.want(), Integer::sum);
            order.add(CraftPlanner.shortId(s.item()));
        }
        assertTrue(order.indexOf(RS + "1k_storage_part") < order.indexOf(RS + "4k_storage_part"));
        assertTrue(order.indexOf(RS + "4k_storage_part") < order.indexOf(RS + "16k_storage_part"));
        assertEquals(RS + "16k_storage_disk", order.get(order.size() - 1));
        assertTrue(plan.steps().size() >= 8, "six levels: disk <- 16k <- 4k <- 1k <- processor <- raw <- silicon: " + order);
    }

    @Test
    void aSearchWithNoEndStopsAtTheBudget() {
        // every item needs one of 40 others a level down, nothing is in stock: a wide failing search
        FakeRecipes f = new FakeRecipes();
        for (int d = 0; d < 10; d++) {
            for (int k = 0; k < 40; k++) {
                List<String> next = new ArrayList<>();
                for (int j = 0; j < 40; j++) next.add("x:l" + (d + 1) + "_" + j);
                f.shapeless("x:l" + d + "_" + k, "x:l" + d + "_" + k, 1, next);
            }
        }
        CraftPlanner p = new CraftPlanner(f);
        long t0 = System.nanoTime();
        Plan plan = p.plan("x:l0_0", 1, new LinkedHashMap<>());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(CraftPlanner.tooDeep("x:l0_0"), plan.error());
        assertTrue(ms < 3000, "gave up in " + ms + " ms");
    }
}
