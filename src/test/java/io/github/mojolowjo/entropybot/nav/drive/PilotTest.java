package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalBlock;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Nav step 9a: the pilot's route position, give-ups and hand-overs (PathExecutor's rules). */
class PilotTest {
    static BetterBlockPos c(int x, int y, int z) { return new BetterBlockPos(x, y, z); }

    /** A straight route south along x 0, y 64, from z 0 to z n, with the goal at its end (or beyond: a part). */
    static Path south(int n, int goalZ) {
        List<BetterBlockPos> cells = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        for (int z = 0; z <= n; z++) cells.add(c(0, 64, z));
        for (int z = 0; z < n; z++) steps.add(new Step(c(0, 64, z), c(0, 64, z + 1), MoveCatalog.TRAVERSE_SOUTH, 4.6));
        return Path.of(cells, steps, new GoalBlock(0, 64, goalZ));
    }

    /** The same route with every move priced high, so the too-slow give-up stays out of the way. */
    static Path slowSouth(int n) {
        Path p = south(n, n);
        List<Step> steps = new ArrayList<>();
        for (Step s : p.steps()) steps.add(new Step(s.src(), s.dest(), s.move(), 1000));
        return Path.of(p.positions(), steps, p.getGoal());
    }

    static MoveDriver.Body at(double x, double y, double z, boolean ground) {
        return new MoveDriver.Body(x, y, z, 0, 0, 0, ground, false, 0f, 0f);
    }

    final FakeMoveWorld w = new FakeMoveWorld();

    Pilot.Act tick(Pilot p, double z) {
        return p.tick(at(0.5, 64, z, true), w, false, false, true, s -> null);
    }

    @Test
    void walksMoveByMoveAndArrives() {
        Pilot p = new Pilot();
        p.start(south(3, 3), "route to 0 64 3");
        Pilot.Act a = tick(p, 0.5);
        assertTrue(a.keys().contains(Keys.Key.FORWARD));
        assertNull(a.ended());
        for (double z = 0.7; z < 3.4; z += 0.25) {
            a = tick(p, z);
            if (a.ended() != null) break;
        }
        assertTrue(a.ok(), a.ended());
        assertTrue(a.ended().startsWith("route to 0 64 3: arrived in "), a.ended());
        assertFalse(p.driving());
        assertTrue(a.keys().isEmpty());
    }

    @Test
    void theFeetAheadSkipToThatMove() {
        Pilot p = new Pilot();
        p.start(south(6, 6), "r");
        tick(p, 0.5);
        tick(p, 3.5);                                                         // pushed two cells on: the start of move 4
        assertEquals("move 4 of 6 (traverse_south)", p.where());
    }

    @Test
    void offTheRouteGivesUpWithTheDistance() {
        Pilot p = new Pilot();
        p.start(south(4, 4), "r");
        tick(p, 0.5);
        Pilot.Act a = p.tick(at(3.6, 64, 1.5, true), w, false, false, true, s -> null);
        assertFalse(a.ok());
        assertEquals("r: off the route: 3.1 blocks from it at move 1 (feet at 3 64 1)", a.ended());
        Pilot q = new Pilot();
        q.start(south(4, 4), "r");
        q.tick(at(0.5, 64, 0.5, true), w, false, false, true, s -> null);
        assertNull(q.tick(at(2.6, 64, 0.5, false), w, false, false, true, s -> null).ended(), "2.1 in the air: within 3");
    }

    @Test
    void moreThanTwoBlocksOffIsToleratedFor200Ticks() {                       // PathExecutor MAX_TICKS_AWAY (PR 4 review)
        Pilot p = new Pilot();
        p.start(slowSouth(4), "r");
        tick(p, 0.5);
        Pilot.Act a = null;
        int n = 0;
        while (n < 300 && (a == null || a.ended() == null)) {
            a = p.tick(at(3.0, 64, 0.5, true), w, false, false, true, s -> null);   // 2.5 blocks off: within 3
            n++;
        }
        assertEquals(201, n);
        assertTrue(a.ended().startsWith("r: off the route for 201 ticks: 2.5 blocks"), a.ended());
        Pilot q = new Pilot();
        q.start(slowSouth(4), "r");
        for (int i = 0; i < 150; i++) q.tick(at(3.0, 64, 0.5, true), w, false, false, true, s -> null);
        q.tick(at(0.5, 64, 0.5, true), w, false, false, true, s -> null);   // back on the route: the count starts again
        for (int i = 0; i < 150; i++) {
            assertNull(q.tick(at(3.0, 64, 0.5, true), w, false, false, true, s -> null).ended());
        }
    }

    @Test
    void feetBackOnAnEarlierMoveGoBackToIt() {                                 // PathExecutor rewind (a lag-back)
        Pilot p = new Pilot();
        p.start(south(6, 6), "r");
        tick(p, 0.5);
        tick(p, 3.5);
        assertEquals("move 4 of 6 (traverse_south)", p.where());
        tick(p, 0.5);
        assertEquals("move 1 of 6 (traverse_south)", p.where());
    }

    @Test
    void feetFarAheadSkipToTheMoveWhoseCellsHoldThem() {                       // PathExecutor skip, pathPosition + 3 on
        Pilot p = new Pilot();
        p.start(south(8, 8), "r");
        tick(p, 0.5);
        tick(p, 5.5);                                                         // cell 5: in move 5's cells, which ends there at once
        assertEquals("move 6 of 8 (traverse_south)", p.where());
    }

    @Test
    void tooSlowGivesUpAfterThePricePlus100Ticks() {
        Pilot p = new Pilot();
        p.start(south(4, 4), "r");
        Pilot.Act a = null;
        for (int i = 0; i < 200 && (a == null || a.ended() == null); i++) a = tick(p, 0.5);
        assertEquals("r: too slow: 105 ticks on move 1 (traverse_south), its price 4.6", a.ended());
    }

    @Test
    void aMoveThatBecameImpossibleIsNotStarted() {
        Pilot p = new Pilot();
        p.start(south(2, 2), "r");
        Pilot.Act a = p.tick(at(0.5, 64, 0.5, true), w, false, false, true, s -> "its cells changed (no longer passable)");
        assertEquals("r: move 1 became impossible: its cells changed (no longer passable)", a.ended());
    }

    @Test
    void aPartEndsShortOfTheGoalAndAsksForTheNext() {
        Pilot p = new Pilot();
        p.start(south(2, 10), "r");
        Pilot.Act a = null;
        for (double z = 0.5; z < 3 && (a == null || !a.replan()); z += 0.3) a = tick(p, z);
        assertTrue(a.replan());
        assertNull(a.ended());
        assertFalse(p.driving());
        p.next(south(2, 10));
        assertTrue(p.driving(), "the next part carries on");
    }

    @Test
    void aFailingMoveEndsTheRouteWithItsReason() {
        Pilot p = new Pilot();
        p.start(south(2, 2), "r");
        w.hole(0, 1);
        Pilot.Act a = tick(p, 0.5);
        assertEquals("r: move 1 (traverse_south 0 64 0 to 0 64 1) failed: needs a block placed at 0 63 1 (placing is off in 9a)", a.ended());
    }

    @Test
    void letsGoOnPauseWorldChangeAndBaritone() {
        Pilot p = new Pilot();
        p.start(south(2, 2), "r");
        assertTrue(p.tick(at(0.5, 64, 0.5, true), w, true, false, true, s -> null).ended().contains("the game paused"));
        p.start(south(2, 2), "r");
        assertTrue(p.tick(at(0.5, 64, 0.5, true), w, false, true, true, s -> null).ended().contains("Baritone is driving"));
        p.start(south(2, 2), "r");
        assertTrue(p.tick(at(0.5, 64, 0.5, true), w, false, false, false, s -> null).ended().contains("the world changed"));
        p.start(south(2, 2), "r");
        assertEquals("r: stopped after 0 ticks, 0 moves (aa nav stop)", p.stop("aa nav stop"));
        assertNull(p.stop("again"));
    }

    @Test
    void movesNineAWillNotDriveEndTheRoute() {
        Pilot p = new Pilot();
        List<BetterBlockPos> cells = List.of(c(0, 64, 0), c(0, 63, 0));
        p.start(Path.of(cells, List.of(new Step(c(0, 64, 0), c(0, 63, 0), MoveCatalog.DOWNWARD, 20)), new GoalBlock(0, 63, 0)), "r");
        assertEquals("r: move 1 (DOWNWARD) cannot be driven in 9a", tick(p, 0.5).ended());
    }
}
