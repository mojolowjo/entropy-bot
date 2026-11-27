package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Nav step 8: every task ends on an observed state or a named failure, and the driver lets go. */
class DriverTest {
    final DriveConfig cfg = DriveConfig.defaults();

    static Driver.See see(float yaw, float pitch, double x, double z, Driver.Target t) {
        return new Driver.See(yaw, pitch, x, z, false, false, true, t);
    }

    /** Runs a look the way DriveLive does (the step maths applied to the next tick's head). */
    @Test
    void aLookArrivesAndSaysSo() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Look(90f, 20f));
        float yaw = 0f, pitch = 0f;
        Driver.Act a;
        int n = 0;
        do {
            a = d.tick(see(yaw, pitch, 0, 0, null));
            if (a.look() != null) {
                LookMath.Turn t = LookMath.step(yaw, pitch, a.look().yaw(), a.look().pitch(), cfg.maxTurn(), 0.5);
                yaw = t.yaw();
                pitch = t.pitch();
            }
        } while (a.ended() == null && ++n < 100);
        assertTrue(a.ok(), a.ended());
        assertTrue(a.ended().startsWith("look 90 20: reached yaw 90"), a.ended());
        assertNull(d.task());
        assertTrue(a.keys().isEmpty());
    }

    @Test
    void aLookThatCannotTurnFailsNamed() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Look(90f, 0f));
        Driver.Act a = null;
        for (int i = 0; i <= cfg.lookTimeout() + 1 && (a == null || a.ended() == null); i++) a = d.tick(see(0f, 0f, 0, 0, null));
        assertFalse(a.ok());
        assertEquals("look 90 0: not reached in 40 ticks: at yaw 0.0 pitch 0.0", a.ended());
    }

    @Test
    void aWalkPressesForwardKeepsItsYawAndCountsBlocks() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Walk(3));
        Driver.Act a = d.tick(see(-90f, 5f, 0.5, 0.5, null));
        assertTrue(a.keys().contains(Keys.Key.FORWARD));
        assertEquals(-90f, a.look().yaw());
        double x = 0.5;
        int n = 0;
        while (a.ended() == null && n++ < 50) {
            x += 0.2;
            a = d.tick(see(-80f, 5f, x, 0.5, null));
            if (a.ended() == null) assertEquals(-90f, a.look().yaw(), "the walk's heading is the one it started with");
        }
        assertTrue(a.ok(), a.ended());
        assertTrue(a.ended().startsWith("walk 3: moved 3.0"), a.ended());
        assertTrue(a.keys().isEmpty(), "forward let go");
    }

    @Test
    void aBlockedWalkIsStuck() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Walk(5));
        Driver.Act a = null;
        for (int i = 0; i < 100 && (a == null || a.ended() == null); i++) a = d.tick(see(0f, 0f, 0.5, 0.5 + Math.min(i, 4) * 0.2, null));
        assertFalse(a.ok());
        assertTrue(a.ended().startsWith("walk 5: stuck: moved 0.80 of 5 blocks"), a.ended());
    }

    static Driver.Target target(boolean empty, boolean on, float progress, String refusal) {
        return new Driver.Target(empty, new LookMath.Turn(10f, 30f), on, progress, refusal, "stone at 1, 2, 3 (up face)");
    }

    @Test
    void aBreakLooksThenHoldsUntilTheBlockIsGone() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Break(new BetterBlockPos(1, 64, 2)));
        Driver.Act a = d.tick(see(0f, 0f, 0, 0, target(false, false, 0.1f, null)));
        assertFalse(a.hit(), "no hit before the look arrives");
        assertEquals(10f, a.look().yaw());
        a = d.tick(see(10f, 30f, 0, 0, target(false, true, 0.1f, null)));
        assertTrue(a.hit());
        for (int i = 0; i < 9; i++) assertTrue(d.tick(see(10f, 30f, 0, 0, target(false, true, 0.1f, null))).hit());
        a = d.tick(see(10f, 30f, 0, 0, target(true, false, 0f, null)));
        assertTrue(a.ok());
        assertEquals("break 1 64 2: block gone in 11 ticks (10 holding)", a.ended());
        assertFalse(a.hit());
    }

    @Test
    void breakFailuresSayWhy() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Break(new BetterBlockPos(0, 0, 0)));
        assertEquals("break 0 0 0: nothing to break at 0 0 0 (it is air)",
                d.tick(see(0f, 0f, 0, 0, target(true, false, 0f, null))).ended());
        d.start(new Driver.Break(new BetterBlockPos(0, 0, 0)));
        assertEquals("break 0 0 0: not broken: out of reach", d.tick(see(0f, 0f, 0, 0, target(false, false, 0f, "out of reach"))).ended());
        d.start(new Driver.Break(new BetterBlockPos(0, 0, 0)));
        assertTrue(d.tick(see(10f, 30f, 0, 0, target(false, true, 0f, null))).ended().contains("no break progress"));
        d.start(new Driver.Break(new BetterBlockPos(0, 0, 0)));
        Driver.Act a = null;
        for (int i = 0; i < 60 && (a == null || a.ended() == null); i++) a = d.tick(see(10f, 30f, 0, 0, target(false, false, 0.5f, null)));
        assertEquals("break 0 0 0: still there after 40 ticks: wrong block under the crosshair: stone at 1, 2, 3 (up face)", a.ended());
        d.start(new Driver.Break(new BetterBlockPos(0, 0, 0)));
        for (int i = 0; i < 100 && ((a = d.tick(see(10f, 30f, 0, 0, target(false, true, 0.5f, null)))).ended() == null); i++) {
            assertTrue(a.hit());
        }
        assertTrue(a.ended().startsWith("break 0 0 0: still there after 43 ticks (43 holding, the game's time 2)"), a.ended());
    }

    @Test
    void aPlaceClicksWithTheFork_sDelayUntilTheBlockIsThere() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Place(new BetterBlockPos(0, 64, 0)));
        assertFalse(d.tick(see(0f, 0f, 0, 0, target(true, false, 0f, null))).use());
        int clicks = 0;
        for (int i = 0; i < 8; i++) if (d.tick(see(10f, 30f, 0, 0, target(true, true, 0f, null))).use()) clicks++;
        assertEquals(2, clicks, "one click, three ticks' wait, one more");
        Driver.Act a = d.tick(see(10f, 30f, 0, 0, target(false, false, 0f, null)));
        assertTrue(a.ok());
        assertEquals("place 0 64 0: block there in 9 ticks (2 clicks)", a.ended());
    }

    @Test
    void placeFailuresSayWhy() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Place(new BetterBlockPos(0, 64, 0)));
        assertEquals("place 0 64 0: not placed: 0 64 0 is not empty (stone at 1, 2, 3 (up face))", d.tick(see(0f, 0f, 0, 0, target(false, false, 0f, null))).ended());
        d.start(new Driver.Place(new BetterBlockPos(0, 64, 0)));
        assertEquals("place 0 64 0: not placed: no full block in the hand", d.tick(see(0f, 0f, 0, 0, target(true, false, 0f, "no full block in the hand"))).ended());
        d.start(new Driver.Place(new BetterBlockPos(0, 64, 0)));
        Driver.Act a = null;
        for (int i = 0; i < 100 && (a == null || a.ended() == null); i++) a = d.tick(see(10f, 30f, 0, 0, target(true, true, 0f, null)));
        assertTrue(a.ended().startsWith("place 0 64 0: not there after 41 ticks (11 clicks): the click placed nothing"), a.ended());
    }

    @Test
    void theDriverLetsGoOnPauseWorldChangeAndBaritone() {
        Driver d = new Driver(cfg);
        d.start(new Driver.Walk(5));
        d.tick(see(0f, 0f, 0, 0, null));
        Driver.Act a = d.tick(new Driver.See(0f, 0f, 0, 0, true, false, true, null));
        assertEquals("walk 5: let go after 1 ticks: the game paused", a.ended());
        assertTrue(a.keys().isEmpty());
        d.start(new Driver.Walk(5));
        assertTrue(d.tick(new Driver.See(0f, 0f, 0, 0, false, true, true, null)).ended().contains("Baritone is driving"));
        d.start(new Driver.Walk(5));
        assertTrue(d.tick(new Driver.See(0f, 0f, 0, 0, false, false, false, null)).ended().contains("the world changed"));
        assertEquals(Driver.Act.idle(), d.tick(see(0f, 0f, 0, 0, null)), "idle: no keys, no look");
        d.start(new Driver.Walk(5));
        assertEquals("walk 5 replaced by look 1 2", d.start(new Driver.Look(1, 2)));
        assertEquals("look 1 2: stopped after 0 ticks (aa nav stop)", d.stop("aa nav stop"));
        assertNull(d.stop("again"));
    }
}
