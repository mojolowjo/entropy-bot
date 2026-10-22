package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** aa-food: the FOOD rung's ways, the park reason, the desire's score. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class FoodOptionsTest {
    static final int[] ME = {0, 64, 0};

    static FoodOptions.FoodFacts facts(int food, Map<String, Integer> bag, boolean furnace, Map<String, Integer> animals, String huntWhy) {
        return new FoodOptions.FoodFacts(food, 0, bag, 24, furnace, animals, huntWhy,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME);
    }

    static Options.Choice choose(FoodOptions.FoodFacts f) {
        return new FoodOptions.Food().choose(f, new Options.OptionMemory(), 0, null, 0, "no stored food", null);
    }

    static FoodOptions.FoodFacts withHerds(Map<String, Integer> animals, java.util.List<FoodOptions.Herd> herds) {
        return new FoodOptions.FoodFacts(6, 0, Map.of(), 24, true, animals, null,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME, 20, 0, null, herds);
    }

    @Test
    void getFoodIsTheChainsRowAndEatingStaysARow() {
        var chain = java.util.List.<io.github.mojolowjo.entropybot.aa.moves.Step>of(new io.github.mojolowjo.entropybot.aa.moves.Step.HuntAt("minecraft:cow", 4, new int[]{15, 0}, "beef", 8));
        Options.Choice c = new FoodOptions.Food().choose(facts(10, Map.of(), true, Map.of(), null), new Options.OptionMemory(), 0, chain, 20, "food <- 8 beef <- hunt 4 cow seen 15 m: 14.5 s", null);
        assertEquals("chain", c.best().id(), c.why());
        assertEquals("hunt 4 cow for beef 8 at 15 0", c.best().command());
        assertTrue(c.best().why().contains("hunt 4 cow seen 15 m"), c.best().why());
        Options.Choice none = choose(facts(10, Map.of(), true, Map.of("cow", 3), null));
        assertTrue(none.dropped().stream().anyMatch(d -> d.startsWith("chain: ")), "no hand-priced hunt row: the chain hunts: " + none.why());
        assertTrue(none.ranked().stream().noneMatch(o -> o.id().startsWith("hunt") || o.id().startsWith("cook")), none.why());
    }

    @Test
    void eatFromTheBagFirst() {
        Options.Choice c = choose(facts(10, Map.of("cooked_beef", 1, "beef", 3), true, Map.of("cow", 3), null));
        assertEquals("eat", c.best().id(), c.why());
        Options.Choice notYet = choose(facts(16, Map.of("cooked_beef", 1), true, Map.of(), null));
        assertTrue(notYet.ranked().stream().noneMatch(o -> o.id().equals("eat")), "down 4, steak gives 8: no waste");
    }

    @Test
    void theWaterWaysAndAFieldWhenNotHungry() {
        FoodOptions.FoodFacts f = new FoodOptions.FoodFacts(16, 2, Map.of("wheat_seeds", 5, "oak_planks", 4), 24, true, Map.of(), null,
                new int[]{5, 64, 5}, "oak", 7, new int[]{3, 64, 0}, 3, new int[]{1, 64, 1}, new int[]{2, 64, 2}, null, true, false, 0, true, ME);
        Options.Choice c = choose(f);
        java.util.Set<String> ids = new java.util.HashSet<>();
        c.ranked().forEach(o -> ids.add(o.id()));
        assertTrue(ids.contains("kelp"), "aa-kelpfish: kelp seen + a furnace is a costed way: " + c.why());
        assertTrue(c.why().contains("fishing: no fishing rod"), c.why());
        assertTrue(c.why().contains("wheat by water: no dry spot next to water"), "aa383: no field here just because water is 12 away: " + c.why());
        FoodOptions.FoodFacts hungry = new FoodOptions.FoodFacts(5, 0, f.bag(), 24, true, Map.of(), null,
                null, "oak", -1, null, -1, null, null, null, true, false, 0, false, ME);
        assertTrue(choose(hungry).why().contains("wheat: too slow while hungry"), choose(hungry).why());
    }

    /** aa383 (c): the field goes by the water, or here with a water bucket; both costed. */
    @Test
    void wheatFieldByTheWaterOrWithABucket() {
        int[] spot = {8, 64, 0};
        FoodOptions.FoodFacts byWater = new FoodOptions.FoodFacts(16, 2, Map.of("wheat_seeds", 5), 24, true, Map.of(), null,
                null, "oak", -1, null, -1, null, null, null, true, false, 0, false, ME, 20, 24, spot);
        Options.Choice c = choose(byWater);
        Options.Option w = c.ranked().stream().filter(o -> o.id().equals("wheat by water")).findFirst().orElseThrow(() -> new AssertionError(c.why()));
        assertEquals("goto 8 64 0 > farm plant wheat here 4 > place farm 8 64 0", w.command());
        assertTrue(c.why().contains("wheat with a bucket: no water bucket"), c.why());
        FoodOptions.FoodFacts bucket = new FoodOptions.FoodFacts(16, 2, Map.of("wheat_seeds", 5, "water_bucket", 1), 24, true, Map.of(), null,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME, 20, 24, null);
        Options.Choice b = choose(bucket);
        Options.Option here = b.ranked().stream().filter(o -> o.id().equals("wheat with a bucket")).findFirst().orElseThrow(() -> new AssertionError(b.why()));
        assertEquals("farm plant wheat here 4 > place farm 0 64 0", here.command());
        // both: the walk to the water is weighed against the bucket's extra minute
        FoodOptions.FoodFacts both = new FoodOptions.FoodFacts(16, 2, Map.of("wheat_seeds", 5, "water_bucket", 1), 24, true, Map.of(), null,
                null, "oak", -1, null, -1, null, null, null, true, false, 0, false, ME, 20, 24, spot);
        assertEquals("wheat by water", choose(both).best().id(), choose(both).why());
    }

    @Test
    void parkedWithTheReasonWhenNoSourceElseNot() {
        FoodOptions.FoodFacts none = facts(6, Map.of(), true, Map.of(), null);
        String why = FoodOptions.noSource(none);
        assertNotNull(why);
        assertTrue(why.startsWith("no food source: ") && why.contains("chain: no stored food"), why);
        FoodOptions.FoodFacts stored = new FoodOptions.FoodFacts(6, 0, Map.of(), 24, true, Map.of(), null,
                null, "oak", -1, null, -1, null, null, null, false, false, 3, false, ME);
        assertNull(FoodOptions.noSource(stored), "food in storage: the planner fetches it");
        assertNull(FoodOptions.noSource(null), "no facts: never parked");
    }

    // ---- the desire ----

    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    @Test
    void desireUrgentAtSixAndStocksUpBeforeHunger() {
        bb.in.hungerDrains = true;
        bb.in.food = 6;
        bb.in.saturation = 0;
        assertTrue(AaDesires.foodScore(bb) >= 75, "aaep1: urgent without aa like: " + AaDesires.foodScore(bb));
        bb.in.food = 20;
        bb.in.saturation = 5;
        bb.in.bag = Map.of();
        assertTrue(AaDesires.foodStockLow(bb), "empty bag under the 8-point minimum");
        double s = AaDesires.foodScore(bb);
        assertTrue(s >= 25 && s < 75, "a stock-up, never urgent: " + s);
        bb.in.bag = Map.of("cooked_beef", 2);
        assertFalse(AaDesires.foodStockLow(bb));
        Need food = AaDesires.needs().stream().filter(r -> r.id.equals("needs.food")).findFirst().orElseThrow();
        assertFalse(food.unmet(bb), "full and stocked: met");
        bb.in.bag = Map.of();
        assertTrue(food.unmet(bb), "full but empty bag: gather before hunger");
    }
}
