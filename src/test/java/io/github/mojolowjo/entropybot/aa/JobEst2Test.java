package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-est2 (run JAY 07:17:49: "dig oak_planks seen 70 m, walk 2205.7 s (path)" - a mineshaft's planks 53 levels down - won at
 * once over a dark oak whose walk was still computing; the bot dug y 62 to 26 and failed 27 blocks short at 07:22:25).
 */
class JobEst2Test {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final Spot DEEP = new Spot(0, 9, 40, 40, "minecraft:oak_planks", 9);
    static final Spot TREE = new Spot(40, 64, 0, 40, "minecraft:oak_log", 4);
    static final double DEEP_S = 2205.7;

    private static Chain planks(PathPrice path) {
        ScenariosTest.world("wooden");
        return NeedChain.resolve("oak_planks", 4, ScenariosTest.w(Map.of(), false, false,
                Map.of("oak_log", List.of(TREE), "oak_planks", List.of(DEEP))).withPath(path).withFeetAt(new int[]{0, 62, 0}));
    }

    private static PathPrice path(PathPrice.Answer tree) {
        return (x, y, z, d) -> x == DEEP.x() && y == DEEP.y() && z == DEEP.z() ? PathPrice.Answer.priced(new PathPrice.Walk(DEEP_S)) : tree;
    }

    @Test
    void thePricedDeepPlank_isHeldWhileTheTreeComputes() {
        Chain c = planks(path(PathPrice.Answer.computing(null)));
        assertTrue(c.ok(), c.why());
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), c.steps(), c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains(": " + NeedChain.PRICED_HELD) && d.contains(", over the stand's wait 30.0 s): not picked while 1 way has no answer yet")), c.dropped().toString());
    }

    @Test
    void thePricedDeepPlank_losesToThePricedTree() {
        Chain c = planks(path(PathPrice.Answer.priced(new PathPrice.Walk(12.0))));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().get(0).text().startsWith("cut"), "the tree is cut: " + c.steps());
    }

    @Test
    void thePricedDeepPlank_losesToTheEstimatedTree() {
        Chain c = planks(path(PathPrice.Answer.estimated(new PathPrice.Walk(10.0), "Baritone's estimate, ranked, not priced")));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().get(0).text().startsWith("cut"), "the tree is cut: " + c.steps());
    }

    @Test
    void aCheapPricedWay_stillWinsAtOnce() {
        Spot near = new Spot(5, 62, 0, 5, "minecraft:oak_planks", 9);
        ScenariosTest.world("wooden");
        Chain c = NeedChain.resolve("oak_planks", 4, ScenariosTest.w(Map.of(), false, false,
                Map.of("oak_log", List.of(TREE), "oak_planks", List.of(near)))
                .withPath((x, y, z, d) -> x == 5 ? PathPrice.Answer.priced(new PathPrice.Walk(2.0)) : PathPrice.Answer.computing(null))
                .withFeetAt(new int[]{0, 62, 0}));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.AwaitPaths), "a way under the stand's wait runs: " + c.steps());
    }

    @Test
    void aWalkThatDugDeep_holdsTheSpotWithTheDepth() {
        List<Step> steps = List.of(new Step.Walk(-190, 9, -47, 10), new Step.Collect("oak_planks", 9, new int[]{-190, 9, -47}));
        FactChange.Fact f = Steps.walkLeads(steps, 0, "no path to -190 9 -47 - stuck at -170 26 -41, 27 blocks short",
                new int[]{-154, 62, -38}, new int[]{-170, 26, -41});
        assertNotNull(f);
        assertTrue(f.key().contains("collect oak_planks at -190 9 -47"), f.key());
        assertTrue(f.text().contains("dug 36 levels, from y 62 to y 26: the spot held"), f.text());
        FactChange.Fact shallow = Steps.walkLeads(steps, 0, "no path", new int[]{0, 62, 0}, new int[]{0, 58, 0});
        assertFalse(shallow.text().contains("dug "), shallow.text());
    }
}
