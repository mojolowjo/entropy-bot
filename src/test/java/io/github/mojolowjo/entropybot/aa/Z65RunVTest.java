package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.KeepClear;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z65 (run V on z64, 2026-10-25 07:05-07:16, docs/runs/runV-z64): item 1 - the craft step's use walked from y 16 to the
 * table 59 up with the fork's own unsteered search ("no path ... (from -261 16 22)"), the take walk after it was a plain
 * GoalNear ("goto -261 75 21 near 2 ... stopped short") and its failure parked the order; item 2 - the spare decision only in
 * aa debug; item 3 - the band walk as a plain GoalYLevel.
 */
class Z65RunVTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    // ---- item 1: the station walk is the chain's priced walk ----

    /** Run V 07:15:15: the bot at -261 16 22, its table at -261 75 21 (60 m): the priced walk, then the craft. */
    @Test
    void aFarKnownTableIsWalkedToByThePricedWalkBeforeTheCraft() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2);
        Spot table = new Spot(-261, 75, 21, 60, "minecraft:crafting_table");
        World w = FakePaths.paths(new World(bag, true, k -> null)).withStations(k -> ItemWorth.CRAFT.equals(k) ? table : null);
        Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        Step walk = new Step.ToStation(ItemWorth.CRAFT, "crafting_table", -261, 75, 21);
        assertEquals(List.of(walk, new Step.Craft("stone_pickaxe", 1)), c.steps(), c.why());
        assertEquals("goto -261 75 21 (the crafting_table, priced path)", walk.text());
        assertEquals("goto", walk.text().substring(0, 4), "the walk verb (the failure classifiers read the first word)");
        // a walk: nothing into the bag (the dry run reads on), the fork's queue sees its goal first, KeepClear keeps its path
        assertEquals(Map.of(), DryRun.produces(walk));
        assertArrayEquals(new int[]{-261, 75, 21}, Steps.walkAhead(c.steps(), 0));
        List<int[]> keep = KeepClear.cells(c.steps(), 0, new int[]{-261, 16, 22}, 3, g -> List.of(new int[]{-261, 17, 22}));
        assertEquals(2, keep.size(), "its kept path's first cell and head cell");
    }

    /** A table the bot stands by (its priced walk 0 s): no walk step, the craft only. */
    @Test
    void aTableInReachHasNoWalkStep() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2);
        Spot table = new Spot(1, 64, 0, 0, "minecraft:crafting_table");
        World w = FakePaths.paths(new World(bag, true, k -> null)).withStations(k -> ItemWorth.CRAFT.equals(k) ? table : null);
        Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertTrue(c.ok(), c.why());
        assertEquals(List.of(new Step.Craft("stone_pickaxe", 1)), c.steps(), c.why());
    }

    /** Run V 07:15:22: the take walk failed with no fact of its own: the next step's source is the fact, the run re-plans. */
    @Test
    void aFailedWalkToASourceIsThatSourcesFactAndTheRunReplans() {
        Step walk = new Step.Walk(-261, 75, 21, 2), take = new Step.TakeStation("crafting_table", -261, 75, 21);
        String why = "couldn't get there (60 blocks away, stopped at -261 16 22) (walk: Baritone stopped short)";
        FactChange.Fact f = Steps.walkLeads(List.of(walk, take), 0, "error: " + why);
        assertNotNull(f);
        assertEquals("take at -261 75 21", FactChange.ownSourceKey(f), "keyed by the take's own source key");
        assertTrue(f.key().startsWith("source "), "a source fact (the order drops that source)");
        assertEquals("the walk to take at -261 75 21 failed: " + why, f.text());
        assertNull(Steps.walkLeads(List.of(walk, new Step.Craft("stick", 4)), 0, why), "no source after it");
        assertNull(Steps.walkLeads(List.of(walk), 0, why), "nothing after it");
        assertNull(Steps.walkLeads(List.of(new Step.Craft("stick", 4), take), 0, why), "not a walk");

        Z61ReservationsTest.Hands h = new Z61ReservationsTest.Hands();
        List<FactChange.Fact> asked = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("owner goal", List.of(walk, take), 0, (failed, fact, w) -> { asked.add(fact); return new Steps.Routine(List.of(new Step.Explore(5)), null); });
        h.status = Car.Status.failed("error: " + why);
        run.tick(1);
        assertEquals(1, asked.size(), "re-planned, never ended: " + run.why());
        assertEquals("take at -261 75 21", FactChange.ownSourceKey(asked.get(0)));
        assertTrue(run.running(), run.why());
    }

    /** The order's re-plan charges the take's key (droppedSources), as a source new to the order. */
    @Test
    void theReplanDropsTheTakeForTheOrder() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) () -> Z62RunSTest.world(Map.of("minecraft:stone_pickaxe", 1), Z62RunSTest.band("east")));
        Goals.Goal g = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        FactChange.Fact f = FactChange.walkFor("take at -261 75 21", "stopped short");
        Steps.Routine r = Goals.replan(bb, g, new Step.Walk(-261, 75, 21, 2), f, "stopped short");
        assertNotNull(r.steps(), r.why());
        assertTrue(Goals.droppedSources(g).containsKey("take at -261 75 21"), Goals.droppedSources(g).toString());
        assertEquals(1, g.sourceReplans);
    }

    // ---- review 1: a station whose walk failed is out for THAT order only ----

    static World farTableWorld() {
        Spot table = new Spot(-261, 75, 21, 60, "minecraft:crafting_table");
        return FakePaths.paths(new World(Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2), true, k -> null))
                .withStations(k -> ItemWorth.CRAFT.equals(k) ? table : null);
    }

    static boolean walksToTable(Chain c) { return c != null && c.steps().stream().anyMatch(s -> s instanceof Step.ToStation); }

    @Test
    void aFailedStationWalkHoldsTheStationForThatOrderOnly() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) Z65RunVTest::farTableWorld);
        bb.in.pos = new int[]{-261, 16, 22};                     // run V 07:15:19: 59 below the table
        Goals.Goal g = Goals.draft(DebugTreeTest.item("stone_pickaxe"), 1, 0, clock.now());
        assertTrue(walksToTable(Goals.chain(bb, g)), "first: the priced walk to the known table");

        Step.ToStation walk = new Step.ToStation(ItemWorth.CRAFT, "crafting_table", -261, 75, 21);
        assertEquals("station at -261 75 21", NeedChain.sourceKey(walk));
        FactChange.stationWalk("crafting_table", -261, 75, 21, "the walk to it ended short: couldn't get there");
        FactChange.Fact f = FactChange.take();
        assertEquals("source station at -261 75 21", f.key());
        Steps.Routine r = Goals.replan(bb, g, walk, f, "couldn't get there");
        assertNotNull(r.steps(), r.why());
        assertFalse(r.steps().stream().anyMatch(s -> s instanceof Step.ToStation), "the table is out for this order: " + r.steps());
        assertTrue(Goals.droppedSources(g).containsKey("station at -261 75 21"));
        assertEquals(1, g.sourceReplans, "a new source: counted as a source drop");
        assertFalse(walksToTable(Goals.chain(bb, g)), "still out from the same spot");

        // the next order: a new goal, nothing dropped - the table is walked to again
        Goals.Goal next = Goals.draft(DebugTreeTest.item("stone_pickaxe"), 1, 0, clock.now());
        assertTrue(walksToTable(Goals.chain(bb, next)), "usable again in the next order");

        // the same order, the bot failures.moved closer (44 blocks): the table is back
        bb.in.pos = new int[]{-261, 60, 22};
        assertTrue(walksToTable(Goals.chain(bb, g)), "usable again once the bot stands closer");
        assertTrue(Goals.droppedSources(g).containsKey("station at -261 75 21"), "the record stays (a repeat is counted as a re-plan)");
    }

    @Test
    void aStationIsBackOnlyWhenTheBotIsFailuresMovedCloser() {
        double m = io.github.mojolowjo.entropybot.aa.brain.Failures.movedV();
        String k = NeedChain.stationKey(0, 70, 0);
        int[] from = {0, 10, 0};                                   // 60 away
        assertFalse(Goals.stationBack(k, from, new int[]{0, (int) (10 + m - 1), 0}), "a little closer: still out");
        assertTrue(Goals.stationBack(k, from, new int[]{0, (int) (10 + m), 0}), "failures.moved closer: back");
        assertFalse(Goals.stationBack(k, from, new int[]{100, 10, 0}), "farther: out");
        assertFalse(Goals.stationBack("take at 0 70 0", from, new int[]{0, 69, 0}), "other sources stay dropped for the order");
        assertFalse(Goals.stationBack(k, null, new int[]{0, 69, 0}), "no position kept: held for the order");
    }

    // ---- review 2: walk-led source re-plans are counted as source drops (0.39.z76 sb4 review: counted, never a stop) ----

    @Test
    void walkLedSourceDropsAreCountedNotCapped() {
        ScenariosTest.world("wooden", "stone");
        Step walk = new Step.Walk(30, 64, 0, 0);
        FactChange.Fact tree = Steps.walkLeads(List.of(walk, new Step.ChopTree(new int[]{30, 64, 0}, 4, null)), 0, "stopped short");
        assertEquals("cut at 30 64 0", FactChange.ownSourceKey(tree), "a tree cell");
        assertEquals("collect cobblestone", FactChange.ownSourceKey(Steps.walkLeads(List.of(walk, new Step.Collect("cobblestone", 3)), 0, "x")));
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) () -> Z62RunSTest.world(Map.of("minecraft:stone_pickaxe", 1), Z62RunSTest.band("east")));
        Goals.Goal g = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        g.sourceReplans = 8;                                       // the deleted replan.maxSourceDrops
        Steps.Routine past = Goals.replan(bb, g, walk, tree, "stopped short");
        assertNotNull(past.steps(), past.why());
        assertEquals(9, g.sourceReplans, "the tree is a new source: counted");
        assertTrue(Goals.droppedSources(g).containsKey("cut at 30 64 0"));
    }

    // ---- item 2: the spare decision at info level, once per chain ----

    /**
     * Run V 07:13:09: a stone pickaxe made in the chain (131 uses) for a strip of 2 x 66 + 3 = 135 blocks: the spare needs
     * 2 sticks and no wood is in the bag or known - "no spare to be had", the worn one is used; with sticks in the bag one
     * spare is made (Z64RunUTest's hand numbers); the chain carries the line for the log.
     */
    @Test
    void theSpareDecisionIsCarriedByTheChain() {
        ScenariosTest.world("wooden", "stone");
        Tuning.set("strip.maxBlocksPerOre", 22);
        Map<String, Integer> noWood = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20);
        Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(noWood, Z62RunSTest.band("east")).withUses(id -> 131));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(0, Z62RunSTest.crafted(c, "stone_pickaxe"), c.steps().toString());
        assertTrue(c.spares().startsWith("stone_pickaxe: 131 uses left, 135 blocks to break; no spare to be had ("), c.spares());
        assertTrue(c.spares().endsWith("): the worn one is used"), c.spares());

        Map<String, Integer> sticks = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain s = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(sticks, Z62RunSTest.band("east")).withUses(id -> 131));
        assertEquals(1, Z62RunSTest.crafted(s, "stone_pickaxe"));
        assertTrue(s.spares().contains("-> 1 spare stone_pickaxe made first"), s.spares());

        Chain none = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(sticks, Z62RunSTest.band("east")).withUses(id -> 135));
        assertEquals("stone_pickaxe: 135 uses left, 135 blocks to break: no spare needed", none.spares());

        // once per chain: the same line again is not said again; another one is
        Goals.Goal g = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, 0);
        assertEquals(g.r.key() + ": spare pickaxes: " + c.spares(), Goals.sparesLine(g, c));
        assertNull(Goals.sparesLine(g, c), "said once");
        assertNotNull(Goals.sparesLine(g, s), "a chain with another decision is said");
        assertNull(Goals.sparesLine(g, new Chain(List.of(), 0, "x", false, null)), "no decision: nothing said");
    }

    // ---- item 3: the band's priced cell ----

    /** The cell's Y: the band's nearest Y, one inside each edge (the walk's goal ends with the feet within one block). */
    @Test
    void theBandCellIsInsideTheBandByOne() {
        Step.ToBand b = new Step.ToBand(5, 31);
        assertEquals(30, b.target(62), "run V 07:05:26: from y 62 to y 30 (feet end at 29..31)");
        assertEquals(6, b.target(-20), "from below: y 6 (feet end at 5..7)");
        assertEquals(12, b.target(12));
        assertEquals(8, new Step.ToBand(7, 8).target(62), "a band under 3 high: its own edge");
        assertEquals(7, new Step.ToBand(7, 7).target(0));
        assertEquals(7, new Step.ToBand(7, 8).target(0), "from below a 2-level band: its low edge, inside it");
        for (int y = -64; y <= 320; y += 7) { int t = new Step.ToBand(7, 8).target(y); assertTrue(t >= 7 && t <= 8, "always inside: " + t); }
    }
}
