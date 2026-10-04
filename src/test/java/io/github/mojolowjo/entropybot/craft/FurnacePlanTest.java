package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.craft.Crafter.Craft;
import io.github.mojolowjo.entropybot.craft.Crafter.Smelt;
import io.github.mojolowjo.entropybot.craft.Crafter.Step;
import io.github.mojolowjo.entropybot.craft.FurnacePlan.Action;
import io.github.mojolowjo.entropybot.craft.FurnacePlan.Collect;
import io.github.mojolowjo.entropybot.craft.FurnacePlan.CraftRun;
import io.github.mojolowjo.entropybot.craft.FurnacePlan.Start;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.mojolowjo.entropybot.craft.FakeRecipes.alts;
import static io.github.mojolowjo.entropybot.craft.FakeRecipes.m;
import static org.junit.jupiter.api.Assertions.*;

/** Package D: a plan's furnace steps run while the bot crafts what doesn't need them (the "efficiency mode"). */
class FurnacePlanTest {
    static final Smelt IRON = new Smelt("minecraft:iron_ingot", m("iron_ingot"), 4, m("raw_iron"), 4, m("coal"), 1);
    static final Smelt GLASS = new Smelt("minecraft:glass", m("glass"), 3, m("sand"), 3, m("coal"), 1);

    static FakeRecipes recipes() {
        FakeRecipes f = new FakeRecipes();
        f.shapeless("x:binding", "x:binding", 1, alts("iron_ingot"), alts("iron_ingot"), alts("iron_ingot"), alts("iron_ingot"));
        f.shapeless("x:disk", "x:disk", 1, alts("x:binding"), alts("glass"), alts("glass"), alts("glass"));
        f.shapeless("x:stick", "x:stick", 4, alts("oak_planks"), alts("oak_planks"));
        return f;
    }

    static final Craft BINDING = new Craft("x:binding", "x:binding", 1, 1, false);
    static final Craft DISK = new Craft("x:disk", "x:disk", 1, 1, false);
    static final Craft STICKS = new Craft("x:stick", "x:stick", 4, 1, false);

    static String show(List<Action> as) {
        List<String> out = new ArrayList<>();
        for (Action a : as) {
            if (a instanceof Start s) out.add("start " + CraftPlanner.shortId(s.smelt().item()));
            else if (a instanceof Collect c) out.add("collect " + CraftPlanner.shortId(c.smelt().item()) + (c.all() ? " all" : " " + c.need()));
            else for (Craft c : ((CraftRun) a).crafts()) out.add("craft " + c.item());
        }
        return String.join(", ", out);
    }

    @Test
    void bothFurnacesRunWhileTheBotCrafts() {
        FakeRecipes r = recipes();
        List<Step> plan = List.of(IRON, STICKS, BINDING, GLASS, DISK);
        assertEquals("start iron_ingot, start glass, craft x:stick, collect iron_ingot 4, craft x:binding, collect glass 3, craft x:disk",
                show(FurnacePlan.order(plan, r::recipe, true, true)),
                "efficient: two furnaces at once, the sticks while they work, each output right before the step that needs it");
        assertEquals("start iron_ingot, collect iron_ingot all, craft x:stick, craft x:binding, start glass, collect glass all, craft x:disk",
                show(FurnacePlan.order(plan, r::recipe, false, true)), "wait: the old way, each furnace in place");
    }

    @Test
    void theSmeltVerbLeavesItsTargetToThePickup() {
        FakeRecipes r = recipes();
        assertEquals("start iron_ingot", show(FurnacePlan.order(List.of(IRON), r::recipe, true, false)), "the job ends once the furnace runs");
        assertEquals("start iron_ingot, collect iron_ingot all", show(FurnacePlan.order(List.of(IRON), r::recipe, false, true)));
        assertEquals("start iron_ingot, collect iron_ingot all", show(FurnacePlan.order(List.of(IRON), r::recipe, true, true)), "a craft collects what was asked for");
    }

    @Test
    void aFurnaceStepFedByAnotherWaitsForItsOutput() {
        FakeRecipes f = PlannerFixesTest.rs();
        CraftPlanner p = new CraftPlanner(f);
        Map<String, Integer> c = new LinkedHashMap<>(Map.of(m("quartz"), 1, m("iron_ingot"), 1, "refinedstorage:processor_binding", 1, m("coal"), 4));
        Crafter.Plan plan = p.plan("refinedstorage:basic_processor", 1, c);
        assertTrue(plan.ok(), plan.error());
        assertEquals("start refinedstorage:silicon, collect refinedstorage:silicon 1, craft refinedstorage:raw_basic_processor, start refinedstorage:basic_processor,"
                + " collect refinedstorage:basic_processor all", show(FurnacePlan.order(plan.steps(), f::recipe, true, true)));
        // furnace into furnace with nothing to craft between
        Smelt a = new Smelt("x:a", "x:mid", 2, "x:ore", 2, m("coal"), 1), b = new Smelt("x:b", "x:end", 2, "x:mid", 2, m("coal"), 1);
        assertEquals("start mid, collect mid 2, start end, collect end all".replace("mid", "x:mid").replace("end", "x:end"),
                show(FurnacePlan.order(List.of(a, b), f::recipe, true, true)));
    }

    @Test
    void theRsDiskChainIsQuickerInterleaved() {
        FakeRecipes f = PlannerFixesTest.rs();
        CraftPlanner p = new CraftPlanner(f);
        Map<String, Integer> c = new LinkedHashMap<>();
        for (Object[] kv : new Object[][]{{"quartz", 200}, {"sand", 40}, {"iron_ingot", 64}, {"gold_ingot", 16}, {"redstone", 200},
                {"refinedstorage:processor_binding", 64}, {"coal", 64}}) c.put(m((String) kv[0]), (Integer) kv[1]);
        Crafter.Plan plan = p.plan("refinedstorage:16k_storage_disk", 1, c);
        assertTrue(plan.ok(), plan.error());
        List<Action> fast = FurnacePlan.order(plan.steps(), f::recipe, true, true), slow = FurnacePlan.order(plan.steps(), f::recipe, false, true);
        // every step is still there, and each output is collected before the step that uses it
        assertEquals(count(slow, Start.class), count(fast, Start.class));
        assertEquals(count(slow, CraftRun.class, true), count(fast, CraftRun.class, true));
        long tFast = FurnacePlan.estimateTicks(fast, 200, 60, 200), tSlow = FurnacePlan.estimateTicks(slow, 200, 60, 200);
        System.out.println("[package D] 16k disk chain: sequential ~" + tSlow / 20 + " s, interleaved ~" + tFast / 20 + " s ("
                + plan.steps().size() + " steps, " + count(fast, Start.class) + " furnace steps)");
        assertTrue(tFast < tSlow, "interleaved " + tFast + " < sequential " + tSlow);
    }

    static int count(List<Action> as, Class<?> k) {
        return count(as, k, false);
    }

    static int count(List<Action> as, Class<?> k, boolean crafts) {
        int n = 0;
        for (Action a : as) if (k.isInstance(a)) n += crafts ? ((CraftRun) a).crafts().size() : 1;
        return n;
    }
}
