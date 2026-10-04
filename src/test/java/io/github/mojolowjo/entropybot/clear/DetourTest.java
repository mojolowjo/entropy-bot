package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Water plan, going around: a bypass within the detour, only natural blocks broken, or a put-back list kept. */
class DetourTest {
    static final ClearBox TUNNEL = WaterScanTest.TUNNEL;      // x 0..20, y 40..42, z -1..1
    static final WaterShell.Axis EAST = new WaterShell.Axis(true, 1);

    /** Dug to x 6; a pool of water x 8..10, y 39..43, z -2..2 fills the tunnel's path and its ring. */
    static FakeWorld pool() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 6, 40, 42, -1, 1, "air");
        w.fill(8, 10, 39, 43, -2, 2, "water");
        return w;
    }

    static void checkSafe(FakeWorld w, Detour.Bypass b, int detour) {
        for (Pos p : b.cells()) {
            assertFalse(w.fluid(p.x(), p.y(), p.z()), "never into liquid: " + p);
            assertTrue(w.air(p.x(), p.y(), p.z()) || !ClearEngine.nextToLiquid(w, p.x(), p.y(), p.z()), "never breaks next to water: " + p);
            assertTrue(p.z() >= TUNNEL.z1() - detour && p.z() <= TUNNEL.z2() + detour, "within the detour: " + p);
        }
    }

    @Test
    void goesAroundThePoolWithinThreeBlocks() {
        FakeWorld w = pool();
        Detour.Bypass b = Detour.plan(w, TUNNEL, EAST, 6, 12, Detour.DEFAULT, false, null);
        assertTrue(b.found(), b.why());
        checkSafe(w, b, 3);
        assertTrue(b.cells().stream().anyMatch(p -> Math.abs(p.z()) == 4), "out to the side, past the water's ring");
        assertEquals(new Pos(6, 40, 0).x(), b.cells().get(0).x(), "starts in the last dug slice");
        Pos last = b.cells().get(b.cells().size() - 1);
        assertEquals(12, last.x(), "ends in the first slice past it");
        assertTrue(TUNNEL.contains(last.x(), last.y(), last.z()));
        assertTrue(b.putBack().isEmpty(), "all natural");
    }

    @Test
    void tooSmallADetourFindsNone() {
        Detour.Bypass b = Detour.plan(pool(), TUNNEL, EAST, 6, 12, 1, false, null);
        assertFalse(b.found());
        assertEquals("no way around within 1 block of the dig", b.why());
    }

    @Test
    void builtBlocksOnlyWithAPutBackList() {
        FakeWorld w = pool();
        w.set(9, 40, 4, "oak_planks");
        w.set(9, 41, -4, "oak_planks");
        assertFalse(Detour.plan(w, TUNNEL, EAST, 6, 12, 3, false, null).found(), "natural only: the planks block both sides");
        Detour.Bypass b = Detour.plan(w, TUNNEL, EAST, 6, 12, 3, true, null);
        assertTrue(b.found(), b.why());
        assertEquals(1, b.putBack().size(), "the one plank it breaks is listed");
        Map.Entry<Pos, String> e = b.putBack().entrySet().iterator().next();
        assertEquals("minecraft:oak_planks", e.getValue());
        assertTrue(b.cells().contains(e.getKey()));
    }

    @Test
    void neverThroughAProtectBoxOrAChest() {
        FakeWorld w = pool();
        Detour.Bypass b = Detour.plan(w, TUNNEL, EAST, 6, 12, 3, true, p -> p.z() >= 3);
        assertTrue(b.found());
        assertTrue(b.cells().stream().allMatch(p -> p.z() < 3), "only the other side");
        w.set(9, 40, -4, "chest");
        assertFalse(Detour.plan(w, TUNNEL, EAST, 6, 12, 3, true, p -> p.z() >= 3).found(), "a chest is never broken, put back or not");
    }
}
