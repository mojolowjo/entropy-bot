package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.ToolUses;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** docs/SCENARIOS.md S36-S38 (0.39.z33, the server run 2026-10-23): an unreachable source, a far station, a worn pickaxe. */
class FarStationWearUnreachableTest {
    static final double TOL = 0.1;

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static World world(Map<String, Integer> bag, Spot table, Map<String, List<Spot>> seen) {
        Map<String, List<Spot>> ids = new HashMap<>();
        for (var e : seen.entrySet()) ids.put(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey(), e.getValue());
        return new World(bag, table != null, false, w -> ScenariosTest.lookup(ids, w), null, null, null, 0, null, k -> ItemWorth.CRAFT.equals(k) ? table : null);
    }

    // ---- S37: a far station against a new one near me ----

    /**
     * S37 by hand: bag 7 planks + 2 sticks, a wooden pickaxe wanted. Table 227 m: walk 227 x 1.3 / 4.317 = 68.4 s + craft
     * 0.3; a new table: craft 0.3 + place 0.15 + pickaxe 0.3 = 0.75 s: placed, the walk dropped with its seconds.
     */
    @Test
    void s37_aTableFarAwayLosesToPlacingANewOneFromTheBag() {
        ScenariosTest.world("wooden");
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 7, "minecraft:stick", 2);
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, world(bag, new Spot(227, 70, 0, 227, "minecraft:crafting_table").notLoaded(), Map.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(0.75, c.seconds(), TOL, c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.PlaceNear), c.steps().toString());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("walk to the known table 227 m (" + NeedChain.UNVERIFIED + "): 68.")), c.dropped().toString());
    }

    /** S37: a table 20 m (inside station.nearM 30) is used without the comparison: S31's 6.32 s, nothing dropped for it. */
    @Test
    void s37_aTableNearIsUsedWithoutTheComparison() {
        ScenariosTest.world("wooden");
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 7, "minecraft:stick", 2);
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, world(bag, new Spot(20, 64, 0, 20, "minecraft:crafting_table"), Map.of()));
        assertEquals(6.32, c.seconds(), TOL, c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.PlaceNear), c.steps().toString());
        assertTrue(c.dropped().stream().noneMatch(d -> d.startsWith("place a new")), c.dropped().toString());
    }

    /** S37: far, and no planks to make one (3 planks, no tree): the walk stands, the new table is dropped with its reason. */
    @Test
    void s37_aFarTableWithNothingToMakeANewOneIsWalkedTo() {
        ScenariosTest.world("wooden");
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2);
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, world(bag, new Spot(227, 70, 0, 227, "minecraft:crafting_table"), Map.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(68.66, c.seconds(), 0.5, c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("place a new table near me: ")), c.dropped().toString());
    }

    // ---- S38: a worn pickaxe ----

    static World stoneWorld(int usesLeft) {
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 3, "minecraft:stick", 2);
        return world(bag, new Spot(2, 64, 0, 2, "minecraft:crafting_table"), Map.of("cobblestone", List.of(new Spot(12, 60, 3, 12, "minecraft:stone", 400))))
                .withUses(id -> id.equals("minecraft:stone_pickaxe") ? usesLeft : ToolUses.UNLIMITED);
    }

    /**
     * S38 by hand: 130 cobblestone, 3 in the bag: 127 to break, the stone pickaxe has 6 uses: a new stone pickaxe first from
     * the bag's 3 cobblestone + 2 sticks at the table 2 m (walk 2 x 1.3 / 4.317 = 0.6 + craft 0.3 = 0.9 s), before the collect; the digging is the same price.
     */
    @Test
    void s38_aPickaxeWithTooFewUsesGetsANewOneFirstAtItsCraftPrice() {
        ScenariosTest.world("wooden", "stone");
        Chain fresh = NeedChain.resolve("cobblestone", 130, stoneWorld(131));
        Chain worn = NeedChain.resolve("cobblestone", 130, stoneWorld(6));
        assertTrue(fresh.ok() && worn.ok(), fresh.why() + " / " + worn.why());
        assertTrue(fresh.steps().stream().noneMatch(s -> s instanceof Step.Craft), fresh.steps().toString());
        int craft = -1, collect = -1;
        for (int i = 0; i < worn.steps().size(); i++) {
            Step s = worn.steps().get(i);
            if (s instanceof Step.Craft k && k.item().contains("stone_pickaxe")) craft = i;
            if (s instanceof Step.Collect && collect < 0) collect = i;
        }
        assertTrue(craft >= 0 && collect > craft, worn.steps().toString());
        assertEquals(fresh.seconds() + 2 * 1.3 / 4.317 + 0.3, worn.seconds(), TOL, fresh.why() + " / " + worn.why());
    }

    /** Rule 15: durability unknown inserts no pickaxe; unlimited neither. */
    @Test
    void s38_unknownOrUnlimitedDurabilityInsertsNothing() {
        ScenariosTest.world("wooden", "stone");
        for (int u : new int[]{ToolUses.UNKNOWN, ToolUses.UNLIMITED}) {
            Chain c = NeedChain.resolve("cobblestone", 130, stoneWorld(u));
            assertTrue(c.ok(), c.why());
            assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft), u + ": " + c.steps());
        }
    }

    @Test
    void s38_usesLeftFromTheItemsData() {
        assertEquals(131, ToolUses.of(131, 0, true, false), "a fresh stone pickaxe");
        assertEquals(6, ToolUses.of(131, 125, true, false), "damaged: max - damage");
        assertEquals(ToolUses.UNLIMITED, ToolUses.of(null, 0, false, false), "no MAX_DAMAGE: unlimited");
        assertEquals(ToolUses.UNLIMITED, ToolUses.of(0, 0, true, false), "max damage 0: unlimited");
        assertEquals(ToolUses.UNKNOWN, ToolUses.of(null, 0, true, true), "a tool with a mod's component only: unknown");
        assertEquals("durability unknown", ToolUses.text(ToolUses.UNKNOWN));
    }

    // ---- S36: an unreachable source ----

    @Test
    void s36_baritonesOwnEndLineIsTheReason() {
        assertEquals("Unable to find any path to raw_iron_block, canceling mine",
                FactChange.baritoneSaid(3, 4, "Unable to find any path to raw_iron_block, canceling mine"));
        assertEquals("Baritone said nothing since the mine started", FactChange.baritoneSaid(3, 3, "old line"));
    }

    /**
     * S36 by hand: cobblestone 3 wanted, a stone spot 12 m the collect cannot reach (Baritone: no path). The step's end sets
     * the source fact; the re-plan prices without that source: the explore, and the tree lists the dropped source.
     */
    @Test
    void s36_anUnreachableSourceIsDroppedAndTheOrderRePlans() {
        ScenariosTest.world("wooden");
        Map<String, Integer> bag = Map.of("minecraft:wooden_pickaxe", 1);
        Map<String, List<Spot>> seen = Map.of("cobblestone", List.of(new Spot(12, 60, 3, 12, "minecraft:stone", 40)));
        Map<String, String> dropped = new LinkedHashMap<>();
        Chain first = NeedChain.resolve("cobblestone", 3, world(bag, null, seen));
        assertTrue(first.ok() && !first.explore(), first.why());
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) {
                if (s instanceof Step.Collect k) FactChange.unreachable(k.item(), "searched from 0 64 0", "Unable to find any path to stone, canceling mine");
                return null;
            }
            @Override public Car.Status status(Step s) { return Car.Status.failed("collect: Baritone's mine process ended with 0 of 3: Unable to find any path to stone, canceling mine"); }
            @Override public void cancel(String why) {}
        });
        Chain[] re = new Chain[1];
        run.start("owner goal", first.steps(), 0, (failed, fact, why) -> {
            assertTrue(fact.key().startsWith("source "), fact.key());
            dropped.put(NeedChain.sourceKey(failed), fact.text());
            re[0] = NeedChain.resolve("cobblestone", 3, world(bag, null, seen).withoutSources(dropped));
            return new Steps.Routine(List.of(new Step.Wait(1)), null);
        });
        for (int t = 1; t < 5 && run.running(); t++) run.tick(t);
        assertNotNull(re[0], run.why());
        assertTrue(re[0].explore(), re[0].why());
        assertTrue(re[0].dropped().contains("collect cobblestone: unreachable (Baritone: Unable to find any path to stone, canceling mine)"), re[0].dropped().toString());
    }

    // ---- S42: a tree the chop cannot reach ----

    /** One cut step that fails "no path to any target (got 0 of 1)" with the chop's source fact; the re-plan's chain on the dropped tree. */
    static Chain s42Replan(Map<String, List<Spot>> seen) {
        Chain first = NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, seen));
        assertTrue(first.ok() && first.steps().get(0) instanceof Step.ChopTree, first.why());
        Map<String, String> dropped = new LinkedHashMap<>();
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) {
                FactChange.unreachable("logs", "at 10 64 0", "no path to any target");     // what ChopEnd says at 0 got
                return Car.Status.failed("cut 1 logs: no path to any target (got 0 of 1)");
            }
            @Override public void cancel(String why) {}
        });
        Chain[] re = new Chain[1];
        run.start("owner goal", first.steps(), 0, (failed, fact, why) -> {
            assertTrue(fact.key().startsWith("source "), fact.key());
            dropped.put(NeedChain.sourceKey(failed), fact.text());
            re[0] = NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, seen).withoutSources(dropped));
            return new Steps.Routine(List.of(new Step.Wait(1)), null);
        });
        for (int t = 1; t < 5 && run.running(); t++) run.tick(t);
        assertNotNull(re[0], "the cut's 0 of 1 re-plans (z36), it does not park: " + run.why());
        assertTrue(re[0].dropped().contains("cut at 10 64 0: unreachable (Baritone: no path to any target)"), re[0].dropped().toString());
        return re[0];
    }

    /** S42 by hand: trees A 10 m and B 40 m; A cannot be reached: the re-plan cuts B. */
    @Test
    void s42_anUnreachableTreeRePlansToTheNextTree() {
        ScenariosTest.world("wooden");
        Chain re = s42Replan(Map.of("oak_log", List.of(ScenariosTest.tree(10, 4), ScenariosTest.tree(40, 4))));
        assertTrue(re.ok() && !re.explore(), re.why());
        assertEquals("cut 1 at 40 64 0", re.steps().get(0).text());
    }

    /** S42: the only tree known cannot be reached: the re-plan explores. */
    @Test
    void s42_anUnreachableOnlyTreeRePlansToAnExplore() {
        ScenariosTest.world("wooden");
        Chain re = s42Replan(Map.of("oak_log", List.of(ScenariosTest.tree(10, 4))));
        assertTrue(re.explore(), re.why());
    }
}
