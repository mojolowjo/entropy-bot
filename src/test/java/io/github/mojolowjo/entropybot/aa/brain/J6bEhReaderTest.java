package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b EH review (rule 23): the brain's inputs read the ONE "below the surface" fact (Jobs.belowSurface: the rock
 * over the head or the hole round the feet) - the trip in a sky-open shaft is under ground (was: 0 rock over the head,
 * "the surface").
 */
class J6bEhReaderTest {
    @AfterEach
    void clear() { Tuning.reset(); }

    @Test
    void theTripInASkyOpenShaftIsUnderGround() {
        Inputs in = new Inputs();
        int[] around = {67, 67, 67, 67, 67, 67, 67, 67};
        in.underDepth = SurfaceHeight.belowSurface(0, SurfaceHeight.holeDepth(62, around));     // AaRuntime: Jobs.belowSurface
        assertEquals(5, in.underDepth);
        assertTrue(BatchWay.under(in), "the 5-deep sky-open shaft: the trip is under ground");
        in.underDepth = SurfaceHeight.belowSurface(0, SurfaceHeight.holeDepth(66, around));
        assertFalse(BatchWay.under(in), "a step below the ground round it: on the surface");
        in.underDepth = SurfaceHeight.belowSurface(20, 0);
        assertTrue(BatchWay.under(in), "rock over the head: under ground as before");
        assertFalse(BatchWay.under(null));
    }
}
