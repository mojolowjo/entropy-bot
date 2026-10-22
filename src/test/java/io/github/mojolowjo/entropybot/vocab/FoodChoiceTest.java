package io.github.mojolowjo.entropybot.vocab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 0.24.3: the food kind picks stock, then plain foods, a modded food only when its plan resolves. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class FoodChoiceTest {
    static final List<String> IDS = List.of("arphex:abyssal_stew", "minecraft:apple", "minecraft:baked_potato", "minecraft:beef", "minecraft:bread",
            "minecraft:carrot", "minecraft:cooked_beef", "minecraft:golden_carrot", "minecraft:potato", "othermod:pie");

    @Test void storageFirst() {
        assertEquals("minecraft:apple", Kinds.pickFood(IDS, Map.of("minecraft:apple", 3, "minecraft:carrot", 1), true, x -> true));
    }

    @Test void breadBeforeModdedWithEmptyStorage() {
        assertEquals("minecraft:bread", Kinds.pickFood(IDS, Map.of(), true, x -> true));
        assertEquals("minecraft:bread", Kinds.pickFood(IDS, null, x -> true));
    }

    @Test void noFarmNeedsTheCropInStock() {
        assertEquals("minecraft:bread", Kinds.pickFood(IDS, Map.of("minecraft:wheat", 6), false, x -> false));
        assertNull(Kinds.pickFood(IDS, Map.of(), false, x -> false));
    }

    @Test void cookedMeatOnlyWithRawInStock() {
        List<String> ids = List.of("minecraft:cooked_beef", "minecraft:golden_carrot", "arphex:abyssal_stew");
        assertNull(Kinds.pickFood(ids, Map.of(), false, x -> false));
        assertEquals("minecraft:cooked_beef", Kinds.pickFood(ids, Map.of("minecraft:beef", 2), false, x -> false));
    }

    @Test void moddedOnlyWhenItResolves() {
        List<String> ids = List.of("arphex:abyssal_stew", "othermod:pie");
        assertNull(Kinds.pickFood(ids, Map.of(), false, x -> false));
        assertEquals("othermod:pie", Kinds.pickFood(ids, Map.of(), false, x -> x.equals("othermod:pie")));
    }

    @Test void goldenCarrotNever() {
        assertNull(Kinds.pickFood(List.of("minecraft:golden_carrot"), Map.of("minecraft:golden_carrot", 5), true, x -> true));
    }

}
