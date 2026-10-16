package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.31.0 (trial 11, item 3): the roof of a house dug into the ground ("nowhere to stand" at -69 61 -11). */
class Fix031ClearTest {
    /** Trial 11's house_main: box x -70..-64, y 57..61, z -16..-10 dug into ground that reaches y 63 all around. */
    static FakeWorld sunkenHouse() {
        FakeWorld w = new FakeWorld();
        w.fill(-80, -54, 50, 63, -26, 0, "stone");
        w.fill(-70, -64, 57, 57, -16, -10, "cobblestone");           // the floor
        w.fill(-69, -65, 58, 61, -15, -11, "air");                    // inside, and the roof layer's inner cells
        for (int x = -70; x <= -64; x++) for (int z = -16; z <= -10; z++) {
            if (x == -70 || x == -64 || z == -16 || z == -10) w.fill(x, x, 58, 61, z, z, "cobblestone");   // walls + roof rim
        }
        return w;
    }

    @Test void anInnerRoofCellUnderTheGroundIsPlacedFromTheFloor() {
        FakeWorld w = sunkenHouse();
        Pos c = new Pos(-69, 61, -11);
        assertFalse(RimPlan.anchors(w, c).isEmpty(), "the wall tops are its neighbours");
        RimPlan.Stance st = RimPlan.sideStand(w, c, -66.5, 58, -12.5);
        assertNotNull(st, "0.31.0: feet on the floor inside (before: nowhere to stand)");
        assertEquals(58, st.feet().y());
        assertTrue(RimPlan.clickableFrom(w, c, st.side(), st.feet().x() + 0.5, st.feet().y() + 1.62, st.feet().z() + 0.5));
    }

    @Test void theWholeRoofGetsStances() {
        FakeWorld w = sunkenHouse();
        List<Pos> miss = RimPlan.missing(w, RimPlan.shapeCells("roof", ClearBox.of(-70, 57, -16, -64, 61, -10)));
        assertEquals(25, miss.size());
        RimPlan.Pick pk = RimPlan.next(w, miss, new HashMap<>(), -66.5, 58, -12.5);
        assertNotNull(pk);
        assertNull(pk.why(), "a stance, not nowhere to stand");
    }

    @Test void aRoofCellIsNotPlacedFromRightBesideItAtBodyHeight() {
        FakeWorld w = sunkenHouse();
        w.fill(-65, -65, 58, 59, -13, -13, "cobblestone");           // live: a pillar Baritone placed inside
        Pos c = new Pos(-66, 61, -13);
        w.fill(-69, -65, 61, 61, -15, -11, "cobblestone");           // the roof placed but this cell
        w.set(c, "air");
        RimPlan.Stance st = RimPlan.sideStand(w, c, -64.6, 60, -12.5);
        assertNotNull(st);
        assertFalse(RimPlan.touches(st.feet().x(), st.feet().y(), st.feet().z(), c), "not on the pillar beside the cell: " + st.feet().key());
    }

    @Test void fromInsideTheFloorBeatsAWallTopOverTheRoof() {
        FakeWorld w = sunkenHouse();
        w.fill(-68, -66, 62, 63, -10, -10, "air");                    // live: a trench over the north wall
        w.set(-68, 61, -11, "cobblestone");                          // a placed roof neighbour
        Pos c = new Pos(-67, 61, -11);
        RimPlan.Stance st = RimPlan.sideStand(w, c, -66.5, 58, -12.5);
        assertNotNull(st);
        assertEquals(58, st.feet().y(), "inside on the floor, not on the wall top at y 62 (no way up there)");
        RimPlan.Stance top = RimPlan.sideStand(w, c, -66.5, 62, -9.5);
        assertNotNull(top);
        assertEquals(62, top.feet().y(), "from above the wall-top stance still comes first");
    }

    @Test void aFloorStanceStillWinsForAWall() {
        FakeWorld w = new FakeWorld();
        w.fill(-6, 12, 53, 53, -6, 12, "stone");
        w.fill(0, 0, 54, 54, 0, 6, "cobblestone");
        RimPlan.Stance st = RimPlan.sideStand(w, new Pos(0, 55, 3), 3.5, 54, 3.5);
        assertNotNull(st);
        assertTrue(st.feet().y() >= 54 && st.feet().y() <= 55, "the old stances come first");
    }
}
