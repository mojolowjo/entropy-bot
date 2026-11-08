package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.BandDirect;
import io.github.mojolowjo.entropybot.aa.moves.BandDry;
import io.github.mojolowjo.entropybot.aa.moves.BandEnd;
import io.github.mojolowjo.entropybot.aa.moves.BandHold;
import io.github.mojolowjo.entropybot.aa.moves.BandLeg;
import io.github.mojolowjo.entropybot.guard.BreakPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** job-dig3 (run JAV): the reused column starts dry; repeated not-known ends hold the column; the dig's lease breaks. */
class Dig3Test {
    private static final List<Boolean> DRY = List.of(false, false, false, false);
    private static final List<Boolean> WET = List.of(true, false, false, false);

    @Test
    void reusedColumnFromWaterWalksToADryCellFirst() {
        assertFalse(BandDirect.startDry(true, true, DRY, DRY), "afloat: not dry, even with a reused column");
        assertFalse(BandDirect.startDry(true, false, DRY, WET), "the reused column's top is wet: not dry");
        assertTrue(BandDirect.startDry(true, false, DRY, DRY));
        assertTrue(BandDirect.startDry(true, false, DRY, null), "no reuse: the start alone");
        assertEquals(BandDry.Act.STEP_DRY, BandDry.act(BandDirect.startDry(true, false, DRY, WET), 5), "the walk to a dry cell first");
    }

    @Test
    void threeNotKnownEndsHoldTheColumnAndTheSideStepRuns() {
        BandHold.clear();
        int x = -135, y = 16, z = -15;
        long t0 = 1_000_000;
        assertFalse(BandHold.notKnown(x, y, z, t0, 3, 30));
        assertFalse(BandHold.notKnown(x, y, z, t0 + 2_000, 3, 30));
        assertFalse(BandHold.held(x, y, z));
        assertTrue(BandHold.notKnown(x, y, z, t0 + 4_000, 3, 30), "the third end in 30 s holds the column");
        assertTrue(BandHold.held(x, y, z));
        String w = BandHold.words(x, z, 3, 30);
        assertArrayEquals(new int[]{x, z}, BandEnd.heldColumn(w), "the band way reads the held column");
        int[] side = BandEnd.sideStep(new int[]{x, 65, z}, BandEnd.heldColumn(w), 6);
        assertNotNull(side, "the side-step picks another column");
        assertFalse(BandHold.held(side[0], y, side[1]));
        assertFalse(BandHold.dropWords(y, BandHold.heldAt(y)).isBlank());
        BandHold.clear();
    }

    @Test
    void endsOutsideTheWindowDoNotHold() {
        BandHold.clear();
        assertFalse(BandHold.notKnown(1, 16, 1, 0, 3, 30));
        assertFalse(BandHold.notKnown(1, 16, 1, 31_000, 3, 30));
        assertFalse(BandHold.notKnown(1, 16, 1, 62_000, 3, 30));
        assertFalse(BandHold.held(1, 16, 1));
        BandHold.clear();
    }

    @Test
    void theDigLeasesBreaks() {
        assertEquals(BreakPolicy.Intent.TRAVEL, BandLeg.INTENT, "the same lease the priced walk dug 46 levels with (run JAT)");
        assertNotEquals(BreakPolicy.Natural.NEVER, BreakPolicy.RuleSet.BASE.breakNatural(), "TRAVEL breaks are priced, not refused");
    }
}
