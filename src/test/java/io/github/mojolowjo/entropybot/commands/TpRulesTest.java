package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** B7e: the sim's "tp" run as pure tests: when a walk starts with /home, and when a /home counts as done or failed. */
class TpRulesTest {
    static final String OW = "minecraft:overworld", NETHER = "minecraft:the_nether";
    /** The sim's home: "sethome" at -15 53 167; the base 1 north of it. */
    static final int[] HOME = {-15, 53, 167}, BASE = {-15, 53, 166};

    static boolean worth(int[] dest, String destDim, int[] me, String botDim) {
        return TpRules.worth(HOME, OW, dest, destDim, me, botDim, 10_000, -100_000);
    }

    @Test
    void farFromANearHomeSpotTeleportsFirst() {
        assertTrue(worth(BASE, null, new int[]{60, 53, 167}, OW), "base from 75 blocks away: /home, then the last bit");
        assertTrue(worth(new int[]{-15, 53, 165}, null, new int[]{-15, 20, 167}, OW), "33 levels below (a seq walk to a base chest)");
        assertFalse(worth(BASE, null, new int[]{-20, 53, 170}, OW), "close to the base: just walks");
    }

    @Test
    void theEdges() {
        assertFalse(worth(BASE, null, new int[]{-15 + 40, 53, 166}, OW), "40 across is not far");
        assertTrue(worth(BASE, null, new int[]{-15 + 41, 53, 166}, OW), "41 is");
        assertFalse(worth(BASE, null, new int[]{-15, 53 - 10, 166}, OW), "10 levels is not far");
        assertTrue(worth(BASE, null, new int[]{-15, 53 + 11, 166}, OW), "11 is (up as well as down)");
        int[] edge = {-15 + 24, 53, 167};
        assertTrue(worth(edge, null, new int[]{200, 53, 167}, OW), "24 from home still counts as near home");
        assertFalse(worth(new int[]{-15 + 25, 53, 167}, null, new int[]{200, 53, 167}, OW), "25 doesn't: /home wouldn't help");
        assertFalse(worth(new int[]{500, 60, 500}, null, new int[]{900, 60, 900}, OW), "a far spot: walk");
    }

    @Test
    void dimensions() {
        assertTrue(worth(BASE, OW, new int[]{0, 70, 0}, NETHER), "the bot in another dimension: /home");
        assertFalse(worth(BASE, NETHER, new int[]{60, 53, 167}, OW), "a spot in another dimension than home: never");
        assertTrue(TpRules.worth(HOME, null, BASE, null, new int[]{60, 53, 167}, OW, 10_000, -100_000), "a home without a dimension is the overworld");
    }

    @Test
    void noHomeOrJustFailedMeansWalk() {
        assertFalse(TpRules.worth(null, null, BASE, null, new int[]{60, 53, 167}, OW, 10_000, -100_000), "no home known");
        assertFalse(TpRules.worth(HOME, OW, null, null, new int[]{60, 53, 167}, OW, 10_000, -100_000), "no destination");
        assertFalse(TpRules.worth(HOME, OW, BASE, null, new int[]{60, 53, 167}, OW, 10_000, 10_000 - 100), "a /home that didn't move me 5 s ago");
        assertFalse(TpRules.worth(HOME, OW, BASE, null, new int[]{60, 53, 167}, OW, 10_000, 10_000 - TpRules.RETRY_TICKS + 1), "not within the minute");
        assertTrue(TpRules.worth(HOME, OW, BASE, null, new int[]{60, 53, 167}, OW, 10_000, 10_000 - TpRules.RETRY_TICKS), "a minute later: tries again");
        assertEquals(1200, TpRules.RETRY_TICKS);
    }

    @Test
    void aHomeIsDoneWhenTheBotJumpedAndFailsAfterTenSeconds() {
        int[] at = {60, 53, 167};
        assertEquals("ok", TpRules.result(at, OW, HOME, OW, 100, 105), "landed at home");
        assertEquals("ok", TpRules.result(at, NETHER, at, OW, 100, 105), "another dimension counts as a jump");
        assertEquals("ok", TpRules.result(at, OW, new int[]{69, 53, 167}, OW, 100, 105), "9 blocks is a jump");
        assertNull(TpRules.result(at, OW, new int[]{68, 53, 167}, OW, 100, 105), "8 blocks (a step or two of walking) is not");
        assertNull(TpRules.result(at, OW, at, OW, 100, 100 + TpRules.WAIT_TICKS - 1), "still waiting (a server warm-up)");
        assertEquals("failed", TpRules.result(at, OW, at, OW, 100, 100 + TpRules.WAIT_TICKS), "10 s and no move: /home did nothing");
    }
}
