package io.github.mojolowjo.entropybot.move;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mojolowjo.entropybot.route.Cell;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.27.3: long-walk legs clipped by a host's "inside" column function (AREAS: the mod's host answers null = no clipping). */
class LegPlanClipTest {
    record Area(String name, int x1, int z1, int x2, int z2) {}

    static final List<Area> AREAS = List.of(new Area("a", 0, 0, 100, 20), new Area("b", 80, 0, 100, 200));

    static boolean in(int x, int z) {
        for (Area a : AREAS) if (x >= a.x1() && x <= a.x2() && z >= a.z1() && z <= a.z2()) return true;
        return false;
    }

    /** The nearest column 2 in from an area's edge (what the old area.return did). */
    static int[] insideFor(int[] xz) {
        if (in(xz[0], xz[1])) return null;
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (Area a : AREAS) {
            int x = Math.max(a.x1() + 2, Math.min(a.x2() - 2, xz[0])), z = Math.max(a.z1() + 2, Math.min(a.z2() - 2, xz[1]));
            double d = Math.hypot(x - xz[0], z - xz[1]);
            if (d < bd) { bd = d; best = new int[]{x, z}; }
        }
        return best;
    }

    @Test void legEndsOutsideMoveInsideTheLastStays() {
        List<LegPlan.Leg> legs = LegPlan.legs(new Cell(5, 64, 5), List.of(), new Cell(90, 64, 190));
        assertTrue(legs.stream().anyMatch(l -> !in(l.end().x(), l.end().z())), "the straight line leaves the areas");
        List<LegPlan.Leg> c = LegPlan.clip(legs, LegPlanClipTest::insideFor);
        assertEquals(legs.size(), c.size());
        for (LegPlan.Leg l : c) assertTrue(in(l.end().x(), l.end().z()), "outside: " + l.end());
        assertEquals(legs.get(legs.size() - 1), c.get(c.size() - 1));
    }

    @Test void noClipperNoChange() {
        List<LegPlan.Leg> legs = LegPlan.legs(new Cell(5, 64, 5), List.of(), new Cell(90, 64, 190));
        assertSame(legs, LegPlan.clip(legs, null));
        List<LegPlan.Leg> c = LegPlan.clip(legs, xz -> null);
        for (int i = 0; i < legs.size(); i++) assertSame(legs.get(i), c.get(i));
    }
}
