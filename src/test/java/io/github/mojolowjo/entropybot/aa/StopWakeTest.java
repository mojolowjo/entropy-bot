package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * stop-wake (to-do/stop-wake-plan.md, the owner's yes 2026-11-21). Cloud tester run 11: two orders stopped on "blocked by
 * water" at -282 49 -76, the bot walked 40 blocks to the chest and stood there 26 minutes ("no wake by itself"). Run 10:
 * a cut step ended "no progress 30 s ... farthest 0 from the start" and was repaired with another tree 87 times.
 */
class StopWakeTest {
    static final String WATER = "stopped at step 1 (dig -282 49 -76 -282 49 -76): blocked by water at -283 49 -76";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static Goals.Goal log(Blackboard bb, String key) {
        return Goals.add(bb, new Thing.Result(key, key, false, List.of("minecraft:" + key), List.of(key), 0, null, key, null), 16, 0, 0);
    }

    static Blackboard at(FakePort port, Clock clock, int x, int y, int z) {
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{x, y, z};
        return bb;
    }

    /** The park's facts, one by one (pure). */
    @Test
    void aParkChangesOnItsFacts() {
        int far = (int) Math.ceil(Failures.movedV()) + 8;
        Goals.Park place = new Goals.Park(Goals.ParkKind.PLACE, new int[]{0, 64, 0}, 10, Set.of("spruce_log at 1 2 3"), Set.of("dig down here"));
        assertNull(Goals.parkChange(place, 10, new int[]{0, 64, 0}, Set.of("spruce_log at 1 2 3"), Set.of("dig down here")), "nothing changed");
        assertNull(Goals.parkChange(place, 99, new int[]{far - 1, 64, 0}, null, Set.of("dig down here")), "a place park: the bag is no change; under the band is no move");
        assertTrue(Goals.parkChange(place, 10, new int[]{far, 64, 0}, null, Set.of("dig down here")).startsWith("moved " + far + " blocks from where it stopped (0 64 0)"));
        assertEquals("no longer held: dig down here", Goals.parkChange(place, 10, new int[]{0, 64, 0}, null, Set.of()));
        assertEquals("a new source seen: spruce_log at 9 9 9", Goals.parkChange(place, 10, new int[]{0, 64, 0}, Set.of("spruce_log at 1 2 3", "spruce_log at 9 9 9"), Set.of("dig down here")));
        assertNull(Goals.parkChange(place, -1, null, null, null), "rule 15: nothing known is no change");
        Goals.Park bag = new Goals.Park(Goals.ParkKind.BAG, new int[]{0, 64, 0}, 10, null, Set.of());
        assertEquals("the bag grew from 10 to 12 items", Goals.parkChange(bag, 12, new int[]{0, 64, 0}, null, Set.of()));
    }

    /** Run 11: stopped by water, then the bot stands 40 blocks away - the order runs again; and again after the next stop (no once-per-order flag). */
    @Test
    void aStoppedOrderRunsAgainWhenTheBotMoved_everyTime() {
        FakePort port = new FakePort();
        Blackboard bb = at(port, new Clock.Fake(0), -282, 50, -76);
        Goals.Goal g = log(bb, "spruce_log");
        assertTrue(Goals.park(bb, g, WATER));
        assertNotNull(g.park);
        assertEquals(Goals.ParkKind.PLACE, g.park.kind());
        assertTrue(port.told.get(0).contains("priced again by itself when I am 24 blocks from -282 50 -76"), port.told.toString());
        assertTrue(Goals.wake(g).contains("priced again by itself"), Goals.wake(g));
        assertTrue(Goals.retryLoopParks(bb).isEmpty(), "nothing changed");
        assertNotNull(g.parked);

        bb.in.pos = new int[]{-256, 70, -108};           // the deposit walk to the chest
        List<String> said = Goals.retryLoopParks(bb);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("order spruce_log runs again: moved "), said.get(0));
        assertNull(g.parked);
        assertNull(g.park);

        assertTrue(Goals.park(bb, g, "no way to get it here"));          // it stops again, at the chest
        assertTrue(Goals.retryLoopParks(bb).isEmpty(), "the same place: no change");
        bb.in.pos = new int[]{-200, 70, -108};
        assertEquals(1, Goals.retryLoopParks(bb).size(), "moved again: priced again (not once per order)");
        assertNull(g.parked);
    }

    /** Every order stopped on the surface: after goals.lookAroundAfterS one order takes one look-around leg; it looks once per stop. */
    @Test
    void everyOrderStoppedOnTheSurface_oneLooksAround() {
        FakePort port = new FakePort();
        Clock.Fake clock = new Clock.Fake(0);
        Blackboard bb = at(port, clock, 0, 64, 0);
        Goals.Goal a = log(bb, "spruce_log"), b = log(bb, "oak_log");
        Goals.park(bb, a, WATER);
        Goals.park(bb, b, WATER);
        List<String> first = Goals.parkedTick(bb, false);
        assertTrue(first.stream().anyMatch(s -> s.startsWith("every order is parked")), first.toString());
        assertFalse(a.lookAround || b.lookAround, "not before goals.lookAroundAfterS");
        clock.advance((long) (Tuning.get("goals.lookAroundAfterS") * 1000));
        List<String> said = Goals.parkedTick(bb, false);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("order spruce_log looks around:"), said.get(0));
        assertTrue(a.lookAround && a.looked && a.parked == null, "the first order's next run is the leg");
        assertNotNull(b.parked, "one order at a time");
        // the leg found nothing: it stops again; the other order takes its leg, then no one looks again
        a.lookAround = false;
        Goals.park(bb, a, "no way to get it here");
        clock.advance(60_000);
        Goals.parkedTick(bb, false);
        clock.advance(60_000);
        List<String> second = Goals.parkedTick(bb, false);
        assertTrue(second.stream().anyMatch(s -> s.startsWith("order oak_log looks around:")) || b.looked, second.toString());
        b.lookAround = false;
        Goals.park(bb, b, "no way to get it here");
        clock.advance(600_000);
        Goals.parkedTick(bb, false);
        clock.advance(600_000);
        Goals.parkedTick(bb, false);
        assertFalse(a.lookAround || b.lookAround, "each looked once for this stop");
        assertNotNull(a.parked);
        // under ground it is the climb way's turn, never the look-around
        Goals.Goal c = log(bb, "birch_log");
        Goals.park(bb, c, WATER);
        clock.advance(600_000);
        Goals.parkedTick(bb, true);
        clock.advance(600_000);
        Goals.parkedTick(bb, true);
        assertFalse(c.lookAround);
    }

    /** Run 10: a walk-first step that ended "no progress" with the feet never off the start cell is the cell's fact (pure). */
    @Test
    void aStepThatNeverLeftItsCellIsTheCellsFact() {
        int[] feet = {-261, 59, -96};
        Step cut = new Step.ChopTree(new int[]{-262, 70, -109}, 7, "minecraft:spruce_log");
        String why = "no progress 30 s: the goal heuristic 77.4 (best 77.4 at 36 s), feet -261 59 -96 (farthest 0 from the start)";
        FactChange.Fact f = Steps.cellFact(cut, why, 0, feet);
        assertNotNull(f);
        assertEquals("source " + NeedChain.boxedInKey(feet), f.key());
        assertEquals(NeedChain.boxedInKey(feet), FactChange.ownSourceKey(f), "held by the cell's key, not the tree's");
        assertNull(Steps.cellFact(cut, why, 3, feet), "the feet moved: the source's fact as before");
        assertNull(Steps.cellFact(cut, "timed out after 600 s", 0, feet), "not the monitor's no-progress");
        assertNull(Steps.cellFact(new Step.ChopTree(new int[]{-262, 60, -97}, 7, "minecraft:spruce_log"), why, 0, feet), "a tree in reach needs no walk");
        assertNull(Steps.cellFact(new Step.Craft("stick", 4), why, 0, feet), "not a step that goes anywhere");
    }

    /** A boxed-in fact near the feet prices the walks beyond the mine's sight out; the same spot is taken without it. */
    @Test
    void noWalkFromABoxedInCell() {
        Z74RunACTest.data();
        Spot far = new Spot(30, 64, 0, 30, "minecraft:stone").covered(0, null);
        NeedChain.WhereFacts here = new NeedChain.WhereFacts(w -> null, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0}, () -> null);
        NeedChain.World open = Z77StoneHereTest.world(word -> List.of(far)).withWhere(here);
        Chain a = NeedChain.resolve("cobblestone", 2, open);
        assertTrue(a.steps().stream().anyMatch(s -> s instanceof Step.Collect), a.why());
        Chain b = NeedChain.resolve("cobblestone", 2, open.withoutSources(Map.of(NeedChain.boxedInKey(new int[]{0, 64, 0}), "no progress 30 s - the feet never left 0 64 0")));
        assertTrue(b.steps().stream().noneMatch(s -> s instanceof Step.Collect), "no walk to the far spot from the boxed-in cell: " + b.steps() + " / " + b.why());
    }
}
