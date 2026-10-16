package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.camp.FoodPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.30.5 (trial 9, findings 3 and 4): the food step, the check's food item, climb's fallback and the honest stair end. */
class FoodClimbTest {

    @Test
    void bootstrapEndsWithTheFoodStep() {
        List<String> s = FoodPlan.withFood(List.of("chop 6", "scan base"));
        assertEquals(List.of("chop 6", "scan base", "bootstrap food"), s);
        assertEquals(List.of("bootstrap food"), FoodPlan.withFood(List.of()));
    }

    @Test
    void enoughFoodDoesNothing() {
        FoodPlan.Decision d = FoodPlan.decide(Map.of("minecraft:bread", 6), true, Map.of("cow", 3));
        assertTrue(d.steps().isEmpty());
        assertTrue(d.text().startsWith("ok: food: I carry 6"));
    }

    @Test
    void huntsTheBestAnimalThenCooksSoftly() {
        FoodPlan.Decision d = FoodPlan.decide(Map.of(), true, Map.of("chicken", 5, "cow", 3));
        assertEquals(List.of("soft hunt 6 cow", "soft cook beef 6"), d.steps());
        assertTrue(d.text().startsWith("ok: "));
        assertFalse(Texts.stepFailed(d.text()));
        FoodPlan.Decision one = FoodPlan.decide(Map.of("minecraft:beef", 2), true, Map.of("pig", 1));
        assertEquals(List.of("soft hunt 2 porkchop".replace("porkchop", "pig"), "soft cook porkchop 2", "soft cook beef 2"), one.steps());
    }

    @Test
    void huntingOffNeverTurnsItOn() {
        FoodPlan.Decision d = FoodPlan.decide(Map.of(), false, Map.of("cow", 2));
        assertTrue(d.steps().isEmpty());
        assertTrue(d.text().contains("hunting is off - next: hunting on then hunt 6 cow"), d.text());
        assertFalse(Texts.stepFailed(d.text()));
    }

    @Test
    void noAnimalsSaysSo() {
        FoodPlan.Decision d = FoodPlan.decide(Map.of("minecraft:apple", 1), true, Map.of());
        assertTrue(d.steps().isEmpty());
        assertTrue(d.text().contains("no animals within 32 - next: hunt 5 when you see some"), d.text());
        assertFalse(Texts.stepFailed(d.text()));
        FoodPlan.Decision raw = FoodPlan.decide(Map.of("minecraft:mutton", 3), true, Map.of());
        assertEquals(List.of("soft cook mutton 3"), raw.steps());
    }

    @Test
    void rawChickenIsNotFood() {
        assertEquals(0, FoodPlan.count(Map.of("minecraft:chicken", 4)));
        assertEquals(5, FoodPlan.count(Map.of("minecraft:cooked_chicken", 2, "minecraft:bread", 3, "minecraft:stone", 9)));
    }

    @Test
    void checkFoodItem() {
        assertNull(FoodPlan.checkFinding(10, Map.of(), true, Map.of()));
        String[] eat = FoodPlan.checkFinding(5, Map.of("minecraft:bread", 2), true, Map.of());
        assertEquals("food 5/20 - next: eat", new SelfCheck.Finding(eat[0], eat[1], eat[2]).line());
        String[] hunt = FoodPlan.checkFinding(5, Map.of(), true, Map.of("cow", 3));
        assertEquals("food 5/20 and nothing to eat - next: hunt 4 cow (then cook beef 4)", new SelfCheck.Finding(hunt[0], hunt[1], hunt[2]).line());
        String[] off = FoodPlan.checkFinding(4, Map.of(), false, Map.of("pig", 1));
        assertEquals("hunting on then hunt 4 pig (then cook porkchop 4)", off[2]);
        String[] none = FoodPlan.checkFinding(4, Map.of(), true, Map.of());
        assertTrue(none[2].endsWith("when you see some"));
    }

    @Test
    void checkFoodItemComesFirst() {
        // SelfCheckLive puts it at index 0, before the rules' findings
        List<SelfCheck.Finding> f = new java.util.ArrayList<>(List.of(new SelfCheck.Finding("base", "no base marked", "place base")));
        String[] h = FoodPlan.checkFinding(5, Map.of(), true, Map.of());
        f.add(0, new SelfCheck.Finding(h[0], h[1], h[2]));
        String r = SelfCheck.report(f);
        assertTrue(r.contains("\n1. food 5/20 and nothing to eat"), r);
        assertTrue(r.contains("\n2. no base marked"), r);
    }

    @Test
    void climbFallsBackAfterTwoFailsNearby() {
        ClimbFallback fb = new ClimbFallback();
        int[] a = {22, 75, 14};
        assertFalse(ClimbFallback.fallback(fb.fails(a)));
        fb.started(a);
        fb.ended("stopped at step 2 (goto 22 76 13): error: couldn't get there");
        assertEquals(1, fb.fails(a));
        assertFalse(ClimbFallback.fallback(fb.fails(a)));
        fb.started(new int[]{23, 75, 14});
        fb.ended("stopped at step 1 (dig ...): error: blocked");
        assertEquals(2, fb.fails(a));
        assertTrue(ClimbFallback.fallback(fb.fails(a)));
        assertEquals(0, fb.fails(new int[]{60, 75, 14}));        // somewhere else: a fresh count
        fb.ended(null);                                          // nothing new: no change
        assertEquals(2, fb.fails(a));
        fb.started(a);
        fb.ended("done - ok");
        assertEquals(0, fb.fails(a));
        fb.failed(a);
        fb.failed(new int[]{50, 75, 14});                         // far away: counting starts again there
        assertEquals(0, fb.fails(a));
    }

    @Test
    void fallbackDirectionTowardTheCamp() {
        int[] h = {22, 75, 14};
        assertEquals("east", ClimbFallback.toward(h, new int[]{40, 90, 20}));
        assertEquals("west", ClimbFallback.toward(h, new int[]{10, 90, 14}));
        assertEquals("south", ClimbFallback.toward(h, new int[]{23, 90, 30}));
        assertEquals("north", ClimbFallback.toward(h, new int[]{21, 90, -5}));
        assertNull(ClimbFallback.toward(h, new int[]{22, 90, 14}));
        assertNull(ClimbFallback.toward(h, null));
        assertEquals(15, ClimbFallback.stepsUp(75, 90, 85));
        assertEquals(10, ClimbFallback.stepsUp(75, Integer.MIN_VALUE, 85));
        assertEquals(StairPlan.MAX, ClimbFallback.stepsUp(0, 200, 0));
        assertEquals(1, ClimbFallback.stepsUp(80, 70, 70));
        assertEquals("climb: my own way failed twice, digging stairs up toward the camp (15 blocks up, east)", ClimbFallback.startText(true, "east", 15));
    }

    @Test
    void honestStairEnd() {
        String msg = "stopped at step 4 (goto 23 77 14): couldn't get there (2 blocks away, stopped at 22 76 14) - I'm in a hole (the rim is 9 above me) - next: climb";
        assertTrue(ClimbFallback.needsNote("climb", msg));
        assertTrue(ClimbFallback.needsNote("dig stairs up", msg));
        assertFalse(ClimbFallback.needsNote("autominer", msg));
        assertFalse(ClimbFallback.needsNote("climb", "done"));
        int[] g = ClimbFallback.gotoGoal(msg);
        assertArrayEquals(new int[]{23, 77, 14}, g);
        String r = ClimbFallback.honestEnd(msg, g, null, "gravel", false);
        assertTrue(r.contains("in the way: gravel at 23 78 14 - next: dig 23 77 14 23 78 14 via engine then goto 23 77 14"), r);
        assertFalse(r.contains("I'm in a hole"), r);
        assertFalse(ClimbFallback.needsNote("climb", r));
        String gap = ClimbFallback.honestEnd(msg, g, null, null, true);
        assertTrue(gap.contains("a gap under 23 77 14") && gap.contains("next: place cobblestone 23 76 14"), gap);
        String open = ClimbFallback.honestEnd(msg, g, null, null, false);
        assertTrue(open.contains("next: goto 23 77 14"), open);
        assertNull(ClimbFallback.gotoGoal("stopped at step 1 (dig 1 2 3 1 3 3): blocked"));
        String hard = ClimbFallback.honestEnd(msg, g, "bedrock", null, false, true);
        assertTrue(hard.contains("in the way: bedrock at 23 77 14 (I can't dig it) - next: climb"), hard);
    }

    @Test
    void failSpotFollowsTheLatestFailure() {
        // live 0.30.5: 22 100 14, then 20 102 14, then the third climb from 21 108 14 (8.1 from the first spot)
        ClimbFallback fb = new ClimbFallback();
        fb.failed(new int[]{22, 100, 14});
        fb.failed(new int[]{20, 102, 14});
        assertTrue(ClimbFallback.fallback(fb.fails(new int[]{21, 108, 14})));
    }
}
