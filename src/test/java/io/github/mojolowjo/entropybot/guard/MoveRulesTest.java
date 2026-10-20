package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AREAS step 4: the move multiplier for Baritone's A* - keep-out impassable, danger dear, inside/edge/outside. */
class MoveRulesTest {
    static final String OW = "minecraft:overworld";
    static final Policy P = Policy.parse("{\"areas\":["
            + "{\"name\":\"pit\",\"type\":\"keep-out\",\"x1\":10,\"y1\":60,\"z1\":10,\"x2\":20,\"y2\":70,\"z2\":20},"
            + "{\"name\":\"swamp\",\"type\":\"danger\",\"x1\":50,\"z1\":50,\"x2\":60,\"z2\":60},"
            + "{\"name\":\"camp\",\"type\":\"work\",\"x1\":0,\"z1\":0,\"x2\":99,\"z2\":99}]}");

    @Test
    void keepOutIsImpassableInsideAndAtTheEdgeFreeOutside() {
        assertEquals(MoveRules.COST_INF, MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 15, 64, 15, 4));
        assertEquals(MoveRules.COST_INF, MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 10, 60, 10, 4), "the edge cell is inside");
        assertEquals(MoveRules.COST_INF, MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 15, 59, 15, 4), "feet below, head inside");
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 9, 64, 10, 4)), "one step outside");
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 15, 58, 15, 4)), "two below: feet and head under it");
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 15, 71, 15, 4)), "above it");
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(P, "minecraft:the_nether", new int[]{0, 64, 0}, 15, 64, 15, 4)), "another dimension");
    }

    @Test
    void aBotInsideAKeepOutBoxWalksOutDearNotStuck() {
        int[] inside = {15, 64, 15};
        assertEquals(4, MoveRules.moveMultiplier(P, OW, inside, 16, 64, 15, 4), "the box is dear while it starts inside");
        assertEquals(4, MoveRules.moveMultiplier(P, OW, new int[]{15, 59, 15}, 16, 64, 15, 4), "head inside counts as inside");
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(P, OW, inside, 9, 64, 15, 4)), "and outside is free: the way out");
        assertEquals(MoveRules.COST_INF, MoveRules.moveMultiplier(P, OW, null, 16, 64, 15, 4), "no start known: impassable");
    }

    @Test
    void dangerIsAMultiplierNeverAVeto() {
        assertEquals(4, MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 55, 64, 55, 4));
        assertEquals(2.5, MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 55, 64, 55, 2.5));
        assertEquals(1, MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 55, 64, 55, 0.2), "never below 1");
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(P, OW, new int[]{0, 64, 0}, 5, 64, 5, 4)), "a work area costs nothing extra");
    }

    @Test
    void earlyOutsAndBounds() {
        Policy none = Policy.parse("{\"areas\":[{\"name\":\"camp\",\"x1\":0,\"z1\":0,\"x2\":99,\"z2\":99}]}");
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(none, OW, new int[]{0, 64, 0}, 5, 64, 5, 4)));
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(null, OW, null, 5, 64, 5, 4)));
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(P, null, null, 5, 64, 5, 4)));
        assertEquals(4, MoveRules.danger(4, 2));
        assertEquals(2, MoveRules.danger(0.5, 2), "below 1: kept");
        assertEquals(2, MoveRules.danger(65, 2), "above 64: kept");
        assertEquals(2, MoveRules.danger(Double.NaN, 2));
        assertEquals(1, P.keepOut.size());
        assertEquals(1, P.danger.size());
        assertEquals("swamp", P.dangerAt(OW, 55, 64, 55).name);
    }
}
