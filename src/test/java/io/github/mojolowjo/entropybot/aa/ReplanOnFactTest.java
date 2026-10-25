package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/SCENARIOS.md S31 (0.39.z30, owner 2026-10-23): a job planned with a known table; the table fact becomes none
 * mid-job; the failed step re-plans the order from the facts of now and the job goes on. No game: the planner on the
 * scenario's facts, the step machine on fake hands that say the changed fact the way the craft job does (FactChange).
 */
class ReplanOnFactTest {
    static final double TOL = 0.1;
    static final Spot TABLE_20 = new Spot(20, 64, 0, 20, "minecraft:crafting_table");

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    /** The world now: the bag and the table memory as the fake game holds them. */
    static World world(Map<String, Integer> bag, Spot table, Map<String, List<Spot>> seen) {
        Map<String, List<Spot>> ids = new HashMap<>(seen);
        // as the live needWorld: a station known anywhere counts (tableKnown), and stationAt says where
        return io.github.mojolowjo.entropybot.aa.FakePaths.paths(new World(bag, table != null, false, w -> ScenariosTest.lookup(ids, w), null, null, null, 0, null, k -> ItemWorth.CRAFT.equals(k) ? table : null));
    }

    static final Map<String, List<Spot>> TREE_10 = Map.of("minecraft:oak_log", List.of(ScenariosTest.tree(10, 1)));

    /** Fake game: every step is done at once and lands in the bag; a table craft with no table known fails like CraftJob.noTable. */
    static final class Game implements Steps.Hands {
        final Map<String, Integer> bag = new HashMap<>();
        Spot table = TABLE_20;
        Map<String, List<Spot>> seen = Map.of();
        final List<String> ran = new ArrayList<>();
        Car.Status now;

        @Override public String start(Step s) {
            ran.add(s.text());
            if (s instanceof Step.Craft k && k.item().contains("wooden_pickaxe") && table == null) {
                FactChange.station(ItemWorth.CRAFT, "crafting table", null);
                return "error: wooden_pickaxe needs a crafting table and none is known anywhere (my placements, Baritone's scan)";
            }
            if (s.text().startsWith("cut")) { bag.merge("minecraft:oak_log", 1, Integer::sum); seen = Map.of(); }
            if (s instanceof Step.Craft k && k.item().contains("planks")) { bag.merge("minecraft:oak_log", -1, Integer::sum); bag.merge("minecraft:oak_planks", 4, Integer::sum); }
            if (s instanceof Step.Craft k && k.item().contains("crafting_table")) { bag.merge("minecraft:oak_planks", -4, Integer::sum); bag.merge("minecraft:crafting_table", 1, Integer::sum); }
            if (s instanceof Step.PlaceNear) { bag.merge("minecraft:crafting_table", -1, Integer::sum); table = new Spot(1, 64, 0, 1, "minecraft:crafting_table"); }
            if (s instanceof Step.Craft k && k.item().contains("wooden_pickaxe")) { bag.merge("minecraft:oak_planks", -3, Integer::sum); bag.merge("minecraft:stick", -2, Integer::sum); bag.merge("minecraft:wooden_pickaxe", 1, Integer::sum); }
            now = Car.Status.done("ok");
            return null;
        }

        @Override public Car.Status status(Step s) { return now; }

        @Override public void cancel(String why) {}
    }

    static Chain plan(Game g) { return NeedChain.resolve("wooden_pickaxe", 1, world(g.bag, g.table, g.seen)); }

    /**
     * S31 by hand (TIME_MODEL, rule 14 rough): bag 3 planks + 2 sticks, a 1-log tree 10 m. Planned with the table known
     * 20 m: walk 20 x 1.3 / 4.317 = 6.02 + craft 0.3 = 6.32 s (making a table instead: cut 3.01 + 3.0, planks 0.3, table 0.3,
     * place 0.15, pickaxe 0.3 = 7.06 s, dearer). The table is gone at the craft: the re-plan is that 7.06 s way.
     */
    @Test
    void s31_aTableGoneMidJobRePlansToMakeAndPlaceOneAndTheOrderFinishes() {
        ScenariosTest.world("wooden");
        Game g = new Game();
        g.bag.put("minecraft:oak_planks", 3);
        g.bag.put("minecraft:stick", 2);
        g.seen = TREE_10;
        Chain first = plan(g);
        assertTrue(first.ok() && !first.explore(), first.why());
        assertEquals(6.32, first.seconds(), TOL, first.why());
        assertTrue(first.steps().stream().noneMatch(s -> s instanceof Step.PlaceNear), first.steps().toString());

        g.table = null;                                  // the stale check forgot it: the block there is deepslate now
        List<Chain> replans = new ArrayList<>();
        Steps run = new Steps(g);
        // the order's planner: the same resolve on the facts of now
        Steps.Replanner rp = (failed, fact, why) -> {
            assertEquals("station " + ItemWorth.CRAFT, fact.key());
            Chain c = plan(g);
            replans.add(c);
            return new Steps.Routine(c.ok() ? c.steps() : null, c.ok() ? null : c.why());
        };
        run.start("owner goal", first.steps(), 0, rp);
        for (int t = 1; t < 20 && run.running(); t++) run.tick(t);
        assertEquals(Steps.Phase.DONE, run.phase(), run.why());
        assertEquals(1, replans.size());
        Chain second = replans.get(0);
        assertEquals(7.06, second.seconds(), TOL, second.why());
        List<String> texts = second.steps().stream().map(Step::text).toList();
        int table = indexOf(texts, "crafting_table"), place = indexOfPlace(second.steps()), pick = indexOf(texts, "wooden_pickaxe");
        assertTrue(table >= 0 && place > table && pick > place, texts.toString());
        assertEquals(1, g.bag.get("minecraft:wooden_pickaxe"));
    }

    @Test
    void aRePlanWithNoWayStopsWithTheStepsOwnReason() {
        Game g = new Game();
        g.table = null;
        Steps run = new Steps(g);
        run.start("owner goal", List.of(new Step.Craft("minecraft:wooden_pickaxe", 1)), 0);
        assertEquals(Steps.Phase.FAILED, run.phase());       // no replanner yet: as before
        assertTrue(run.why().contains("needs a crafting table and none is known anywhere"), run.why());

        // item 4: the re-plan on the real planner finds no table and no way to make one (3 planks, no logs seen)
        ScenariosTest.world("wooden");
        g.bag.put("minecraft:oak_planks", 3);
        g.bag.put("minecraft:stick", 2);
        Steps run2 = new Steps(g);
        run2.start("owner goal", List.of(new Step.Craft("minecraft:wooden_pickaxe", 1)), 0, (f, fact, why) -> {
            Chain c = plan(g);
            return new Steps.Routine(c.ok() && !c.explore() ? c.steps() : null, c.why());
        });
        assertEquals(Steps.Phase.FAILED, run2.phase());
        assertTrue(run2.why().contains("wooden_pickaxe needs a crafting table and none is known anywhere (my placements, Baritone's scan)"), run2.why());
        assertTrue(run2.why().contains("(re-plan: "), run2.why());
    }

    /**
     * 0.39.z70 (D2 3.1): the same try again - the same step, the same station fact, from the same cell, the same bag - parks
     * the run with the honest-loop words (z30's "the same step failed again" by text is gone).
     */
    @Test
    void theSameTryAgainAfterARePlanParksWithTheHonestWords() {
        Game g = new Game();
        g.table = null;
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return g.start(s); }
            @Override public Car.Status status(Step s) { return g.status(s); }
            @Override public void cancel(String why) {}
            @Override public int[] feet() { return new int[]{0, 64, 0}; }
            @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag bag() {
                return new io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag() {
                    @Override public int bestTier(String kind) { return 0; }
                    @Override public String bestTool(String kind) { return null; }
                    @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                    @Override public int count(String id) { return g.bag.getOrDefault(id, 0); }
                };
            }
        });
        Step craft = new Step.Craft("minecraft:wooden_pickaxe", 1);
        int[] asked = {0};
        run.start("owner goal", List.of(craft), 0, (f, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(craft), null); });
        for (int t = 1; t < 10 && run.running(); t++) run.tick(t);
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertEquals(1, asked[0]);
        assertTrue(run.why().contains("the same way failed twice from the same place with the same bag: craft minecraft:wooden_pickaxe 1 - "), run.why());
        assertTrue(run.why().contains("(re-plans this run: 1; dropped: none)"), run.why());
    }

    @Test
    void aFailureWithNoChangedFactIsNotRePlanned() {
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return s instanceof Step.Wait ? null : "error: the grid did not make it"; }
            @Override public Car.Status status(Step s) { return Car.Status.done("ok"); }
            @Override public void cancel(String why) {}
        });
        run.start("owner goal", List.of(new Step.Craft("minecraft:stick", 4)), 0, (f, fact, why) -> fail("no fact, no re-plan"));
        for (int t = 1; t < 5 && run.running(); t++) run.tick(t);
        assertEquals(Steps.Phase.FAILED, run.phase());
    }

    /** Rule 15: a remembered table whose chunk is not loaded is priced as remembered and the step says it is unverified. */
    @Test
    void aStationNotLoadedIsPricedAsRememberedAndMarkedUnverified() {
        ScenariosTest.world("wooden");
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2);
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, world(bag, TABLE_20.notLoaded(), Map.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(6.32, c.seconds(), TOL, c.why());
        assertTrue(c.why().contains(NeedChain.UNVERIFIED), c.why());
        assertFalse(NeedChain.resolve("wooden_pickaxe", 1, world(bag, TABLE_20, Map.of())).why().contains(NeedChain.UNVERIFIED));
    }

    static int indexOf(List<String> texts, String item) {
        for (int i = 0; i < texts.size(); i++) if (texts.get(i).startsWith("craft") && texts.get(i).contains(item)) return i;
        return -1;
    }

    static int indexOfPlace(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i) instanceof Step.PlaceNear) return i;
        return -1;
    }
}
