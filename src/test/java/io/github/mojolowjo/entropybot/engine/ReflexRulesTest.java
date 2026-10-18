package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReflexRulesTest {

    @Test
    void nearestShoreIsTheClosestDryCell() {
        // a lake round 0 62 0: dry ground east from x 7 (at y 62) and north from z -5 (one up, y 63)
        ReflexRules.Dry dry = (x, y, z) -> (x >= 7 && y == 62) || (z <= -5 && y == 63);
        assertArrayEquals(new int[]{0, 63, -5}, ReflexRules.nearestShore(0, 62, 0, 32, dry));
        assertArrayEquals(new int[]{7, 62, 0}, ReflexRules.nearestShore(2, 62, 0, 32, dry));
        assertNull(ReflexRules.nearestShore(0, 62, 0, 4, dry), "none within 4");
        assertNull(ReflexRules.nearestShore(0, 62, 0, 32, (x, y, z) -> y == 70), "too far up");
    }
    @Test
    void eatsAtFourteenOrWhenHurtBelowEighteen() {
        assertTrue(ReflexRules.wantsMeal(14, 20));
        assertFalse(ReflexRules.wantsMeal(15, 20));
        assertTrue(ReflexRules.wantsMeal(17, 17));
        assertFalse(ReflexRules.wantsMeal(18, 10));
        assertFalse(ReflexRules.wantsMeal(20, 4));
    }

    @Test
    void neverEatsTheBlacklistOrHarmfulFood() {
        assertEquals(-1, ReflexRules.foodScore("minecraft:rotten_flesh", 4, 0.8f, true, 20));
        assertEquals(-1, ReflexRules.foodScore("minecraft:chorus_fruit", 4, 2.4f, false, 20));
        assertEquals(-1, ReflexRules.foodScore("minecraft:spider_eye", 2, 3.2f, true, 20));
        assertEquals(-1, ReflexRules.foodScore("minecraft:pufferfish", 1, 0.2f, true, 20));
        assertEquals(-1, ReflexRules.foodScore("somemod:bad_berry", 3, 1f, true, 20));
        assertTrue(ReflexRules.foodScore("minecraft:bread", 5, 6f, false, 20) > 0);
    }

    @Test
    void goldenApplesOnlyBelowSixHearts() {
        assertEquals(-1, ReflexRules.foodScore("minecraft:golden_apple", 4, 9.6f, false, 12));
        assertTrue(ReflexRules.foodScore("minecraft:golden_apple", 4, 9.6f, false, 11) > 0);
        assertEquals(-1, ReflexRules.foodScore("minecraft:enchanted_golden_apple", 4, 9.6f, false, 20));
    }

    @Test
    void betterFoodScoresHigher() {
        assertTrue(ReflexRules.foodScore("minecraft:cooked_beef", 8, 12.8f, false, 20) > ReflexRules.foodScore("minecraft:bread", 5, 6f, false, 20));
    }

    @Test
    void monstersBehindRockDontCountUnlessHurt() {
        assertTrue(ReflexRules.counts(6, false, true));
        assertFalse(ReflexRules.counts(6, false, false));
        assertTrue(ReflexRules.counts(2, false, false));     // right here: it counts, seen or not
        assertFalse(ReflexRules.counts(9, false, true));     // beyond 8
        assertTrue(ReflexRules.counts(20, true, false));     // just hurt: 24 blocks, sight or not
        assertFalse(ReflexRules.counts(25, true, true));
    }

    @Test
    void awayFromPointsAwayFromTheThreat() {
        int[] a = ReflexRules.awayFrom(0.5, 0.5, 3.5, 0.5, 12);
        assertEquals(-12, a[0]);
        assertEquals(0, a[1]);
    }
}
