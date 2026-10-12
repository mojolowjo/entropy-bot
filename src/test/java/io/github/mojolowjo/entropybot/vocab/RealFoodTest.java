package io.github.mojolowjo.entropybot.vocab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 0.27.3 (d6): the food kind is real food; a potion-like modded edible is never picked, a costly plan is skipped by the caller. */
class RealFoodTest {
    static final String ELIXIR = "arphex:elixir_of_splintered_sanity";

    @Test void potionLikeIsNotFood() {
        assertFalse(Kinds.realFood(ELIXIR));
        assertFalse(Kinds.realFood("somemod:healing_potion"));
        assertFalse(Kinds.realFood("minecraft:rotten_flesh"));
        assertTrue(Kinds.realFood("minecraft:bread"));
        assertTrue(Kinds.realFood("farmersdelight:beef_stew"));
    }

    @Test void elixirInStockOrResolvingIsNeverPicked() {
        List<String> ids = List.of(ELIXIR, "minecraft:bread", "minecraft:cooked_beef");
        assertNull(Kinds.pickFood(List.of(ELIXIR), Map.of(ELIXIR, 40), false, false, x -> true));
        assertEquals("minecraft:bread", Kinds.pickFood(ids, Map.of(ELIXIR, 40), true, false, x -> true));
    }

    @Test void aModdedFoodTheCallerRefusesIsSkipped() {
        // the caller's resolves says no (its plan mines over Kinds.MAX_FOOD_MINING): nothing is picked
        assertNull(Kinds.pickFood(List.of("somemod:golden_pie"), Map.of(), false, false, x -> false));
        assertEquals("somemod:golden_pie", Kinds.pickFood(List.of("somemod:golden_pie"), Map.of(), false, false, x -> true));
        assertEquals(256, Kinds.MAX_FOOD_MINING);
    }
}
