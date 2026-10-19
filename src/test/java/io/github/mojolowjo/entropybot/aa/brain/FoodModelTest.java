package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.engine.ReflexRules;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** aa-food: the curve, the drain model, the stock target and the eat rule. */
class FoodModelTest {

    @Test
    void foodCurveIsUrgentAtSixWithoutLike() {
        assertEquals(0, Curves.food(18));
        assertEquals(35, Curves.food(10), 1);
        assertTrue(Curves.food(7) < 75, "7 is not urgent yet: " + Curves.food(7));
        assertTrue(Curves.food(6) >= 75, "aaep1: 6/20 scored 56 before");
        assertTrue(Curves.food(5) > 90, "5 beats the night shelter (90)");
        assertEquals(100, Curves.food(3));
    }

    @Test
    void bagEnergyAndBestNutrition() {
        Map<String, Integer> bag = Map.of("cooked_beef", 2, "bread", 1, "rotten_flesh", 5, "cobblestone", 64);
        assertEquals(2 * 20.8 + 11, FoodModel.energy(bag), 1e-9, "rotten flesh is no plannable food");
        assertEquals(8, FoodModel.bestNutrition(bag));
        assertEquals(0, FoodModel.bestNutrition(Map.of("chicken", 3)), "raw chicken poisons: not counted");
        assertEquals(0, FoodModel.energy(null));
    }

    @Test
    void drainIsMeasuredPerKindAndReplacesTheHandRate() {
        BrainStats s = new BrainStats();
        assertFalse(s.recordDrain("IRON", 1, 0.5), "under a minute: no sample");
        assertEquals(FoodModel.handRate(TaskKind.IRON), s.drain("IRON", FoodModel.handRate(TaskKind.IRON)));
        assertTrue(s.recordDrain("IRON", 6, 5));          // 1.2 per min
        assertEquals(0.45, s.drain("IRON", 0.45), 1e-9, "one sample is not enough");
        assertTrue(s.recordDrain("IRON", 6, 5));
        assertEquals(1.2, s.drain("IRON", 0.45), 1e-9);
        assertTrue(s.recordDrain("EXPLORE", 20, 5));      // 4 per min
        assertTrue(s.recordDrain("EXPLORE", 20, 5));
        assertEquals((2 * 1.2 + 2 * 4) / 4, s.meanDrain(0.4), 1e-9);
        assertEquals(4, FoodModel.plannedRate(s, TaskKind.EXPLORE), 1e-9, "the running kind's rate when higher");
        assertEquals(2.6, FoodModel.plannedRate(s, TaskKind.IRON), 1e-9, "else the measured mean");
    }

    @Test
    void drainGoesIntoTheStatsFileNextToTheCosts() {
        BrainStats s = new BrainStats();
        s.record("needs.iron", true, 400);
        s.recordDrain("IRON", 6, 5);
        s.recordDrain("IRON", 6, 5);
        String j = s.toJson();
        assertTrue(j.contains("\"drain.IRON\":{\"n\":2,\"ok\":2,\"avgS\":-1.0,\"perMin\":1.200}"), j);
        assertTrue(j.contains("\"needs.iron\""), j);
        BrainStats t = new BrainStats();
        t.putDrain("IRON", 2, 1.2);
        assertEquals(1.2, t.drain("IRON", 0), 1e-9);
        assertTrue(s.text().contains("food drain IRON 2 samples"), s.text());
    }

    @Test
    void stockTargetCoversTheNextHourLessTheBodysReserve() {
        // 0.4 per min x 60 = 24; a full body (20 + 5 - 6 = 19) leaves 5: the minimum 8
        assertEquals(8, FoodModel.target(0.4, 20, 5), 1e-9);
        assertEquals(24, FoodModel.target(0.4, 6, 0), 1e-9, "hungry: the whole hour in the bag");
        assertEquals(64, FoodModel.target(3, 6, 0), 1e-9, "capped");
        assertEquals(-1, FoodModel.stockScore(30, 24));
        assertEquals(60, FoodModel.stockScore(0, 24), 1e-9, "empty bag: 25 + 35, under the urgent line");
        assertTrue(FoodModel.stockScore(12, 24) < 40);
        assertEquals(3, FoodModel.count(24, 8));
        assertEquals(16, FoodModel.count(1000, 1));
    }

    @Test
    void eatRuleWastesNothingButEatsWhenUrgent() {
        assertFalse(ReflexRules.wantsMeal(14, 20, 8), "down 6, steak gives 8: wait");
        assertTrue(ReflexRules.wantsMeal(12, 20, 8), "down 8: eat");
        assertTrue(ReflexRules.wantsMeal(19, 20, 1), "dried kelp fits at 19");
        assertTrue(ReflexRules.wantsMeal(6, 20, 20), "urgent: anything");
        assertTrue(ReflexRules.wantsMeal(17, 10, 8), "hurt: healing needs 18");
        assertTrue(ReflexRules.wantsMeal(14, 20, 0), "no food: the old rule (the food run starts)");
        assertFalse(ReflexRules.wantsMeal(15, 20, 0));
    }
}
