package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 vein (owner 2026-10-27, "a vein is not a chop"; fork job-vein): which process a cut / collect runs on, and the
 * step's words from the process's own end (the mine's "none in sight" replaces the deleted explore watch; the vein's
 * "none in view" at the priced spot; the walk to the spot short), each the spot's near-collect fact.
 */
class CollectHandTest {
    @BeforeEach
    void clear() { FactChange.clear(); }

    @Test
    void aTreeAndAStationTakeAreTheChop_oreIsTheVein_anyOtherBlockTheMine() {
        assertEquals(CollectHand.Hand.CHOP, CollectHand.of(false, true, false), "logs: the chop (it approaches a tree seen from afar)");
        assertEquals(CollectHand.Hand.CHOP, CollectHand.of(true, false, true), "a station take: the chop's component at hand, whatever the block");
        assertEquals(CollectHand.Hand.VEIN, CollectHand.of(false, false, true), "ore: the vein (only blocks in view)");
        assertEquals(CollectHand.Hand.MINE, CollectHand.of(false, false, false), "stone, sand: the stock mine under the lease");
        assertEquals("vein", CollectHand.Hand.VEIN.name);
        assertEquals("chop", CollectHand.Hand.CHOP.name);
        assertEquals("mine", CollectHand.Hand.MINE.name);
    }

    /** Run DE's shape (37 35 7, the feet 40 m off at 8 62 2): the fork's mine ends at once; the collect fails with the spot's fact. */
    @Test
    void theMinesOwnNoneInSight_failsTheSpotCollect_inTheExploreWatchsWords() {
        Step.Collect k = new Step.Collect("dark_oak_planks", 2, new int[]{37, 35, 7});
        String why = CollectHand.mineNoneKnown(k, "none in sight (10 blocks of the feet)", new int[]{8, 62, 2}, "collect 2 dark_oak_planks", 0, 2);
        assertNotNull(why);
        assertTrue(why.startsWith("collect 2 dark_oak_planks: Baritone's mine knows no dark_oak_planks in its sight (10 blocks of the feet)"), why);
        assertTrue(why.contains("the priced spot 37 35 7 (40 m) is not what it goes for, got 0 of 2"), why);
        assertTrue(why.contains("its end: none in sight (10 blocks of the feet)"), "the process's own end: " + why);
        FactChange.Fact f = FactChange.take();
        assertNotNull(f, "the spot's near-collect fact is said");
        assertEquals(why, f.text());
        String range = CollectHand.mineNoneKnown(k, "none in range", new int[]{8, 62, 2}, "collect 2 dark_oak_planks", 0, 2);
        assertTrue(range.contains("knows no dark_oak_planks in range"), range);
        assertNull(CollectHand.mineNoneKnown(k, "have 4 valid items", new int[]{8, 62, 2}, "collect 2 dark_oak_planks", 2, 2), "another end: as it is");
        assertNull(CollectHand.mineNoneKnown(new Step.Collect("dark_oak_planks", 2), "none in range", new int[]{8, 62, 2}, "c", 0, 2), "no priced spot: the end as it is");
    }

    @Test
    void theVeinsNoneInViewAtTheSpot_isTheSpotsFact_otherEndsAreTheChopsRules() {
        Step.Collect k = new Step.Collect("raw_iron", 3, new int[]{12, 40, 5});
        String why = CollectHand.veinNoneInView(k, "none in view (4 of 6 blocks of the vein not in view)", new int[]{13, 40, 5}, "collect 3 raw_iron", 1, 3);
        assertNotNull(why);
        assertTrue(why.contains("Baritone's vein has no raw_iron in view at the priced spot 12 40 5"), why);
        assertTrue(why.contains("none in view (4 of 6 blocks of the vein not in view)") && why.endsWith("got 1 of 3"), why);
        assertEquals(why, FactChange.take().text());
        assertNull(CollectHand.veinNoneInView(k, "none in view", new int[]{13, 40, 5}, "c", 3, 3), "the want met: done whatever it says");
        assertNull(CollectHand.veinNoneInView(k, "cluster 6 blocks: picked up 4 of 6", new int[]{13, 40, 5}, "c", 1, 3), "a short pickup: ChopEnd's words");
    }

    /** Review of 4e5e57e1: a vein whose goal cells all drift from the priced spot raises the spot's near-collect fact, as the mine's. */
    @Test
    void aVeinGoingForBlocksAwayFromTheSpot_isTheSpotsFact_asTheMines() {
        Step.Collect k = new Step.Collect("raw_iron", 3, new int[]{12, 40, 5});
        String why = CollectHand.targetAway(k, java.util.List.of(new int[]{40, 40, 5}, new int[]{41, 39, 5}), 16, CollectHand.Hand.VEIN, "collect 3 raw_iron", 1, 3);
        assertNotNull(why);
        assertEquals("collect 3 raw_iron: Baritone's vein goes for blocks away from the priced spot 12 40 5 (its 2 goal cells all more than failures.moved 16 from it), got 1 of 3", why);
        assertEquals(why, FactChange.take().text(), "the near-collect fact is written");
        assertNull(CollectHand.targetAway(k, java.util.List.of(new int[]{40, 40, 5}, new int[]{14, 40, 5}), 16, CollectHand.Hand.VEIN, "c", 1, 3), "one goal cell near the spot: no claim");
        assertNull(CollectHand.targetAway(k, java.util.List.of(), 16, CollectHand.Hand.VEIN, "c", 1, 3), "no goal cell (a break in reach): no claim");
        assertNull(FactChange.take());
        String mine = CollectHand.targetAway(k, java.util.List.of(new int[]{40, 40, 5}), 16, CollectHand.Hand.MINE, "collect 3 raw_iron", 0, 3);
        assertTrue(mine.startsWith("collect 3 raw_iron: Baritone's mine goes for blocks away from the priced spot 12 40 5 (its 1 goal cell all"), "the mine's words unchanged: " + mine);
    }

    @Test
    void theWalkToTheSpotEndingShort_isTheSpotsFact() {
        Step.Collect k = new Step.Collect("raw_iron", 3, new int[]{12, 40, 5});
        String why = CollectHand.veinWalkShort(k, new int[]{0, 64, 5}, "collect 3 raw_iron", "no path");
        assertTrue(why.startsWith("collect 3 raw_iron: the walk to the priced spot 12 40 5 before the vein ended 27 m from it (no path)"), why);
        assertEquals(why, FactChange.take().text());
    }
}
