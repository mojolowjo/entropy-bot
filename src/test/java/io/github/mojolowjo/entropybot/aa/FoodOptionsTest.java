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
class FoodOptionsTest {
    static final int[] ME = {0, 64, 0};

    static FoodOptions.FoodFacts facts(int food, Map<String, Integer> bag, boolean furnace, Map<String, Integer> animals, String huntWhy) {
        return new FoodOptions.FoodFacts(food, 0, bag, 24, furnace, animals, 0, huntWhy,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME);
    }

    static Options.Choice choose(FoodOptions.FoodFacts f) {
        return new FoodOptions.Food().choose(f, new Options.OptionMemory(), 0, null, 0, "no stored food", null);
    }

    static FoodOptions.FoodFacts withHerds(Map<String, Integer> animals, java.util.List<FoodOptions.Herd> herds) {
        return new FoodOptions.FoodFacts(6, 0, Map.of(), 24, true, animals, 0, null,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME, Map.of(), 20, 96, null, null, herds);
    }

    @Test
    void theBedHuntsSheepForWoolFirst() {
        assertEquals("goal bed", AaLadder.bedChain(3, null));
        assertEquals("hunt 2 sheep for white_wool 3 then goal bed", AaLadder.bedChain(1, null));
        assertEquals("hunt 3 sheep for white_wool 3 at 40 -8 then goal bed", AaLadder.bedChain(0, new int[]{40, -8, 50, 80}));
    }

    @Test
    void aSeenHerdIsARoundTripPricedByConfidence() {
        var fresh = new FoodOptions.Herd("cow", 80, 64, 0, 8, 1.0, 80);
        Options.Choice c = choose(withHerds(Map.of(), java.util.List.of(fresh)));
        assertNotNull(c.best(), c.why());
        assertEquals("go hunt cow", c.best().id());
        assertEquals("hunt 2 cow at 80 0 then cook beef 2", c.best().command());
        assertEquals("sighting/animal cow 80 0", c.best().memoryKey());
        assertNotNull(c.best().site(), "the site feeds the sequencer");
        // an older sighting costs more: x 1 / confidence
        var old = new FoodOptions.Herd("cow", 80, 64, 0, 8, 0.25, 80);
        double cFresh = c.best().cost(), cOld = choose(withHerds(Map.of(), java.util.List.of(old))).best().cost();
        assertEquals(4 * cFresh, cOld, 1e-6);
        // the best herd of a type wins; a type in reach is the plain hunt, not a walk to memory
        var near = new FoodOptions.Herd("cow", 20, 64, 0, 3, 1.0, 20);
        assertEquals("hunt 2 cow at 20 0 then cook beef 2", choose(withHerds(Map.of(), java.util.List.of(fresh, near))).best().command());
        Options.Choice inReach = choose(withHerds(Map.of("cow", 4), java.util.List.of(fresh)));
        assertEquals("hunt cow", inReach.best().id());
        assertTrue(inReach.ranked().stream().noneMatch(o -> o.id().equals("go hunt cow")));
        // forgotten (under 0.05) or too far: dropped with why
        Options.Choice gone = choose(withHerds(Map.of(), java.util.List.of(new FoodOptions.Herd("cow", 80, 64, 0, 8, 0.01, 80),
                new FoodOptions.Herd("pig", 900, 64, 0, 8, 1, 900))));
        assertTrue(gone.dropped().stream().anyMatch(d -> d.contains("seen too long ago")), gone.dropped().toString());
        assertTrue(gone.dropped().stream().anyMatch(d -> d.startsWith("go hunt pig at 900 0: 900 away")), gone.dropped().toString());
    }

    @Test
    void aaep1HuntsTheAnimalsNearAndCooksTheMeat() {
        Options.Choice c = choose(facts(6, Map.of(), true, Map.of("cow", 9, "sheep", 4, "chicken", 3), null));
        assertNotNull(c.best(), c.why());
        assertEquals("hunt cow", c.best().id());
        assertEquals("hunt 2 cow then cook beef 2", c.best().command(), "24 points: 2 cooked beef (20.8 each)");
    }

    @Test
    void urgentFoodHuntsOutsideTheAreas() {
        FoodOptions.FoodFacts f = new FoodOptions.FoodFacts(5, 0, Map.of(), 24, true, Map.of(), 3, null,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME, Map.of("cow", 3), 20);
        Options.Choice c = choose(f);
        assertNotNull(c.best(), c.why());
        assertEquals("hunt out cow", c.best().id());
        assertTrue(c.best().command().startsWith("hunt 2 cow out then cook beef"), c.best().command());
        FoodOptions.FoodFacts calm = new FoodOptions.FoodFacts(12, 0, Map.of(), 24, true, Map.of(), 3, null,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME, Map.of("cow", 3), 20);
        Options.Choice cc = choose(calm);
        assertEquals("hunt out cow", cc.best().id(), "aa-costs: not urgent, still the only way, at a dearer travel");
        assertTrue(cc.best().cost() > c.best().cost(), cc.why());
        FoodOptions.FoodFacts calmBerry = new FoodOptions.FoodFacts(12, 0, Map.of(), 24, true, Map.of(), 3, null,
                null, "oak", -1, new int[]{5, 64, 5}, 5, null, null, null, false, false, 0, false, ME, Map.of("cow", 3), 20, 40);
        assertEquals("berries", choose(calmBerry).best().id(), "a berry bush near beats a far hunt when not urgent: " + choose(calmBerry).why());
    }

    @Test
    void noFurnaceNeverRawChicken() {
        Options.Choice c = choose(facts(10, Map.of(), false, Map.of("chicken", 5), null));
        assertNull(c.best(), c.why());
        assertTrue(c.why().contains("chickens only, no furnace"), c.why());
        Options.Choice pig = choose(facts(10, Map.of(), false, Map.of("pig", 2), null));
        assertTrue(pig.best().command().startsWith("hunt ") && !pig.best().command().contains("cook"), pig.why());
    }

    @Test
    void eatFromTheBagFirstAndCookWhatIsRaw() {
        Options.Choice c = choose(facts(10, Map.of("cooked_beef", 1, "beef", 3), true, Map.of("cow", 3), null));
        assertEquals("eat", c.best().id(), c.why());
        assertEquals("cook beef", c.ranked().get(1).id(), c.why());
        assertEquals("cook beef 3", c.ranked().get(1).command());
        Options.Choice notYet = choose(facts(16, Map.of("cooked_beef", 1), true, Map.of(), null));
        assertTrue(notYet.ranked().stream().noneMatch(o -> o.id().equals("eat")), "down 4, steak gives 8: no waste");
    }

    @Test
    void theOwnersHuntingOffIsSaid() {
        Options.Choice c = choose(facts(6, Map.of(), true, Map.of("cow", 9), "hunting is off"));
        assertNull(c.best());
        assertTrue(c.why().contains("hunt: hunting is off"), c.why());
    }

    @Test
    void foragingWaysAndAWheatFieldWhenNotHungry() {
        FoodOptions.FoodFacts f = new FoodOptions.FoodFacts(16, 2, Map.of("wheat_seeds", 5, "oak_planks", 4), 24, true, Map.of(), 0, null,
                new int[]{5, 64, 5}, "oak", 7, new int[]{3, 64, 0}, 3, new int[]{1, 64, 1}, new int[]{2, 64, 2}, null, true, false, 0, true, ME);
        Options.Choice c = choose(f);
        java.util.Set<String> ids = new java.util.HashSet<>();
        c.ranked().forEach(o -> ids.add(o.id()));
        assertTrue(ids.containsAll(java.util.List.of("berries", "stew", "apples")), c.why());
        assertTrue(ids.contains("kelp"), "aa-kelpfish: kelp seen + a furnace is a costed way: " + c.why());
        assertTrue(c.why().contains("fishing: no fishing rod"), c.why());
        assertTrue(c.why().contains("wheat by water: no dry spot next to water"), "aa383: no field here just because water is 12 away: " + c.why());
        Options.Option stew = c.ranked().stream().filter(o -> o.id().equals("stew")).findFirst().orElseThrow();
        assertEquals("dig 1 64 1 1 64 1 then dig 2 64 2 2 64 2 then craft bowl 1 then craft mushroom_stew 1", stew.command());
        // hungry: no field (too slow)
        FoodOptions.FoodFacts hungry = new FoodOptions.FoodFacts(5, 0, f.bag(), 24, true, Map.of(), 0, null,
                null, "oak", -1, null, -1, null, null, null, true, false, 0, false, ME);
        assertTrue(choose(hungry).why().contains("wheat: too slow while hungry"), choose(hungry).why());
    }

    /** aa383 (c): the field goes by the water, or here with a water bucket; both costed. */
    @Test
    void wheatFieldByTheWaterOrWithABucket() {
        int[] spot = {8, 64, 0};
        FoodOptions.FoodFacts byWater = new FoodOptions.FoodFacts(16, 2, Map.of("wheat_seeds", 5), 24, true, Map.of(), 0, null,
                null, "oak", -1, null, -1, null, null, null, true, false, 0, false, ME, Map.of(), 20, 24, null, spot);
        Options.Choice c = choose(byWater);
        Options.Option w = c.ranked().stream().filter(o -> o.id().equals("wheat by water")).findFirst().orElseThrow(() -> new AssertionError(c.why()));
        assertEquals("goto 8 64 0 then farm plant wheat here 4 then place farm 8 64 0", w.command());
        assertTrue(c.why().contains("wheat with a bucket: no water bucket"), c.why());
        FoodOptions.FoodFacts bucket = new FoodOptions.FoodFacts(16, 2, Map.of("wheat_seeds", 5, "water_bucket", 1), 24, true, Map.of(), 0, null,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME, Map.of(), 20, 24, null, null);
        Options.Choice b = choose(bucket);
        Options.Option here = b.ranked().stream().filter(o -> o.id().equals("wheat with a bucket")).findFirst().orElseThrow(() -> new AssertionError(b.why()));
        assertEquals("farm plant wheat here 4 then place farm 0 64 0", here.command());
        // both: the walk to the water is weighed against the bucket's extra minute
        FoodOptions.FoodFacts both = new FoodOptions.FoodFacts(16, 2, Map.of("wheat_seeds", 5, "water_bucket", 1), 24, true, Map.of(), 0, null,
                null, "oak", -1, null, -1, null, null, null, true, false, 0, false, ME, Map.of(), 20, 24, null, spot);
        assertEquals("wheat by water", choose(both).best().id(), choose(both).why());
    }

    /** Owner's rule 2026-10-19 (b): a food stock may walk out to animals at any hunger, beside the hunt inside; costed. */
    @Test
    void walkingOutIsACostedOptionBesideTheHuntInside() {
        FoodOptions.FoodFacts f = new FoodOptions.FoodFacts(19, 5, Map.of(), 24, true, Map.of("chicken", 1), 0, null,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME, Map.of("cow", 4), 20, 30, new int[]{30, 64, 0}, null);
        Options.Choice c = choose(f);
        assertTrue(c.ranked().stream().anyMatch(o -> o.id().equals("hunt chicken")), c.why());
        Options.Option out = c.ranked().stream().filter(o -> o.id().equals("hunt out cow")).findFirst().orElseThrow(() -> new AssertionError(c.why()));
        assertTrue(out.command().startsWith("hunt 2 cow out"), out.command());
        assertTrue(c.ranked().indexOf(out) > 0, "not hungry: the walk out is dear, the hunt inside wins: " + c.why());
        // far (past the hunt's reach): walk there first
        FoodOptions.FoodFacts far = new FoodOptions.FoodFacts(19, 5, Map.of(), 24, true, Map.of(), 0, null,
                null, "oak", -1, null, -1, null, null, null, false, false, 0, false, ME, Map.of("cow", 4), 20, 70, new int[]{70, 64, 0}, null);
        Options.Choice fc = choose(far);
        assertEquals("hunt out cow", fc.best().id(), "the only way: taken at its cost: " + fc.why());
        assertTrue(fc.best().command().startsWith("goto 70 64 0 then hunt 2 cow out"), fc.best().command());
    }

    @Test
    void parkedWithTheReasonWhenNoSourceElseNot() {
        FoodOptions.FoodFacts none = facts(6, Map.of(), true, Map.of(), null);
        String why = FoodOptions.noSource(none);
        assertNotNull(why);
        assertTrue(why.startsWith("no food source: ") && why.contains("hunt: no animal in reach"), why);
        assertNull(FoodOptions.noSource(facts(6, Map.of(), true, Map.of("pig", 1), null)));
        FoodOptions.FoodFacts stored = new FoodOptions.FoodFacts(6, 0, Map.of(), 24, true, Map.of(), 0, null,
                null, "oak", -1, null, -1, null, null, null, false, false, 3, false, ME);
        assertNull(FoodOptions.noSource(stored), "food in storage: the planner fetches it");
        assertNull(FoodOptions.noSource(null), "no facts: never parked");
    }

    @Test
    void aSlowWayThatGainedNothingIsChargedNotRerunForever() {
        FoodOptions.FoodFacts f = new FoodOptions.FoodFacts(16, 2, Map.of(), 24, true, Map.of(), 0, null,
                new int[]{5, 64, 5}, "oak", 7, null, -1, null, null, null, false, false, 0, false, ME);
        FoodOptions.Food set = new FoodOptions.Food();
        Options.OptionMemory m = new Options.OptionMemory();
        assertEquals("apples", set.choose(f, m, 0, null, 0, null, null).best().id());
        Options.Choice again = set.choose(f, m, 1000, null, 0, null, null);
        assertNull(again.best(), again.why());
        assertTrue(again.why().contains("no food gained last time"), again.why());
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
        Rung food = AaLadder.build().stream().filter(r -> r.id.equals("needs.food")).findFirst().orElseThrow();
        assertFalse(food.unmet(bb), "full and stocked: met");
        bb.in.bag = Map.of();
        assertTrue(food.unmet(bb), "full but empty bag: gather before hunger");
    }

    @Test
    void desireParksOnTheFactsAndComesBack() {
        Supplier<FoodOptions.FoodFacts> sup = () -> facts(6, Map.of(), true, Map.of(), null);
        bb.fact(FoodOptions.FACTS, sup);
        assertNotNull(AaDesires.foodInfeasible(bb));
        Supplier<FoodOptions.FoodFacts> cows = () -> facts(6, Map.of(), true, Map.of("cow", 2), null);
        bb.fact(FoodOptions.FACTS, cows);
        assertNull(AaDesires.foodInfeasible(bb), "re-checked: animals came into reach");
    }
}
