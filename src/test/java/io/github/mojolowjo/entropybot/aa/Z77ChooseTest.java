package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 choose (manager 2026-10-26 ~14:40 on run BW, owner's seed: "explore 5 until raw_iron" ran 140 s and failed while
 * the next stand got "walk to 20 21 -10: 23 s, complete"; cycle 5's open line). An explore way wins only against ways with a
 * final answer (rule 15: an unknown is never a verdict); while another way of the same need is still computing the pick is
 * not priced yet - the bot stands (Step.AwaitPaths, rule 22) and the need is priced again when the answer lands. A cheaper
 * priced way still wins at once.
 * <p>BW's shape on the planner data, a stone pickaxe in the bag, 3 raw_iron to get: iron ore seen 8 m (one block, its walk
 * priced) and the cave's iron at 20 21 -10 (two blocks, 76 m, its walk computing). The near way alone digs 1 and explores
 * for the other 2. Hand numbers (TIME_MODEL): a walk is the straight line x 1.3 / 4.317 (8 m 2.41 s, 76 m 22.89 s, BW's
 * 23 s); an iron ore with the stone pickaxe (speed 4, hardness 3, the right tool) 4 / 3 / 30 a tick -> 23 ticks = 1.15 s.
 */
class Z77ChooseTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final Spot NEAR = new Spot(8, 64, 0, 8, "minecraft:iron_ore", 1);
    static final Spot CAVE = new Spot(20, 21, -10, 76, "minecraft:iron_ore", 2);
    static final double TOL = 0.1;
    static final Map<String, Integer> BAG = Map.of("minecraft:stone_pickaxe", 1);

    static Chain rawIron(ScenariosTest.FakePath path, Spot... seen) {
        ScenariosTest.world("wooden", "stone");
        return NeedChain.resolve("raw_iron", 3, ScenariosTest.w(BAG, false, false, Map.of("raw_iron", List.of(seen))).withPath(path));
    }

    /** BW's facts: the cave's walk computing, an explore available -> the bot stands, no explore; the dropped list says why. */
    @Test
    void bwsFacts_aWayStillComputing_standsNotExplores() {
        Chain c = rawIron(new ScenariosTest.FakePath().asLine(NEAR).computing(CAVE), NEAR, CAVE);
        assertTrue(c.ok() && !c.waiting(), c.why());
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), c.steps(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), "no explore while a way computes: " + c.steps());
        assertTrue(c.why().contains("stand (" + NeedChain.PATHS_COMPUTING + "1 way waiting"), c.why());
        String near = c.dropped().stream().filter(d -> d.startsWith("raw_iron 8 m: ")).findFirst().orElseThrow(() -> new AssertionError(c.dropped().toString()));
        assertTrue(near.startsWith("raw_iron 8 m: " + NeedChain.EXPLORE_HELD + "raw_iron): not picked while 1 way has no answer yet (raw_iron 76 m)"), near);
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("raw_iron 76 m: " + PathPrice.NOT_KNOWN + " yet (computing)")), c.dropped().toString());
    }

    /** The cave's walk answered 23 s: the cave way runs - both ores dug, no explore: 2.41 + 22.89 + 3 x 1.15 = 28.75 s. */
    @Test
    void theCaveWalkAnswered_theCaveWayRuns() {
        Chain c = rawIron(new ScenariosTest.FakePath().asLine(NEAR).asLine(CAVE), NEAR, CAVE);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(28.75, c.seconds(), TOL, c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Collect k && java.util.Arrays.equals(k.at(), new int[]{20, 21, -10})), "the cave's iron is dug: " + c.steps());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.AwaitPaths || s instanceof Step.Explore), c.steps().toString());
    }

    /** The same facts with the cave's walk UNREACHABLE: every other way final - the near ore and the explore run, as before. */
    @Test
    void theCaveWalkUnreachable_theExploreRuns() {
        Chain c = rawIron(new ScenariosTest.FakePath().asLine(NEAR).unreachable(CAVE), NEAR, CAVE);
        assertTrue(c.ok() && c.explore(), c.why());
        assertTrue(c.steps().get(0) instanceof Step.Collect k && java.util.Arrays.equals(k.at(), new int[]{8, 64, 0}), c.steps().toString());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Explore), c.steps().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.AwaitPaths), "no stand on a final answer: " + c.steps());
    }

    /**
     * One level up, the same shape: a group pick (any one of raw_iron, oak_log; 2 of it) where the raw_iron way explores (one
     * ore seen 8 m, the rest an explore) and the oak_log way STANDS on its tree's walk still computing (its own requirement
     * waits): the way that stands is taken, not the explore. Before, both were "no price" and the first listed won.
     */
    @Test
    void aWayThatStandsOnItsWalk_beatsAnExplore() {
        ScenariosTest.world("wooden", "stone");
        Spot tree = ScenariosTest.tree(30, 4);
        NeedChain.World w = ScenariosTest.w(BAG, false, false, Map.of("raw_iron", List.of(NEAR), "oak_log", List.of(tree)))
                .withPath(new ScenariosTest.FakePath().asLine(NEAR).computing(tree));
        Chain c = NeedChain.resolveAny("raw_iron or oak_log", List.of("minecraft:raw_iron", "minecraft:oak_log"), 2, w, null);
        assertTrue(c.ok(), c.why());
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), c.steps(), c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("raw_iron: " + NeedChain.EXPLORE_HELD + "raw_iron): not picked while 1 way has no answer yet (oak_log)")), c.dropped().toString());
    }

    /** A priced way that covers the need beside a way still computing: the priced way runs at once, no stand: 2.41 + 3 x 1.15 = 5.86 s. */
    @Test
    void aPricedWay_winsAtOnce_besideOneComputing() {
        Spot near3 = new Spot(8, 64, 0, 8, "minecraft:iron_ore", 3);
        Chain c = rawIron(new ScenariosTest.FakePath().asLine(near3).computing(CAVE), near3, CAVE);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(5.86, c.seconds(), TOL, c.why());
        assertEquals(List.of(new Step.Collect("raw_iron", 3, new int[]{8, 64, 0})), c.steps(), c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("raw_iron 76 m: " + PathPrice.NOT_KNOWN)), c.dropped().toString());
    }
}
