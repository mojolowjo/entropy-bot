package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 fc (run FC, 2026-10-28, round 1 run 3 stopped; the manager's build, the D6 coder's part named in the commit):
 * (1) run EW 12:28:01: "no progress 30 s ... Baritone not executing, a search running" stopped the caving step while Baritone
 * searched - a search still computing is not known (rule 15), the monitor's clock holds; (2) run FC 14:48:21: boxed in at
 * 43 47 5 on a plank floor over a pit under open sky, the ring's top the feet and no climb at depth 0 - "no way out is
 * priced" with no walk asked, every order stopped: the way out is the explore whose FIRST walk runs under the ordinary
 * lease, every refusing test said (rule 7); (3) run FC: the caving step at 46 57 -1 ended NO_BRANCH with 110,111 of 110,150
 * cells unseen - the branch goals spanned the SEEN frontier cells, so they held where they were seen: the goal spans the
 * unseen index cells behind the frontier.
 */
class FcTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int[] FEET = {21, 46, -3};

    /** Signals of a step whose Baritone is not executing: {@code searching} = a search still computing. */
    static StepSignals quiet(boolean searching) {
        return new StepSignals(true, null, FEET, 46.8, "g", false, false, searching, 0, -1, -1, null, 0, -1, null, null);
    }

    /** Cause 1: 45 s of "not executing, a search running" is no stall; the same 45 s with no search is the stall as before. */
    @Test
    void theMonitorsClockHolds_whileASearchComputes() {
        Z71StepMonitorTest.Hands h = new Z71StepMonitorTest.Hands();
        h.script = s -> quiet(true);
        Z71StepMonitorTest.Planner p = new Z71StepMonitorTest.Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron_pickaxe", List.of(new Step.ToCave(21, 46, -3)), 0, p);
        Z71StepMonitorTest.run(run, h, p, 45);
        assertEquals(0, p.facts.size(), "a search computing holds the clock (rule 15): " + h.logged);

        Z71StepMonitorTest.Hands h2 = new Z71StepMonitorTest.Hands();
        h2.script = s -> quiet(false);
        Z71StepMonitorTest.Planner p2 = new Z71StepMonitorTest.Planner(h2);
        Steps run2 = new Steps(h2);
        run2.start("goal.iron_pickaxe", List.of(new Step.ToCave(21, 46, -3)), 0, p2);
        Z71StepMonitorTest.run(run2, h2, p2, 45);
        assertEquals(1, p2.facts.size(), "no search, nothing executing, no progress: the stall as before: " + h2.logged);
    }

    /** Cause 2: FC's plank floor - the ring's top is the feet, depth 0, boxed in: the leased explore, every refusing test said. */
    @Test
    void boxedInWithNoWalkUpAndNoClimb_theExploresFirstWalkIsLeased_andTheRefusalsAreSaid() {
        int[] feet = {8, 62, 1};                                             // the fixture's feet (J6bEhTest.iron's where facts); FC's were 43 47 5
        var over = NeedChain.Surface.noLeg(0, feet, false, feet, 0);       // the ring's top within RING is the feet's floor
        // FC: no band leg moved the bot either (the walk down to the band unreachable, as in run EM's fixture)
        PathPrice noBandWalk = (x, y, z, d) -> y < 40 ? PathPrice.Answer.unreachable("no path: the complete search found none")
                : PathPrice.Answer.priced(new PathPrice.Walk(FakePaths.walkS(d)));
        Chain c = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of(NeedChain.boxedInKey(feet), "3 no-break walks in a row found no path from here")).withPath(noBandWalk));
        assertTrue(c.ok(), "a way, never a stop (was: 'no way out is priced from here'): " + c.why());
        assertTrue(c.steps().get(0) instanceof Step.Explore e && e.leased(), "the explore from here, its first walk leased: " + c.steps());
        String said = c.why() + " | " + c.missing();
        assertTrue(said.contains("boxed in here: the explore from here, its first walk under the ordinary lease"), "the way says what it is: " + said);
        assertTrue(new Step.Explore(5, "raw_iron", true).text().contains("its first walk may break or place"), "the step says it");
        // not boxed: the plain no-break explore, as before
        Chain free = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withPath(noBandWalk));
        assertTrue(free.steps().stream().anyMatch(s -> s instanceof Step.Explore e && !e.leased()), "not boxed: no lease - " + free.steps());
    }

    /** Cause 3: a branch's goal spans the unseen cells behind its frontier; a record of before names none: the frontier as before. */
    @Test
    void aBranchGoalSpansTheUnseenCellsBehindItsFrontier() {
        long f = Sightings.cell(46, 57, -1);
        List<Long> frontier = List.of(f, Sightings.cell(47, 57, -1));
        List<Long> behind = List.of(Sightings.cell(46, 50, -1), Sightings.cell(46, 45, -1), Sightings.cell(46, 40, -1));
        var g = new CaveRecord.Group(f, 2, behind.size(), frontier, behind);
        List<int[]> cells = CaveWalk.branchCells(g, new int[]{46, 57, 0});
        assertEquals(3, cells.size());
        assertArrayEquals(new int[]{46, 50, -1}, cells.get(0), "the unseen cell nearest the feet first");
        assertTrue(cells.stream().noneMatch(c -> c[1] == 57), "never the seen frontier cells (they hold where they were seen)");
        var old = new CaveRecord.Group(f, 2, 3, frontier);
        assertEquals(2, CaveWalk.branchCells(old, new int[]{46, 57, 0}).size(), "a record of before fc: its frontier cells");
        assertEquals("no stand sees behind it (no standable cell within reach and sight of its 3 unseen cell(s))", CaveWalk.noStandSees(3));
    }
}
