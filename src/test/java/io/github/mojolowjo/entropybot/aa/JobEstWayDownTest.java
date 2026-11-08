package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-est (runs JAI 00:45: "goto -190 9 -47 near 10, collect oak_planks 9", no path 45 blocks short; JAD: dug y 57 to 11 after a
 * mineshaft's planks): Baritone's estimate prices a level down at half a fall, so a buried plank ranked like a surface one.
 * An estimated walk to a spot under the feet is charged a stone break per level below estimate.freeDropLevels.
 */
class JobEstWayDownTest {

    private static final PathPrice EST = (x, y, z, d) -> PathPrice.Answer.estimated(new PathPrice.Walk(d * 0.25), "not known after 30 s from here: Baritone's estimate, ranked, not priced");

    private static Chain planks(Spot plankSpot) {
        ScenariosTest.world("wooden");
        Spot tree = new Spot(40, 64, 0, 40, "minecraft:oak_log", 4);
        return NeedChain.resolve("oak_planks", 4, ScenariosTest.w(Map.of(), false, false,
                Map.of("oak_log", List.of(tree), "oak_planks", List.of(plankSpot))).withPath(EST).withFeetAt(new int[]{0, 64, 0}));
    }

    @Test
    void theLevelsDownArePure() {
        assertEquals(0, NeedChain.digLevels(64, 64, 0), "same level");
        assertEquals(0, NeedChain.digLevels(64, 61, 0), "within the free drop");
        assertEquals(42, NeedChain.digLevels(64, 19, 0), "45 down less 3");
        assertEquals(40, NeedChain.digLevels(64, 19, 2), "the known cover is charged by coverS, not twice");
        assertEquals(0, NeedChain.digLevels(64, 80, 0), "above the feet: no dig");
    }

    @Test
    void theDeepPlankPricesAboveTheNearTree() {
        Chain c = planks(new Spot(0, 34, 0, 30, "minecraft:oak_planks", 9));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().get(0).text().startsWith("cut"), "the tree is cut, not the buried plank: " + c.steps());
    }

    @Test
    void theSurfacePlankAtTheSameDistancePricesAsBefore() {
        Chain c = planks(new Spot(0, 64, 30, 30, "minecraft:oak_planks", 9));
        assertTrue(c.ok(), c.why());
        assertFalse(c.steps().get(0).text().startsWith("cut"), "the surface plank at the same distance still beats the farther tree: " + c.steps());
    }

    @Test
    void theProbeLineText() {
        String l = NeedChain.wayProbeLine("minecraft:oak_planks", List.of("tree 20 m", "oak_planks 45 m", "a", "b"),
                List.of(new double[]{30, 5, 20}, new double[]{60, 11, 49}, new double[]{70, 0, 0}, new double[]{80, 0, 0}));
        assertTrue(l.startsWith("[entropybot] probe ways: "), l);
        assertTrue(l.contains("1. tree 20 m 30.0 s (walk 5.0 s, dig 20.0 s, rest 5.0 s)"), l);
        assertTrue(l.contains(" | 3. a "), l);
        assertFalse(l.contains("4. b"), "the top three only: " + l);
    }
}
