package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.craft.CraftPlanner.AllPlan;
import io.github.mojolowjo.entropybot.craft.CraftPlanner.Target;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.mojolowjo.entropybot.craft.PlannerFixesTest.CRYSTAL;
import static io.github.mojolowjo.entropybot.craft.PlannerFixesTest.MA;
import static io.github.mojolowjo.entropybot.craft.PlannerFixesTest.counts;
import static io.github.mojolowjo.entropybot.craft.PlannerFixesTest.ess;
import static org.junit.jupiter.api.Assertions.*;

/** Package E: "upgrade <essence> [n]" - names, refusals, rounds sized to the bag, and the batching it gets from GridLoop. */
class UpgradeTest {
    final FakeRecipes f = PlannerFixesTest.essences();
    final CraftPlanner ma = new CraftPlanner(f);

    Upgrade.Parsed parse(String t) {
        return Upgrade.parse(t, q -> ma.resolveItem(q, Map.of()));
    }

    @Test
    void theTiersByName() {
        Upgrade.Parsed p = parse("imperium 4");
        assertTrue(p.ok(), p.error());
        assertEquals(ess("imperium"), p.id());
        assertEquals(3, p.tier());
        assertEquals(4, p.n());
        assertEquals(1, parse("supremium_essence").n());
        assertEquals(ess("supremium"), parse("supremium essence 2").id());
        assertEquals(ess("tertium"), parse(MA + "tertium_essence 2").id());
        assertEquals(Upgrade.MAX_N, parse("prudentium 999999").n(), "capped");
        assertTrue(parse("inferium 64").error().startsWith("error: inferium is the lowest tier"));
        assertTrue(parse("dirt 3").error().startsWith("error: dirt is not an essence tier I know"));
        assertEquals(Upgrade.USAGE, parse("").error());
        assertEquals(Upgrade.USAGE, parse("imperium lots").error());
        assertEquals("error: upgrade needs a count of 1 or more", parse("imperium 0").error());
        assertTrue(parse("insanium 1").error().startsWith("error: I don't know insanium_essence here"), "no Mystical Agradditions in this pack");
        assertEquals(2, Upgrade.tierOf(ess("tertium")));
        assertEquals(-1, Upgrade.tierOf("minecraft:dirt"));
    }

    @Test
    void theMath() {
        assertEquals(256, Upgrade.perUnit(4, 0));
        assertEquals(4, Upgrade.perUnit(1, 0));
        assertEquals(85, Upgrade.crafts(1, 4, 0), "TO-LOOK-AT-LATER 15: 1 supremium = 85 crafts");
        assertEquals(340, Upgrade.crafts(4, 4, 0));
        assertEquals(5, Upgrade.crafts(1, 3, 1), "imperium from prudentium: 4 tertium crafts + 1");
        assertEquals(3 + 1 + 2 + 1, Upgrade.roundSlots(Map.of("a", 130, "b", 0), List.of(64, 65)), "3 for the 130, 1 + 2 for the outputs, + the margin");
        assertEquals(0, Upgrade.roundSize(10, 3, k -> 4), "not even one fits");
        assertEquals(10, Upgrade.roundSize(10, 30, k -> 1));
        assertEquals(7, Upgrade.roundSize(100, 30, k -> 4 * k + 2));
        assertEquals(0, Upgrade.roundSize(5, 30, k -> Integer.MAX_VALUE), "can't be planned");
    }

    @Test
    void theRecipeAndItsCrystal() {
        RecipeData up = Upgrade.tierUp(ma, ess("supremium"));
        assertNotNull(up);
        assertEquals(MA + "essence/supremium", up.id(), "the build-up recipe, never the uncraft");
        assertEquals(List.of(CRYSTAL), Upgrade.crystals(ma, up));
        assertNull(Upgrade.tierUp(ma, ess("inferium")), "nothing makes inferium from a lower tier");
        assertEquals("error: I know no recipe that makes mysticalagriculture:inferium_essence from the tier below with an infusion crystal",
                Upgrade.noRecipe(ess("inferium")));
        assertTrue(Upgrade.noCrystal(List.of(CRYSTAL, MA + "master_infusion_crystal"), true)
                .startsWith("error: I need mysticalagriculture:infusion_crystal or mysticalagriculture:master_infusion_crystal to upgrade essence"));
        assertTrue(Upgrade.crystalWorn(CRYSTAL, 40, 85).contains("has 40 uses left and this takes about 85 crafts"));
        // the planner may take a supremium apart when no lower tier is anywhere: upgrade refuses that plan
        AllPlan apart = ma.planAll(List.of(new Target(ess("imperium"), 4)), counts(ess("supremium"), 1, CRYSTAL, 1));
        assertTrue(apart.ok(), apart.error());
        assertNotNull(Upgrade.breakdownIn(ma, apart.steps()));
        String why = Upgrade.notUp(ma, apart.steps(), ess("imperium"));
        assertEquals("it would take a higher tier apart for 4 mysticalagriculture:imperium_essence", why);
        assertTrue(Upgrade.notUpRefusal(4, ess("imperium"), why).startsWith("error: I can't upgrade to 4 mysticalagriculture:imperium_essence by building up: it would take"));
        AllPlan up2 = ma.planAll(List.of(new Target(ess("imperium"), 1)), counts(ess("inferium"), 64, CRYSTAL, 1));
        assertNull(Upgrade.breakdownIn(ma, up2.steps()), "building up is fine");
        assertNull(Upgrade.notUp(ma, up2.steps(), ess("imperium")));
    }

    @Test
    void unpackingTheTargetsOwnBlockIsNotAnUpgrade() {
        // prudentium blocks in storage, no inferium: the planner would unpack a block - that is not "made"
        FakeRecipes g = PlannerFixesTest.essences();
        String block = MA + "prudentium_block";
        g.shaped(MA + "prudentium_block", block, 1, new String[]{"ppp", "ppp", "ppp"}, 'p', List.of(ess("prudentium")));
        g.shapeless(MA + "prudentium_essence_from_block", ess("prudentium"), 9, List.of(block));
        CraftPlanner pl = new CraftPlanner(g);
        Map<String, Integer> inv = new LinkedHashMap<>(), combined = counts(block, 10, CRYSTAL, 1);
        Upgrade.RoundPlan rp = Upgrade.roundPlan(pl, ess("prudentium"), 9, inv, combined);
        assertNotNull(rp);
        assertEquals("making mysticalagriculture:prudentium_essence would use up mysticalagriculture:prudentium_block (the same tier or higher)",
                Upgrade.notUp(pl, rp.plan().steps(), ess("prudentium")));
        assertEquals(Integer.MAX_VALUE, Upgrade.slotsFor(pl, ess("prudentium"), 9, inv, combined), "a round never sizes such a plan");
        assertEquals(0, Upgrade.roundSize(9, 30, k -> Upgrade.slotsFor(pl, ess("prudentium"), k, inv, combined)));
        // the lower tier's block is fine: tertium from prudentium blocks
        Upgrade.RoundPlan rp2 = Upgrade.roundPlan(pl, ess("tertium"), 2, inv, combined);
        assertNotNull(rp2);
        assertNull(Upgrade.notUp(pl, rp2.plan().steps(), ess("tertium")));
        assertEquals(1, Upgrade.tierOfAny(block));
    }

    @Test
    void theWornCheckLooksAtTheCrystalTheGridTakes() {
        String master = MA + "master_infusion_crystal";
        // menu order: inventory rows, then the hotbar; GridLayout takes the alternative there is most of, GridLoop its first stack
        assertEquals(1, Upgrade.crystalSlot(List.of("", CRYSTAL, master, CRYSTAL), List.of(CRYSTAL, master)));
        assertEquals(2, Upgrade.crystalSlot(List.of("", CRYSTAL, master, master), List.of(CRYSTAL, master)));
        assertEquals(1, Upgrade.crystalSlot(List.of("", CRYSTAL, master), List.of(CRYSTAL, master)), "a tie: the first alternative");
        assertEquals(-1, Upgrade.crystalSlot(List.of("", "minecraft:dirt"), List.of(CRYSTAL)));
    }

    @Test
    void roundsFitTheBag() {
        // the RS network holds plenty of inferium and the crystal; the bag has 30 free slots
        Map<String, Integer> inv = new LinkedHashMap<>(), combined = counts(ess("inferium"), 20000, CRYSTAL, 1);
        // a supremium round: 4 stacks of inferium a supremium (+ the intermediates, the crystal, the margin)
        int k = Upgrade.roundSize(10, 30, n -> Upgrade.slotsFor(ma, ess("supremium"), n, inv, combined));
        assertEquals(4, k, "4 supremium = 16 stacks of inferium + 4 + 1 + 1 + 1 of the tiers + the crystal + 1");
        assertTrue(Upgrade.slotsFor(ma, ess("supremium"), 5, inv, combined) > 30);
        // prudentium: 4 inferium each, 352 a round
        assertEquals(352, Upgrade.roundSize(1000, 30, n -> Upgrade.slotsFor(ma, ess("prudentium"), n, inv, combined)));
        // with tertium in the bag the climb starts there: far fewer slots
        Map<String, Integer> inv2 = counts(ess("tertium"), 64, CRYSTAL, 1);
        assertEquals(1 + 1, Upgrade.slotsFor(ma, ess("imperium"), 16, inv2, CraftTexts.combine(inv2, combined)),
                "nothing fetched (the bag's 64 tertium do): 16 imperium = 1 slot, + the margin");
        assertEquals(Integer.MAX_VALUE, Upgrade.slotsFor(ma, ess("supremium"), 1, Map.of(), counts(ess("inferium"), 10)), "too little anywhere");
    }

    @Test
    void fourSupremiumAreSevenFillsNotThreeHundredForty() {
        // the plan for 4 supremium from 1024 inferium and the crystal, crafted step by step with GridLoop on a fake table
        Map<String, Integer> c = counts(ess("inferium"), 1024, CRYSTAL, 1);
        AllPlan p = ma.planAll(List.of(new Target(ess("supremium"), 4)), c);
        assertTrue(p.ok(), p.error());
        assertEquals(340, Upgrade.crystalCrafts(ma, p.steps()));
        Map<String, Integer> bag = counts(ess("inferium"), 1024, CRYSTAL, 1);
        int fills = 0;
        for (Crafter.Step s : p.steps()) {
            Crafter.Craft cr = (Crafter.Craft) s;
            RecipeData r = f.recipe(cr.recipeId());
            GridLoopTest.Table t = new GridLoopTest.Table(3, r, 2);
            for (Map.Entry<String, Integer> e : bag.entrySet()) if (e.getValue() > 0) t.give(e.getKey(), e.getValue());
            t.keeps = CRYSTAL;
            GridLoop loop = new GridLoop(r, 3, cr.want(), 0);
            GridLoop.Out[] o = new GridLoop.Out[1];
            GridLoopTest.run(loop, t, 4000, o, GridLoopTest.crystalItems(true));
            assertEquals(GridLoop.State.DONE, o[0].state(), cr.item());
            fills += loop.batches();
            bag.clear();
            for (String id : List.of(ess("inferium"), ess("prudentium"), ess("tertium"), ess("imperium"), ess("supremium"), CRYSTAL)) bag.put(id, t.has(id));
        }
        assertEquals(4, bag.get(ess("supremium")));
        assertEquals(1, bag.get(CRYSTAL), "kept");
        assertEquals(0, bag.get(ess("inferium")));
        assertEquals(4 + 1 + 1 + 1, fills, "256 prudentium in 4 fills, then one fill a tier");
    }

    @Test
    void theTexts() {
        assertEquals("upgrading to 16 mysticalagriculture:imperium_essence (round 2, 8 made)", Upgrade.label(16, ess("imperium"), 2, 8));
        assertEquals("made 4 mysticalagriculture:supremium_essence in 1 round; the mysticalagriculture:infusion_crystal is in my bag (659 uses left)",
                Upgrade.done(4, ess("supremium"), 1, CRYSTAL, 659));
        assertTrue(Upgrade.bagFull(8, 16, ess("imperium")).startsWith("my inventory is too full for another round after making 8 of 16"));
        assertTrue(Upgrade.cantPlan(4, ess("supremium"), "missing 20 x").startsWith("error: I can't upgrade to 4 mysticalagriculture:supremium_essence - missing 20 x"));
    }
}
