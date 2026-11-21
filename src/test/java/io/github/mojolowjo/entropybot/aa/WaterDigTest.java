package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cloud tester runs 10 and 11 (2026-11-21, seed entropy-a beside a pond; the owner's yes: "no dig-down from a wet start;
 * after water, another column further off"). Run 10: "water came into the column mid-dig" held "to y 51..63", the next plan's
 * band was "to y 46..58" and the same dig started 6 s later 5 blocks away; then "dig -261 49 -94 -261 62 -94: blocked by
 * water at -261 60 -94" had no fact and the order parked. Run 11: the same park at -282 49 -76.
 */
class WaterDigTest {
    static final String STONE = "minecraft:stone", DIRT = "minecraft:dirt";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** A cover column with water beside one of its cells is no source (the dig hand leaves a block that touches a fluid). */
    @Test
    void aColumnWithWaterBesideItIsNoSource() {
        NearbyExposureTest.World w = new NearbyExposureTest.World().put(5, 48, 0, STONE);
        for (int y = 49; y <= 61; y++) w.put(5, y, 0, DIRT);
        w.air(5, 62, 0);
        Map<String, Integer> buried = new HashMap<>();
        assertEquals(13, Nearby.withCover(w, new Spot(5, 48, 0, 13, STONE), buried).cover(), "dry: a source with its cover");
        w.put(6, 60, 0, "minecraft:water");
        assertNull(Nearby.withCover(w, new Spot(5, 48, 0, 13, STONE), buried));
        assertEquals(1, buried.getOrDefault(STONE + Nearby.FLUID_BESIDE, 0), buried.toString());
    }

    /** A cover dig that ended failed is its spot's near-collect fact (the order re-plans, never a park with no fact). */
    @Test
    void aFailedCoverDigIsItsSpotsFact() {
        Step.Dig dig = new Step.Dig(new int[]{-261, 49, -94}, new int[]{-261, 62, -94}, false, false);
        List<Step> steps = List.of(new Step.Walk(-261, 63, -94, 2), dig, new Step.Collect("cobblestone", 32, new int[]{-261, 48, -94}));
        FactChange.Fact f = Steps.digFact(steps, 1, "error: blocked by water at -261 60 -94 - broke 1 block");
        assertNotNull(f);
        assertEquals("source " + NeedChain.nearKey("cobblestone", new int[]{-261, 48, -94}), f.key());
        assertEquals(NeedChain.nearKey("cobblestone", new int[]{-261, 48, -94}), FactChange.ownSourceKey(f), "held by its own key, not the step's");
        assertTrue(f.text().contains("blocked by water at -261 60 -94"), f.text());
        assertNull(Steps.digFact(steps, 0, "x"), "not a dig");
        assertNull(Steps.digFact(List.of(dig), 0, "x"), "no collect of the spot under it: the owner's own dig");
        assertNull(Steps.digFact(List.of(new Step.Dig(new int[]{0, 60, 0}, new int[]{3, 62, 3}, false, false), new Step.Collect("cobblestone", 1, new int[]{0, 59, 0})), 0, "x"), "a box, not a column");
    }

    /** The dig down's fact holds the place by its own key (never the step's band key), lifted by the bot's move. */
    @Test
    void theDigDownFactHoldsThePlace() {
        FactChange.digDown("stopped at y 62 of 51: water came into the column mid-dig");
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + NeedChain.DIG_DOWN_KEY, f.key());
        assertEquals(NeedChain.DIG_DOWN_KEY, FactChange.ownSourceKey(f));
    }

    /** A dig down held near here is not offered: no ToBand step, whatever the band's Y would be. */
    @Test
    void aHeldDigDownIsNotOfferedAgainNearby() {
        Z74RunACTest.data();
        Chain open = NeedChain.resolve("cobblestone", 2, J6bEpTest.pond(62));
        assertTrue(open.steps().stream().anyMatch(s -> s instanceof Step.ToBand), open.steps().toString());
        Chain held = NeedChain.resolve("cobblestone", 2, J6bEpTest.pond(62).withoutSources(Map.of(NeedChain.DIG_DOWN_KEY, "water came into the column mid-dig")));
        assertTrue(held.steps().stream().noneMatch(s -> s instanceof Step.ToBand), held.steps().toString());
    }
}
