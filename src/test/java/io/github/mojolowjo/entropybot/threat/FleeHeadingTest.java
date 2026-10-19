package io.github.mojolowjo.entropybot.threat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FleeHeadingTest {
    static FleeHeading.Mob m(double x, double z) { return new FleeHeading.Mob(x, z, 1); }

    static double dist(int[] a, double x, double z) { return Math.hypot(a[0] + 0.5 - x, a[1] + 0.5 - z); }

    @Test
    void oneMobStraightAway() {
        int[] a = FleeHeading.choose(0, 0, List.of(m(3, 0)), 16);
        assertNotNull(a);
        assertTrue(a[0] < -14, a[0] + " " + a[1]);
    }

    @Test
    void neverRunsIntoTheSecondMob() {
        // nearest at +x; another behind the bot at -x 6: the old rule ran straight into it
        int[] a = FleeHeading.choose(0, 0, List.of(m(3, 0), m(-6, 0)), 16);
        assertNotNull(a);
        assertTrue(Math.abs(a[1]) > 10, "sideways: " + a[0] + " " + a[1]);
        assertTrue(dist(a, -6, 0) > 8 && dist(a, 3, 0) > 8);
    }

    @Test
    void picksTheGapInARing() {
        // mobs all round but the +z side
        List<FleeHeading.Mob> ring = List.of(m(4, 0), m(-4, 0), m(0, -4), m(3, -3), m(-3, -3));
        int[] a = FleeHeading.choose(0, 0, ring, 16);
        assertNotNull(a);
        assertTrue(a[1] > 10, a[0] + " " + a[1]);
    }

    @Test
    void corneredReturnsNull() {
        List<FleeHeading.Mob> ring = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) ring.add(m(3 * Math.cos(i * Math.PI / 4), 3 * Math.sin(i * Math.PI / 4)));
        assertNull(FleeHeading.choose(0, 0, ring, 16));
        assertNull(FleeHeading.choose(0, 0, List.of(), 16));
    }
}
