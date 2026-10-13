package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.28.2 (trial fix package 2, B3): the stone step uses gather's quarry when the staircase meets ice, snow or water. */
class BootstrapQuarryTest {
    private static BootstrapPlan.Plan plan(boolean blocked) {
        int[] f = {0, 64, 0};
        return BootstrapPlan.plan(new BootstrapPlan.Facts(Map.of("minecraft:oak_log", 16), false, false, false, true,
                f, new int[]{-2, 64, 0}, new int[]{0, 64, -2}, new int[]{0, 64, 2}, new int[]{1, 0}, false, blocked));
    }

    @Test
    void staircaseWhenClear() {
        assertTrue(plan(false).steps().stream().anyMatch(s -> s.startsWith("dig ")));
        assertFalse(plan(false).steps().stream().anyMatch(s -> s.startsWith("gather cobblestone")));
    }

    @Test
    void gatherWhenBlocked() {
        BootstrapPlan.Plan p = plan(true);
        assertFalse(p.steps().stream().anyMatch(s -> s.startsWith("dig ")), p.steps().toString());
        // tools 3+3+1+2 = 9, furnace 8: 17 needed, 0 had, +4 spare
        assertTrue(p.steps().contains("gather cobblestone 21"), p.steps().toString());
        int g = p.steps().indexOf("gather cobblestone 21");
        assertEquals("craft wooden_pickaxe 1", p.steps().get(g - 1));
    }
}
