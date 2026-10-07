package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.github.mojolowjo.entropybot.engine.SurvivalRules.Effect;
import static io.github.mojolowjo.entropybot.engine.SurvivalRules.Fire;
import static org.junit.jupiter.api.Assertions.*;

/** 0.23.6 survival reflexes: the fire and effect decision tables. */
class SurvivalRulesTest {
    @Test
    void fireTable() {
        // onFire, resistant, inHazard, bucket, mayPlace, water, potion
        assertEquals(Fire.NONE, SurvivalRules.fire(false, false, false, true, true, true, true));
        assertEquals(Fire.NONE, SurvivalRules.fire(true, true, false, true, true, true, true));
        assertEquals(Fire.STEP_OUT, SurvivalRules.fire(true, false, true, true, true, true, true));
        assertEquals(Fire.STEP_OUT, SurvivalRules.fire(false, false, true, false, false, false, false), "standing in fire before it burns");
        assertEquals(Fire.NONE, SurvivalRules.fire(true, true, true, false, false, false, false), "fire resistant: stays");
        assertEquals(Fire.PLACE_WATER, SurvivalRules.fire(true, false, false, true, true, true, true));
        assertEquals(Fire.DRINK_FIRE_RES, SurvivalRules.fire(true, false, false, true, false, true, true), "bucket but placing not allowed");
        assertEquals(Fire.WALK_WATER, SurvivalRules.fire(true, false, false, false, false, true, false));
        assertEquals(Fire.MOVE_AWAY, SurvivalRules.fire(true, false, false, false, false, false, false));
    }

    @Test
    void waterPlacement() {
        assertTrue(SurvivalRules.mayPlaceWater(true, false, false, true));
        assertFalse(SurvivalRules.mayPlaceWater(false, false, false, true), "the guard (safe area, outside the areas)");
        assertFalse(SurvivalRules.mayPlaceWater(true, true, false, true), "the Nether");
        assertFalse(SurvivalRules.mayPlaceWater(true, false, true, true), "a container near");
        assertFalse(SurvivalRules.mayPlaceWater(true, false, false, false), "feet cell taken");
    }

    @Test
    void effectTable() {
        assertEquals(Effect.DRINK_MILK, SurvivalRules.effects(Set.of("poison"), true, 20, false, false, 0));
        assertEquals(Effect.DRINK_MILK, SurvivalRules.effects(Set.of("wither", "speed"), true, 20, false, false, 0));
        assertEquals(Effect.NOTE_NO_MILK, SurvivalRules.effects(Set.of("hunger"), false, 20, false, false, 0));
        assertEquals(Effect.LOG, SurvivalRules.effects(Set.of("weakness"), true, 20, false, false, 0));
        assertEquals(Effect.LOG, SurvivalRules.effects(Set.of("slowness"), false, 20, false, false, 0));
        assertEquals(Effect.NONE, SurvivalRules.effects(Set.of("speed"), true, 20, false, false, 0));
    }

    @Test
    void fightPotionOncePer30s() {
        assertEquals(Effect.DRINK_HEAL, SurvivalRules.effects(Set.of(), false, 5, true, true, 600));
        assertEquals(Effect.NONE, SurvivalRules.effects(Set.of(), false, 5, true, true, 599), "drank one under 30 s ago");
        assertEquals(Effect.NONE, SurvivalRules.effects(Set.of(), false, 5, false, true, 9999), "not in a fight");
        assertEquals(Effect.NONE, SurvivalRules.effects(Set.of(), false, 6, true, true, 9999), "health 6 is not under 6");
        assertEquals(Effect.NOTE_NO_MILK, SurvivalRules.effects(Set.of("poison"), false, 5, true, false, 9999), "no potion");
        assertEquals(Effect.LOG, SurvivalRules.effects(Set.of("regeneration", "weakness"), false, 5, true, true, 9999), "already regenerating");
    }
}
