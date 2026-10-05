package io.github.mojolowjo.entropybot.routewalk;

import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.CostToGo;
import io.github.mojolowjo.entropybot.route.Door;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.route.RouteStats;
import io.github.mojolowjo.entropybot.route.SectionKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteWalkTest {
    static final Cell START = new Cell(0, 64, 0), GOAL = new Cell(120, 64, 0);

    static Door door(int x, int y, int z) {
        return new Door(1, new long[4], x, y, z, x, y, z, x, y + 1, z, true, true);
    }

    static CostToGo table() {
        SectionKey k = SectionKey.of(0, 0, 64, 0);
        CostToGo.BoxCosts b = new CostToGo.BoxCosts(k, false, new Door[]{door(15, 64, 8)}, new double[]{300});
        return new CostToGo(0, SectionKey.of(0, GOAL.x(), GOAL.y(), GOAL.z()), GOAL, Map.of(k, b));
    }

    static RoutePlan okPlan() {
        List<RoutePlan.Step> path = List.of(
                new RoutePlan.Step(SectionKey.of(0, 0, 64, 0), 0, new Cell(15, 64, 8)),
                new RoutePlan.Step(SectionKey.of(0, 16, 64, 0), 0, new Cell(31, 64, 8)),
                new RoutePlan.Step(SectionKey.of(0, 32, 64, 0), 0, new Cell(47, 64, 8)),
                new RoutePlan.Step(SectionKey.of(0, 48, 64, 0), 0, new Cell(63, 64, 8)),
                new RoutePlan.Step(SectionKey.of(0, 64, 64, 0), 0, new Cell(79, 64, 8)),
                new RoutePlan.Step(SectionKey.of(0, 80, 64, 0), 0, new Cell(95, 64, 8)));
        return new RoutePlan(RoutePlan.Status.OK, 500, path, table(), 12, 900, "ok");
    }

    static RouteWalk walk(RouteWalk.Mode m, CompletableFuture<RoutePlan> f) {
        return new RouteWalk(m, 0, START, GOAL, f, 1000);
    }

    @Test
    void waitsThenTimesOutAfter300msAndCountsIt() {
        RouteCounters c = new RouteCounters();
        RouteWalk w = walk(RouteWalk.Mode.GOAL, new CompletableFuture<>());
        assertEquals(RouteWalk.Phase.PLANNING, w.poll(1100, c, null));
        assertEquals(RouteWalk.Phase.PLANNING, w.poll(1299, c, null));
        assertEquals(RouteWalk.Phase.PLAIN, w.poll(1300, c, null));
        assertTrue(w.used().startsWith("plain (planning took over 300 ms"));
        assertEquals(300, w.planWaitMs());
        RouteStats s = c.snapshot(0, 0, 0);
        assertEquals(1, s.planningTimeouts());
        // polling again changes nothing and counts nothing more
        assertEquals(RouteWalk.Phase.PLAIN, w.poll(5000, c, null));
        assertEquals(1, c.snapshot(0, 0, 0).planningTimeouts());
    }

    @Test
    void aTimeoutOrAnEndedWalkCancelsThePlan() {
        CompletableFuture<RoutePlan> f = new CompletableFuture<>();
        RouteWalk w = walk(RouteWalk.Mode.GOAL, f);
        w.poll(1300, new RouteCounters(), null);
        assertTrue(f.isCancelled(), "the planner thread skips it");
        CompletableFuture<RoutePlan> g = new CompletableFuture<>();
        RouteWalk v = walk(RouteWalk.Mode.LEGS, g);
        v.cancel();
        assertTrue(g.isCancelled());
        CompletableFuture<RoutePlan> done = CompletableFuture.completedFuture(okPlan());
        walk(RouteWalk.Mode.GOAL, done).cancel();
        assertFalse(done.isCancelled(), "a plan that came is left alone");
        walk(RouteWalk.Mode.GOAL, null).cancel();      // no plan asked: nothing to do, no throw
    }

    @Test
    void goalModeWithATable() {
        RouteWalk w = walk(RouteWalk.Mode.GOAL, CompletableFuture.completedFuture(okPlan()));
        assertEquals(RouteWalk.Phase.ROUTED, w.poll(1040, new RouteCounters(), null));
        assertTrue(w.routed());
        assertEquals("goal", w.used());
        assertEquals(1, w.table().size());
        assertEquals(40, w.planWaitMs());
        assertFalse(w.hasNextLeg());
        assertNull(w.currentLeg());
    }

    @Test
    void goalModeWithAnEmptyTableIsPlain() {
        RoutePlan p = new RoutePlan(RoutePlan.Status.NO_GOAL_BOX, Double.POSITIVE_INFINITY, List.of(), CostToGo.empty(), 0, 10,
                "goal box not built yet");
        RouteWalk w = walk(RouteWalk.Mode.GOAL, CompletableFuture.completedFuture(p));
        assertEquals(RouteWalk.Phase.PLAIN, w.poll(1001, new RouteCounters(), null));
        assertEquals("plain (no plan: goal box not built yet)", w.used());
    }

    @Test
    void goalModeUsesATableEvenWhenUnreachable() {
        // review: the table guides from the boxes it knows also with NO_START_BOX / UNREACHABLE
        RoutePlan p = new RoutePlan(RoutePlan.Status.NO_START_BOX, Double.POSITIVE_INFINITY, List.of(), table(), 3, 10, "start box not built");
        RouteWalk w = walk(RouteWalk.Mode.GOAL, CompletableFuture.completedFuture(p));
        assertEquals(RouteWalk.Phase.ROUTED, w.poll(1001, new RouteCounters(), null));
    }

    @Test
    void legsModeSplitsAndStepsThroughTheLegs() {
        RouteWalk w = walk(RouteWalk.Mode.LEGS, CompletableFuture.completedFuture(okPlan()));
        assertEquals(RouteWalk.Phase.ROUTED, w.poll(1001, new RouteCounters(), null));
        List<Cell> legs = w.legs();
        assertTrue(legs.size() >= 3, "legs " + legs);
        assertEquals(GOAL, legs.get(legs.size() - 1));
        assertEquals("legs(" + legs.size() + ")", w.used());
        assertEquals(legs.get(0), w.currentLeg());
        assertFalse(w.lastLeg());
        int n = 0;
        while (w.hasNextLeg()) {
            w.nextLeg();
            n++;
        }
        assertEquals(legs.size() - 1, n);
        assertTrue(w.lastLeg());
        assertEquals(GOAL, w.currentLeg());
    }

    @Test
    void legsModeNeedsAnOkPath() {
        RoutePlan p = new RoutePlan(RoutePlan.Status.UNREACHABLE, Double.POSITIVE_INFINITY, List.of(), table(), 3, 10, "no door path");
        RouteWalk w = walk(RouteWalk.Mode.LEGS, CompletableFuture.completedFuture(p));
        assertEquals(RouteWalk.Phase.PLAIN, w.poll(1001, new RouteCounters(), null));
        assertEquals("plain (no route: no door path)", w.used());
    }

    @Test
    void failedFutureIsPlainAndCounted() {
        CompletableFuture<RoutePlan> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalStateException("boom"));
        RouteCounters c = new RouteCounters();
        RouteWalk w = walk(RouteWalk.Mode.GOAL, f);
        assertEquals(RouteWalk.Phase.PLAIN, w.poll(1001, c, null));
        assertTrue(w.used().contains("boom"), w.used());
        assertEquals(1, c.snapshot(0, 0, 0).workerExceptions());
    }

    @Test
    void nullPlanOrNullFutureIsPlain() {
        assertEquals(RouteWalk.Phase.PLAIN, walk(RouteWalk.Mode.GOAL, CompletableFuture.completedFuture(null)).poll(1001, null, null));
        assertEquals(RouteWalk.Phase.PLAIN, walk(RouteWalk.Mode.GOAL, null).poll(1001, null, null));
    }

    @Test
    void fallbackAfterNoPathOnceAndCounted() {
        RouteCounters c = new RouteCounters();
        RouteWalk w = walk(RouteWalk.Mode.GOAL, CompletableFuture.completedFuture(okPlan()));
        w.poll(1001, c, null);
        assertTrue(w.fallBack("no path", c));
        assertEquals(RouteWalk.Phase.FELL_BACK, w.phase());
        assertEquals("goal -> plain (no path)", w.used());
        assertFalse(w.routed());
        assertFalse(w.fallBack("no path", c), "only once");
        RouteStats s = c.snapshot(0, 0, 0);
        assertEquals(1, s.fallbacksNoPath());
        assertEquals(0, s.fallbacksStuck());
    }

    @Test
    void fallbackAfterStuckInLegsMode() {
        RouteCounters c = new RouteCounters();
        RouteWalk w = walk(RouteWalk.Mode.LEGS, CompletableFuture.completedFuture(okPlan()));
        w.poll(1001, c, null);
        assertTrue(w.fallBack("stuck", c));
        assertFalse(w.hasNextLeg());
        assertNull(w.currentLeg());
        assertEquals(1, c.snapshot(0, 0, 0).fallbacksStuck());
    }

    @Test
    void plainWalkHasNoFallback() {
        RouteCounters c = new RouteCounters();
        RouteWalk w = walk(RouteWalk.Mode.GOAL, new CompletableFuture<>());
        w.poll(2000, c, null);
        assertFalse(w.fallBack("no path", c));
        assertEquals(0, c.snapshot(0, 0, 0).fallbacksNoPath());
    }

    @Test
    void modeWords() {
        assertEquals(RouteWalk.Mode.GOAL, RouteWalk.Mode.parse(" Goal "));
        assertEquals(RouteWalk.Mode.LEGS, RouteWalk.Mode.parse("legs"));
        assertEquals(RouteWalk.Mode.PLAIN, RouteWalk.Mode.parse("plain"));
        assertNull(RouteWalk.Mode.parse("fast"));
        assertNull(RouteWalk.Mode.parse(null));
        assertEquals("legs", RouteWalk.Mode.LEGS.word());
    }
}
