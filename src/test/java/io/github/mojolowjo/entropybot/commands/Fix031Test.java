package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.gather.GatherRules;
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

    @Test void aHouseDigWithoutAPickaxeCraftsOne() {
        String msg = "stopped: digging -70 57 -16 to -64 61 -10 - needs a pickaxe for stone (broke 168 blocks) - next: craft wooden_pickaxe 1";
        String step = "dig -70 57 -16 -64 61 -10";
        assertEquals("craft stone_pickaxe 1", GatherRules.chainPickaxeFix(step, msg, Map.of("minecraft:cobblestone", 125, "minecraft:stick", 2), 0));
        assertEquals("craft stone_pickaxe 1", GatherRules.chainPickaxeFix(step, msg, Map.of("cobblestone", 3, "oak_planks", 2), 0), "short ids too");
        assertEquals("craft wooden_pickaxe 1", GatherRules.chainPickaxeFix(step, msg, Map.of("minecraft:oak_log", 2), 0));
        assertNull(GatherRules.chainPickaxeFix(step, msg, Map.of("minecraft:dirt", 64), 0), "nothing to make one from");
        assertNull(GatherRules.chainPickaxeFix(step, msg, Map.of("minecraft:cobblestone", 9, "minecraft:stick", 9), GatherRules.MAX_CHAIN_PICK_FIXES));
        assertNull(GatherRules.chainPickaxeFix("dig stone 10", msg, Map.of("minecraft:cobblestone", 9, "minecraft:stick", 9), 0), "gather has its own");
        assertNull(GatherRules.chainPickaxeFix("build roof cobblestone house_main", msg, Map.of("minecraft:cobblestone", 9), 0));
        assertNull(GatherRules.chainPickaxeFix(step, "stopped: blocked by water at 1 2 3", Map.of("minecraft:cobblestone", 9), 0));
        assertEquals("craft wooden_pickaxe 1", GatherRules.chainPickaxeFix(step, "stopped: out of pickaxes (stone needs one)", Map.of("minecraft:oak_planks", 5), 0));
    }
}
