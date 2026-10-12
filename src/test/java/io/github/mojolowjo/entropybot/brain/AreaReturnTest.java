package io.github.mojolowjo.entropybot.brain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.27.3 area.return: nearest area, nearest inside cell, the stand height, when it fires, the tree node. */
class AreaReturnTest {
    static final List<AreaReturn.Area> AREAS = List.of(
            new AreaReturn.Area("camp", -24, -24, 24, 24),
            new AreaReturn.Area("mine", 100, 0, 140, 40));

    @Test void nearestAreaAndCellTwoInside() {
        AreaReturn.Target t = AreaReturn.nearest(AREAS, 40, 5);
        assertEquals("camp", t.name());
        assertEquals(22, t.x());
        assertEquals(5, t.z());
        assertEquals(18, t.dist(), 1e-9);
        AreaReturn.Target m = AreaReturn.nearest(AREAS, 90, 50);
        assertEquals("mine", m.name());
        assertEquals(102, m.x());
        assertEquals(38, m.z());
    }

    @Test void noAreasNoTarget() {
        assertNull(AreaReturn.nearest(List.of(), 0, 0));
        assertNull(AreaReturn.nearest(null, 0, 0));
    }

    @Test void narrowAreaUsesItsMiddle() {
        AreaReturn.Target t = AreaReturn.nearest(List.of(new AreaReturn.Area("lane", 0, 0, 1, 50)), -10, 20);
        assertTrue(t.x() >= 0 && t.x() <= 1);
        assertEquals(20, t.z());
    }

    @Test void standHeightNearestTheBot() {
        assertEquals(67, AreaReturn.standY(0, 64, 0, f -> f[1] == 67 || f[1] == 60));
        assertEquals(62, AreaReturn.standY(0, 64, 0, f -> f[1] == 62 || f[1] == 67));
        assertNull(AreaReturn.standY(0, 64, 0, f -> false));
        AreaReturn.Target t = AreaReturn.withY(AreaReturn.nearest(AREAS, 40, 5), 70, f -> f[1] == 68);
        assertEquals("goto 22 68 5", t.chain());
        assertEquals("goto 22 5", AreaReturn.withY(AreaReturn.nearest(AREAS, 40, 5), 70, f -> false).chain());
    }

    @Test void firesOnlyWhenFree() {
        AreaReturn.Target t = AreaReturn.nearest(AREAS, 40, 5);
        assertTrue(AreaReturn.fires(true, t, false, null, null, false, null));
        assertFalse(AreaReturn.fires(false, t, false, null, null, false, null));
        assertFalse(AreaReturn.fires(true, t, true, null, null, false, null));
        assertFalse(AreaReturn.fires(true, null, false, null, null, false, null));
        assertFalse(AreaReturn.fires(true, t, false, "goto 1 2 3", null, false, null));
        assertFalse(AreaReturn.fires(true, t, false, null, "guarding", false, null));
        assertFalse(AreaReturn.fires(true, t, false, null, null, true, null));
        assertFalse(AreaReturn.fires(true, t, false, null, null, false, "until 12:00"));
        assertTrue(AreaReturn.why(true, t, false, null, null, false, "x").contains("parked"));
        assertNull(AreaReturn.why(true, t, true, null, null, false, null));
        assertEquals("I am outside my areas - walking back into camp", AreaReturn.whisper("camp"));
    }

    @Test void treeNodeRightAfterDanger() {
        BrainState s = new BrainState();
        s.areasHere = false;
        s.areaReturn = AreaReturn.withY(AreaReturn.nearest(AREAS, 40, 5), 64, f -> f[1] == 64);
        BrainTree.Decision d = BrainTest.decide(s, null);
        assertEquals("area.return", d.branch());
        assertEquals("goto 22 64 5", d.chain());
        assertEquals(BrainTree.AREA_NEED, d.need());
        s.danger = true;
        assertEquals("danger", BrainTest.decide(s, null).branch());
        s.danger = false;
        s.ownerJob = "goto 0 0 0";
        assertNotEquals("area.return", BrainTest.decide(s, null).branch());
        s.ownerJob = null;
        assertEquals(BrainTree.Kind.KEEP, BrainTest.decide(s, new BrainTree.Running(BrainTree.AREA_NEED, "goto 22 64 5", 90)).kind());
    }
}
