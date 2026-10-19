package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FoodPlanWaterTest {
    @Test
    void kelpAndFishWays() {
        List<String> dropped = new ArrayList<>();
        List<FoodPlan.Way> w = FoodPlan.waterWays(Map.of("minecraft:stick", 3, "minecraft:string", 2), true, true, true, dropped);
        assertEquals(2, w.size());
        assertEquals("gather kelp 8 cook", w.get(0).chain());
        assertEquals("fish 4", w.get(1).chain());
        assertTrue(dropped.isEmpty());
        assertTrue(FoodPlan.waterWays(Map.of(), true, false, false, null).isEmpty(), "raw kelp without a furnace is no food");
    }

    @Test
    void reasonsWhenTheyCannotRun() {
        List<String> dropped = new ArrayList<>();
        assertTrue(FoodPlan.waterWays(Map.of("minecraft:stick", 1), false, true, false, dropped).isEmpty());
        assertEquals(2, dropped.size());
        assertTrue(dropped.get(0).startsWith("kelp:"));
        assertTrue(dropped.get(1).contains("3 sticks + 2 string"));
        dropped.clear();
        assertTrue(FoodPlan.waterWays(Map.of("minecraft:fishing_rod", 1), false, false, false, dropped).isEmpty());
        assertTrue(dropped.get(1).contains("no water"));
        assertEquals("fish 4", FoodPlan.waterWays(Map.of("minecraft:fishing_rod", 1), false, true, false, null).get(0).chain());
    }
}