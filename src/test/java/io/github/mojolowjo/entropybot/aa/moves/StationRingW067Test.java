package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-own-aside-worker (worlds run 4, w067, a YES in run 3: "place crafting_table near me under ground (a cell with room at
 * my height): no station cell with room within 3 blocks of 172 40 -186 (0 free cells without a standable ring)"). The
 * suspect was job-review-leftovers' StationPlace change (f45c10e1: the ring a Tuning entry; a56c8196: the ring read for a
 * shaft open to the sky). Replayed on w067's numbers (feet 172 40 -186, "under ground (surface 35 up)": the leaf-free
 * surface 75): the feet column is not on the surface by the check before that change either, so the ring is never read
 * and the request is the same as before it - the plain under-ground words the log shows, not the shaft's. The "0 free
 * cells" is the fork's own count (LegRules.stationCells: no cell within 3 of the feet, at y 39-41, empty with empty above
 * and a solid top below), not a refusal by the ring.
 */
class StationRingW067Test {
    @AfterEach
    void reset() { Tuning.reset(); }

    static final int[] FEET = {172, 40, -186};
    static final int NO_LEAVES = 75;                 // run 4 w067 log line 1026: "at 172 40 -186, under ground (surface 35 up)"

    @Test
    void w067_theRingRefusedNothing_theRequestIsThePlainUnderGroundOne() {
        assertFalse(StationPlace.onSurface(FEET[1], NO_LEAVES), "the check before job-review-leftovers: under ground too");
        for (int ringY : new int[]{NO_LEAVES, NO_LEAVES - 10, FEET[1], SurfaceHeight.NOT_KNOWN}) {
            assertFalse(StationPlace.onSurface(FEET[1], NO_LEAVES, ringY), "the ring never lifts a column under ground to the surface: ring " + ringY);
            StationPlace.Ask a = StationPlace.ask(FEET, NO_LEAVES, ringY, null);
            assertFalse(a.surface());
            assertEquals("near me under ground (a cell with room at my height)", a.why(), "w067's own words: the ring was not read (ring " + ringY + ")");
            assertArrayEquals(FEET, a.center());
        }
        // the Tuning entry is the old constant's value: the same ring of columns as before f45c10e1
        assertEquals(2, Tuning.i("station.ring"));
        int[] seen = {0};
        StationPlace.ringSurface((x, z) -> { seen[0]++; assertEquals(2, Math.max(Math.abs(x - FEET[0]), Math.abs(z - FEET[2]))); return NO_LEAVES; }, FEET[0], FEET[2]);
        assertEquals(16, seen[0], "the 16 columns two out, as the old RING = 2");
    }
}
