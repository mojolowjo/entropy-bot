package io.github.mojolowjo.entropybot.routewalk;

import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.CostToGo;
import io.github.mojolowjo.entropybot.route.Door;
import io.github.mojolowjo.entropybot.route.RouteGoalMath;
import io.github.mojolowjo.entropybot.route.SectionKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RouteGoal's logic is RouteGoalMath over the wrapped goal: same numbers, the wrapped goal's isInGoal, never infinity. */
class RouteGoalCoreTest {
    static final double CH = 3.563;

    /** A plain goal at (gx, gy, gz): Baritone-like straight-line heuristic (no BaritoneAPI needed). */
    static RouteGoalCore.Plain plainAt(int gx, int gy, int gz) {
        return new RouteGoalCore.Plain() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return x == gx && y == gy && z == gz;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                double dx = x - gx, dz = z - gz;
                return Math.sqrt(dx * dx + dz * dz) * CH + Math.abs(y - gy) * 2;
            }
        };
    }

    static Door door(int face, int x, int y, int z) {
        return new Door(face, new long[4], x, y, z, x, y, z, x, y + 1, z, true, true);
    }

    static CostToGo table(boolean staleSecond) {
        Map<SectionKey, CostToGo.BoxCosts> m = new HashMap<>();
        SectionKey a = SectionKey.of(0, 0, 64, 0), b = SectionKey.of(0, 16, 64, 0);
        // box a: east door cheap to the goal, north door expensive; box b: one door (stale or not)
        m.put(a, new CostToGo.BoxCosts(a, false, new Door[]{door(1, 15, 64, 8), door(4, 8, 64, 0)}, new double[]{2000, 9000}));
        m.put(b, new CostToGo.BoxCosts(b, staleSecond, new Door[]{door(1, 31, 64, 8)}, new double[]{1500}));
        return new CostToGo(0, SectionKey.of(0, 200, 64, 8), new Cell(200, 64, 8), m);
    }

    @Test
    void sameNumbersAsRouteGoalMath() {
        RouteGoalCore.Plain plain = plainAt(200, 64, 8);
        CostToGo t = table(false);
        RouteGoalCore g = new RouteGoalCore(plain, t, CH);
        for (int x = -4; x < 40; x += 3) {
            for (int z = -2; z < 18; z += 4) {
                double want = RouteGoalMath.heuristic(t, x, 64, z, plain.heuristic(x, 64, z), CH);
                assertEquals(want, g.heuristic(x, 64, z), 1e-9, x + " " + z);
                assertTrue(g.heuristic(x, 64, z) < Double.POSITIVE_INFINITY);
                assertTrue(g.heuristic(x, 64, z) >= plain.heuristic(x, 64, z) - 1e-9, "never below plain");
            }
        }
    }

    @Test
    void picksTheCheapestDoorOfTheBox() {
        RouteGoalCore g = new RouteGoalCore(plainAt(200, 64, 8), table(false), CH);
        // at the east door itself: its 2000 ticks, not the north door's 9000
        assertEquals(Math.max(2000, plainAt(200, 64, 8).heuristic(15, 64, 8)), g.heuristic(15, 64, 8), 1e-9);
    }

    @Test
    void staleOrUnknownBoxIsPlain() {
        RouteGoalCore.Plain plain = plainAt(200, 64, 8);
        RouteGoalCore g = new RouteGoalCore(plain, table(true), CH);
        assertEquals(plain.heuristic(20, 64, 8), g.heuristic(20, 64, 8), 1e-9);       // box b is stale
        assertEquals(plain.heuristic(100, 64, 100), g.heuristic(100, 64, 100), 1e-9); // no box
        RouteGoalCore empty = new RouteGoalCore(plain, null, CH);
        assertEquals(plain.heuristic(5, 64, 5), empty.heuristic(5, 64, 5), 1e-9);
    }

    @Test
    void isInGoalIsTheWrappedGoals() {
        RouteGoalCore g = new RouteGoalCore(plainAt(200, 64, 8), table(false), CH);
        assertTrue(g.isInGoal(200, 64, 8));
        assertFalse(g.isInGoal(15, 64, 8));
    }

    @Test
    void costHeuristicScalesTheWalkToTheDoor() {
        RouteGoalCore.Plain flat = new RouteGoalCore.Plain() {
            @Override
            public boolean isInGoal(int x, int y, int z) { return false; }

            @Override
            public double heuristic(int x, int y, int z) { return 0; }
        };
        CostToGo t = table(false);
        double a = new RouteGoalCore(flat, t, 3.563).heuristic(0, 64, 8);
        double b = new RouteGoalCore(flat, t, 7.0).heuristic(0, 64, 8);
        assertEquals(15 * 3.563 + 2000, a, 1e-9);
        assertEquals(15 * 7.0 + 2000, b, 1e-9);
    }
}

