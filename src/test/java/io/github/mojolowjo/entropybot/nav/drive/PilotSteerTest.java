package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.nav.steer.Choice;
import io.github.mojolowjo.entropybot.nav.steer.Sim;
import io.github.mojolowjo.entropybot.nav.steer.Steerer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Steering S2: the pilot hands water and off-route spots to the steerer, and takes them back. */
class PilotSteerTest {
    /** A flat body that moves a quarter block a tick the way it faces while forward is held (back: the other way). */
    static final class Flat implements Sim {
        double x, y = 64, z;
        boolean moves = true;
        Flat real;

        @Override public void reset() { x = real.x; y = real.y; z = real.z; }
        @Override public void step(Choice c, float yaw) {
            if (!moves) return;
            double r = Math.toRadians(yaw), s = 0.25 * c.forward();
            x += -Math.sin(r) * s;
            z += Math.cos(r) * s;
        }
        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public boolean hurt() { return false; }
        @Override public int air() { return 300; }
    }

    static final class StubGhost implements Pilot.Ghost {
        boolean water;
        final Flat sim = new Flat();
        @Override public boolean inWater() { return water; }
        @Override public Sim sim() { return sim; }
        @Override public Steerer.Budget budget() { return Steerer.Budget.NONE; }
    }

    final FakeMoveWorld w = new FakeMoveWorld();

    @AfterEach void reset() { Tuning.reset(); }

    /** Runs the pilot on the real flat body, the body following the act's keys and facing; returns the end line. */
    static String run(Pilot p, Flat real, FakeMoveWorld w, int max) {
        for (int i = 0; i < max; i++) {
            Pilot.Act a = p.tick(new MoveDriver.Body(real.x, real.y, real.z, 0, 0, 0, true, false, 0f, 0f), w, false, false, true, s -> null);
            if (a.ended() != null) return a.ended();
            int f = a.keys().contains(Keys.Key.FORWARD) ? 1 : a.keys().contains(Keys.Key.BACK) ? -1 : 0;
            if (a.yaw() != null && f != 0) {
                double r = Math.toRadians(a.yaw());
                real.x += -Math.sin(r) * 0.25 * f;
                real.z += Math.cos(r) * 0.25 * f;
            }
        }
        return null;
    }

    StubGhost ghost(Flat real) {
        StubGhost g = new StubGhost();
        g.sim.real = real;
        return g;
    }

    @Test void inWaterTheSteererDrivesEachMoveToTheGoal() {
        Flat real = new Flat();
        real.x = 0.5; real.z = 0.5;
        StubGhost g = ghost(real);
        g.water = true;
        Pilot p = new Pilot();
        p.ghost(g);
        p.start(PilotTest.south(3, 3), "swim");
        Pilot.Act first = p.tick(new MoveDriver.Body(real.x, real.y, real.z, 0, 0, 0, true, false, 0f, 0f), w, false, false, true, s -> null);
        assertEquals(Pilot.Steer.WATER, p.steering());
        assertTrue(first.keys().contains(Keys.Key.FORWARD), first.keys().toString());
        String end = run(p, real, w, 200);
        assertNotNull(end, p.steerStatus());
        assertTrue(end.startsWith("swim: arrived in "), end);
    }

    @Test void threeBlocksOffTheRouteItSteersBackInsteadOfGivingUp() {
        Flat real = new Flat();
        real.x = 3.5; real.z = 1.5;                                          // 3 blocks east of a route along x 0
        Pilot p = new Pilot();
        p.ghost(ghost(real));
        p.start(PilotTest.slowSouth(4), "back");
        Pilot.Act a = p.tick(new MoveDriver.Body(real.x, real.y, real.z, 0, 0, 0, true, false, 0f, 0f), w, false, false, true, s -> null);
        assertNull(a.ended(), a.ended());
        assertEquals(Pilot.Steer.BACK_TO_ROUTE, p.steering());
        for (int i = 0; i < 60 && p.steering() == Pilot.Steer.BACK_TO_ROUTE; i++) {
            run(p, real, w, 1);
        }
        assertEquals(Pilot.Steer.OFF, p.steering(), p.steerStatus());
        assertTrue(Math.abs(real.x - 0.5) < 0.8, "back on the route: x " + real.x);
    }

    @Test void stuckInWaterTheTripEndsWithTheSteerersReason() {
        Flat real = new Flat();
        real.x = 0.5; real.z = 0.5;
        StubGhost g = ghost(real);
        g.water = true;
        g.sim.moves = false;
        Pilot p = new Pilot();
        p.ghost(g);
        p.start(PilotTest.south(3, 3), "stuck");
        String end = null;
        for (int i = 0; i < 200 && end == null; i++) {
            end = p.tick(new MoveDriver.Body(real.x, real.y, real.z, 0, 0, 0, true, false, 0f, 0f), w, false, false, true, s -> null).ended();
        }
        assertNotNull(end);
        assertTrue(end.startsWith("stuck: steer (water) at move 1: no choice got closer than"), end);
    }

    @Test void steerOffLeavesTheOldRules() {
        Tuning.set("steer.on", 0);
        Flat real = new Flat();
        real.x = 3.5; real.z = 1.5;
        Pilot p = new Pilot();
        p.ghost(ghost(real));
        p.start(PilotTest.slowSouth(4), "old");
        Pilot.Act a = p.tick(new MoveDriver.Body(real.x, real.y, real.z, 0, 0, 0, true, false, 0f, 0f), w, false, false, true, s -> null);
        assertNotNull(a.ended());
        assertTrue(a.ended().startsWith("old: off the route: 3.0 blocks"), a.ended());
    }
}
