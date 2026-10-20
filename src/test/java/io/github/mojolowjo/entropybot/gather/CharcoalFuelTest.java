package io.github.mojolowjo.entropybot.gather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** aa-r2fix: "gather charcoal 31" ran "mine cave coal 4 10m"; charcoal's fuel is planks from logs. */
class CharcoalFuelTest {
    @Test void planksFromSpareLogsNeverCoal() {
        GatherTest.Fake w = new GatherTest.Fake();
        w.bag.put("minecraft:oak_log", 12);
        GatherPlan.Step s = GatherPlan.next("charcoal", 8, w);
        assertEquals(GatherPlan.Kind.CRAFT, s.kind(), String.valueOf(s.command(0)));
        assertEquals("minecraft:oak_planks", s.leaf());
        assertTrue(s.n() >= 4);
    }

    @Test void noSpareLogsCutsMoreNotACave() {
        GatherTest.Fake w = new GatherTest.Fake();
        w.bag.put("minecraft:oak_log", 8);
        GatherPlan.Step c = GatherPlan.next("charcoal", 8, w);
        assertEquals(GatherPlan.Kind.SOURCE, c.kind());
        assertTrue(c.command(0).startsWith("chop"), c.command(0));
    }

    @Test void withFuelPlanksItSmelts() {
        GatherTest.Fake w = new GatherTest.Fake();
        w.bag.put("minecraft:oak_log", 8);
        w.bag.put("minecraft:oak_planks", 8);
        GatherPlan.Step s = GatherPlan.next("charcoal", 8, w);
        assertEquals(GatherPlan.Kind.CRAFT, s.kind());
        assertEquals("minecraft:charcoal", s.leaf());
    }
}
