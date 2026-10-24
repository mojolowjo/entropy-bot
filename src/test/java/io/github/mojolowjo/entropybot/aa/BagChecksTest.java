package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.ToolUses;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/SCENARIOS.md S39 (0.39.z34, owner 2026-10-23: "when it is doing a job we need more checks on the inventory during
 * it"): a collect runs with a stone pickaxe at 1 use; it breaks after the first block; the step's bag look ends it with
 * "stone_pickaxe gone (broke) ...", the order re-plans with the pickaxe's craft first and completes. No game: the planner
 * on the scenario's facts, the step machine on fake hands whose bag is a map.
 */
class BagChecksTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final Spot STONE_12 = new Spot(12, 60, 3, 12, "minecraft:stone", 400);

    /** Fake game: the bag a map; a collect gets one block a tick while a pickaxe is there; the first use breaks the worn one. */
    static class Game implements Steps.Hands {
        final Map<String, Integer> bag = new HashMap<>();
        final Map<String, Integer> uses = new HashMap<>();
        final List<String> ran = new ArrayList<>(), cancelled = new ArrayList<>();
        Step running;
        int got;
        Car.Status now;

        @Override public String start(Step s) {
            ran.add(s.text());
            running = s;
            got = 0;
            now = Car.Status.done("ok");
            if (s instanceof Step.Craft k && k.item().contains("stone_pickaxe")) {
                bag.merge("minecraft:cobblestone", -3, Integer::sum);
                bag.merge("minecraft:stick", -2, Integer::sum);
                bag.merge("minecraft:stone_pickaxe", 1, Integer::sum);
                uses.put("minecraft:stone_pickaxe", 131);
            }
            return null;
        }

        @Override public Car.Status status(Step s) {
            if (!(s instanceof Step.Collect k)) return now;
            if (bag.getOrDefault("minecraft:stone_pickaxe", 0) > 0 && got < k.n()) {
                got++;
                bag.merge("minecraft:cobblestone", 1, Integer::sum);
                int u = uses.merge("minecraft:stone_pickaxe", -1, Integer::sum);
                if (u <= 0) bag.remove("minecraft:stone_pickaxe");       // the game removes a tool whose uses run out
            }
            // Baritone's mine without a pickaxe: it keeps "running" (no drop); only the bag look ends it
            return got >= k.n() ? Car.Status.done("ok: got " + got) : Car.Status.running(got + " of " + k.n());
        }

        @Override public void cancel(String why) { cancelled.add(why); }

        @Override public StepNeeds.Bag bag() {
            return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) {
                    int t = 0;
                    for (var e : bag.entrySet()) if (e.getValue() > 0) t = Math.max(t, MiningTiers.toolTier(e.getKey(), kind));
                    return t;
                }

                @Override public String bestTool(String kind) {
                    String id = null;
                    int t = 0;
                    for (var e : bag.entrySet()) if (e.getValue() > 0 && MiningTiers.toolTier(e.getKey(), kind) > t) { t = MiningTiers.toolTier(e.getKey(), kind); id = e.getKey(); }
                    return id;
                }

                @Override public int usesLeft(String id) { return uses.getOrDefault(id, ToolUses.UNLIMITED); }

                @Override public int count(String id) { return bag.getOrDefault(id, 0); }
            };
        }
    }

    static World world(Game g) {
        Map<String, List<Spot>> ids = new HashMap<>(Map.of("minecraft:cobblestone", List.of(STONE_12)));
        return new World(new HashMap<>(g.bag), true, false, w -> ScenariosTest.lookup(ids, w), null, null, null, 0, null,
                k -> ItemWorth.CRAFT.equals(k) ? new Spot(2, 64, 0, 2, "minecraft:crafting_table") : null);
    }

    static Game s39Game() {
        Game g = new Game();
        g.bag.put("minecraft:stone_pickaxe", 1);
        g.bag.put("minecraft:cobblestone", 3);
        g.bag.put("minecraft:stick", 2);
        g.uses.put("minecraft:stone_pickaxe", 1);
        return g;
    }

    /**
     * S39 by hand: order 6 cobblestone, bag 3 + a stone pickaxe at 1 use (its durability not counted at plan: the plan is the
     * collect alone). The first block breaks it (bag 4, no pickaxe); the next bag look (job.bagCheckTicks 20 ticks) ends the
     * collect with "stone_pickaxe gone (broke), the collect needs a pickaxe of tier >= 1"; the re-plan crafts a stone
     * pickaxe from 3 of the 4 cobblestone + 2 sticks at the table 2 m, then collects the 5 left; the order completes.
     */
    @Test
    void s39_aPickaxeThatBreaksMidCollectEndsTheStepAndTheRePlanCraftsOneFirst() {
        ScenariosTest.world("wooden", "stone");
        Game g = s39Game();
        Chain first = NeedChain.resolve("cobblestone", 6, world(g));
        assertTrue(first.ok() && !first.explore(), first.why());
        assertTrue(first.steps().stream().noneMatch(s -> s instanceof Step.Craft), first.steps().toString());
        FactChange.Fact[] seen = new FactChange.Fact[1];
        List<Chain> replans = new ArrayList<>();
        Steps run = new Steps(g);
        run.start("owner goal", first.steps(), 0, (failed, fact, why) -> {
            seen[0] = fact;
            Chain c = NeedChain.resolve("cobblestone", 6, world(g));
            replans.add(c);
            return new Steps.Routine(c.ok() ? c.steps() : null, c.why());
        });
        String collectLine = null;
        int t = 1;
        for (; t < 400 && run.running(); t++) {
            if (collectLine == null && run.stepText() != null && run.stepText().contains("collect")) collectLine = run.stepText();
            run.tick(t);
        }
        assertEquals(Steps.Phase.DONE, run.phase(), run.why());
        assertNotNull(collectLine);
        assertTrue(collectLine.matches(".*collect minecraft:cobblestone \\d+ \\[needs: pickaxe tier>=1\\]") || collectLine.matches(".*collect cobblestone \\d+ \\[needs: pickaxe tier>=1\\]"), collectLine);
        assertNotNull(seen[0]);
        assertEquals("bag pickaxe tier>=1", seen[0].key());
        assertEquals("stone_pickaxe gone (broke), the collect needs a pickaxe of tier >= 1", seen[0].text());
        assertEquals(1, replans.size());
        List<String> texts = replans.get(0).steps().stream().map(Step::text).toList();
        int craft = -1, collect = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (craft < 0 && texts.get(i).startsWith("craft") && texts.get(i).contains("stone_pickaxe")) craft = i;
            if (collect < 0 && texts.get(i).startsWith("collect")) collect = i;
        }
        assertTrue(craft >= 0 && collect > craft, texts.toString());
        // 0.39.z37: the re-plan collects 5 (the bag's 4 less the 3 the new pickaxe takes, 6 asked): the order's count stands
        assertEquals(6, g.bag.get("minecraft:cobblestone"), g.bag.toString());
        assertEquals(1, g.bag.get("minecraft:stone_pickaxe"), g.bag.toString());
        assertTrue(t < Tuning.get("steps.stepMaxS") * 20, "ended by the bag look, not the step's time limit: tick " + t);
        assertTrue(g.cancelled.stream().anyMatch(c -> c.startsWith("stone_pickaxe gone (broke)")), g.cancelled.toString());
    }

    /** No look before job.bagCheckTicks: the tool step runs on between looks (the cost: one bag pass per look). */
    @Test
    void theBagLookRunsEveryBagCheckTicksOnly() {
        ScenariosTest.world("wooden", "stone");
        Game g = s39Game();
        Steps run = new Steps(g);
        run.start("k", List.of(new Step.Collect("minecraft:cobblestone", 5)), 0);
        int every = Tuning.i("job.bagCheckTicks");
        for (int t = 1; t < every; t++) run.tick(t);
        assertTrue(run.running(), run.why());
        run.tick(every);
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertTrue(run.why().contains("stone_pickaxe gone (broke), the collect needs a pickaxe of tier >= 1"), run.why());
    }

    /** Before the start: no tool at all is the bag fact, said; the step's hands never start. */
    @Test
    void aToolStepWithNoToolIsTheBagFactBeforeItStarts() {
        ScenariosTest.world("wooden", "stone");
        Game g = new Game();
        g.bag.put("minecraft:cobblestone", 3);
        Steps run = new Steps(g);
        run.start("k", List.of(new Step.Collect("minecraft:cobblestone", 5)), 0);
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertTrue(run.why().contains("no pickaxe of tier >= 1 in the bag, the collect needs a pickaxe of tier >= 1"), run.why());
        assertTrue(g.ran.isEmpty(), g.ran.toString());
    }

    /** A craft that ends short with its recipe's input gone (another craft took it) is the bag fact and re-plans. */
    @Test
    void aCraftEndingShortOfItsRecipeInputsIsTheBagFact() {
        ScenariosTest.world("wooden", "stone");
        StepNeeds n = StepNeeds.of(new Step.Craft("minecraft:stone_pickaxe", 1));
        assertEquals(Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2), n.consumes(), n.text());
        assertEquals("[needs: cobblestone 3, stick 2]", n.text().replace("stick 2, cobblestone 3", "cobblestone 3, stick 2"));
        Game g = new Game() {
            @Override public String start(Step s) { ran.add(s.text()); return null; }

            @Override public Car.Status status(Step s) { return Car.Status.failed("craft ended: not enough"); }
        };
        g.bag.put("minecraft:cobblestone", 1);
        g.bag.put("minecraft:stick", 2);
        FactChange.Fact[] seen = new FactChange.Fact[1];
        Steps run = new Steps(g);
        run.start("k", List.of(new Step.Craft("minecraft:stone_pickaxe", 1)), 0, (f, fact, why) -> { seen[0] = fact; return new Steps.Routine(null, "no way"); });
        run.tick(1);
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertNotNull(seen[0]);
        assertEquals("bag cobblestone", seen[0].key());
        assertEquals("cobblestone: 1 of 3 in the bag, the craft uses 3", seen[0].text());
    }

    /** Rule 15: no mining data is said in the line and checks nothing. */
    @Test
    void noMiningDataIsSaidNotChecked() {
        MiningTiers.use(null);
        StepNeeds n = StepNeeds.of(new Step.Collect("minecraft:cobblestone", 2));
        assertFalse(n.hasTool());
        assertEquals("[needs: not loaded: no mining data]", n.text());
    }

    /** 0.39.z37: Mine KNOWN / DIG / CAVE declare the family's tier (not STRIP), a collect of a tag the least tier of its members, a smelt its fuel. */
    @Test
    void z37MineFamilyTagCollectAndSmeltFuelAreDeclared() {
        TestData d = ScenariosTest.world("wooden", "stone");
        for (Step.Mine.How how : List.of(Step.Mine.How.KNOWN, Step.Mine.How.DIG, Step.Mine.How.CAVE)) {
            StepNeeds n = StepNeeds.of(new Step.Mine("iron", 3, how));
            assertTrue(n.hasTool() && n.minTier() == 2 && n.toolKind().equals("pickaxe"), how + " " + n.text());
        }
        assertEquals(StepNeeds.NONE, StepNeeds.of(new Step.Mine("iron", 3, Step.Mine.How.STRIP)));
        assertEquals("[needs: not loaded: no block of the family nickel in the mining data]", StepNeeds.of(new Step.Mine("nickel", 1, Step.Mine.How.KNOWN)).text());
        d.tag("iron_ore", "minecraft:z37_set").tag("coal_ore", "minecraft:z37_set");
        StepNeeds tag = StepNeeds.of(new Step.Collect("#minecraft:z37_set", 2));
        assertTrue(tag.hasTool() && tag.minTier() == 1, "the least tier over the members: " + tag.text());
        d.tag("dirt", "minecraft:z37_set");
        assertEquals(StepNeeds.NONE, StepNeeds.of(new Step.Collect("#minecraft:z37_set", 2)), "a member that needs no pickaxe");
        StepNeeds smelt = StepNeeds.of(new Step.Smelt("minecraft:cooked_beef", 2, true));
        assertEquals(400, smelt.fuelTicks(), smelt.text());
        Game g = new Game();
        g.bag.put("minecraft:beef", 2);
        assertTrue(smelt.itemShort(g.bag(), "smelt").startsWith("fuel: 0 of 400 burn ticks"), smelt.itemShort(g.bag(), "smelt"));
        g.bag.put("minecraft:coal", 1);
        assertNull(smelt.itemShort(g.bag(), "smelt"), "any item with a burn time, from the fuel table");
    }
}