package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.engine.CreeperRules.Act;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Cell;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Cycle;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Line;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Mode;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Obs;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Phase;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Situation;
import io.github.mojolowjo.entropybot.guard.Box;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CreeperRulesTest {
    private static final String OW = "minecraft:overworld";

    // ---- engage or flee ----

    private static Situation ok() {
        return new Situation(Mode.MELEE, 2, 20f, 1, false, false, false, false, 5, true, null, false);
    }

    @Test
    void engagesALoneCreeperWithASword() {
        assertNull(CreeperRules.refusal(ok()));
        // an axe is the second choice
        assertNull(CreeperRules.refusal(new Situation(Mode.MELEE, 1, 12f, 1, false, false, false, false, 5, true, null, false)));
        assertNull(CreeperRules.refusal(new Situation(Mode.BOW, 2, 20f, 1, false, false, false, false, 6, true, null, false)));
    }

    @Test
    void eachRefusalHasItsReason() {
        assertEquals("creepers: flee is set", CreeperRules.refusal(new Situation(Mode.FLEE, 2, 20f, 1, false, false, false, false, 5, true, null, false)));
        assertEquals("no sword or axe", CreeperRules.refusal(new Situation(Mode.MELEE, 0, 20f, 1, false, false, false, false, 5, true, null, false)));
        assertTrue(CreeperRules.refusal(new Situation(Mode.MELEE, 2, 11.5f, 1, false, false, false, false, 5, true, null, false)).startsWith("health"));
        assertEquals("2 creepers within 16", CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 2, false, false, false, false, 5, true, null, false)));
        assertEquals("another monster is near", CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 1, true, false, false, false, 5, true, null, false)));
        assertEquals("it is charged", CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 1, false, true, false, false, 5, true, null, false)));
        assertTrue(CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 1, false, false, true, false, 5, true, null, false)).contains("protect box"));
        assertEquals("it is next to built blocks", CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 1, false, false, false, true, 5, true, null, false)));
        assertEquals("no room to back off (a wall after 2.3)", CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 1, false, false, false, false, 2.25, true, "a wall", false)));
        assertEquals("too narrow here (a tunnel)", CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 1, false, false, false, false, 5, false, null, false)));
        assertEquals("gave up on this one", CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 1, false, false, false, false, 5, true, null, true)));
    }

    @Test
    void builtBlocksAndProtectBoxes() {
        assertTrue(CreeperRules.nearBuilds(1, 0));
        assertTrue(CreeperRules.nearBuilds(0, 6));
        assertFalse(CreeperRules.nearBuilds(0, 2));     // a couple of the mine's torches
        List<Box> base = List.of(new Box("base", OW, -40, 50, 170, -10, 70, 200));
        assertTrue(CreeperRules.nearBox(base, OW, -25, 60, 185, 16));     // inside
        assertTrue(CreeperRules.nearBox(base, OW, 5, 60, 185, 16));       // 15 east of it
        assertFalse(CreeperRules.nearBox(base, OW, 20, 60, 185, 16));     // 30 away
        assertFalse(CreeperRules.nearBox(base, "minecraft:the_nether", -25, 60, 185, 16));
        assertFalse(CreeperRules.nearBox(null, OW, 0, 0, 0, 16));
    }

    // ---- the retreat line ----

    /** Ground: floor up to y 63, air from 64; cells set by hand override it. */
    private static final class Fixture implements CreeperRules.Grid {
        final Map<String, Cell> set = new HashMap<>();
        int groundTop = 63;

        Fixture put(int x, int y, int z, Cell c) {
            set.put(x + "," + y + "," + z, c);
            return this;
        }

        Fixture box(int x1, int y1, int z1, int x2, int y2, int z2, Cell c) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) put(x, y, z, c);
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            Cell c = set.get(x + "," + y + "," + z);
            if (c != null) return c;
            return y <= groundTop ? Cell.FLOOR : Cell.FREE;
        }
    }

    /** The bot at 0.5 64 0.5, the creeper 5 blocks west: the line runs east (+x). */
    private static Line east(Fixture f) {
        return CreeperRules.retreatLine(f, 0.5, 64, 0.5, 5, 0, 5);
    }

    @Test
    void openFieldIsClearAndOpen() {
        Line l = east(new Fixture());
        assertEquals(5, l.clear(), 1e-9);
        assertTrue(l.open());
        assertNull(l.stop());
        // diagonally too
        Line d = CreeperRules.retreatLine(new Fixture(), 0.5, 64, 0.5, 3, 4, 5);
        assertEquals(5, d.clear(), 1e-9);
        assertTrue(d.open());
    }

    @Test
    void aThreeWideTunnelIsWalkableButNotOpen() {
        Fixture f = new Fixture().box(-10, 64, -2, 10, 66, -2, Cell.FLOOR).box(-10, 64, 2, 10, 66, 2, Cell.FLOOR).box(-10, 66, -1, 10, 66, 1, Cell.FLOOR);
        Line l = east(f);
        assertEquals(5, l.clear(), 1e-9);
        assertFalse(l.open());
        // standing at its edge it still is a tunnel
        assertFalse(CreeperRules.retreatLine(f, 0.5, 64, -0.5, 5, 0, 5).open());
        String why = CreeperRules.refusal(new Situation(Mode.MELEE, 2, 20f, 1, false, false, false, false, l.clear(), l.open(), l.stop(), false));
        assertEquals("too narrow here (a tunnel)", why);
    }

    @Test
    void aOneWideTunnelIsNotOpen() {
        Fixture f = new Fixture().box(-10, 64, -1, 10, 65, -1, Cell.FLOOR).box(-10, 64, 1, 10, 65, 1, Cell.FLOOR).box(-10, 66, -1, 10, 66, 1, Cell.FLOOR);
        Line l = east(f);
        assertEquals(5, l.clear(), 1e-9);
        assertFalse(l.open());
    }

    @Test
    void aLedgeStopsTheLine() {
        Fixture f = new Fixture().box(3, 62, -5, 10, 63, 5, Cell.FREE);       // two down from x 3 on
        Line l = east(f);
        assertEquals(2.25, l.clear(), 1e-9);
        assertEquals("a drop", l.stop());
    }

    @Test
    void oneStepDownOrUpIsFine() {
        Fixture down = new Fixture().box(2, 63, -5, 10, 63, 5, Cell.FREE);
        assertEquals(5, east(down).clear(), 1e-9);
        Fixture up = new Fixture().box(3, 64, -5, 10, 64, 5, Cell.FLOOR);
        assertEquals(5, east(up).clear(), 1e-9);
        // no room to jump: a wall
        Fixture low = new Fixture().box(3, 64, -5, 10, 64, 5, Cell.FLOOR).box(0, 66, -5, 10, 66, 5, Cell.FLOOR);
        Line l = east(low);
        assertEquals("a wall", l.stop());
        assertEquals(2.25, l.clear(), 1e-9);
    }

    @Test
    void waterAndHazardsStopTheLine() {
        Fixture water = new Fixture().box(2, 63, -5, 4, 63, 5, Cell.LIQUID);
        Line l = east(water);
        assertEquals("liquid", l.stop());
        assertEquals(1.25, l.clear(), 1e-9);
        Fixture fire = new Fixture().put(3, 64, 0, Cell.BAD);
        Line h = east(fire);
        assertEquals("a hazard", h.stop());
        assertTrue(h.clear() < 5);
    }

    @Test
    void theBotsEdgesCount() {
        // a post just beside the centre line, inside the player's half width
        Fixture f = new Fixture().box(3, 64, 1, 3, 65, 1, Cell.FLOOR);
        Line l = CreeperRules.retreatLine(f, 0.5, 64, 0.8, 5, 0, 5);
        assertTrue(l.clear() < 5);
        assertEquals("a wall", l.stop());
        // with the post a block further off the line is clear
        Fixture g = new Fixture().box(3, 64, 2, 3, 65, 2, Cell.FLOOR);
        assertEquals(5, CreeperRules.retreatLine(g, 0.5, 64, 0.5, 5, 0, 5).clear(), 1e-9);
    }

    // ---- the cycle ----

    private static Obs obs(double d, int dir, float swelling, float cooldown, double room) {
        return new Obs(d, dir, swelling, cooldown, room, false, false, true);
    }

    @Test
    void noChargeOverAHole() {
        Cycle c = new Cycle();
        assertEquals(Act.WAIT, c.step(0, new Obs(5, -1, 0, 1, 5, false, false, false)));
        assertEquals(Act.CHARGE, c.step(1, obs(5, -1, 0, 1, 5)));
        // the ground towards it turned bad mid-charge: stop and wait
        assertEquals(Act.WAIT, c.step(2, new Obs(4, -1, 0, 1, 5, false, false, false)));
    }

    @Test
    void hitBackCooledRepeat() {
        Cycle c = new Cycle();
        // waiting at 5 with the cooldown not ready
        assertEquals(Act.WAIT, c.step(0, obs(5, -1, 0, 0.5f, 5)));
        // ready: charge
        assertEquals(Act.CHARGE, c.step(1, obs(5, -1, 0, 1, 5)));
        assertEquals(Act.CHARGE, c.step(2, obs(4, -1, 0, 1, 5)));
        // in reach: hit once, then back off
        assertEquals(Act.HIT, c.step(3, obs(2.9, 1, 0.05f, 1, 5)));
        assertEquals(Phase.BACK, c.phase());
        assertEquals(1, c.hits());
        // the fuse burns within 7: keep backing off
        assertEquals(Act.BACK, c.step(4, obs(5, 1, 0.2f, 0.1f, 5)));
        assertEquals(Act.BACK, c.step(5, obs(6.9, 1, 0.3f, 0.2f, 5)));
        // beyond 7 it cools
        assertEquals(Act.WAIT, c.step(6, obs(7.2, -1, 0.3f, 0.3f, 5)));
        // ready again but the fuse is still high: wait
        assertEquals(Act.WAIT, c.step(20, obs(6, -1, 0.25f, 1, 5)));
        // cooled: again
        assertEquals(Act.CHARGE, c.step(30, obs(6, -1, 0.1f, 1, 5)));
        assertEquals(2, c.charges());
    }

    @Test
    void backingOffEndsWhenTheFuseCools() {
        Cycle c = new Cycle();
        c.step(0, obs(5, -1, 0, 1, 5));
        assertEquals(Act.HIT, c.step(1, obs(3, -1, 0, 1, 5)));
        // knocked back, not swelling, 4.5 away: done backing off
        assertEquals(Act.BACK, c.step(2, obs(4, -1, 0, 0.1f, 5)));
        assertEquals(Act.WAIT, c.step(3, obs(4.6, -1, 0, 0.1f, 5)));
    }

    @Test
    void tooCloseWhileNotReadyBacksOff() {
        Cycle c = new Cycle();
        assertEquals(Act.BACK, c.step(0, obs(3.5, -1, 0, 0.4f, 5)));
        assertEquals(Act.WAIT, c.step(1, obs(4.6, -1, 0, 0.5f, 5)));
    }

    @Test
    void noChargeWithoutRoom() {
        Cycle c = new Cycle();
        assertEquals(Act.WAIT, c.step(0, obs(5, -1, 0, 1, 3)));
        assertEquals(Phase.WAIT, c.phase());
    }

    @Test
    void aChargeMeetingAFuseTurnsBack() {
        Cycle c = new Cycle();
        assertEquals(Act.CHARGE, c.step(0, obs(5, -1, 0, 1, 5)));
        assertEquals(Act.BACK, c.step(1, obs(3.5, 1, 0.45f, 1, 5)));
    }

    @Test
    void safetyValveFlees() {
        // swelling 4 blocks away with nowhere to go
        Cycle c = new Cycle();
        assertEquals(Act.FLEE, c.step(0, obs(4, 1, 0.3f, 1, 0.5)));
        assertTrue(c.why().contains("can't back off"));
        // backing off into a wall while it swells
        Cycle b = new Cycle();
        assertEquals(Act.BACK, b.step(0, obs(4, 1, 0.3f, 1, 5)));
        assertEquals(Act.FLEE, b.step(1, obs(4.2, 1, 0.4f, 1, 0.2)));
        assertTrue(b.why().startsWith("cornered"));
        // stalled the same way
        Cycle s = new Cycle();
        s.step(0, obs(4, 1, 0.3f, 1, 5));
        assertEquals(Act.FLEE, s.step(1, new Obs(4.1, 1, 0.4f, 1, 5, true, false, true)));
        // a wall behind while it is not swelling: just wait there
        Cycle w = new Cycle();
        w.step(0, obs(3.5, -1, 0, 0.3f, 5));
        assertEquals(Act.WAIT, w.step(1, obs(3.6, -1, 0, 0.3f, 0.2)));
    }

    @Test
    void givesUpAfterThirtySeconds() {
        Cycle c = new Cycle();
        assertEquals(Act.WAIT, c.step(100, obs(6, -1, 0, 0.5f, 5)));
        assertEquals(Act.WAIT, c.step(699, obs(6, -1, 0, 0.5f, 5)));
        assertEquals(Act.FLEE, c.step(700, obs(6, -1, 0, 0.5f, 5)));
        assertTrue(c.why().contains("30 s"));
    }

    @Test
    void givesUpAfterChargesWithoutDamage() {
        Cycle c = new Cycle();
        long t = 0;
        for (int i = 0; i < CreeperRules.MAX_MISSES; i++) {
            assertEquals(Act.CHARGE, c.step(t, obs(6, -1, 0, 1, 5)));
            t += CreeperRules.CHARGE_TICKS;
            assertEquals(Act.WAIT, c.step(t, obs(6, -1, 0, 1, 5)));    // never arrived
            t++;
        }
        assertEquals(Act.FLEE, c.step(t, obs(6, -1, 0, 1, 5)));
        assertTrue(c.why().contains("without damage"));
        // damage resets the count
        Cycle d = new Cycle();
        t = 0;
        for (int i = 0; i < CreeperRules.MAX_MISSES + 2; i++) {
            assertEquals(Act.CHARGE, d.step(t, obs(6, -1, 0, 1, 5)));
            d.damaged();
            t += CreeperRules.CHARGE_TICKS;
            d.step(t, obs(6, -1, 0, 1, 5));
            t++;
        }
        assertNotEquals(Act.FLEE, d.step(t, obs(6, -1, 0, 1, 5)));
    }

    @Test
    void bowFromRangeOnlyWhenReady() {
        Cycle c = new Cycle();
        assertEquals(Act.SHOOT, c.step(0, new Obs(11, -1, 0, 1, 5, false, true, true)));
        assertEquals(Phase.SHOOT, c.phase());
        // it came close: melee as usual
        assertEquals(Act.CHARGE, c.step(5, new Obs(7.5, -1, 0, 1, 5, false, false, true)));
        // without the bow, beyond 8: wait for it
        Cycle w = new Cycle();
        assertEquals(Act.WAIT, w.step(0, new Obs(11, -1, 0, 1, 5, false, false, true)));
    }

    // ---- the bow's aim ----

    @Test
    void bowPitchHitsTheMark() {
        for (double[] c : new double[][]{{10, 0}, {15, 0}, {8, -1.5}, {12, 2}}) {
            double pitch = CreeperRules.bowPitch(c[0], c[1], CreeperRules.ARROW_SPEED);
            assertFalse(Double.isNaN(pitch));
            double[] h = CreeperRules.arrowAt(CreeperRules.ARROW_SPEED, Math.toRadians(-pitch), c[0]);
            assertNotNull(h);
            assertEquals(c[1], h[0], 0.02, "height at " + c[0]);
        }
        // level ground at 15: aimed a little up (negative pitch), and the arrow drops on the way
        double p15 = CreeperRules.bowPitch(15, 0, 3.0);
        assertTrue(p15 < 0 && p15 > -5, "pitch " + p15);
        assertTrue(CreeperRules.bowPitch(15, 0, 3.0) < CreeperRules.bowPitch(8, 0, 3.0));
        assertTrue(Double.isNaN(CreeperRules.bowPitch(10, 60, 3.0)));
        assertTrue(Double.isNaN(CreeperRules.bowPitch(500, 0, 3.0)));
        double ft = CreeperRules.flightTicks(12, CreeperRules.bowPitch(12, 0, 3.0), 3.0);
        assertTrue(ft > 3.5 && ft < 5, "ticks " + ft);
    }

    @Test
    void modeWords() {
        assertEquals(Mode.MELEE, Mode.parse(null));
        assertEquals(Mode.MELEE, Mode.parse("whatever"));
        assertEquals(Mode.FLEE, Mode.parse("Flee"));
        assertEquals(Mode.BOW, Mode.parse(" bow "));
        assertEquals("bow", Mode.BOW.word());
    }
}
