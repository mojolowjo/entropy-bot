package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6: the cave way reads the known-cave fact - its entry cell (the walk) and its size BUCKET for the minutes it
 * holds (CaveWay.holdsMinutes, never past cave.exploreMinutes); no cell list reaches the planner (the manager's ruling).
 */
class J6CaveWayTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** A known cave of size {@code size} 30 m off at its entry 20 21 -10, its yield 3 ore a minute (measured). */
    static CaveWay known(CaveIndex.Size size) { return new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 3, true, size); }

    @Test
    void aKnownCaveBigEnough_isTheCaveWay_walkedToItsEntry_boundByItsSize() {
        CaveWay cw = known(CaveIndex.Size.LARGE);
        Chain c = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(cw));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(Z77CaveWayTest.caveMined(c), c.steps().toString());
        assertTrue(c.steps().contains(new Step.Walk(20, 21, -10, 0)), "the walk to its entry: " + c.steps());
        Step.Mine m = (Step.Mine) c.steps().stream().filter(s -> s instanceof Step.Mine x && x.how() == Step.Mine.How.CAVE).findFirst().orElseThrow();
        assertEquals((int) Math.ceil(Math.min(Tuning.get("cave.exploreMinutes"), cw.holdsMinutes())), m.minutes(), "the minutes: its size or the cap");
    }

    @Test
    void aKnownCaveTooSmallForTheLack_isNoWay_saidWithItsSize() {
        CaveWay small = known(CaveIndex.Size.SMALL);        // under a minute of walking
        assertEquals(1, small.holdsMinutes(), "a small cave holds its upper edge, one minute");
        Chain c = NeedChain.resolve("raw_iron", 12, Z77CaveWayTest.world(small));
        assertFalse(Z77CaveWayTest.caveMined(c), c.steps().toString());
        assertTrue(c.why().contains("min a small cave holds"), c.why());
    }

    @Test
    void noSizeKnown_theCapAlone() {
        assertEquals(Double.POSITIVE_INFINITY, known(null).holdsMinutes());
    }

    /** The manager's ruling: the known fact reaches the planner as an entry, a size bucket and a depth band - never a cell list or a cell count. */
    @Test
    void noCellListReachesThePlanner() {
        for (Class<?> r : new Class<?>[]{CaveWay.class, io.github.mojolowjo.entropybot.sense.Sightings.KnownCave.class})
            for (var c : r.getRecordComponents()) {
                assertFalse(c.getName().toLowerCase(java.util.Locale.ROOT).contains("cell"), r.getSimpleName() + "." + c.getName());
                assertFalse(c.getName().equals("minY") || c.getName().equals("maxY"), r.getSimpleName() + "." + c.getName());
            }
        for (var c : CaveWay.class.getRecordComponents())
            assertFalse(java.util.Collection.class.isAssignableFrom(c.getType()) || c.getType().isArray(), "CaveWay." + c.getName() + " is a list");
        assertTrue(java.util.Arrays.stream(io.github.mojolowjo.entropybot.sense.Sightings.KnownCave.class.getRecordComponents()).anyMatch(c -> c.getType() == CaveIndex.Size.class));
        assertTrue(java.util.Arrays.stream(io.github.mojolowjo.entropybot.sense.Sightings.KnownCave.class.getRecordComponents()).anyMatch(c -> c.getType() == CaveIndex.Depth.class));
    }
}
