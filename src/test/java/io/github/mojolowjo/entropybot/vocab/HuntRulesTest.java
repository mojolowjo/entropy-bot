package io.github.mojolowjo.entropybot.vocab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 0.24.3: hunt's target filter, stop rule and the food source order with hunting on/off. */
class HuntRulesTest {
    static HuntRules.Target t(String type) { return new HuntRules.Target(type, false, false, false, false, false, false, true); }

    @Test void targetFilter() {
        List<String> ok = HuntRules.ANIMALS;
        assertTrue(HuntRules.mayTake(t("minecraft:cow"), ok, null));
        assertFalse(HuntRules.mayTake(t("minecraft:wolf"), ok, null));
        assertFalse(HuntRules.mayTake(new HuntRules.Target("minecraft:cow", true, false, false, false, false, false, true), ok, null));   // tamed
        assertFalse(HuntRules.mayTake(new HuntRules.Target("minecraft:cow", false, true, false, false, false, false, true), ok, null));   // named
        assertFalse(HuntRules.mayTake(new HuntRules.Target("minecraft:cow", false, false, false, false, false, true, true), ok, null));   // a calf
        assertFalse(HuntRules.mayTake(new HuntRules.Target("minecraft:cow", false, false, false, false, false, false, false), ok, null));  // outside the areas
        assertFalse(HuntRules.mayTake(new HuntRules.Target("minecraft:villager", false, false, false, false, true, false, true), ok, null));
        assertFalse(HuntRules.mayTake(new HuntRules.Target("minecraft:player", false, false, false, true, false, false, true), ok, null));
        assertFalse(HuntRules.mayTake(t("minecraft:pig"), ok, "minecraft:cow"));                   // hunt 3 cow
        assertFalse(HuntRules.mayTake(t("minecraft:sheep"), List.of("minecraft:cow"), null));     // kinds animals exclude sheep
    }

    @Test void stopsAtN() {
        assertFalse(HuntRules.done(Map.of("minecraft:beef", 2), 3, null));
        assertTrue(HuntRules.done(Map.of("minecraft:beef", 2, "minecraft:porkchop", 1), 3, null));
        assertFalse(HuntRules.done(Map.of("minecraft:beef", 2, "minecraft:porkchop", 1), 3, "minecraft:cow"));
        assertTrue(HuntRules.done(Map.of("minecraft:beef", 3), 3, "minecraft:cow"));
    }

    @Test void parse() {
        assertArrayEquals(new Object[]{3, "minecraft:cow"}, HuntRules.parse("3 cows"));
        assertArrayEquals(new Object[]{4, null}, HuntRules.parse(""));
        assertNull(HuntRules.parse("3 wolf"));
        assertEquals("hunt {n} cow", HuntRules.source("minecraft:beef"));
    }

    @Test void foodOrderWithHunting() {
        List<String> ids = List.of("minecraft:bread", "minecraft:cooked_beef", "minecraft:apple", "othermod:pie");
        // no farm, no stock: off -> nothing plain (the modded pie only if it resolves); on -> cooked beef before the modded food
        assertNull(Kinds.pickFood(ids, Map.of(), false, false, x -> false));
        assertEquals("minecraft:cooked_beef", Kinds.pickFood(ids, Map.of(), false, true, x -> true));
        // crops still come first
        assertEquals("minecraft:bread", Kinds.pickFood(ids, Map.of("minecraft:wheat", 3), false, true, x -> true));
        // stock first of all
        assertEquals("minecraft:apple", Kinds.pickFood(ids, Map.of("minecraft:apple", 2), false, true, x -> true));
    }

    @Test void animalsKind() {
        assertEquals(HuntRules.ANIMALS.size(), Kinds.expand("animals", List.of("minecraft:stone"), x -> false, Kinds.Rule.none()).size());
    }
}
