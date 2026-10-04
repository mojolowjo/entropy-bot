package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Water plan: telling sources from flowing water, the upstream search, the large-body limit. */
class WaterScanTest {
    /** The tunnel: x 0..20, y 40..42, z -1..1 (3x3), in stone. */
    static final ClearBox TUNNEL = ClearBox.of(0, 40, -1, 20, 42, 1);

    @Test
    void faceShellIsOneBlockOutAcrossAFace() {
        assertTrue(WaterScan.faceShell(TUNNEL, 5, 40, 2));
        assertTrue(WaterScan.faceShell(TUNNEL, 5, 43, 0));
        assertTrue(WaterScan.faceShell(TUNNEL, 21, 41, 0));
        assertFalse(WaterScan.faceShell(TUNNEL, 5, 43, 2), "an edge: no face shared");
        assertFalse(WaterScan.faceShell(TUNNEL, 5, 41, 0), "inside");
        assertFalse(WaterScan.faceShell(TUNNEL, 5, 41, 3), "two out");
    }

    @Test
    void aSourceInTheRingIsInside() {
        FakeWorld w = new FakeWorld();
        w.set(10, 41, 2, "water");                  // a source in the ring, beside the tunnel
        w.flow(10, 41, 1, 7, false);                // flowing into the box
        WaterScan.Scan s = WaterScan.scan(w, TUNNEL, new Pos(10, 41, 1));
        assertEquals(WaterScan.Kind.SOURCES_INSIDE, s.kind());
        assertEquals(1, s.inside());
        assertEquals("1 source block inside the dig", s.describe());
    }

    @Test
    void waterFlowingInFromASourceOutsideIsPluggedWhereItEnters() {
        FakeWorld w = new FakeWorld();
        // a source 3 blocks beside the tunnel, a stream of flowing water toward it: 6 (z 4), 5 (z 3), 4 (z 2), 3 in the box (z 1)
        w.set(10, 41, 5, "water");
        w.flow(10, 41, 4, 7, false);
        w.flow(10, 41, 3, 6, false);
        w.flow(10, 41, 2, 5, false);
        w.flow(10, 41, 1, 4, false);
        w.flow(10, 41, 0, 3, false);
        // and water downstream of the box that doesn't feed it (weaker): not part of the search
        w.flow(10, 41, -2, 1, false);
        WaterScan.Scan s = WaterScan.scan(w, TUNNEL, new Pos(10, 41, 0));
        assertEquals(WaterScan.Kind.FLOWING_IN, s.kind());
        assertEquals(Set.of(new Pos(10, 41, 5)), s.sources());
        assertEquals(java.util.List.of(new Pos(10, 41, 2)), s.plug(), "the one cell where it enters the box");
        assertFalse(s.visited().contains(new Pos(10, 41, -2)), "downstream water isn't upstream");
    }

    @Test
    void fallingWaterIsFollowedUp() {
        FakeWorld w = new FakeWorld();
        // a source 4 above the tunnel's roof, falling down to it through a shaft, then flowing in over the roof's edge
        w.set(10, 47, 0, "water");
        for (int y = 43; y <= 46; y++) w.flow(10, y, 0, 8, true);
        w.flow(10, 42, 0, 8, true);
        WaterScan.Scan s = WaterScan.scan(w, TUNNEL, new Pos(10, 42, 0));
        assertEquals(WaterScan.Kind.FLOWING_IN, s.kind());
        assertTrue(s.sources().contains(new Pos(10, 47, 0)));
        assertEquals(java.util.List.of(new Pos(10, 43, 0)), s.plug(), "the falling cell just above the roof");
    }

    @Test
    void waterWithNoSourceLeftIsFlowingIn() {
        FakeWorld w = new FakeWorld();
        w.flow(10, 41, 0, 3, false);
        WaterScan.Scan s = WaterScan.scan(w, TUNNEL, new Pos(10, 41, 0));
        assertEquals(WaterScan.Kind.FLOWING_IN, s.kind());
        assertTrue(s.plug().isEmpty(), "nothing enters: it drains by itself");
    }

    @Test
    void aLargeBodyIsCountedUpToTheLimit() {
        FakeWorld w = new FakeWorld();
        // a lake beside the tunnel: 9 x 3 x 9 = 243 sources, touching the ring at z 2
        w.fill(6, 14, 38, 40, 2, 10, "water");
        WaterScan.Scan s = WaterScan.scan(w, TUNNEL, new Pos(10, 40, 2));
        assertEquals(WaterScan.Kind.LARGE, s.kind());
        assertEquals(WaterScan.LARGE_LIMIT + 1, s.sources().size(), "it stops counting past the limit");
        assertTrue(s.describe().startsWith("a large body of water (65+ source blocks)"), s.describe());
        // a pool of 64 is not large
        FakeWorld p = new FakeWorld();
        p.fill(8, 11, 40, 41, 2, 9, "water");       // 4 x 2 x 8 = 64
        assertEquals(WaterScan.Kind.SOURCES_INSIDE, WaterScan.scan(p, TUNNEL, new Pos(8, 40, 2)).kind());
    }

    @Test
    void lavaIsLava() {
        FakeWorld w = new FakeWorld();
        w.set(10, 41, 2, "lava");
        assertEquals(WaterScan.Kind.LAVA, WaterScan.scan(w, TUNNEL, new Pos(10, 41, 2)).kind());
        assertEquals(WaterScan.Kind.NONE, WaterScan.scan(w, TUNNEL, new Pos(10, 41, 3)).kind(), "no fluid at the spot");
    }
}
