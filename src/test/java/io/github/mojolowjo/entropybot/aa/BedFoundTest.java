package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4 fix 3: the sleep verb reads both findBlock answers (live: NumberFormatException "white_bed", 4x). */
class BedFoundTest {
    @Test
    void nearAndFarFormsAndNone() {
        assertArrayEquals(new int[]{-53, 70, -6}, BedSpot.foundAt("found -53 70 -6 block.minecraft.white_bed dist 4"));
        assertArrayEquals(new int[]{-85, 31, -31}, BedSpot.foundAt("found white_bed at -85 31 -31, 41 blocks (loaded chunks)"));
        assertArrayEquals(new int[]{12, 64, 7}, BedSpot.foundAt("remembered red_bed at 12 64 7, 90 blocks (Baritone's memory)"));
        assertArrayEquals(new int[]{1, 2, 3}, BedSpot.foundAt("known white_bed at 1 2 3, 200 blocks (my notes)"));
        assertNull(BedSpot.foundAt("none within 16, in loaded chunks or remembered"));
        assertNull(BedSpot.foundAt("found white_bed"));
        assertNull(BedSpot.foundAt(null));
    }
}
