package io.github.mojolowjo.entropybot.move;

import io.github.mojolowjo.entropybot.route.Cell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.3 movement package: the pure rules. */
class MovementRulesTest {
    @AfterEach
    void clear() { EdgePenalties.GLOBAL.clear(); }

    // ---- legs ----

    @Test
    void assistOnlyForFarGoals() {
        assertFalse(LegPlan.needsAssist(new int[]{0, 64, 0}, new int[]{40, 64, 20}));
        assertTrue(LegPlan.needsAssist(new int[]{0, 64, 0}, new int[]{49, 64, 0}));
        assertTrue(LegPlan.needsAssist(new int[]{0, 64, 0}, new int[]{5, 40, 0}), "11+ levels off");
        assertFalse(LegPlan.needsAssist(new int[]{0, 64, 0}, new int[]{5, 54, 0}));
    }

    @Test
    void legsFollowAFakeRouteMapAtMost40Apart() {
        Cell start = new Cell(0, 64, 0), goal = new Cell(150, 70, 0);
        List<Cell> doors = List.of(new Cell(16, 64, 0), new Cell(32, 65, 0), new Cell(48, 66, 0), new Cell(64, 66, 5),
                new Cell(80, 67, 5), new Cell(96, 68, 0), new Cell(112, 69, 0), new Cell(128, 70, 0), new Cell(144, 70, 0));
        List<LegPlan.Leg> legs = LegPlan.legs(start, doors, goal);
        assertTrue(legs.size() >= 4 && legs.size() <= 6, "legs " + legs);
        Cell prev = start;
        for (LegPlan.Leg l : legs) {
            assertTrue(prev.dist(l.end()) <= LegPlan.MAX_LEG + 1e-9 || l.last(), "leg too long " + prev + " -> " + l.end());
            assertFalse(l.xzOnly());
            prev = l.end();
        }
        assertEquals(goal, legs.get(legs.size() - 1).end());
        assertTrue(legs.get(legs.size() - 1).last());
        assertTrue(doors.contains(legs.get(0).end()), "legs end on the map's doors");
    }

    @Test
    void withoutAMapTheLegsAreStraightAndHeightless() {
        List<LegPlan.Leg> legs = LegPlan.legs(new Cell(0, 64, 0), List.of(), new Cell(0, 80, 150));
        assertEquals(5, legs.size());
        for (int i = 0; i < 4; i++) {
            assertTrue(legs.get(i).xzOnly());
            assertEquals(0, legs.get(i).end().x());
            assertEquals(30 * (i + 1), legs.get(i).end().z());
        }
        assertFalse(legs.get(4).xzOnly());
        assertEquals(new Cell(0, 80, 150), legs.get(4).end());
        assertEquals(1, LegPlan.legs(new Cell(0, 64, 0), null, new Cell(10, 64, 0)).size());
        assertEquals(6, LegPlan.distTo(legs.get(0), 0, 10, 24), 1e-9, "xz-only: height ignored");
    }

    // ---- no dead stops ----

    @Test
    void noDeadStopLaddersToTheReportAfterThree() {
        NoDeadStop m = new NoDeadStop();
        assertEquals(NoDeadStop.Action.NEXT_WAYPOINT, m.onFail(true));
        assertTrue(NoDeadStop.keepsMoving(NoDeadStop.Action.NEXT_WAYPOINT));
        assertEquals(NoDeadStop.Action.DETOUR, m.onFail(true));
        assertTrue(NoDeadStop.keepsMoving(NoDeadStop.Action.DETOUR));
        assertEquals(NoDeadStop.Action.DIG_OUT, m.onFail(true), "underground in the areas: dig out first");
        assertEquals(NoDeadStop.Action.REPORT, m.onFail(true), "after the dig-out: report");
        assertFalse(NoDeadStop.keepsMoving(NoDeadStop.Action.REPORT));
        assertEquals(4, m.totalFails());
    }

    @Test
    void onTheSurfaceTheThirdFailReportsAndAReachedLegResets() {
        NoDeadStop m = new NoDeadStop();
        m.onFail(false);
        m.onFail(false);
        m.onLegReached();
        assertEquals(0, m.failsInRow());
        assertEquals(NoDeadStop.Action.NEXT_WAYPOINT, m.onFail(false), "a reached leg starts the count again");
        assertEquals(NoDeadStop.Action.DETOUR, m.onFail(false));
        assertEquals(NoDeadStop.Action.REPORT, m.onFail(false), "no dig-out on the surface");
        assertEquals(NoDeadStop.Action.REPORT, m.digOutFailed());
    }

    // ---- ready paths ----

    static Map<String, Cell> targets() {
        Map<String, Cell> t = new LinkedHashMap<>();
        t.put("home", new Cell(100, 64, 0));
        t.put("mine", new Cell(0, 30, 100));
        return t;
    }

    @Test
    void readyPathsPlanOncePerBudgetAndRefreshWhenMovedOrChanged() {
        ReadyPaths r = new ReadyPaths();
        r.setTargets(targets());
        Cell me = new Cell(0, 64, 0);
        long t = 10_000;
        ReadyPaths.Entry a = r.due(me, t);
        assertNotNull(a);
        assertNull(r.due(me, t + 500), "one plan per 2 s");
        r.store(a, me, List.of(new Cell(1, 64, 0), new Cell(2, 64, 0), new Cell(3, 64, 0)), false, t + 100);
        ReadyPaths.Entry b = r.due(me, t + 2000);
        assertNotNull(b);
        assertNotEquals(a.name, b.name);
        r.store(b, me, List.of(new Cell(0, 63, 1)), false, t + 2100);
        assertNull(r.due(me, t + 4000), "all fresh");
        // used by a walk to (near) the target
        assertSame(a, r.lookup(me, new Cell(101, 64, 1), t + 4000));
        // moved 8+: stale
        assertNull(r.lookup(new Cell(8, 64, 0), new Cell(100, 64, 0), t + 4000));
        assertNotNull(r.due(new Cell(8, 64, 0), t + 4000));
        // a block change on a's cells: due again
        ReadyPaths r2 = new ReadyPaths();
        r2.setTargets(targets());
        ReadyPaths.Entry e = r2.due(me, t);
        r2.store(e, me, List.of(new Cell(5, 64, 0)), false, t);
        assertEquals(0, r2.blockChanged(50, 64, 50));
        assertEquals(1, r2.blockChanged(6, 65, 1));
        assertNull(r2.lookup(me, e.target, t + 10));
    }

    @Test
    void lastGoalsAndAChainsNextWalkComeFirst() {
        ReadyPaths r = new ReadyPaths();
        for (int i = 0; i < 7; i++) r.rememberGoal(new Cell(i * 10, 64, 0));
        assertEquals(ReadyPaths.LAST_GOALS, r.lastGoals().size());
        assertEquals(new Cell(60, 64, 0), r.lastGoals().get(0));
        r.setPriority(new Cell(500, 64, 500));
        r.setTargets(targets());
        assertEquals("next", r.names().get(0));
        ReadyPaths.Entry e = r.due(new Cell(0, 64, 0), 50_000);
        assertEquals("next", e.name);
        r.setPriority(new Cell(500, 64, 500));          // the same goal again: kept, not planned anew
        assertEquals("next", r.names().get(0));
        r.setPriority(null);
        assertFalse(r.names().contains("next"));
    }

    // ---- profiles ----

    @Test
    void profilesSwitchAndRestore() {
        Map<String, Object> s = new HashMap<>();
        s.put("primarytimeoutms", 500L);
        s.put("failuretimeoutms", 2000L);
        s.put("planaheadprimarytimeoutms", 4000L);
        s.put("allowsprint", true);
        Profiles.Access a = new Profiles.Access() {
            public Object get(String n) { return s.get(n); }

            public void set(String n, Object v) { s.put(n, v); }
        };
        Profiles p = new Profiles();
        assertEquals(Profiles.Profile.WALK, Profiles.choose(true, false, false));
        assertEquals(Profiles.Profile.MINE, Profiles.choose(true, true, false));
        assertEquals(Profiles.Profile.FLEE, Profiles.choose(true, true, true));
        assertEquals(Profiles.Profile.NONE, Profiles.choose(false, false, false));
        assertTrue(p.apply(Profiles.Profile.WALK, a));
        assertEquals(150L, s.get("primarytimeoutms"));
        assertFalse(p.apply(Profiles.Profile.WALK, a), "already on");
        p.apply(Profiles.Profile.FLEE, a);
        assertEquals(100L, s.get("primarytimeoutms"));
        p.apply(Profiles.Profile.NONE, a);
        assertEquals(500L, s.get("primarytimeoutms"), "the owner's values back");
        assertEquals(4000L, s.get("planaheadprimarytimeoutms"));
        assertEquals(Profiles.Profile.NONE, p.active());
        assertEquals(3, p.switches());
    }

    // ---- resume, long legs, penalties ----

    @Test
    void resumeOnlyNearThePausePoint() {
        assertTrue(LegJudge.resume(new int[]{0, 64, 0}, new int[]{2, 64, 2}));
        assertFalse(LegJudge.resume(new int[]{0, 64, 0}, new int[]{3, 64, 3}));
        assertFalse(LegJudge.resume(null, new int[]{0, 64, 0}));
    }

    @Test
    void aLegOverTwiceItsStraightDistanceRanLongAndReplansTheWhole() {
        assertTrue(LegJudge.ranLong(73, 36));
        assertFalse(LegJudge.ranLong(70, 36));
        assertFalse(LegJudge.ranLong(20, 5), "short legs are never judged");
        assertTrue(LegJudge.replanWhole(true, false));
        assertTrue(LegJudge.replanWhole(false, true));
        assertFalse(LegJudge.replanWhole(false, false));
    }

    /** A fake two-way map: the chooser takes the cheaper way with the penalties added. */
    static String choose(Map<String, Double> ways, Map<String, String> edgeOf, long now) {
        String best = null;
        double bestCost = Double.MAX_VALUE;
        for (var w : ways.entrySet()) {
            double c = w.getValue() + EdgePenalties.GLOBAL.penalty(edgeOf.get(w.getKey()), now);
            if (c < bestCost) {
                bestCost = c;
                best = w.getKey();
            }
        }
        return best;
    }

    @Test
    void aFailedEdgeSendsTheNextRouteRoundTheDetourAndDecays() {
        Map<String, Double> ways = new LinkedHashMap<>();
        ways.put("ravine", 100.0);
        ways.put("detour", 160.0);
        Map<String, String> edge = Map.of("ravine", EdgePenalties.key("box(0,1,4,0)", 16, 64, 8), "detour", EdgePenalties.key("box(0,1,4,1)", 16, 64, 24));
        long now = 1_000_000;
        assertEquals("ravine", choose(ways, edge, now));
        EdgePenalties.GLOBAL.add(edge.get("ravine"), EdgePenalties.FAIL, now);       // the leg across the ravine failed
        assertEquals("detour", choose(ways, edge, now + 1000));
        assertEquals(1, EdgePenalties.GLOBAL.count(now));
        // halves every 2 days; after ~14 days it is gone
        assertEquals(EdgePenalties.FAIL / 2, EdgePenalties.GLOBAL.penalty(edge.get("ravine"), now + EdgePenalties.HALF_LIFE_MS), 1e-6);
        assertEquals(0, EdgePenalties.GLOBAL.penalty(edge.get("ravine"), now + 10 * EdgePenalties.HALF_LIFE_MS));
        assertEquals("ravine", choose(ways, edge, now + 10 * EdgePenalties.HALF_LIFE_MS));
        // saved and loaded with the route map
        String json = EdgePenalties.GLOBAL.toJson(now);
        EdgePenalties.GLOBAL.clear();
        EdgePenalties.GLOBAL.loadJson(json);
        assertEquals(EdgePenalties.FAIL, EdgePenalties.GLOBAL.penalty(edge.get("ravine"), now), 0.1);
        // capped
        for (int i = 0; i < 20; i++) EdgePenalties.GLOBAL.add(edge.get("detour"), EdgePenalties.FAIL, now);
        assertEquals(EdgePenalties.MAX, EdgePenalties.GLOBAL.penalty(edge.get("detour"), now), 1e-6);
    }
}
