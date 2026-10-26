package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.guard.WorldGenFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z74 (runs AC on z72 and AD on z73): (1) a source's price counts its drop chance; (2) the monitor's no-path rule does
 * not cut a place step; (3) a chop fells the whole tree; (4) the dimension's base block is a priced dig-down source. Hand
 * numbers: the walk is the straight line x 1.3 at the walk speed (FakePaths), leaves break by hand in 0.3 s (hardness 0.2 x
 * 1.5, the game's formula), ticks at 20 a second.
 */
class Z74RunACTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final String STICK = "minecraft:stick", LEAVES = "minecraft:oak_leaves";

    /** The loot data: leaves give a stick at 2% (1-2 of them: 0.03 expected a block), a log itself, stone cobblestone. */
    static final LootBook BOOK = new LootBook(List.of(
            new LootBook.Source(LootBook.Kind.BLOCK, LEAVES, STICK, 0.03, 0.02, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:oak_log", "minecraft:oak_log", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:stone", "minecraft:cobblestone", 1, 1, false, null)), "test");

    static TestData data() {
        TestData d = ScenariosTest.world("wooden", "stone");
        d.block("oak_leaves", 0.2, 0);
        d.install();
        return d;
    }

    static World world(Map<String, Integer> bag, Map<String, List<Spot>> seen) {
        return ScenariosTest.w(bag, true, false, seen).withMobs(x -> List.of(), BOOK);
    }

    static double walk(double m) { return Unit.seconds(m * io.github.mojolowjo.entropybot.aa.FakePaths.LINE_X); }

    // ---- 1. the drop chance ----

    /** A log in the bag: craft stick (planks 0.3 s + sticks 0.3 s) beats 67 leaves for 2 sticks at 0.03 a block. */
    @Test
    void i1_aLogBeatsLeavesAtTheirDropChance() {
        data();
        Spot leaves = new Spot(5, 64, 0, 5, LEAVES);
        Chain c = NeedChain.resolve("stick", 2, world(Map.of("minecraft:oak_log", 1), Map.of(STICK, List.of(leaves))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Collect), c.steps().toString());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Craft k && k.item().contains("stick")), c.steps().toString());
        assertEquals(0.6, c.seconds(), 0.05, c.why());
    }

    /**
     * The leaves the only way: still offered, at the honest price - 2 sticks at 0.03 a block = ceil(66.7) = 67 blocks;
     * 67 x 0.3 s to break + the 5 m walk (1.51 s) for each of the 67 trips (each chance block its own trip) = 120.98 s.
     */
    @Test
    void i1_aChanceSourceAloneIsOfferedAtItsHonestPrice() {
        data();
        Spot leaves = new Spot(5, 64, 0, 5, LEAVES);
        Chain c = NeedChain.resolve("stick", 2, world(Map.of(), Map.of(STICK, List.of(leaves))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of(new Step.Collect("stick", 2)), c.steps());
        assertEquals(67 * 0.3 + 67 * walk(5), c.seconds(), 0.1, c.why());
    }

    /** Rule 15: a block the book holds no entry for (not the item's own) is never priced as 1 a block: the spot is left out. */
    @Test
    void i1_anUnknownYieldIsNotOne() {
        data();
        Spot birch = new Spot(5, 64, 0, 5, "minecraft:birch_leaves");
        Chain c = NeedChain.resolve("stick", 2, world(Map.of(), Map.of(STICK, List.of(birch))));
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Collect), c.why() + " " + c.steps());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains("is not known (no loot entry")), c.dropped().toString());
    }

    // ---- 2 and 3: the monitor ----

    static StepSignals sig(double goal, long calcFailed, int made, long placed) {
        return new StepSignals(true, null, new int[]{-155, 68, -92}, goal, "JankyComposite", false, false, false, calcFailed, made, -1, null, placed, -1, null);
    }

    static final class Hands implements Steps.Hands {
        java.util.function.IntFunction<StepSignals> script;
        java.util.function.IntFunction<Car.Status> status = s -> Car.Status.running("building");
        int second;
        final List<String> cancelled = new ArrayList<>(), started = new ArrayList<>();

        @Override public String start(Step s) { started.add(s.text()); return null; }
        @Override public Car.Status status(Step s) { return status.apply(second); }
        @Override public void cancel(String why) { cancelled.add(why); }
        @Override public int[] feet() { return new int[]{-155, 68, -92}; }
        @Override public StepSignals signals(Step s) { return script.apply(second); }
    }

    /**
     * Run AC 16:54:07: three CALC_FAILED in 7 s while the builder tries cells for the furnace (goal 6906.7, its own composite
     * search; nothing executing; 4 blocks placed earlier): CONTINUE - a placement has no no-path verdict; with nothing placed,
     * no move and no break for monitor.stallS: REPLAN at 30 s.
     */
    @Test
    void i2_aPlacementIsNeverCutByTheNoPathRule() {
        Hands h = new Hands();
        h.script = s -> sig(6906.7, s >= 7 ? 3 : s >= 4 ? 2 : s >= 2 ? 1 : 0, -1, 4);
        List<Integer> at = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("goal.torch", List.of(new Step.PlaceNear("furnace")), 0, (f, fact, why) -> { at.add(h.second); return new Steps.Routine(List.of(new Step.Wait(5)), null); });
        for (int s = 1; s <= 29 && at.isEmpty(); s++) { h.second = s; run.tick(s * 20L); }
        assertTrue(at.isEmpty(), "no verdict while the builder works: " + run.why());
        assertTrue(h.cancelled.isEmpty(), h.cancelled.toString());
        for (int s = 30; s <= 40 && at.isEmpty(); s++) { h.second = s; run.tick(s * 20L); }
        assertEquals(List.of(30), at, "nothing placed, no move for monitor.stallS: the stall");
    }

    /** A place step that keeps placing (a build leg) is progress, whatever Baritone's searches say. */
    @Test
    void i2_placingIsProgress() {
        Hands h = new Hands();
        h.script = s -> sig(6906.7, s, -1, s / 5);
        List<Integer> at = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("k", List.of(new Step.PlaceNear("furnace")), 0, (f, fact, why) -> { at.add(h.second); return null; });
        for (int s = 1; s <= 60; s++) { h.second = s; run.tick(s * 20L); }
        assertTrue(at.isEmpty(), "a block placed every 5 s: " + at);
    }

    /** Run AD 17:09:18: a 4-log tree, step "cut 1": no COMPLETE at 1 (the tree is the job), the step ends with 4 in the bag. */
    @Test
    void i3_aChopFellsTheWholeTree() {
        Hands h = new Hands();
        h.script = s -> sig(3, 0, Math.min(4, s), 0);
        h.status = s -> s >= 6 ? Car.Status.done("ok: tree 4 logs: got 4") : Car.Status.running("cut 1 logs: " + Math.min(4, s) + " of 1");
        Steps run = new Steps(h);
        run.start("goal.torch", List.of(new Step.ChopTree(new int[]{-28, 70, -51}, 1, null), new Step.Wait(5)), 0, (f, fact, why) -> null);
        for (int s = 1; s <= 6; s++) { h.second = s; run.tick(s * 20L); }
        assertTrue(h.cancelled.isEmpty(), "never cut short at 1 of 1: " + h.cancelled);
        assertEquals(List.of("cut 1 at -28 70 -51", "wait 5"), h.started);
        assertTrue(run.why().contains("wait 5"), run.why());
    }

    // ---- 4. the base block ----

    static final String OVERWORLD = "{\"default_block\":{\"Name\":\"minecraft:stone\"},\"surface_rule\":{\"type\":\"minecraft:sequence\",\"sequence\":["
            + "{\"type\":\"minecraft:condition\",\"if_true\":{\"type\":\"minecraft:vertical_gradient\",\"random_name\":\"minecraft:deepslate\","
            + "\"true_at_and_below\":{\"absolute\":0},\"false_at_and_above\":{\"absolute\":8}},\"then_run\":{\"type\":\"minecraft:block\","
            + "\"result_state\":{\"Name\":\"minecraft:deepslate\",\"Properties\":{\"axis\":\"y\"}}}}]}}";

    /** The noise settings' default block and deepslate gradient; the world preset names the overworld's settings. */
    @Test
    void i4_theBaseBlockIsReadFromTheNoiseSettings() {
        GenWhere.Base b = GenWhere.base("minecraft:overworld", JsonParser.parseString(OVERWORLD));
        assertEquals(new GenWhere.Base("minecraft:stone", 8, "minecraft:deepslate", "noise settings minecraft:overworld"), b);
        Map<String, String> dims = new java.util.TreeMap<>();
        GenWhere.dimensions("minecraft:normal", JsonParser.parseString("{\"dimensions\":{\"minecraft:overworld\":{\"type\":\"minecraft:overworld\","
                + "\"generator\":{\"type\":\"minecraft:noise\",\"settings\":\"minecraft:overworld\"}},\"minecraft:the_end\":{\"generator\":{\"type\":\"minecraft:noise\",\"settings\":\"minecraft:end\"}}}}"), true, dims);
        assertEquals(Map.of("minecraft:overworld", "minecraft:overworld", "minecraft:the_end", "minecraft:end"), dims);
        assertNull(GenWhere.base("x", JsonParser.parseString("{\"sea_level\":63}")), "no default block: none");

        var ores = new WorldGenFiles.Ores(Map.of(), Map.of(), List.of(), 0, null, List.of(), Map.of("minecraft:overworld", b), dims);
        assertEquals(b, ores.baseOf("minecraft:overworld"));
        assertNull(ores.baseOf("minecraft:the_end"), "the end's settings not read: not known");
        // the cache keeps them (version 6)
        var back = WorldGenFiles.fromCache(WorldGenFiles.toCache("h", new WorldGenFiles.Read(java.util.Set.of(), 1, 0, null, ores)), "h");
        assertEquals(b, back.ores().baseOf("minecraft:overworld"));
    }

    static World baseWorld(Map<String, Integer> bag) {
        GenWhere.Base b = new GenWhere.Base("minecraft:stone", 8, "minecraft:deepslate", "noise settings minecraft:overworld");
        return world(bag, Map.of()).withWhere(new NeedChain.WhereFacts(w -> null, () -> new NeedChain.Surface(0, 0, 70, 0, 0), () -> new int[]{0, 70, 0}, () -> b));
    }

    /**
     * Run AD 17:10:08's shape: grass over stone, no stone seen, a wooden pickaxe in the bag. The cobblestone (the furnace's 8)
     * comes from the base block: dig down to y 66 (the feet 70, the top block 69, the surface layer 3) on the priced path,
     * then collect 8 cobblestone - priced, no explore.
     */
    @Test
    void i4_theFurnacesCobblestoneFromTheBaseBlock() {
        data();
        Chain c = NeedChain.resolve("cobblestone", 8, baseWorld(Map.of("minecraft:wooden_pickaxe", 1)));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(new Step.ToBand(66, 66), c.steps().get(0), c.steps().toString());
        assertEquals(new Step.Collect("cobblestone", 8), c.steps().get(1), c.steps().toString());
        assertTrue(c.why().contains("dig down 4 to stone (the noise settings' default block, deepslate below y 8"), c.why());
        Chain f = NeedChain.resolve("furnace", 1, baseWorld(Map.of("minecraft:wooden_pickaxe", 1)));
        assertTrue(f.ok() && !f.explore(), f.why());
        assertTrue(f.seconds() < 173, "the furnace (and charcoal after it, a 10 s smelt) well under run AD's 173 s coal strip: " + f.why());
    }

    /** Not on the surface, or no base block known: no such way (rule 15) - the explore as before. */
    @Test
    void i4_noBaseWayWhenNotKnown() {
        data();
        Chain c = NeedChain.resolve("cobblestone", 8, world(Map.of("minecraft:wooden_pickaxe", 1), Map.of()));
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ToBand && ((Step.ToBand) s).minY() == 66), c.steps().toString());
        GenWhere.Base b = new GenWhere.Base("minecraft:stone", 8, "minecraft:deepslate", "x");
        Chain under = NeedChain.resolve("cobblestone", 8, world(Map.of("minecraft:wooden_pickaxe", 1), Map.of())
                .withWhere(new NeedChain.WhereFacts(w -> null, () -> new NeedChain.Surface(5, 0, 75, 0, 5), () -> new int[]{0, 70, 0}, () -> b)));
        assertTrue(under.steps().stream().noneMatch(s -> s instanceof Step.ToBand && ((Step.ToBand) s).minY() == 66), "under ground: the base block is round the bot, seen or not: " + under.steps());
    }
}
