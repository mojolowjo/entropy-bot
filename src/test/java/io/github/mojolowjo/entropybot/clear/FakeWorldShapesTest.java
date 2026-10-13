package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FakeWorldShapesTest {

    @Test
    void testSnowLayerRayPassesOver() {
        FakeWorld world = new FakeWorld();
        world.set(1, 60, 0, "snow");
        ClearWorld.Hit hit = world.clip(0.5, 61.62, 0.5, 1.5, 60.5, 0.5);
        // the ray ends at the cell's centre, above the layer: nothing hit, or at least not the snow cell
        assertTrue(hit == null || !(hit.x() == 1 && hit.y() == 60 && hit.z() == 0), String.valueOf(hit));
    }

    @Test
    void testSnowLayerRayHitsFromAbove() {
        FakeWorld world = new FakeWorld();
        world.set(1, 60, 0, "snow");
        ClearWorld.Hit hit = world.clip(0.5, 61.62, 0.5, 1.5, 60.06, 0.5);
        assertNotNull(hit);
        assertEquals(1, hit.x());
        assertEquals(60, hit.y());
        assertEquals(0, hit.z());
        assertEquals(ClearWorld.Face.DOWN, hit.face());
    }

    @Test
    void testStoneRayHitsCentre() {
        FakeWorld world = new FakeWorld();
        // Set stone at (1,60,0) because above y52 is air by default
        world.set(1, 60, 0, "stone");
        ClearWorld.Hit hit = world.clip(0.5, 61.62, 0.5, 1.5, 60.5, 0.5);
        assertNotNull(hit);
        assertEquals(1, hit.x());
        assertEquals(60, hit.y());
        assertEquals(0, hit.z());
    }
}