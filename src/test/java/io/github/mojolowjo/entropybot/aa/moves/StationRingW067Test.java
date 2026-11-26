package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.restore.RestoreRules;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-own-aside-worker (worlds run 4, w067, a YES in run 3: "place crafting_table near me under ground (a cell with room at
 * my height): no station cell with room within 3 blocks of 172 40 -186 (0 free cells without a standable ring)"). Replayed
 * on w067's numbers (feet 172 40 -186, "under ground (surface 35 up)": the leaf-free surface 75). Merge review 2026-11-25
 * findings 11 and 16: the place step reads the planner's own surface fact (NeedChain.Surface.atFeet on the rock over the
 * head and the hole round the feet), so the request is the plain under-ground one whatever the ground round the feet reads.
 * The "0 free cells" is the fork's own count (LegRules.stationCells), not a refusal by the request.
 */
class StationRingW067Test {
    @AfterEach
    void reset() { Tuning.reset(); }

    static final int[] FEET = {172, 40, -186};
    static final int NO_LEAVES = 75;                 // run 4 w067 log line 1026: "at 172 40 -186, under ground (surface 35 up)"

    @Test
    void w067_theRequestIsThePlainUnderGroundOne() {
        int rock = RestoreRules.rockOverHead(false, NO_LEAVES, FEET[1]);
        assertEquals(36, rock);
        for (int ringY : new int[]{NO_LEAVES, NO_LEAVES - 10, FEET[1], SurfaceHeight.NOT_KNOWN}) {
            NeedChain.Surface s = NeedChain.Surface.atFeet(FEET, rock, SurfaceHeight.pitDepth(FEET, (x, z) -> x == FEET[0] && z == FEET[2] ? NO_LEAVES : ringY));
            assertTrue(s.under(), "the ground round never lifts a column under rock to the surface: ring " + ringY);
            StationPlace.Ask a = StationPlace.ask(FEET, s, null);
            assertFalse(a.surface());
            assertEquals("near me under ground (a cell with room at my height)", a.why(), "w067's own words (ring " + ringY + ")");
            assertArrayEquals(FEET, a.center());
        }
    }
}
