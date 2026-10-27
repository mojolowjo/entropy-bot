package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 hotbar (candidate-strip-pace, runs CW-DI: no line named the tool in hand; DI strip 1 4.9 s/cell with the
 * wooden pickaxe in hotbar 1 and the stone pickaxe in bag slot 28): the tool probe's gate, its per-stage trigger and
 * its line.
 */
class Z77ToolProbeTest {

    @Test
    void offByDefault_onWithTheTuning() {
        assertFalse(ProbeLines.toolOn(), "debug.toolProbe ships 0");
        Tuning.set("debug.toolProbe", 1);
        try {
            assertTrue(ProbeLines.toolOn());
        } finally {
            Tuning.set("debug.toolProbe", 0);
        }
        assertFalse(ProbeLines.toolOn());
    }

    @Test
    void oneLinePerBreakStage_aNewBlockStartsAgain() {
        ProbeLines.Stages s = new ProbeLines.Stages();
        assertFalse(s.due(null, -1), "nothing being broken");
        assertTrue(s.due("1 2 3", 0));
        assertFalse(s.due("1 2 3", 0), "the same stage again: no line");
        assertTrue(s.due("1 2 3", 1));
        assertTrue(s.due("1 2 4", 1), "another cell at the same stage is a new block");
        assertFalse(s.due("1 2 4", -1));
        assertTrue(s.due("1 2 4", 1), "after a pause the block's stage is said again");
    }

    @Test
    void theWoodenPickaxeInHandWithAStoneOneInTheBagIsLower() {
        ProbeLines.Held hand = new ProbeLines.Held("minecraft:wooden_pickaxe", 0, 2.0);
        ProbeLines.Held best = new ProbeLines.Held("minecraft:stone_pickaxe", 27, 4.0);
        assertTrue(ProbeLines.lower(hand, best));
        assertEquals("t=120 stage 3 at 10 11 12 stone | hand wooden_pickaxe (hotbar 1, speed 2.0) | bag best stone_pickaxe (bag slot 28, speed 4.0)"
                        + " | LOWER than the bag's | step strip strip 40 at y 16",
                ProbeLines.tool(120, 3, "10 11 12", "minecraft:stone", hand, best, "strip strip 40 at y 16"));
    }

    @Test
    void theBagsFastestInHandIsBestInHand_anEmptyHandIsNamed() {
        ProbeLines.Held hand = new ProbeLines.Held("minecraft:stone_pickaxe", 0, 4.0);
        assertFalse(ProbeLines.lower(hand, hand));
        assertFalse(ProbeLines.lower(hand, new ProbeLines.Held("minecraft:iron_pickaxe", 30, 4.0)), "a tie is not lower");
        assertEquals("t=5 stage 0 at 0 64 0 stone | hand stone_pickaxe (hotbar 1, speed 4.0) | best in hand | step -",
                ProbeLines.tool(5, 0, "0 64 0", "minecraft:stone", hand, hand, null));
        ProbeLines.Held empty = new ProbeLines.Held("", 3, 1.0);
        ProbeLines.Held pick = new ProbeLines.Held("minecraft:stone_pickaxe", 20, 4.0);
        assertTrue(ProbeLines.lower(empty, pick), "dug by hand with a pickaxe in the bag");
        assertTrue(ProbeLines.tool(5, 0, "0 64 0", "minecraft:stone", empty, pick, null).contains("hand empty hand (hotbar 4, speed 1.0)"));
    }
}
