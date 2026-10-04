package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WatchCameraTest {
    @Test
    void walkYawMatchesMinecraft() {
        assertEquals(0f, WatchCamera.walkYaw(0, 1), 0.001f, "south = 0");
        assertEquals(90f, WatchCamera.walkYaw(-1, 0), 0.001f, "west = 90");
        assertEquals(-90f, WatchCamera.walkYaw(1, 0), 0.001f, "east = -90");
        assertEquals(180f, Math.abs(WatchCamera.walkYaw(0, -1)), 0.001f, "north = +-180");
    }

    @Test
    void turnTakesTheShortWay() {
        assertEquals(20f, WatchCamera.turn(170f, -170f), 0.001f);
        assertEquals(-20f, WatchCamera.turn(-170f, 170f), 0.001f);
        assertEquals(45f, WatchCamera.turn(0f, 45f), 0.001f);
    }

    @Test
    void standingKeepsTheYaw() {
        assertEquals(33f, WatchCamera.follow(33f, 0.0, 0.0));
        assertEquals(33f, WatchCamera.follow(33f, 0.01, 0.01), "a drift below the moving speed");
    }

    @Test
    void walkingEasesTowardsTheDirectionWithoutOvershoot() {
        float yaw = 0f;
        for (int i = 0; i < 100; i++) {
            float next = WatchCamera.follow(yaw, -0.2, 0);           // walking west: 90
            assertEquals(true, next >= yaw && next <= 90.001f);
            yaw = next;
        }
        assertEquals(90f, yaw, 0.5f);
    }

    @Test
    void turningAroundTheBackOfTheCircleDoesNotSpinTheLongWay() {
        float yaw = 170f;
        float next = WatchCamera.follow(yaw, 0, -0.2);               // north = +-180
        assertEquals(true, next > 170f && next < 190f);
    }

    @Test
    void hookReportTellsAttachedFromNot() {
        assertEquals(true, WatchCamera.hookReport(false, true, 0, -1, 5000).contains("NOT in"));
        assertEquals(true, WatchCamera.hookReport(true, true, 0, 4000, 5000).contains("NOT running"));
        assertEquals(true, WatchCamera.hookReport(true, true, 59, 10, 5000).contains("active (59 calls/s)"));
        assertEquals(true, WatchCamera.hookReport(true, false, -1, 99999, 0).startsWith("hook: active"), "off: a quiet hook is not an error");
    }

    @Test
    void distanceIsFourToEight() {
        assertEquals(6f, WatchCamera.parseDistance(" 6 "));
        assertEquals(-1f, WatchCamera.parseDistance("3"));
        assertEquals(-1f, WatchCamera.parseDistance("9"));
        assertEquals(-1f, WatchCamera.parseDistance("x"));
    }
}