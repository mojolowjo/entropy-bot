package io.github.mojolowjo.entropybot.nav.drive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Nav step 8: the look's maths against the fork's AimProcessor and RotationUtils numbers. */
class LookMathTest {

    @Test
    void oneMouseCountAtDefaultSensitivityIsAFifteenthOfADegree() {
        // sensitivity 0.5: f = 0.5 * 0.6 + 0.2 = 0.5, f^3 * 8 = 1, * 0.15
        assertEquals(0.15f, LookMath.mouseToAngle(1, 0.5), 1e-6);
        assertEquals(1.5f, LookMath.mouseToAngle(10, 0.5), 1e-5);
    }

    @Test
    void aMouseMoveLandsOnWholeCounts() {
        float r = LookMath.mouseMove(0f, 1.0f, 0.5);         // 1.0 / 0.15 = 6.67 -> 7 counts -> 1.05
        assertEquals(1.05f, r, 1e-5);
        assertEquals(0f, LookMath.mouseMove(0f, 0.07f, 0.5), 1e-6, "less than half a count does not move");
    }

    @Test
    void towardUsesVanillasAngles() {
        LookMath.Turn south = LookMath.toward(0, 0, 0, 0, 0, 5);
        assertEquals(0f, south.yaw(), 1e-4);
        assertEquals(0f, south.pitch(), 1e-4);
        assertEquals(-90f, LookMath.toward(0, 0, 0, 5, 0, 0).yaw(), 1e-4, "east is -90");
        assertEquals(90f, LookMath.toward(0, 0, 0, -5, 0, 0).yaw(), 1e-4, "west is 90");
        assertEquals(-180f, LookMath.toward(0, 0, 0, 0, 0, -5).yaw(), 1e-4, "north wraps to -180");
        assertEquals(45f, LookMath.toward(0, 1, 0, 1, 0, 0).pitch(), 1e-4, "down is positive pitch");
    }

    @Test
    void aStepTurnsTheShortWayAndIsCapped() {
        LookMath.Turn t = LookMath.step(170f, 0f, -170f, 0f, 30f, 0.5);
        assertEquals(190f, t.yaw(), 0.1f, "170 to -170 is 20 degrees on (the yaw stays continuous), not 340 back");
        LookMath.Turn capped = LookMath.step(0f, 0f, 90f, 80f, 30f, 0.5);
        assertEquals(30f, capped.yaw(), 0.1f);
        assertEquals(30f, capped.pitch(), 0.1f);
        assertEquals(90f, LookMath.step(0f, 85f, 0f, 200f, 30f, 0.5).pitch(), 0.15f, "the pitch stops at 90");
    }

    @Test
    void stepsReachTheTargetWithinOneDegree() {
        float yaw = 12.3f, pitch = -40f;
        int n = 0;
        while (!LookMath.reached(yaw, pitch, -135f, 25f, 1f) && n < 40) {
            LookMath.Turn t = LookMath.step(yaw, pitch, -135f, 25f, 30f, 0.5);
            yaw = t.yaw();
            pitch = t.pitch();
            n++;
        }
        assertTrue(n <= 6, "147 degrees at 30 a tick: 5 or 6 ticks, took " + n);
        assertTrue(LookMath.reached(yaw + 720f, pitch, -135f, 25f, 1f), "reached ignores whole turns");
    }

    @Test
    void wrap() {
        assertEquals(-180f, LookMath.wrap(180f));
        assertEquals(-90f, LookMath.wrap(270f));
        assertEquals(10f, LookMath.wrap(-350f));
    }
}
