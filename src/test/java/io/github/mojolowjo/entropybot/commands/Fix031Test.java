package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.31.0: the dig-out's snow step (b) and the chain's pickaxe craft after a broken pickaxe (trial 11, item 2). */
class Fix031Test {
    @Test void lowSnowIsWalkedThrough() {
        assertTrue(ClimbPlan.snowWalkable(1, false));
        assertTrue(ClimbPlan.snowWalkable(2, false), "2 layers fit under a 2-high gap");
        assertFalse(ClimbPlan.snowWalkable(3, false), "3 layers poke into the block 2 above");
        assertTrue(ClimbPlan.snowWalkable(3, true));
        assertTrue(ClimbPlan.snowWalkable(4, true));
        assertFalse(ClimbPlan.snowWalkable(5, true), "5+ layers are a step up");
        assertFalse(ClimbPlan.snowWalkable(8, true));
    }

}
