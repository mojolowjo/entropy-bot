package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.engine.ReflexRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** needs-ruling 0.39.z32 (NEEDS_RULING 2 and 3, SCENARIOS S34/S35): cooked before raw, eating to heal, a sword in the bag. */
class FoodAndSwordTest {
    @BeforeEach
    void data() {
        TestData d = TestData.planner();
        for (String m : List.of("wooden", "stone", "iron", "diamond")) d.tag(m + "_sword", "minecraft:swords");
        d.install();
    }

    @AfterEach
    void clear() { TestData.clear(); Tuning.reset(); }

    static int price(String id, int nutrition, float sat) { return ReflexRules.foodScore(id, nutrition, sat, false, 20); }

    /** S34: cooked beef 80 + 128 + 500 = 708 beats raw beef 30 + 18 = 48; raw only is still eaten (48 > -1). */
    @Test
    void s34CookedBeforeRawFromTheRecipeData() {
        assertTrue(ReflexRules.cooked("minecraft:cooked_beef"));
        assertFalse(ReflexRules.cooked("minecraft:beef"));
        assertFalse(ReflexRules.cooked("minecraft:bread"), "not a cooking recipe's result");
        assertEquals(708, price("minecraft:cooked_beef", 8, 12.8f));
        assertEquals(48, price("minecraft:beef", 3, 1.8f));
        assertTrue(price("minecraft:beef", 3, 1.8f) > 0, "raw only: raw is eaten");
        assertTrue(price("minecraft:cooked_chicken", 6, 7.2f) > price("minecraft:golden_carrot", 6, 14.4f), "cooked first, whatever the bar");
    }

    /** S34: no cooking data loaded: nothing counts as cooked (said once), the plain price stands. */
    @Test
    void noRecipeDataNothingIsCooked() {
        TestData.clear();
        assertFalse(ReflexRules.cooked("minecraft:cooked_beef"));
    }

    /** S34: under needs.healEatHp (10) with food in the bag the bot eats whenever the bar is not full. */
    @Test
    void s34LowHealthEatsFirst() {
        assertTrue(ReflexRules.wantsMeal(19, 8, 8), "hp 8 < 10, bar 19: eat (the reflex pauses the step)");
        assertFalse(ReflexRules.wantsMeal(19, 12, 8), "hp 12: the bar decides (19 + 8 > 20: wait)");
        assertFalse(ReflexRules.wantsMeal(20, 4, 8), "a full bar: the game refuses a bite");
        assertFalse(ReflexRules.healEat(19, 4, 0), "no food in the bag");
    }

    /** S35: no sword in the bag, planks and sticks in it, a table at hand: one craft. */
    @Test
    void s35ASwordFromPlanksAndAStick() {
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 4, "minecraft:stick", 2, "minecraft:crafting_table", 1);
        assertFalse(AaDesires.hasSword(bag));
        assertTrue(AaDesires.hasSword(Map.of("minecraft:stone_sword", 1)), "any sword of the tag");
        assertNull(AaDesires.swordsMissing());
        NeedChain.Chain c = NeedChain.of(new Step.Obtain(AaDesires.SWORDS, 1), new NeedChain.World(bag, true, w -> null));
        assertTrue(c.ok(), c.why());
        assertTrue(c.why().startsWith("swords <- 1 wooden_sword <- craft: 2 oak_planks + 1 stick: 0.3 s"), c.why());
        assertEquals(List.of("wooden_sword"), c.steps().stream().map(x -> ((Step.Craft) x).item()).toList());
        assertEquals(1, c.steps().stream().filter(x -> x instanceof Step.Craft).count(), c.steps().toString());
    }

    /** Rule 15: no item data: the sword need says "not loaded". */
    @Test
    void swordTagNotLoadedIsSaid() {
        TestData.clear();
        assertEquals("not loaded: no item data (the swords tag)", AaDesires.swordsMissing());
    }

    @Test
    void theSwordNeedIsInTheNeedsAtItsLowScore() {
        assertTrue(AaDesires.needs().stream().anyMatch(n -> n.id.equals(AaDesires.SWORD_ID)));
        assertEquals(20, Tuning.get("needs.swordScore"), 1e-9);
    }
}
