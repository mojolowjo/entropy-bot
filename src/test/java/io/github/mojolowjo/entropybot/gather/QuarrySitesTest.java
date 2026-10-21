package io.github.mojolowjo.entropybot.gather;

import io.github.mojolowjo.entropybot.aa.brain.Failures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** regression o1: the quarry site is a priced choice; failed and wet sites are facts every caller shares. */
class QuarrySitesTest {
    /** Stone below y 60 everywhere; water at x -59..-57 (the o1 spot is -59 61 -2). */
    static StonePit.Cell cell(int x, int y, int z) {
        if (x >= -61 && x <= -55 && y >= 58 && y <= 61) return StonePit.Cell.LIQUID;
        return y < 61 ? StonePit.Cell.STONE : StonePit.Cell.AIR;
    }

    static final QuarrySites.Planner PL = f -> StonePit.plan(QuarrySitesTest::cell, f, new int[]{1, 0}, 21);

    final int[] wet = {-59, 61, -2}, dry = {-30, 61, -2}, far = {-10, 61, -2};

    @BeforeEach void fresh() { QuarrySites.bind(new Failures()); }

    @Test void aWaterAdjacentSiteIsNeverChosen() {
        var pick = QuarrySites.choose(List.of(wet, dry), wet, PL, 0);
        assertNotNull(pick.site());
        assertArrayEquals(dry, pick.site());
        assertTrue(pick.dropped().get(0).contains("water or lava"), pick.dropped().toString());
        assertTrue(QuarrySites.held(wet, 1));
    }

    @Test void aFailedSiteIsSkippedByAllThreeCallers() {
        QuarrySites.charge(dry, "dug, gave nothing", 0);
        for (String caller : List.of("bootstrap", "TOOLS", "TORCHES")) {
            var pick = QuarrySites.choose(List.of(dry, far), dry, PL, 1000);
            assertArrayEquals(far, pick.site(), caller);
            assertTrue(pick.dropped().get(0).startsWith("-30 -2: dug, gave nothing"), caller + " " + pick.dropped());
        }
        assertEquals(1, QuarrySites.failures().fails(QuarrySites.DESIRE, "-30 -2"));
    }

    @Test void noSiteSaysWhy() {
        var pick = QuarrySites.choose(List.of(wet), wet, PL, 0);
        assertNull(pick.site());
        assertTrue(pick.terms().contains("water or lava"), pick.terms());
    }

    @Test void theCheaperSiteWins() {
        var pick = QuarrySites.choose(List.of(far, dry), dry, PL, 0);
        assertArrayEquals(dry, pick.site());
        assertTrue(pick.here(dry));
    }
}
