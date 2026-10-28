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
        assertTrue(c.steps().contains(new Step.ToCave(20, 21, -10)), "J6b run EF: the walk to its lip (the entry in reach): " + c.steps());
        Step.Cave m = (Step.Cave) c.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertEquals((int) Math.ceil(Math.min(Tuning.get("cave.exploreMinutes"), cw.holdsMinutes())), m.minutes(), "the minutes: its size or the cap");
        assertEquals(java.util.List.of(20, 21, -10), java.util.List.of(m.x(), m.y(), m.z()), "the caving step at the known cave's entry");
    }

    @Test
    void aKnownCaveTooSmallForTheLack_givesItsShare_theRestFromTheNextWay() {
        CaveWay small = known(CaveIndex.Size.SMALL);        // under a minute of walking
        assertEquals(1, small.holdsMinutes(), "a small cave holds its upper edge, one minute");
        // 0.39.z78 J6c run ET (B, rule 23 (1)): was "no way" - now its share (the yield over the minute it holds) and the
        // rest of the need from the next way (here the explore)
        Chain c = NeedChain.resolve("raw_iron", 12, Z77CaveWayTest.world(small));
        int share = NeedChain.caveShare(small);
        assertTrue(share >= 1 && share < 12, "a share of the lack: " + share);
        Step.Cave m = (Step.Cave) c.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertEquals(share, m.n(), "the caving step mines its share: " + c.steps());
        assertTrue(c.why().contains(share + " of 12 ore from the cave, the other " + (12 - share) + " from: "), c.why());
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
            // J6b review 6: leftKeys are the SEEN ORE spots left there (what a collect way sees anyway, by sight), never the cave's cells
            // J6b run EF (3): entries are the cave's OPENING cells to walk to the lip of (walk targets), never its volume's cells
            // run EM: more is the OTHER known caves ranked after this one (each a CaveWay of the same facts), never cells
            assertFalse(!c.getName().equals("leftKeys") && !c.getName().equals("entries") && !c.getName().equals("more") && (java.util.Collection.class.isAssignableFrom(c.getType()) || c.getType().isArray()), "CaveWay." + c.getName() + " is a list");
        assertTrue(java.util.Arrays.stream(io.github.mojolowjo.entropybot.sense.Sightings.KnownCave.class.getRecordComponents()).anyMatch(c -> c.getType() == CaveIndex.Size.class));
        assertTrue(java.util.Arrays.stream(io.github.mojolowjo.entropybot.sense.Sightings.KnownCave.class.getRecordComponents()).anyMatch(c -> c.getType() == CaveIndex.Depth.class));
    }
}
