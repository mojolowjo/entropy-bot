package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.commands.SegmentPicks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-segpick (run JAR 04:09:41: the stand walked the first segment of a way to mineshaft planks 55 blocks under the feet,
 * picked by Baritone's raw estimate, and abandoned it a second later on the price ask's timeout).
 */
class SegmentPickTest {
    @AfterEach void reset() { Tuning.reset(); }

    private static final String TREE = "20,64,0", DEEP = "-190,9,-57";

    @Test
    void theDeepSpotIsNotPickedOverTheNearTree() {
        ScenariosTest.world("wooden");
        Map<String, Double> raw = Map.of(TREE, 5.0, DEEP, 3.0);   // Baritone's heuristic: a level down is half a fall, no dig
        assertEquals(DEEP, SegmentPicks.cheapest(List.of(TREE, DEEP), raw::get), "the raw estimate alone picks the deep way");
        String best = SegmentPicks.cheapest(List.of(TREE, DEEP), k -> {
            int y = Integer.parseInt(k.split(",")[1]);
            return SegmentPicks.wayS(raw.get(k), 64, y, null, 0);
        });
        assertEquals(TREE, best, "the mod's estimate (a stone break per level past the free drop) puts the deep way behind the tree");
        assertTrue(NeedChain.wayDownS(64, 9, null, 0) > 0);
        assertEquals(0, NeedChain.wayDownS(64, 64, null, 0));
        assertTrue(Double.isNaN(SegmentPicks.wayS(Double.NaN, 64, 9, null, 0)), "not known stays not known");
    }

    @Test
    void aLongSegmentIsNotWalked() {
        assertEquals(60, Tuning.get("pathCost.firstSegmentMaxS"));
        assertTrue(SegmentPicks.segmentTooLong(39677), "run JAR's 55-cell segment");
        assertFalse(SegmentPicks.segmentTooLong(60 * 20));
        Tuning.set("pathCost.firstSegmentMaxS", 5000);
        assertFalse(SegmentPicks.segmentTooLong(39677));
    }

    @Test
    void anAbandonedWayIsNotRetriedInsideTheWindow() {
        assertEquals(30, Tuning.get("pathCost.segmentRetryS"));
        SegmentPicks.SegmentHold h = new SegmentPicks.SegmentHold();
        h.hold(DEEP, 1000);
        assertTrue(h.held(DEEP, 1000));
        assertTrue(h.held(DEEP, 1000 + 30 * 20 - 1));
        assertFalse(h.held(TREE, 1001), "other ways are not held");
        assertFalse(h.held(DEEP, 1000 + 30 * 20), "past the window it may be walked again");
    }
}
