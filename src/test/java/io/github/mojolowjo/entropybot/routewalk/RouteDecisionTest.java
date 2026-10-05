package io.github.mojolowjo.entropybot.routewalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteDecisionTest {
    private static RouteDecision.Result ok() {
        return RouteDecision.decide(true, true, false, 0, 0, true, true, 100, true);
    }

    @Test
    void aRoutedGoalWalkGetsALongerStuckWindow() {
        assertEquals(1800, RouteDecision.stuckTicks(true, 600), "review S1: 90 s for a RouteGoal detour");
        assertEquals(600, RouteDecision.stuckTicks(false, 600), "plain walks and legs keep today's 30 s");
        assertEquals(2400, RouteDecision.stuckTicks(true, 2400), "never shorter than the plain window");
    }

    @Test
    void allConditionsMetUsesTheRouter() {
        RouteDecision.Result r = ok();
        assertTrue(r.use());
        assertEquals("ok", r.why());
    }

    @Test
    void featureOff() {
        assertEquals("route off", RouteDecision.decide(false, true, false, 0, 0, true, true, 100, true).why());
    }

    @Test
    void plannerNotRunning() {
        RouteDecision.Result r = RouteDecision.decide(true, false, false, 0, 0, true, true, 100, true);
        assertFalse(r.use());
        assertEquals("planner not running", r.why());
    }

    @Test
    void breakingOnStaysPlain() {
        assertEquals("breaking is on", RouteDecision.decide(true, true, true, 0, 0, true, true, 100, true).why());
    }

    @Test
    void dimensions() {
        assertEquals("another dimension", RouteDecision.decide(true, true, false, 0, -1, true, true, 100, true).why());
        assertEquals("not the overworld", RouteDecision.decide(true, true, false, -1, -1, true, true, 100, true).why());
    }

    @Test
    void areas() {
        assertEquals("start outside my areas", RouteDecision.decide(true, true, false, 0, 0, false, true, 100, true).why());
        assertEquals("goal outside my areas", RouteDecision.decide(true, true, false, 0, 0, true, false, 100, true).why());
    }

    @Test
    void distanceMustBeOver32() {
        assertFalse(RouteDecision.decide(true, true, false, 0, 0, true, true, 32, true).use());
        assertEquals("short walk", RouteDecision.decide(true, true, false, 0, 0, true, true, 10, true).why());
        assertTrue(RouteDecision.decide(true, true, false, 0, 0, true, true, 32.5, true).use());
        assertFalse(RouteDecision.decide(true, true, false, 0, 0, true, true, Double.NaN, true).use());
    }

    @Test
    void movingTargetStaysPlain() {
        assertEquals("not a fixed goal", RouteDecision.decide(true, true, false, 0, 0, true, true, 100, false).why());
    }
}
