package io.github.mojolowjo.entropybot.craft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.craft.CraftPlanner.AllPlan;
import io.github.mojolowjo.entropybot.craft.CraftPlanner.Target;
import io.github.mojolowjo.entropybot.craft.Crafter.Craft;
import io.github.mojolowjo.entropybot.craft.Crafter.Plan;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Collect;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Job;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Next;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Put;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Slots;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.mojolowjo.entropybot.craft.FakeRecipes.alts;
import static io.github.mojolowjo.entropybot.craft.FakeRecipes.m;
import static io.github.mojolowjo.entropybot.craft.PlannerFixesTest.CRYSTAL;
import static io.github.mojolowjo.entropybot.craft.PlannerFixesTest.MA;
import static io.github.mojolowjo.entropybot.craft.PlannerFixesTest.counts;
import static io.github.mojolowjo.entropybot.craft.PlannerFixesTest.ess;
import static org.junit.jupiter.api.Assertions.*;

/** Package D, after the review: the findings 1-5 and 8 as tests (7 is in FurnaceChainTest). */
class ReviewFixesTest {
    static final String OW = "minecraft:overworld";
    static final int[] F1 = {-32, 53, 183};
    static final Crafter.Smelt IRON = new Crafter.Smelt("minecraft:iron_ingot", m("iron_ingot"), 40, m("raw_iron"), 40, m("coal"), 5);
    static final long T0 = 1_000_000_000L;

    // ---- 1: the lower tier in storage counts too ----

    @Test
    void aSupremiumInTheBagIsNotTakenApartWhilePrudentiumWaitsInStorage() {
        CraftPlanner ma = new CraftPlanner(PlannerFixesTest.essences());
        Map<String, Integer> bag = counts(ess("supremium"), 1, CRYSTAL, 1), storage = counts(ess("prudentium"), 64);
        Map<String, Integer> combined = CraftTexts.combine(bag, storage);
        // the bag-only pass alone would take it apart (supremium -> imperium -> tertium): the old bug
        AllPlan blind = ma.planAll(List.of(new Target(ess("tertium"), 1)), new LinkedHashMap<>(bag));
        assertTrue(blind.ok(), "without the stock the breakdown looks allowed");
        // with the stock: refused on the bag pass, planned from storage on the second
        AllPlan r = ma.planAll(List.of(new Target(ess("tertium"), 1)), new LinkedHashMap<>(bag), combined);
        assertFalse(r.ok());
        assertTrue(r.error().contains("not breaking down " + ess("imperium") + " for it: I have " + ess("prudentium")), r.error());
        String need = CraftTexts.needReply(ma, ess("tertium") + " 1", bag, storage, true);
        assertEquals("I can make it: 1 " + ess("tertium") + "; from storage: 4 " + ess("prudentium"), need, "4 prudentium + the crystal, the supremium untouched");
        Plan sm = ma.planSmelt(ess("tertium"), 1, new LinkedHashMap<>(bag), combined);
        assertEquals(CraftPlanner.NO_SMELTING, sm.error(), "the smelt overload takes the stock too (no furnace recipe here)");
    }

    // ---- 2: unpacking a block is not a tier breakdown ----

    static FakeRecipes ironWithNuggets() {
        FakeRecipes f = CraftPlannerTest.world();
        f.shaped("iron_ingot_from_nuggets", "iron_ingot", 1, new String[]{"nnn", "nnn", "nnn"}, 'n', alts("iron_nugget"));
        f.shapeless("iron_nugget", "iron_nugget", 9, alts("iron_ingot"));
        f.shapeless("bone_meal_from_bone_block", "bone_meal", 9, alts("bone_block"));
        f.shaped("bone_block", "bone_block", 1, new String[]{"bbb", "bbb", "bbb"}, 'b', alts("bone_meal"));
        f.shapeless("bone_meal", "bone_meal", 3, alts("bone"));
        return f;
    }

    @Test
    void aNuggetInStockDoesNotStopABlockFromBeingUnpacked() {
        FakeRecipes f = ironWithNuggets();
        CraftPlanner p = new CraftPlanner(f);
        assertTrue(p.isBreakdown(f.recipe("minecraft:iron_ingot_from_iron_block")), "still tried after the recipes that build up");
        assertFalse(p.isTierBreakdown(f.recipe("minecraft:iron_ingot_from_iron_block")), "but not a tier: the lower-tier rule doesn't apply");
        assertTrue(new CraftPlanner(PlannerFixesTest.essences()).isTierBreakdown(PlannerFixesTest.essences().recipe(MA + "essence/tertium_uncraft")));
        Plan plan = p.plan(m("iron_ingot"), 9, counts("iron_nugget", 3, "iron_block", 1));
        assertTrue(plan.ok(), plan.error());
        assertEquals(List.of(new Craft("minecraft:iron_ingot_from_iron_block", m("iron_ingot"), 9, 1, false)), plan.steps());
        // a hopper's worth of iron stored as blocks, with a nugget somewhere
        Plan hopper = p.plan(m("iron_ingot"), 5, counts("iron_nugget", 1, "iron_block", 1));
        assertTrue(hopper.ok(), hopper.error());
        // bone meal: bones in stock (the up recipe) and a bone block -> 9 is still allowed when the bones aren't enough
        Plan bm = p.plan(m("bone_meal"), 9, counts("bone", 1, "bone_block", 1));
        assertTrue(bm.ok(), bm.error());
        // a tier breakdown found by its shape, without "uncraft" in its id: the reverse needs the crystal
        FakeRecipes e = new FakeRecipes().withCatalysts(CRYSTAL);
        e.shaped(MA + "imperium_up", ess("imperium"), 1, new String[]{".e.", "ece", ".e."}, 'e', alts(ess("tertium")), 'c', alts(CRYSTAL));
        e.shapeless(MA + "tertium_from_imperium", ess("tertium"), 4, alts(ess("imperium")));
        assertTrue(new CraftPlanner(e).isTierBreakdown(e.recipe(MA + "tertium_from_imperium")));
    }

    // ---- 3: never another player's ingots at a shared furnace ----

    @Test
    void aSharedFurnaceGivesNothingThatMayNotBeOurs() {
        FurnaceJobs fj = new FurnaceJobs(new JsonObject(), null);
        Job j = fj.add(F1, OW, IRON, FurnaceJobs.CRAFT, null, T0);
        // someone put their raw iron in after ours: input went up between two looks
        Collect up = FurnaceJobs.collect(new Slots(m("raw_iron"), 38, null, 0, m("iron_ingot"), 4), j, 0, 40, T0 + 50_000, T0, 30);
        assertEquals(Next.GONE, up.next());
        assertEquals(0, up.take());
        // more in it than we put in: someone else's ingots in the output
        Collect more = FurnaceJobs.collect(new Slots(m("raw_iron"), 30, null, 0, m("iron_ingot"), 20), j, 0, 40, T0 + 100_000, T0, -1);
        assertEquals(Next.GONE, more.next(), "30 + 20 > the 40 I put in");
        assertEquals(0, more.take());
        // our own: 10 done, 30 left, the step needs 6 -> only 6
        Collect ours = FurnaceJobs.collect(new Slots(m("raw_iron"), 30, null, 0, m("iron_ingot"), 10), j, 0, 6, T0 + 100_000, T0, 31);
        assertEquals(new Collect(Next.TAKE, 6, null), ours, "a TAKE takes only what the step needs");
        fj.collected(j, 6);
        assertEquals(new Collect(Next.TAKE, 4, null), FurnaceJobs.collect(new Slots(m("raw_iron"), 30, null, 0, m("iron_ingot"), 4), j, 0, 34, T0 + 101_000, T0, 30));
    }

    // ---- 4: the fuel slot ----

    @Test
    void anotherFuelMakesItBusyOurFuelIsToppedUp() {
        assertEquals(Put.BUSY, FurnaceJobs.check(new Slots(null, 0, m("oak_planks"), 3, null, 0), m("coal"), null).put(), "never burn someone's planks");
        assertEquals("its fuel slot holds 1 bucket, not my coal", FurnaceJobs.check(new Slots(null, 0, m("bucket"), 1, null, 0), m("coal"), null).why(), "an empty bucket would stall it");
        assertEquals(Put.FREE, FurnaceJobs.check(new Slots(null, 0, m("coal"), 2, null, 0), m("coal"), null).put(), "the same fuel: topped up");
        assertEquals(Put.FREE, FurnaceJobs.check(Slots.EMPTY, m("coal"), null).put());
    }

    // ---- 5 and 8: stalled jobs, leftovers ----

    @Test
    void aStalledJobIsLeftOutOfThePickupsUntilAskedByHand() {
        FurnaceJobs fj = new FurnaceJobs(new JsonObject(), null);
        Job j = fj.add(F1, OW, IRON, FurnaceJobs.SMELT, null, T0);
        assertEquals(1, fj.due(j.dueAt, OW).size());
        fj.markStalled(j);
        assertTrue(fj.due(j.dueAt + 3_600_000L, OW).isEmpty(), "never retried by itself");
        fj.unstall(List.of(j));
        assertEquals(1, fj.due(j.dueAt + 1000, OW).size(), "a manual \"smelt collect\" tries it again");
        fj.later(List.of(j), j.dueAt, 30 * 60_000L);
        assertTrue(fj.due(j.dueAt + 10 * 60_000L, OW).isEmpty(), "a full bag: half an hour");
    }

    @Test
    void aForgottenJobsLeftoverIsOursToClearNotBusyForever() {
        JsonObject root = new JsonObject();
        FurnaceJobs fj = new FurnaceJobs(root, null);
        Job j = fj.add(F1, OW, IRON, FurnaceJobs.SMELT, null, T0);
        fj.collected(j, 10);
        fj.forget(j);                                   // "smelt forget" (or expiry): 30 may still be in it
        FurnaceJobs again = new FurnaceJobs(JsonParser.parseString(root.toString()).getAsJsonObject(), null);
        FurnaceJobs.Leftover lo = again.leftover(F1, OW);
        assertNotNull(lo, "remembered across a restart");
        assertEquals(30, lo.count());
        assertEquals(Put.CLEAR_FIRST, FurnaceJobs.check(new Slots(null, 0, null, 0, m("iron_ingot"), 30), m("coal"), lo).put());
        assertEquals(Put.BUSY, FurnaceJobs.check(new Slots(null, 0, null, 0, m("iron_ingot"), 31), m("coal"), lo).put(), "more than ours: someone else's");
        assertEquals(Put.BUSY, FurnaceJobs.check(new Slots(m("raw_iron"), 5, null, 0, m("iron_ingot"), 25), m("coal"), lo).put(), "still smelting: wait");
        again.clearLeftover(F1, OW);
        assertNull(again.leftover(F1, OW));
        // expiry leaves one too
        FurnaceJobs ex = new FurnaceJobs(new JsonObject(), null);
        Job k = ex.add(F1, OW, IRON, FurnaceJobs.SMELT, null, T0);
        ex.expire(k.dueAt + FurnaceJobs.EXPIRE_MS + 1, T0);
        assertEquals(40, ex.leftover(F1, OW).count());
    }
}
