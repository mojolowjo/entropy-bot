package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.21.1: `bootstrap status` only reports (pure text from facts; the game side starts nothing on that form). */
class BootstrapStatusTest {
    @Test
    void statusReportsTheCampAndStartsNothing() {
        String s = BootstrapPlan.statusText(Map.of("minecraft:stone_pickaxe", 1, "minecraft:torch", 7), true, false, true, false, null, null);
        assertTrue(s.startsWith("bootstrap:"), s);
        assertTrue(s.contains("table yes") && s.contains("furnace no") && s.contains("chest yes"), s);
        assertTrue(s.contains("pickaxe yes") && s.contains("axe no") && s.contains("torches 7"), s);
        assertTrue(s.contains("camp mark not set") && s.contains("last run: none"), s);
        assertFalse(s.startsWith("error") || s.startsWith("started"), s);
    }

    @Test
    void statusShowsARunningChainAndTheLastRun() {
        String s = BootstrapPlan.statusText(Map.of(), false, false, false, true, "chain bootstrap step 3/20", "refused: no spot");
        assertTrue(s.contains("running: chain bootstrap step 3/20") && s.contains("last run: refused: no spot") && s.contains("camp mark set"), s);
    }
}
