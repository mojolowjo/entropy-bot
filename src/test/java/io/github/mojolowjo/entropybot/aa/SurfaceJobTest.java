package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.WalkEndText;
import io.github.mojolowjo.entropybot.restore.RestoreRules;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-surface (run JAL): the leaf-free top under a crown and under a log, "not known" for an unloaded column, and the walk
 * up's end with a reason (never "stop unknown").
 */
class SurfaceJobTest {
    static final int MIN = -64;

    /** Grass at 63, feet at 64; the column's skipped cells (leaves, air) as given. */
    static int depth(int motionBlocking, Set<Integer> skip, boolean sky) {
        int s = SurfaceHeight.noLeaves(true, motionBlocking, MIN, skip::contains);
        return RestoreRules.rockOverHead(sky, s, 64);
    }

    @Test
    void aBotUnderACrownWithItsFeetOnGrassIsOnTheSurface() {
        // leaves 67..70 over air 64..66, MOTION_BLOCKING 71 (over the top leaf); no sky through the crown
        assertEquals(64, SurfaceHeight.noLeaves(true, 71, MIN, Set.of(64, 65, 66, 67, 68, 69, 70)::contains));
        assertEquals(0, depth(71, Set.of(64, 65, 66, 67, 68, 69, 70), false), "under a crown: 0 under ground");
    }

    @Test
    void aLogOverTheFeetIsSolid() {
        // a log at 68 (not skipped), leaves 69..70 over it, air 64..67
        assertEquals(69, SurfaceHeight.noLeaves(true, 71, MIN, Set.of(64, 65, 66, 67, 69, 70)::contains));
        assertTrue(depth(71, Set.of(64, 65, 66, 67, 69, 70), false) > 0, "a log over the head is a roof");
    }

    @Test
    void anUnloadedColumnIsNotKnownNeverUnderGround() {
        assertEquals(SurfaceHeight.NOT_KNOWN, SurfaceHeight.noLeaves(false, 71, MIN, y -> false));
        assertEquals(0, SurfaceHeight.holeDepth(64, new int[]{SurfaceHeight.NOT_KNOWN, SurfaceHeight.NOT_KNOWN}), "no neighbour known: no depth claimed");
    }

    @Test
    void theWalkEndSaysItsReason() {
        var unknownStop = new WalkEndText.Calc(true, false, false, "unknown");
        String t = WalkEndText.of("-56 66 -12", false, unknownStop, 3, 4, "Pathing failed: goal unreachable");
        assertEquals("ended short at -56 66 -12: Baritone last said 'Pathing failed: goal unreachable'", t);
        assertFalse(WalkEndText.of("-56 66 -12", false, unknownStop, 3, 3, null).contains("unknown"), "no end says unknown");
        assertTrue(WalkEndText.of("1 2 3", false, new WalkEndText.Calc(true, true, false, null), 0, 0, null).contains("no path (the graph exhausted)"));
        assertEquals("ended at its goal -56 68 -12", WalkEndText.of("-56 68 -12", true, WalkEndText.Calc.NONE, 0, 0, null));
    }
}
