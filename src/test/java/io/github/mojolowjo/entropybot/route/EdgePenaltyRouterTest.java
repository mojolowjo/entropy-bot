package io.github.mojolowjo.entropybot.route;

import io.github.mojolowjo.entropybot.move.EdgePenalties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.3: a penalised door (a leg that failed or ran long) steers the door-graph router onto another way. */
class EdgePenaltyRouterTest {
    @AfterEach
    void clear() { EdgePenalties.GLOBAL.clear(); }

    @Test
    void aPenalisedEdgeIsAvoidedByTheNextPlan() {
        FakeWorld w = new FakeWorld(64);
        RouteStore store = RouterTest.buildBoxes(w, 0, 4, 0, 1);        // two rows of boxes: two ways east
        Cell s = new Cell(8, 64, 8), g = new Cell(72, 64, 8);
        RoutePlan first = RouterTest.plan(store, w, s, g);
        assertEquals(RoutePlan.Status.OK, first.status(), first.reason());
        Set<String> firstDoors = new HashSet<>();
        long now = System.currentTimeMillis();
        for (RoutePlan.Step st : first.path()) {
            String k = EdgePenalties.key(st.box().toString(), st.rep().x(), st.rep().y(), st.rep().z());
            firstDoors.add(k);
            EdgePenalties.GLOBAL.add(k, EdgePenalties.MAX, now);         // the ravine: every edge of that way failed
        }
        assertEquals(first.path().size(), EdgePenalties.GLOBAL.count(now));
        RoutePlan second = RouterTest.plan(store, w, s, g);
        assertEquals(RoutePlan.Status.OK, second.status(), second.reason());
        boolean changed = false;
        for (RoutePlan.Step st : second.path())
            if (!firstDoors.contains(EdgePenalties.key(st.box().toString(), st.rep().x(), st.rep().y(), st.rep().z()))) changed = true;
        assertTrue(changed, "the second plan takes another way: " + first.path() + " vs " + second.path());
        assertTrue(second.ticks() > first.ticks(), "the detour costs more than the first way did");
    }
}
