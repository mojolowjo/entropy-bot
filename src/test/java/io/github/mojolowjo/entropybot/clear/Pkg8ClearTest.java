package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.30.7 (package 8): the roof stance on open ground (item 2) and the entity-in-the-way texts (item 1). */
class Pkg8ClearTest {
    /** shed3 shifted: ground y 53, walls 54..56 on the ring x 0..6 z 0..6, the roof at y 57. */
    static FakeWorld shed() {
        FakeWorld w = new FakeWorld();
        w.fill(-6, 12, 53, 53, -6, 12, "stone");
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) {
            if (x == 0 || x == 6 || z == 0 || z == 6) w.fill(x, x, 54, 56, z, z, "cobblestone");
        }
        return w;
    }

    static void roofRing(FakeWorld w, int ring) {
        for (int x = ring; x <= 6 - ring; x++) for (int z = ring; z <= 6 - ring; z++) {
            if (x == ring || x == 6 - ring || z == ring || z == 6 - ring) w.fill(x, x, 57, 57, z, z, "cobblestone");
        }
    }

    @Test void anInnerRoofCellIsPlacedFromThePlacedRoof() {
        FakeWorld w = shed();
        roofRing(w, 0);                                          // the rim over the walls
        Pos c = new Pos(1, 57, 1);
        assertFalse(RimPlan.anchors(w, c).isEmpty(), "the rim is its neighbour");
        RimPlan.Stance st = RimPlan.sideStand(w, c, 3.5, 58, 0.5);
        assertNotNull(st, "0.30.7: feet on the placed roof (before: nowhere to stand)");
        assertEquals(58, st.feet().y());
        assertTrue(RimPlan.clickableFrom(w, c, st.side(), st.feet().x() + 0.5, st.feet().y() + 1.62, st.feet().z() + 0.5));
    }

    @Test void theCentreCellToo() {
        FakeWorld w = shed();
        roofRing(w, 0);
        roofRing(w, 1);
        roofRing(w, 2);
        Pos c = new Pos(3, 57, 3);
        RimPlan.Stance st = RimPlan.sideStand(w, c, 3.5, 58, 1.5);
        assertNotNull(st);
        assertEquals(58, st.feet().y());
        RimPlan.Pick pk = RimPlan.next(w, RimPlan.missing(w, List.of(c)), new HashMap<>(), 0.5, 58, 0.5);
        assertNotNull(pk);
        assertNull(pk.why(), "a stance, not nowhere to stand");
    }

    @Test void theRimInwardOrderComesFromTheAnchors() {
        FakeWorld w = shed();
        roofRing(w, 0);
        List<Pos> miss = RimPlan.missing(w, RimPlan.shapeCells("roof", ClearBox.of(0, 53, 0, 6, 57, 6)));
        assertEquals(25, miss.size());
        RimPlan.Pick pk = RimPlan.next(w, miss, new HashMap<>(), 3.5, 58, 0.5);
        assertNotNull(pk);
        int ring = Math.min(Math.min(pk.cell().x(), 6 - pk.cell().x()), Math.min(pk.cell().z(), 6 - pk.cell().z()));
        assertEquals(1, ring, "the first inner ring first (the centre has no neighbour yet)");
    }

    @Test void aWallCellStillUsesTheFloor() {
        FakeWorld w = new FakeWorld();
        w.fill(-6, 12, 53, 53, -6, 12, "stone");
        w.fill(0, 0, 54, 54, 0, 6, "cobblestone");                 // the lowest wall row west
        Pos c = new Pos(0, 55, 3);
        RimPlan.Stance st = RimPlan.sideStand(w, c, 3.5, 54, 3.5);
        assertNotNull(st);
        assertTrue(st.feet().y() <= 55, "the floor stance wins over standing on the wall");
    }

    @Test void entityTexts() {
        assertEquals(PlaceRules.InWay.MY_CORPSE, PlaceRules.inWayKind("corpse:corpse", true, false));
        assertEquals(PlaceRules.InWay.OTHER, PlaceRules.inWayKind("corpse:corpse", false, false), "someone else's corpse");
        assertEquals(PlaceRules.InWay.MOB, PlaceRules.inWayKind("minecraft:cow", false, true));
        assertEquals(PlaceRules.InWay.OTHER, PlaceRules.inWayKind("minecraft:boat", false, false));
        assertEquals("error: my corpse is in the way at 35 131 23 - next: death",
                PlaceRules.entityText(PlaceRules.InWay.MY_CORPSE, "corpse", 35, 131, 23));
        assertEquals("error: a cow is in the way at 1 2 3 (waiting)", PlaceRules.entityText(PlaceRules.InWay.MOB, "cow", 1, 2, 3));
        assertEquals("error: a boat is in the way at 1 2 3 - next: move it, or place somewhere else",
                PlaceRules.entityText(PlaceRules.InWay.OTHER, "boat", 1, 2, 3));
        assertEquals("a cow stayed in the way at 1 2 3 for 10 s - next: place again when it has moved",
                PlaceRules.mobStayedText(PlaceRules.waitedFor("error: a cow is in the way at 1 2 3 (waiting)"), 1, 2, 3));
        String empty = PlaceRules.entityText(PlaceRules.InWay.MY_EMPTY_CORPSE, "corpse", 1, 2, 3);
        assertEquals("error: my emptied corpse is in the way at 1 2 3 (waiting)", empty);
        assertEquals("my emptied corpse", PlaceRules.waitedFor(empty));
        assertEquals(200, PlaceRules.MOB_WAIT_TICKS);
    }
}
