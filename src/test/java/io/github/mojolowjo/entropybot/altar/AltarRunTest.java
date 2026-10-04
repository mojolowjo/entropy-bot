package io.github.mojolowjo.entropybot.altar;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.mojolowjo.entropybot.altar.FakeAltar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Package E: the altar run against a fake of item 14's altar (clicks land 2 ticks late, a craft takes 3 s). */
class AltarRunTest {
    final JsonObject brain = new JsonObject();
    final AltarMemory mem = new AltarMemory(brain, null);

    AltarRun run(FakeAltar a, int n) {
        AltarPlan.Layout l = AltarPlan.layout(ALTAR, a, p -> true, 8);
        assertTrue(l.ok(), l.error());
        return new AltarRun(l, AltarPlanTest.pick(), SEEDS, n, mem);
    }

    /** Every 2 ticks (as Seq), until it ends; {@code at}: a hook per tick. */
    static AltarRun.Out drive(AltarRun r, FakeAltar a, int maxTicks, java.util.function.LongConsumer at) {
        for (long t = 1; t <= maxTicks; t++) {
            a.tick(t);
            if (at != null) at.accept(t);
            if (t % 2 != 0) continue;
            AltarRun.Out o = r.tick(a, t);
            if (o.state() != AltarRun.State.WAIT) return o;
        }
        return null;
    }

    static FakeAltar stocked(int seeds) {
        return FakeAltar.item14().give(BASE, seeds).give(SILICON, 4 * seeds).give(PRUD, 4 * seeds);
    }

    @Test
    void twoSeedsTheWayItem14MadeOne() {
        FakeAltar a = stocked(2);
        AltarRun r = run(a, 2);
        AltarRun.Out o = drive(r, a, 4000, null);
        assertNotNull(o, "never ended");
        assertEquals(AltarRun.State.DONE, o.state(), o.text());
        assertEquals("made 2 mysticalagriculture:silicon_seeds on the infusion altar at -23 53 156 (in my bag)", o.text());
        assertEquals(2, a.bag(SEEDS));
        assertEquals(0, a.bag(BASE) + a.bag(SILICON) + a.bag(PRUD), "everything used");
        // item 14's order: the base on the altar, silicon on the first 4 pedestals, prudentium on the other 4, the button, the take
        assertEquals(List.of("-23 53 156 prosperity_seed_base", "-26 53 156 silicon", "-25 53 154 silicon", "-25 53 158 silicon", "-23 53 153 silicon",
                "-23 53 159 prudentium_essence", "-21 53 154 prudentium_essence", "-21 53 158 prudentium_essence", "-20 53 156 prudentium_essence",
                "-23 53 157 -", "-23 53 156 -"), a.clicks.subList(0, 11));
        assertEquals(22, a.clicks.size(), "nothing clicked twice");
        assertEquals(0, mem.count(), "nothing of the bot's left on the altar");
        assertNull(mem.pressed());
    }

    @Test
    void somebodyElsesItemIsNeverTouched() {
        FakeAltar a = stocked(1).put(PEDS[6], 0, "minecraft:iron_ingot");
        AltarRun.Out o = drive(run(a, 1), a, 400, null);
        assertEquals(AltarRun.State.FAIL, o.state());
        assertTrue(o.text().startsWith("the pedestal at -21 53 158 holds 1 iron_ingot that isn't mine"), o.text());
        assertTrue(a.clicks.isEmpty(), "not one click");
        assertEquals("minecraft:iron_ingot", a.on(PEDS[6], 0));
    }

    @Test
    void somebodyTakingTheAltarMidRoundStopsItAndTheBotTakesOnlyItsOwnBack() {
        FakeAltar a = stocked(1);
        AltarRun r = run(a, 1);
        // after 3 pedestals are filled someone puts their own diamond on pedestal 5
        AltarRun.Out o = drive(r, a, 2000, t -> {
            if (a.on(PEDS[2], 0) != null && a.on(PEDS[4], 0) == null && a.held.get(k(PEDS[4])).isEmpty()) a.put(PEDS[4], 0, "minecraft:diamond");
        });
        assertEquals(AltarRun.State.FAIL, o.state(), String.valueOf(o.text()));
        assertTrue(o.text().contains("diamond"), o.text());
        assertEquals("minecraft:diamond", a.on(PEDS[4], 0), "the diamond stays");
        assertNull(a.on(ALTAR, 0), "its own base taken back");
        for (int i = 0; i < 4; i++) assertNull(a.on(PEDS[i], 0));
        assertEquals(1, a.bag(BASE));
        assertEquals(4, a.bag(SILICON));
        assertEquals(0, mem.count());
    }

    @Test
    void aStoppedRunsItemsAreUsedWhereTheyFit() {
        // a stop left the base on the altar and silicon on pedestal 1 (noted as the bot's), and iron of an older run on pedestal 8
        FakeAltar a = stocked(1).put(ALTAR, 0, BASE).put(PEDS[0], 0, SILICON).put(PEDS[7], 0, "minecraft:iron_ingot");
        a.bag.merge(BASE, -1, Integer::sum);
        a.bag.merge(SILICON, -1, Integer::sum);
        mem.place(ALTAR, BASE);
        mem.place(PEDS[0], SILICON);
        mem.place(PEDS[7], "minecraft:iron_ingot");
        AltarRun.Out o = drive(run(a, 1), a, 4000, null);
        assertEquals(AltarRun.State.DONE, o.state(), o.text());
        assertEquals(1, a.bag(SEEDS));
        assertEquals(1, a.bag("minecraft:iron_ingot"), "the old iron taken back first");
        assertEquals(0, a.placements(ALTAR), "the base already there is used, not placed again");
        assertEquals(0, a.placements(PEDS[0]));
        assertEquals(1, a.placements(PEDS[7]));
        assertEquals(0, mem.count());
    }

    @Test
    void theSeedAStoppedRunLeftIsCollected() {
        FakeAltar a = stocked(1).put(ALTAR, 1, SEEDS);
        mem.pressed(SEEDS);
        AltarRun r = run(a, 1);
        AltarRun.Out o = drive(r, a, 4000, null);
        assertEquals(AltarRun.State.DONE, o.state(), o.text());
        assertEquals(2, a.bag(SEEDS));
        assertEquals(1, r.recovered());
        assertTrue(o.text().endsWith("also took 1 mysticalagriculture:silicon_seeds a stopped infuse had left on the altar"), o.text());
    }

    @Test
    void aShortBagOrAFullOneFailsBeforeAnyClick() {
        FakeAltar a = FakeAltar.item14().give(BASE, 1).give(SILICON, 3).give(PRUD, 4);
        AltarRun.Out o = drive(run(a, 1), a, 400, null);
        assertEquals(AltarRun.State.FAIL, o.state());
        assertEquals("I have 3 refinedstorage:silicon and seed 1 needs 4 more", o.text());
        assertTrue(a.clicks.isEmpty());
        FakeAltar b = stocked(1);
        b.free = 0;
        o = drive(run(b, 1), b, 400, null);
        assertEquals("my inventory is full - no room for the seed", o.text());
        assertTrue(b.clicks.isEmpty());
    }

    @Test
    void anAltarThatNeverStartsGivesEverythingBack() {
        FakeAltar a = stocked(1);
        a.peds = List.of("minecraft:iron_ingot");          // the altar wants something else: it never starts
        AltarRun.Out o = drive(run(a, 1), a, 4000, null);
        assertEquals(AltarRun.State.FAIL, o.state());
        assertTrue(o.text().startsWith("the altar didn't start after 2 presses of the button"), o.text());
        assertEquals(2, a.presses);
        assertEquals(1, a.bag(BASE));
        assertEquals(4, a.bag(SILICON));
        assertEquals(4, a.bag(PRUD));
        assertEquals(0, mem.count(), "all taken back");
        assertNull(mem.pressed());
    }

    @Test
    void aFightMidRoundNeverPlacesTwice() {
        FakeAltar a = stocked(1);
        AltarRun r = run(a, 1);
        boolean[] held = {false};
        AltarRun.Out o = drive(r, a, 4000, t -> {
            if (!held[0] && a.on(PEDS[2], 0) != null) {
                held[0] = true;
                r.interrupted();
                a.menuOpen = true;                      // the fight left the inventory open
            }
        });
        assertTrue(held[0]);
        assertEquals(AltarRun.State.DONE, o.state(), o.text());
        assertEquals(1, a.placements(ALTAR));
        for (int[] p : PEDS) assertEquals(1, a.placements(p), "pedestal " + k(p));
        assertEquals(1, a.bag(SEEDS));
    }

    @Test
    void aCraftInProgressIsWaitedForThroughAMeal() {
        FakeAltar a = stocked(1);
        a.craftTicks = 200;
        AltarRun r = run(a, 1);
        boolean[] held = {false};
        AltarRun.Out o = drive(r, a, 4000, t -> {
            if (!held[0] && a.active) {
                held[0] = true;
                r.interrupted();
            }
        });
        assertEquals(AltarRun.State.DONE, o.state(), o.text());
        assertEquals(1, a.presses, "never pressed again while it crafts");
    }

    @Test
    void theMemorySurvivesInCommandsJson() {
        mem.place(PEDS[1], SILICON);
        mem.pressed(SEEDS);
        AltarMemory again = new AltarMemory(brain, null);
        assertEquals(SILICON, again.placed(PEDS[1]));
        assertEquals(SEEDS, again.pressed());
        assertEquals("{\"altar\":{\"placed\":{\"-25 53 154\":\"refinedstorage:silicon\"},\"pressed\":\"mysticalagriculture:silicon_seeds\"}}", brain.toString());
        again.forget(PEDS[1]);
        again.pressed(null);
        assertEquals(0, mem.count());
        assertNull(mem.pressed());
        // the notes made right before a click are flushed at once, a forget only marks the file changed
        int[] flushed = {0}, marked = {0};
        AltarMemory m2 = new AltarMemory(new JsonObject(), () -> marked[0]++, () -> flushed[0]++);
        m2.place(PEDS[0], SILICON);
        m2.pressed(SEEDS);
        m2.forget(PEDS[0]);
        assertEquals(2, flushed[0]);
        assertEquals(1, marked[0]);
    }

    @Test
    void aFightThatPushesTheBotAwayIsWalkedBackFrom() {
        // review fix 2: the knockback leaves every pedestal out of reach; the job walks back onto the spot (Seq.walkBackTo)
        FakeAltar a = stocked(1);
        AltarRun r = run(a, 1);
        long[] pushedAt = {-1};
        AltarRun.Out o = drive(r, a, 4000, t -> {
            if (pushedAt[0] < 0 && a.on(PEDS[2], 0) != null) {
                pushedAt[0] = t;
                a.at = new double[]{-16.5, 53, 162.5};
                r.interrupted();
            }
            if (pushedAt[0] > 0 && t == pushedAt[0] + 1) a.at = new double[]{-21.5, 53, 157.5};    // the walk back, before the next step
        });
        assertEquals(AltarRun.State.DONE, o.state(), o.text());
        for (int[] p : PEDS) assertEquals(1, a.placements(p));
        // without the walk back every click is refused: it ends, and what is still on the pedestals stays noted as its own
        FakeAltar b = stocked(1);
        AltarMemory m = new AltarMemory(new JsonObject(), null);
        AltarRun r2 = new AltarRun(AltarPlan.layout(ALTAR, b, p -> true, 8), AltarPlanTest.pick(), SEEDS, 1, m);
        boolean[] pushed = {false};
        AltarRun.Out o2 = drive(r2, b, 4000, t -> {
            if (!pushed[0] && b.on(PEDS[2], 0) != null) {
                pushed[0] = true;
                b.at = new double[]{-16.5, 53, 162.5};
                r2.interrupted();
            }
        });
        assertEquals(AltarRun.State.FAIL, o2.state());
        assertTrue(o2.text().contains("out of my reach"), o2.text());
        assertTrue(m.count() >= 3, "the base and 3 pedestals stay noted: the next infuse takes them back");
    }

    @Test
    void standingOffTheSpotsCentreStillReachesEverything() {
        // review fix 3: reach is measured to the block's box with 0.5 to spare at the stand spot
        FakeAltar a = stocked(1);
        a.at = new double[]{-21.1, 53, 157.9};
        AltarRun.Out o = drive(run(a, 1), a, 4000, null);
        assertEquals(AltarRun.State.DONE, o.state(), o.text());
    }

    @Test
    void aNoteForAnEmptiedPedestalDoesNotMakeAFriendsItemTheBots() {
        // review fix 1: the bot's silicon was taken off pedestal 1 by hand; a friend later puts their own silicon there
        FakeAltar a = stocked(1);
        mem.place(PEDS[0], SILICON);
        mem.pressed(SEEDS);
        AltarPlan.Layout l = AltarPlan.layout(ALTAR, a, p -> true, 8);
        AltarPlan.Survey sv = AltarPlan.survey(l, AltarPlan.targets(l, AltarPlanTest.pick()), a, mem);
        assertNull(sv.foreign());
        assertNull(mem.placed(PEDS[0]), "one look at the empty pedestal forgets the note");
        assertNull(mem.pressed(), "nothing of the bot's on the altar and no output: pressed is dropped");
        a.put(PEDS[0], 0, SILICON);
        AltarRun.Out o = drive(run(a, 1), a, 400, null);
        assertEquals(AltarRun.State.FAIL, o.state());
        assertTrue(o.text().startsWith("the pedestal at -26 53 156 holds 1 refinedstorage:silicon that isn't mine"), o.text());
        assertTrue(a.clicks.isEmpty());
        assertEquals(SILICON, a.on(PEDS[0], 0), "the friend's silicon stays");
    }
}
