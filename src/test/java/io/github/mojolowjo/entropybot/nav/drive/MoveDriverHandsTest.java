package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.Rotation;
import org.junit.jupiter.api.Test;

import static io.github.mojolowjo.entropybot.nav.drive.MoveDriverTest.c;
import static io.github.mojolowjo.entropybot.nav.drive.MoveDriverTest.d;
import static org.junit.jupiter.api.Assertions.*;

/** Nav step 9b: break before the move, bridge, step, pillar, dig down and parkour (the fork's updateState rules). */
class MoveDriverHandsTest {

    static MoveDriver.Body at(double x, double y, double z, boolean ground, float yaw, float pitch) {
        return new MoveDriver.Body(x, y, z, 0, 0, 0, ground, false, yaw, pitch);
    }

    static MoveDriver.Body at(double x, double y, double z) {
        return at(x, y, z, true, 0f, 0f);
    }

    @Test
    void aBlockInTheWayIsBrokenFirstWalkingUpToIt() {
        FakeMoveWorld w = new FakeMoveWorld().solid(0, 65, 1);
        FakeHands h = new FakeHands();
        h.reach.put(c(0, 65, 1), new Rotation(0, -20));
        MoveDriver t = d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1));
        MoveDriver.MoveAct a = t.tick(at(0.5, 64, 0.5), w, h);
        assertEquals(new MoveDriver.Hand(false, c(0, 65, 1), false), a.hand(), "not on it yet: no hit");
        assertEquals(-20f, a.pitch());
        assertTrue(a.keys().contains(Keys.Key.FORWARD), "walkWhileBreaking: 1.0 from the cell");
        h.lookingAt.add(c(0, 65, 1));
        a = t.tick(at(0.5, 64, 0.8), w, h);
        assertTrue(a.hand().click(), "the crosshair on it: hit");
        assertFalse(a.keys().contains(Keys.Key.FORWARD), "pressed up against it (0.7): stand");
        assertTrue(t.tick(at(0.5, 64, 0.5, true, 0f, -20f), w, h).hand().click(), "the head on the aim: hit");
        w.solid.clear();
        assertNull(t.tick(at(0.5, 64, 0.8), w, h).hand(), "gone: walk on");
    }

    @Test
    void aTraverseBridgesTheGap() {
        FakeMoveWorld w = new FakeMoveWorld().hole(0, 1);
        FakeHands h = new FakeHands();
        MoveDriver t = d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1));
        h.place.put(c(0, 63, 1), new MoveDriver.PlaceSense(new Rotation(0, 70), false));
        MoveDriver.MoveAct a = t.tick(at(0.5, 64, 0.5), w, h);
        assertEquals(new MoveDriver.Hand(true, c(0, 63, 1), false), a.hand());
        assertTrue(a.keys().contains(Keys.Key.FORWARD), "attempting, straight ahead and 1.0 away: forward");
        assertTrue(a.keys().contains(Keys.Key.SNEAK), "a face found: sneak (attemptToPlaceABlock wouldSneak)");
        h.place.put(c(0, 63, 1), new MoveDriver.PlaceSense(new Rotation(0, 70), true));
        a = t.tick(at(0.5, 64, 0.9), w, h);
        assertTrue(a.keys().contains(Keys.Key.SNEAK));
        assertFalse(a.hand().click(), "ready but not crouching yet");
        h.crouching = true;
        assertTrue(t.tick(at(0.5, 64, 0.9), w, h).hand().click(), "ready and crouching: place");
        h.block = false;
        assertEquals("needs a block placed at 0 63 1 and the bag has no full block", t.tick(at(0.5, 64, 0.9), w, h).why());
    }

    @Test
    void overTheGapItPlacesBackAgainstTheBlockWalkedOff() {
        FakeMoveWorld w = new FakeMoveWorld().hole(0, 1);
        FakeHands h = new FakeHands();
        MoveDriver t = d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1));
        MoveDriver.MoveAct a = t.tick(at(0.5, 64, 1.5), w, h);
        assertEquals(new MoveDriver.Hand(true, c(0, 63, 1), false), a.hand());
        assertTrue(a.keys().contains(Keys.Key.SNEAK), "0.0 from the cell: sneak");
        assertFalse(a.keys().contains(Keys.Key.BACK), "0.5 from the face: no back");
        a = t.tick(at(0.5, 64, 1.2), w, h);
        assertTrue(a.keys().contains(Keys.Key.BACK), "0.2 from the face (under 0.29): back off");
        h.lookingAt.add(c(0, 63, 0));
        assertTrue(t.tick(at(0.5, 64, 1.5), w, h).hand().click(), "the crosshair on the block walked off: place");
    }

    @Test
    void anAscendPlacesItsStepAndBacksOffAfter10Ticks() {
        FakeMoveWorld w = new FakeMoveWorld();
        FakeHands h = new FakeHands();
        MoveDriver up = d(MoveCatalog.ASCEND_SOUTH, c(0, 64, 0), c(0, 65, 1));
        h.place.put(c(0, 64, 1), new MoveDriver.PlaceSense(new Rotation(0, 60), true));
        h.crouching = true;
        MoveDriver.MoveAct a = up.tick(at(0.5, 64, 0.5), w, h);
        assertEquals(new MoveDriver.Hand(true, c(0, 64, 1), true), a.hand());
        assertTrue(a.keys().contains(Keys.Key.SNEAK));
        for (int i = 0; i < 10; i++) a = up.tick(at(0.5, 64, 0.5), w, h);
        assertTrue(a.keys().contains(Keys.Key.BACK), "11 ticks without the step: back off");
    }

    @Test
    void aPillarJumpsCentredAndPlacesOnceRisen() {
        FakeMoveWorld w = new FakeMoveWorld();
        FakeHands h = new FakeHands();
        MoveDriver p = d(MoveCatalog.PILLAR, c(0, 64, 0), c(0, 65, 0));
        MoveDriver.MoveAct a = p.tick(at(0.5, 64, 0.5), w, h);
        assertTrue(a.keys().contains(Keys.Key.SNEAK) && a.keys().contains(Keys.Key.JUMP), a.keys().toString());
        assertEquals(new MoveDriver.Hand(true, c(0, 64, 0), false), a.hand());
        assertTrue(p.tick(at(0.8, 64, 0.5), w, h).keys().contains(Keys.Key.FORWARD), "0.3 off centre: walk back");
        h.crouching = true;
        h.lookingAt.add(c(0, 63, 0));
        a = p.tick(at(0.5, 65.2, 0.5, false, 0f, 90f), w, h);
        assertTrue(a.hand().click(), "risen 0.2 over the cell, crouching, looking down: place");
        assertFalse(a.keys().contains(Keys.Key.JUMP));
        w.solid(0, 64, 0);
        assertEquals(MoveDriver.Status.SUCCESS, p.tick(at(0.5, 65.0, 0.5), w, h).status());
        h.block = false;
        assertEquals("needs a block placed at 0 64 0 and the bag has no full block",
                d(MoveCatalog.PILLAR, c(0, 64, 0), c(0, 65, 0)).tick(at(0.5, 64, 0.5), new FakeMoveWorld(), h).why());
        FakeMoveWorld flower = new FakeMoveWorld();
        flower.fixed.add(c(0, 64, 0));
        h.block = true;
        assertFalse(d(MoveCatalog.PILLAR, c(0, 64, 0), c(0, 65, 0)).tick(at(0.5, 64, 0.5), flower, h).hand().place(),
                "a block that is not replaceable where the block goes: break it");
    }

    @Test
    void aDigDownBreaksTheFloorWaitsCentredThenWalksAtIt() {
        FakeMoveWorld w = new FakeMoveWorld();
        FakeHands h = new FakeHands();
        h.reach.put(c(0, 63, 0), new Rotation(0, 90));
        MoveDriver dn = d(MoveCatalog.DOWNWARD, c(0, 64, 0), c(0, 63, 0));
        assertEquals(new MoveDriver.Hand(false, c(0, 63, 0), false), dn.tick(at(0.5, 64, 0.5), w, h).hand());
        FakeMoveWorld open = new FakeMoveWorld().hole(0, 0);
        MoveDriver.MoveAct a = null;
        for (int i = 0; i < 10; i++) {
            a = dn.tick(at(0.5, 64, 0.5), open, h);
            assertTrue(a.keys().isEmpty(), "centred: wait for the fall");
        }
        assertTrue(dn.tick(at(0.5, 64, 0.5), open, h).keys().contains(Keys.Key.FORWARD), "after 10 ticks: walk at it");
        assertEquals(MoveDriver.Status.SUCCESS, dn.tick(at(0.5, 63, 0.5), open, h).status());
        assertEquals(MoveDriver.Status.FAILED, dn.tick(at(2.5, 64, 0.5), open, h).status());
    }

    @Test
    void parkourJumpsLateOverATwoGapAndSprintsAFourGap() {
        FakeMoveWorld w = new FakeMoveWorld().hole(0, 1).hole(0, 2);
        FakeHands h = new FakeHands();
        MoveDriver pk = d(MoveCatalog.PARKOUR_SOUTH, c(0, 64, 0), c(0, 64, 3));
        MoveDriver.MoveAct a = pk.tick(at(0.5, 64, 0.5), w, h);
        assertTrue(a.keys().contains(Keys.Key.FORWARD));
        assertFalse(a.keys().contains(Keys.Key.SPRINT), "a 2-gap: no sprint");
        assertFalse(a.keys().contains(Keys.Key.JUMP));
        assertFalse(pk.tick(at(0.5, 64, 1.05), w, h).keys().contains(Keys.Key.JUMP), "0.55 from the start: too early");
        assertTrue(pk.tick(at(0.5, 64, 1.25), w, h).keys().contains(Keys.Key.JUMP), "0.75 from the start: jump");
        assertEquals(MoveDriver.Status.SUCCESS, pk.tick(at(0.5, 64, 3.4), w, h).status());
        assertTrue(pk.tick(at(0.5, 63.5, 1.5, false, 0f, 0f), w, h).why().startsWith("fell short of the jump"));
        MoveDriver four = d(MoveCatalog.PARKOUR_SOUTH, c(0, 64, 0), c(0, 64, 4));
        assertTrue(four.tick(at(0.5, 64, 0.5), w, h).keys().contains(Keys.Key.SPRINT), "a 3-gap (dist 4): sprint");
    }
}
