package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Water plan: the shell geometry (the 5x5 ring and the front cap of a 3x3 tunnel), the slice order, the block count. */
class WaterShellTest {
    static final ClearBox TUNNEL = WaterScanTest.TUNNEL;      // x 0..20, y 40..42, z -1..1
    static final WaterShell.Axis EAST = new WaterShell.Axis(true, 1);

    @Test
    void theRingIsTheOneBlockFrameOfA5x5() {
        List<Pos> ring = WaterShell.ring(TUNNEL, EAST, 7);
        assertEquals(16, ring.size(), "5x5 minus the 3x3 inside");
        Set<Pos> set = new HashSet<>(ring);
        assertEquals(16, set.size());
        for (Pos p : ring) {
            assertEquals(7, p.x());
            assertTrue(p.y() >= 39 && p.y() <= 43 && p.z() >= -2 && p.z() <= 2);
            assertFalse(TUNNEL.contains(p.x(), p.y(), p.z()), "never inside the tunnel");
            assertTrue(TUNNEL.grow(1).contains(p.x(), p.y(), p.z()), "within one block of it");
        }
        assertTrue(set.contains(new Pos(7, 39, -2)) && set.contains(new Pos(7, 43, 2)), "the corners too");
        assertEquals(9, WaterShell.cross(TUNNEL, EAST, 7).size());
        assertEquals(List.of(8), WaterShell.cap(TUNNEL, EAST, 7).stream().map(Pos::x).distinct().toList(), "the cap is the plane ahead");
        assertEquals(List.of(6), WaterShell.cap(TUNNEL, new WaterShell.Axis(true, -1), 7).stream().map(Pos::x).distinct().toList());
    }

    @Test
    void everyInsideCellIsWalledBySolidOnceTheSliceIsSealed() {
        // a slice full of water all round: after the ring, the cap and the fill, each of the 9 inside cells has only
        // solid blocks or the dug tunnel behind it as neighbours: no water can reach it
        FakeWorld w = new FakeWorld();
        w.fill(5, 10, 38, 44, -3, 3, "water");
        w.fill(0, 4, 40, 42, -1, 1, "air");           // dug up to x 4
        List<WaterShell.Cell> cells = WaterShell.sliceNeeds(w, TUNNEL, EAST, 5).cells();
        assertEquals(16 + 9 + 9, cells.size(), "ring 16, cap 9, fill 9");
        assertEquals(34, WaterShell.count(w, TUNNEL, EAST, 5), "the block count");
        for (WaterShell.Cell c : cells) w.set(c.pos(), "cobbled_deepslate");
        for (Pos p : WaterShell.cross(TUNNEL, EAST, 5)) {
            for (int[] s : ClearEngine.SIDES6) {
                int x = p.x() + s[0], y = p.y() + s[1], z = p.z() + s[2];
                assertFalse(w.fluid(x, y, z), "water next to " + p);
            }
            assertTrue(ClearEngine.clearableState(w, p.x(), p.y(), p.z(), false));
            assertFalse(ClearEngine.nextToLiquid(w, p.x(), p.y(), p.z()), "the dig may take it");
        }
    }

    @Test
    void theCapPastTheBoxEndTakesAirToo() {
        FakeWorld w = new FakeWorld();
        w.fill(20, 21, 40, 42, -1, 1, "air");
        w.set(21, 41, 0, "water");
        List<WaterShell.Cell> cells = WaterShell.sliceNeeds(w, TUNNEL, EAST, 20).cells();
        long caps = cells.stream().filter(c -> c.role() == WaterShell.Role.CAP).count();
        assertEquals(9, caps, "the end face: water and air");
        for (WaterShell.Cell c : cells) assertTrue(TUNNEL.grow(1).contains(c.pos().x(), c.pos().y(), c.pos().z()));
    }

    @Test
    void lavaOrAWaterloggedBlockStopsTheSlice() {
        FakeWorld w = new FakeWorld();
        w.set(5, 43, 0, "lava");
        WaterShell.SliceNeeds n = WaterShell.sliceNeeds(w, TUNNEL, EAST, 5);
        assertEquals(List.of("5 43 0 (lava)"), n.problems());
    }

    @Test
    void theAxisFollowsTheLongSideTowardTheWater() {
        assertEquals(new WaterShell.Axis(true, 1), WaterShell.axisFor(TUNNEL, -1.5, 0.5, new Pos(9, 41, 0)));
        assertEquals(new WaterShell.Axis(true, -1), WaterShell.axisFor(TUNNEL, 18.5, 0.5, new Pos(9, 41, 0)));
        ClearBox ns = ClearBox.of(0, 40, 0, 2, 42, 30);
        assertEquals(new WaterShell.Axis(false, 1), WaterShell.axisFor(ns, 1.5, -0.5, new Pos(1, 41, 12)));
    }

    @Test
    void theStartSliceIsTheFirstUndugOneTowardTheWater() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 6, 40, 42, -1, 1, "air");
        assertEquals(7, WaterShell.startSlice(w, TUNNEL, EAST, 3.5, 0.5));
        // water in the ring of a dug slice counts too
        w.set(5, 41, 2, "water");
        assertEquals(5, WaterShell.startSlice(w, TUNNEL, EAST, 3.5, 0.5));
    }

    @Test
    void shellBlocksAreNeverBuiltBlocks() {
        // the shell stays as the tunnel's wall: a later dig must still be free to take it, so its blocks are never on the
        // protected (built) list
        for (String id : FloorFill.FALLBACK) assertFalse(ClearRules.builtId(id), id);
    }
}
