package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6: the cave way reads the known-cave fact - its entry cell (the walk) and its size for the minutes it holds
 * (CaveWay.holdsMinutes, never past cave.exploreMinutes).
 */
class J6CaveWayTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** A known cave of {@code cells} cells 30 m off at its entry 20 21 -10, its yield 3 ore a minute (measured). */
    static CaveWay known(long cells) { return new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 3, true, cells); }

    @Test
    void aKnownCaveBigEnough_isTheCaveWay_walkedToItsEntry_boundByItsSize() {
        CaveWay cw = known(5000);
        Chain c = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(cw));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(Z77CaveWayTest.caveMined(c), c.steps().toString());
        assertTrue(c.steps().contains(new Step.Walk(20, 21, -10, 0)), "the walk to its entry: " + c.steps());
        Step.Mine m = (Step.Mine) c.steps().stream().filter(s -> s instanceof Step.Mine x && x.how() == Step.Mine.How.CAVE).findFirst().orElseThrow();
        assertEquals((int) Math.ceil(Math.min(Tuning.get("cave.exploreMinutes"), cw.holdsMinutes())), m.minutes(), "the minutes: its size or the cap");
    }

    @Test
    void aKnownCaveTooSmallForTheLack_isNoWay_saidWithItsSize() {
        CaveWay small = known(40);                          // 40 cells: well under a minute of walking
        assertTrue(small.holdsMinutes() < 1, "40 cells hold " + small.holdsMinutes() + " min");
        Chain c = NeedChain.resolve("raw_iron", 12, Z77CaveWayTest.world(small));
        assertFalse(Z77CaveWayTest.caveMined(c), c.steps().toString());
        assertTrue(c.why().contains("min its 40 cells hold"), c.why());
    }

    @Test
    void noSizeKnown_theCapAlone() {
        assertEquals(Double.POSITIVE_INFINITY, known(0).holdsMinutes());
    }
}
