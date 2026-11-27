package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Nav step 9a: each walking move's tick decisions, against the fork's rules. */
class MoveDriverTest {
    static BetterBlockPos c(int x, int y, int z) { return new BetterBlockPos(x, y, z); }

    static MoveDriver.Body at(double x, double y, double z) {
        return new MoveDriver.Body(x, y, z, 0, 0, 0, true, false, 0f, 0f);
    }

    static MoveDriver d(MoveCatalog m, BetterBlockPos src, BetterBlockPos dest) {
        return MoveDriver.of(new Step(src, dest, m, 4.6));
    }

    @Test
    void aTraverseWalksSprintsAndEndsInTheCell() {
        FakeMoveWorld w = new FakeMoveWorld();
        MoveDriver t = d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1));
        MoveDriver.MoveAct a = t.tick(at(0.5, 64, 0.5), w);
        assertEquals(MoveDriver.Status.RUNNING, a.status());
        assertTrue(a.keys().contains(Keys.Key.FORWARD) && a.keys().contains(Keys.Key.SPRINT), a.keys().toString());
        assertEquals(0f, a.yaw(), 1e-3, "south is yaw 0");
        assertEquals(MoveDriver.Status.SUCCESS, t.tick(at(0.5, 64, 1.4), w).status());
        MoveDriver over = d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1));
        over.tick(at(0.5, 64, 0.5), w);
        assertEquals(MoveDriver.Status.SUCCESS, over.tick(at(0.5, 64, 2.2), w).status(), "overshootTraverse: one past counts");
    }

    @Test
    void noSprintTowardLavaAndNamedFailures() {
        FakeMoveWorld w = new FakeMoveWorld().lava(0, 64, 2);
        assertFalse(d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1)).tick(at(0.5, 64, 0.5), w).keys().contains(Keys.Key.SPRINT));
        FakeMoveWorld wall = new FakeMoveWorld().solid(0, 65, 1);
        MoveDriver.MoveAct a = d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1)).tick(at(0.5, 64, 0.5), wall);
        assertEquals(MoveDriver.Status.RUNNING, a.status(), "9b: a block in the way is broken first");
        assertEquals(new MoveDriver.Hand(false, c(0, 65, 1), true), a.hand(), "no face in sight: aim at the centre and hit (the fork)");
        FakeMoveWorld hole = new FakeMoveWorld().hole(0, 1);
        assertEquals("needs a block placed at 0 63 1 and the bag has no full block", d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1)).tick(at(0.5, 64, 0.5), hole).why());
    }

    @Test
    void anAscendJumpsWithHeadRoomOrOnceLinedUpAndClose() {
        FakeMoveWorld w = new FakeMoveWorld().solid(0, 64, 1);
        MoveDriver a = d(MoveCatalog.ASCEND_SOUTH, c(0, 64, 0), c(0, 65, 1));
        assertTrue(a.tick(at(0.5, 64, 0.5), w).keys().contains(Keys.Key.JUMP), "head room clear: jump at once");
        FakeMoveWorld low = new FakeMoveWorld().solid(0, 64, 1).solid(1, 66, 0);   // a block beside the head: not clear
        MoveDriver b = d(MoveCatalog.ASCEND_SOUTH, c(0, 64, 0), c(0, 65, 1));
        assertTrue(b.tick(at(0.5, 64, 0.5), low).keys().contains(Keys.Key.JUMP), "1.0 from the cell's centre, lined up: jump");
        MoveDriver far = d(MoveCatalog.ASCEND_SOUTH, c(0, 64, 0), c(0, 65, 1));
        assertFalse(far.tick(at(0.5, 64, -0.3), low).keys().contains(Keys.Key.JUMP), "1.8 away: not yet");
        MoveDriver side = d(MoveCatalog.ASCEND_SOUTH, c(0, 64, 0), c(0, 65, 1));
        assertFalse(side.tick(at(0.8, 64, 0.5), low).keys().contains(Keys.Key.JUMP), "0.3 off to the side: not yet");
        assertEquals(MoveDriver.Status.SUCCESS, a.tick(at(0.5, 65, 1.3), w).status());
        MoveDriver fell = d(MoveCatalog.ASCEND_SOUTH, c(0, 64, 0), c(0, 65, 1));
        assertEquals(MoveDriver.Status.FAILED, fell.tick(at(0.5, 63.0, 0.5), new FakeMoveWorld().solid(0, 64, 1).hole(0, 0)).status());
    }

    @Test
    void aDescendAimsPastTheCellThenLands() {
        FakeMoveWorld w = new FakeMoveWorld().hole(0, 1);                          // the floor at 0 62 1: dest 0 63 1
        w.solid(0, 62, 1);
        MoveDriver d = d(MoveCatalog.DESCEND_SOUTH, c(0, 64, 0), c(0, 63, 1));
        MoveDriver.MoveAct a = d.tick(at(0.5, 64, 0.5), w);
        assertTrue(a.keys().contains(Keys.Key.FORWARD));
        assertEquals(0f, a.yaw(), 1e-3, "toward 0 63 2, straight south");
        assertEquals(MoveDriver.Status.SUCCESS, d.tick(at(0.5, 63.0, 1.5), w).status());
        assertEquals(MoveDriver.Status.RUNNING, d(MoveCatalog.DESCEND_SOUTH, c(0, 64, 0), c(0, 63, 1)).tick(at(0.5, 63.7, 1.5), w).status(), "still in the air");
    }

    @Test
    void aDescendWithLavaAheadAimsShort() {
        FakeMoveWorld w = new FakeMoveWorld().hole(0, 1).lava(0, 63, 2);
        w.solid(0, 62, 1);
        MoveDriver.MoveAct a = d(MoveCatalog.DESCEND_SOUTH, c(0, 64, 0), c(0, 63, 1)).tick(at(0.2, 64, 0.5), w);
        // safe mode: the point 0.17 src + 0.83 dest = (0.5, 1.33): from x 0.2 the yaw leans east
        assertTrue(a.yaw() < 0f, "aims at the cell, not past it: " + a.yaw());
    }

    @Test
    void aFallSteersToTheCentreAndEndsOnTheGround() {
        FakeMoveWorld w = new FakeMoveWorld().hole(0, 1);                          // a shaft at 0 1 down to the floor at y 59
        MoveDriver f = d(MoveCatalog.DESCEND_SOUTH, c(0, 64, 0), c(0, 60, 1));
        assertTrue(f instanceof MoveDriver.Fall);
        MoveDriver.MoveAct a = f.tick(new MoveDriver.Body(0.9, 61.5, 1.5, 0, -0.6, 0, false, false, 0f, 0f), w);
        assertTrue(a.keys().contains(Keys.Key.FORWARD) && a.keys().contains(Keys.Key.SNEAK), "off centre and falling fast: " + a.keys() + " " + a.why());
        assertEquals(MoveDriver.Status.SUCCESS, f.tick(at(0.5, 60.0, 1.5), w).status());
        MoveDriver deep = d(MoveCatalog.DESCEND_SOUTH, c(0, 64, 0), c(0, 60, 1));
        assertEquals(MoveDriver.Status.FAILED, deep.tick(at(0.5, 58.5, 1.5), w).status());
    }

    @Test
    void aDiagonalSprintsWithItsCornersOpenAndFailsOffItsCells() {
        FakeMoveWorld w = new FakeMoveWorld();
        MoveDriver g = d(MoveCatalog.DIAGONAL_SOUTHEAST, c(0, 64, 0), c(1, 64, 1));
        MoveDriver.MoveAct a = g.tick(at(0.5, 64, 0.5), w);
        assertTrue(a.keys().contains(Keys.Key.SPRINT));
        assertEquals(-45f, a.yaw(), 1e-3);
        FakeMoveWorld corner = new FakeMoveWorld().solid(1, 64, 0).solid(1, 65, 0);
        MoveDriver.MoveAct b = d(MoveCatalog.DIAGONAL_SOUTHEAST, c(0, 64, 0), c(1, 64, 1)).tick(at(0.5, 64, 0.5), corner);
        assertEquals(MoveDriver.Status.RUNNING, b.status(), "one corner blocked: it walks past it");
        assertFalse(b.keys().contains(Keys.Key.SPRINT));
        assertEquals(MoveDriver.Status.FAILED, g.tick(at(-1.5, 64, 0.5), w).status());
        assertEquals(MoveDriver.Status.SUCCESS, g.tick(at(1.5, 64, 1.5), w).status());
    }

    @Test
    void everyMoveHasADriver() {
        assertTrue(MoveDriver.of(new Step(c(0, 64, 0), c(0, 63, 0), MoveCatalog.DOWNWARD, 20)) instanceof MoveDriver.Downward);
        assertTrue(MoveDriver.of(new Step(c(0, 64, 0), c(0, 65, 0), MoveCatalog.PILLAR, 20)) instanceof MoveDriver.Pillar);
        assertTrue(MoveDriver.of(new Step(c(0, 64, 0), c(0, 64, 3), MoveCatalog.PARKOUR_SOUTH, 20)) instanceof MoveDriver.Parkour);
    }

    @Test
    void swimUpInALiquid() {
        FakeMoveWorld w = new FakeMoveWorld();
        w.water.add(c(0, 64, 0));
        assertTrue(d(MoveCatalog.TRAVERSE_SOUTH, c(0, 64, 0), c(0, 64, 1)).tick(at(0.5, 64.1, 0.5), w).keys().contains(Keys.Key.JUMP));
    }
}
