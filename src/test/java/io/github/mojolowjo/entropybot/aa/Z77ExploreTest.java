package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.vocab.ExploreWords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 J2, the explore items: E0 (a stand whose wait is over is a re-price, never a try: no park), E1 (a joined until
 * word's surface part turns the land-first water term on), E2 (under ground no explore aims through rock: the strip at the
 * feet, a cave cell in the band, or the walk up first), the explore's honest price (manager 2026-10-26: its seconds, was
 * 450 walk-blocks = 104 s), E4 (a collect whose Baritone goal is away from its priced spot ends by that spot's fact).
 * Hand numbers as Z77PlaybookTest (stone 0.6 s, iron ore 1.15 s, 16 blocks an ore at y 16).
 */
class Z77ExploreTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final String IRON_ORE = "minecraft:iron_ore", STONE = "minecraft:stone";

    static LootBook book() {
        return new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, IRON_ORE, "minecraft:raw_iron", 1, 1, false, null)), "test");
    }

    static GenWhere.Fact where(String word) {
        if (word.contains("log")) return new GenWhere.Fact(GenWhere.Kind.SURFACE, 0, 0, "minecraft:oak_log", "minecraft:trees_plains", null);
        if (word.contains("raw_iron")) return new GenWhere.Fact(GenWhere.Kind.BAND, 0, 31, IRON_ORE, "minecraft:ore_test", null);
        return GenWhere.unknown("test");
    }

    /** The bot at -181 16 27 (run BC), 48 blocks of rock over it; the surface cell 64 above; walks by FakePaths (the line x 1.3). */
    static NeedChain.World deep(java.util.function.Function<String, StripBand> strip) {
        ScenariosTest.world("wooden", "stone");
        return ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 2, "minecraft:furnace", 1, "minecraft:crafting_table", 1, "minecraft:coal", 1), false, false, Map.of())
                .withMobs(x -> List.of(), book()).withStrip(strip)
                .withWhere(new NeedChain.WhereFacts(Z77ExploreTest::where, () -> new NeedChain.Surface(48, -181, 64, 27, 48), () -> new int[]{-181, 16, 27}));
    }

    // ---- E2: deep under ground ----

    /**
     * (a) Feet in the rock at y 16 in iron's band: the way is the strip at the feet - 3 ore x 16 = 48 cells x 2 x 0.6 = 57.6
     * + 3 x 1.15 = 3.45: 61.05 s, no walk (the strip's own cell, 0 m); no Explore step, no walk to a column.
     */
    @Test
    void e2a_theStripAtTheFeet_noExplore() {
        var w = deep(word -> word.contains("raw_iron") || word.contains("iron_ore")
                ? new StripBand(List.of(IRON_ORE), 0, 31, 16, 256, -181, 27, 16, "west", STONE) : null);
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(61.05, c.seconds(), 0.1, c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore || s instanceof Step.ToStrip || s instanceof Step.ToBand), c.steps().toString());
        assertTrue(c.steps().get(0) instanceof Step.Strip st && st.y() == 16, c.steps().toString());
    }

    /**
     * (c) Feet outside every band of the need (no strip facts here) and no cave known: the walk up first (its priced path:
     * 48 m x 1.3 / 4.317 = 14.45 s), then the explore on the surface - never a band leg from down here, never a target
     * through the rock (run BC: GoalXZ -206 50 from -181 16 27).
     */
    @Test
    void e2c_noStripNoCave_theWalkUpFirstThenTheSurfaceExplore() {
        Chain c = NeedChain.resolve("raw_iron", 3, deep(word -> null));
        assertTrue(c.ok() && c.explore(), c.why());
        assertEquals(List.of(new Step.ToSurface(-181, 64, 27), new Step.Explore(NeedChain.exploreMinutes(), "raw_iron")), c.steps());
        assertTrue(c.why().contains("explore for raw_iron from the surface"), c.why());
        assertEquals(FakePaths.walkS(48) + NeedChain.exploreS(), c.seconds(), 0.1, "the walk up and the explore's honest price: " + c.why());
    }

    /** No walk up to be had (no path up): no explore from down here at all - the way fails with the rule-22 reason (said). */
    @Test
    void e2c_noWalkUp_noExploreThroughRock() {
        Chain c = NeedChain.resolve("raw_iron", 3, deep(word -> null).withPath((x, y, z, d) -> PathPrice.Answer.unreachable("no path")));
        assertFalse(c.ok(), c.why());
        assertTrue(c.missing().startsWith(NeedChain.UNDER_GROUND), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), c.steps().toString());
    }

    /** (b) at runtime: from under ground only the cave cells IN the target's band are targets (a cell goal); none: no target. */
    @Test
    void e2b_underGroundOnlyCaveCellsInTheBand() {
        List<int[]> cells = List.of(new int[]{-163, 60, 41}, new int[]{-190, 20, 51}, new int[]{-269, 98, 66});
        List<int[]> in = ExploreRules.inBand(cells, -24, 56);
        assertEquals(1, in.size());
        assertArrayEquals(new int[]{-190, 20, 51}, in.get(0));
        assertTrue(ExploreRules.inBand(List.of(new int[]{-163, 60, 41}, new int[]{-269, 98, 66}), -24, 56).isEmpty(), "run BC's candidates: none in the band");
    }

    /** The under-ground explore that found no target holds the band: the next explore runs on the surface, no band leg back down. */
    @Test
    void e2_aHeldBandSendsTheExploreOverland() {
        ScenariosTest.world("wooden", "stone");
        var w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), false, false, Map.of()).withMobs(x -> List.of(), book()).withBand(x -> new int[]{0, 31})
                .withWhere(new NeedChain.WhereFacts(Z77ExploreTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0}));
        Chain first = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(first.steps().stream().anyMatch(s -> s instanceof Step.ToBand), "from the surface: the band leg as before (z69): " + first.steps());
        FactChange.band(0, 31, "under ground: no cave cell in the band");
        FactChange.Fact f = FactChange.take();
        Chain again = NeedChain.resolve("raw_iron", 3, w.withoutSources(Map.of(f.key().substring("source ".length()), f.text())));
        assertTrue(again.explore(), again.why());
        assertTrue(again.steps().stream().noneMatch(s -> s instanceof Step.ToBand), "no band leg again: " + again.steps());
    }

    // ---- E1: the ocean spawn ----

    /**
     * Feet on a beach in chunk 0,0; the loaded chunk 1 ring east is water, land 3 rings west, every other chunk seen. The
     * joined until word reads UNKNOWN as a whole (placed both ways), so the water term was off: east (ring 1). Read per part,
     * the logs part is a surface word: east counts 1 + exploreWords.waterRings 4 = 5 > west 3: west.
     */
    @Test
    void e1_aSurfacePartOfAJoinedWordTurnsTheWaterTermOn() {
        String joined = "#minecraft:logs|minecraft:raw_iron";
        assertTrue(ExploreRules.anySurfacePart(joined, Z77ExploreTest::where));
        assertFalse(ExploreRules.anySurfacePart("minecraft:raw_iron", Z77ExploreTest::where));
        ExploreWords.BiPred seen = (x, z) -> !((x == 1 && z == 0) || (x == -3 && z == 0));
        ExploreWords.BiPred water = (x, z) -> x == 1 && z == 0;
        assertArrayEquals(new int[]{1, 0}, ExploreWords.next(0, 0, 0, 0, null, seen, (x, z) -> false), "today: the sea, ring 1");
        assertArrayEquals(new int[]{-3, 0}, ExploreWords.next(0, 0, 0, 0, null, seen, (x, z) -> false, water, ExploreWords.waterRings()), "with E1: the land, ring 3");
    }

    // ---- the explore's honest price ----

    /**
     * Manager 2026-10-26 (a unit slip): the explore leg is its own seconds - exploreMinutes 5 x 60 x (1 + exploreReturn 0.5) =
     * 450 s (was 450 walk-BLOCKS = 104 s). An explore row is dearer than a 60 s strip by that much: it loses (and in the
     * chain an explore never competes with a priced way anyway).
     */
    @Test
    void theExploreIsPricedInSeconds_andLosesToAStrip() {
        assertEquals(450, NeedChain.exploreS(), 1e-9);
        ScenariosTest.world("wooden");
        Chain ex = NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, Map.of()));
        assertTrue(ex.explore(), ex.why());
        assertEquals(450, ex.seconds(), 0.01, "the row's price: the explore leg in seconds");
        assertEquals(Unit.blocks(450), ex.cost(), 0.01);
        assertTrue(ex.seconds() > 61.05, "an honest explore loses to the 61 s strip at the feet");
    }

    // ---- E0: a stand is no try ----

    /**
     * Two stands in one order (run AZ 05:13:31): each ends its wait with the price fact; the order is priced again each time -
     * never parked on the wait ("the same try failed again"); the walk then runs; zero movement while standing (rule 22).
     */
    @Test
    void e0_twoStandsInOneOrder_noPark() {
        Z76StandTest.FeetHands h = new Z76StandTest.FeetHands();
        Steps run = new Steps(h);
        List<FactChange.Fact> facts = new ArrayList<>();
        Step walk = new Step.Walk(30, 64, 0, 0);
        run.start("goal.chestplate", List.of(new Step.AwaitPaths(30)), 0, (failed, fact, why) -> {
            facts.add(fact);
            return new Steps.Routine(facts.size() == 1 ? List.of(new Step.AwaitPaths(30)) : List.of(walk), null);
        });
        Z76StandTest.tick(run, h, 20 * 30);
        assertEquals(1, facts.size());
        assertArrayEquals(new int[]{0, 64, 0}, h.feet, "zero movement while standing");
        Z76StandTest.tick(run, h, 20 * 30);
        assertEquals(2, facts.size(), "the second stand's end is priced again too");
        assertTrue(facts.get(1).price());
        assertTrue(run.running(), "never parked on the wait: " + run.why());
        assertEquals(List.of("stand while paths compute (up to 30 s)", "stand while paths compute (up to 30 s)", walk.text()), h.started);
        assertNull(run.lastFailed(), "a re-price is no failed try");
    }

    // ---- E4: the collect's goal away from its priced spot ----

    /** Baritone's goal cells all more than failures.moved (16) from the priced spot: that spot's fact; one near it, none, or no spot: not. */
    @Test
    void e4_aCollectWhoseGoalIsAwayFromItsSpot() {
        Step.Collect k = new Step.Collect("oak_planks", 11, new int[]{75, 64, 0});
        double moved = 16;
        assertTrue(k.targetAway(List.of(new int[]{900, 64, 0}, new int[]{-400, 70, 30}), moved), "run AP: planks ~1000 blocks away");
        assertFalse(k.targetAway(List.of(new int[]{900, 64, 0}, new int[]{80, 64, 0}), moved), "one goal at the priced spot");
        assertFalse(k.targetAway(List.of(), moved), "no goal read (rule 15)");
        assertFalse(new Step.Collect("oak_planks", 11).targetAway(List.of(new int[]{900, 64, 0}), moved), "no priced spot");
    }
}
