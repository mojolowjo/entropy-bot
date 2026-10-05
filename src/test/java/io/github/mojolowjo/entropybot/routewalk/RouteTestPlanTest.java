package io.github.mojolowjo.entropybot.routewalk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTestPlanTest {
    @Test
    void sixTripsGiveEveryModeOneTripEachWay() {
        List<RouteTestPlan.Trip> t = RouteTestPlan.trips(6);
        assertEquals(6, t.size());
        Map<String, Integer> seen = new HashMap<>();
        for (RouteTestPlan.Trip x : t) seen.merge(x.mode() + "/" + x.aToB(), 1, Integer::sum);
        assertEquals(6, seen.size(), seen.toString());
        assertEquals(1, t.get(0).n());
        assertEquals(RouteWalk.Mode.GOAL, t.get(0).mode());
        assertTrue(t.get(0).aToB());
        assertFalse(t.get(1).aToB());
        assertEquals(RouteWalk.Mode.PLAIN, t.get(2).mode());
    }

    @Test
    void clamped() {
        assertEquals(1, RouteTestPlan.trips(0).size());
        assertEquals(RouteTestPlan.MAX_TRIPS, RouteTestPlan.trips(500).size());
    }
}
