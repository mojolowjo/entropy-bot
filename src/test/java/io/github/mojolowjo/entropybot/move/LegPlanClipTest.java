package io.github.mojolowjo.entropybot.move;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mojolowjo.entropybot.sense.AreaReturn;
import io.github.mojolowjo.entropybot.route.Cell;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.27.3: long-walk legs clipped to the union of the areas (two areas joined at a corner: the straight line cuts outside). */
class LegPlanClipTest {
    static final List<AreaReturn.Area> AREAS = List.of(new AreaReturn.Area("a", 0, 0, 100, 20), new AreaReturn.Area("b", 80, 0, 100, 200));

    static boolean in(int x, int z) {
        for (AreaReturn.Area a : AREAS) if (x >= a.x1() && x <= a.x2() && z >= a.z1() && z <= a.z2()) return true;
        return false;
    }

    static int[] insideFor(int[] xz) {
        if (in(xz[0], xz[1])) return null;
        AreaReturn.Target t = AreaReturn.nearest(AREAS, xz[0], xz[1]);
        return new int[]{t.x(), t.z()};
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
