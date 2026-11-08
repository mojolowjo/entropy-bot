package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepMonitor;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-mon (run JAI 00:46:42-00:52:42: the chop said "no face in sight from the eye" once, then 6 min with no move, no
 * break, no word; the 180 s outer watch fired twice): the monitor's silent rule (monitor.silentS 45) and the try's end
 * cell (run JAJ: two NO_BRANCH ends at two places parked as "the same way"). Ticks at 20 a second (game fact); a look
 * each second. The signals hold the stall clock (a search running, nothing executing), so only the silent rule can end.
 */
class MonSilentStepTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final String CHOP = "chop: no direct break of dark_oak_log at -182 59 -63: no face in sight from the eye";

    /** A search running, nothing executing, the feet {@code feet}, break stage {@code stage}. */
    static StepSignals held(int[] feet, int stage) {
        return new StepSignals(true, null, feet, 10, null, false, false, true, 0, -1, stage, stage < 0 ? null : "1 2 3", 0, -1, null, null);
    }

    static class Hands extends Z71StepMonitorTest.Hands {
        IntFunction<StepMonitor.Said> said = s -> new StepMonitor.Said("Chop", CHOP, 5);
        @Override public StepMonitor.Said said(Step s) { return said == null ? null : said.apply(second); }
    }

    @Test
    void aSilentStepEndsAtSilentSWithBaritonesLastLine() {
        Hands h = new Hands();
        h.script = s -> held(Z71StepMonitorTest.FEET, -1);
        Z71StepMonitorTest.Planner p = new Z71StepMonitorTest.Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(5, 64, 5, 0)), 0, p);
        Z71StepMonitorTest.run(run, h, p, 44);
        assertTrue(p.facts.isEmpty(), "the search hold keeps the stall rule quiet; silent not due yet");
        Z71StepMonitorTest.run(run, h, p, 60);
        assertEquals(List.of(45), p.at);
        String t = p.facts.get(0).text();
        assertTrue(t.startsWith("no progress 45 s at goto 5 64 5: Baritone's Chop last said '" + CHOP + "' before this step"), t);
        assertTrue(h.logged.stream().anyMatch(l -> l.contains("monitor: no progress 45 s")), h.logged.toString());
    }

    @Test
    void aStepThatMovesOrBreaksDoesNotEnd() {
        for (int mode = 0; mode < 2; mode++) {
            int m = mode;
            Hands h = new Hands();
            h.script = s -> m == 0 ? held(new int[]{s % 2, 62, 0}, -1) : held(Z71StepMonitorTest.FEET, s % 3);
            Z71StepMonitorTest.Planner p = new Z71StepMonitorTest.Planner(h);
            Steps run = new Steps(h);
            run.start("goal.iron", List.of(new Step.Walk(5, 64, 5, 0)), 0, p);
            Z71StepMonitorTest.run(run, h, p, 150);
            assertTrue(p.facts.isEmpty(), "mode " + m + ": " + p.facts);
        }
    }

    @Test
    void aNewLineResetsTheSilenceClock() {
        Hands h = new Hands();
        h.script = s -> held(Z71StepMonitorTest.FEET, -1);
        h.said = s -> s >= 30 ? new StepMonitor.Said("Chop", "chop: walking to a stand", 6) : new StepMonitor.Said("Chop", CHOP, 5);
        Z71StepMonitorTest.Planner p = new Z71StepMonitorTest.Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(5, 64, 5, 0)), 0, p);
        Z71StepMonitorTest.run(run, h, p, 100);
        assertEquals(List.of(75), p.at);
        assertTrue(p.facts.get(0).text().contains("last said 'chop: walking to a stand' 45 s ago"), p.facts.get(0).text());
    }

    @Test
    void withoutBaritonesLinesTheMonitorLeavesItToTheOuterWatch() {
        Hands h = new Hands();
        h.said = null;
        h.script = s -> held(Z71StepMonitorTest.FEET, -1);
        Z71StepMonitorTest.Planner p = new Z71StepMonitorTest.Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(5, 64, 5, 0)), 0, p);
        Z71StepMonitorTest.run(run, h, p, 150);
        assertTrue(p.facts.isEmpty(), "no lines known: no silent end (rule 15)");
        Hands n = new Hands();
        n.script = s -> StepSignals.notLoaded("Baritone not loaded");
        Steps r2 = new Steps(n);
        r2.start("goal.iron", List.of(new Step.Walk(5, 64, 5, 0)), 0, new Z71StepMonitorTest.Planner(n));
        for (int s = 1; s <= 60; s++) { n.second = s; r2.tick(s * 20L); }
        assertTrue(r2.running());
        assertFalse(r2.monitorJudges(), "not loaded: the monitor cannot judge, the outer watch fires");
    }

    @Test
    void theSameLineTwiceParksAnotherLineDoesNot() {
        for (int mode = 0; mode < 2; mode++) {
            int m = mode;
            Hands h = new Hands();
            h.script = s -> held(Z71StepMonitorTest.FEET, -1);
            h.said = s -> m == 1 && s > 45 ? new StepMonitor.Said("Chop", "chop: another line", 6) : new StepMonitor.Said("Chop", CHOP, 5);
            Step walk = new Step.Walk(5, 64, 5, 0);
            int[] asked = {0};
            Steps run = new Steps(h);
            run.start("goal.iron", List.of(walk), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(walk), null); });
            for (int s = 1; s <= 100 && run.running(); s++) { h.second = s; run.tick(s * 20L); }
            if (m == 0) {
                assertEquals(Steps.Phase.FAILED, run.phase());
                assertEquals(1, asked[0]);
                assertTrue(run.why().contains("the same way failed twice from the same place"), run.why());
            } else assertEquals(2, asked[0], "another last line is another way of failing: " + run.why());
        }
    }

    /** Run JAJ: two NO_BRANCH ends of the same caving step from the same start cell park only when they ended at the same cell. */
    @Test
    void twoEndsAtDifferentCellsAreTwoTries() {
        for (int mode = 0; mode < 2; mode++) {
            int m = mode;
            int[][] feet = {{-124, 64, -32}};
            boolean[] fail = {false};
            Hands h = new Hands() {
                @Override public int[] feet() { return feet[0]; }
                @Override public Car.Status status(Step s) { return fail[0] ? Car.Status.failed("NO_BRANCH") : Car.Status.running("caving"); }
                @Override public FactChange.Fact lastFact() { return new FactChange.Fact("source cave -124 64 -32", "the caving step ended - NO_BRANCH"); }
            };
            h.said = null;
            Step cave = new Step.Cave(-124, 64, -32, "iron", 4, 10);
            int[] asked = {0};
            Steps run = new Steps(h);
            run.start("goal.iron", List.of(cave), 0, (failed, fact, why) -> {
                asked[0]++;
                feet[0] = new int[]{-124, 64, -32};       // back at the start cell for the next try
                fail[0] = false;
                return new Steps.Routine(List.of(cave), null);
            });
            for (int s = 1; s <= 12 && run.running(); s++) {
                h.second = s;
                if (s == 3) { feet[0] = new int[]{-130, 70, -30}; fail[0] = true; }
                if (s == 6) { feet[0] = m == 0 ? new int[]{-136, 76, -29} : new int[]{-130, 70, -30}; fail[0] = true; }
                run.tick(s * 20L);
            }
            if (m == 0) assertEquals(2, asked[0], "two end cells: two tries, no park: " + run.why());
            else {
                assertEquals(Steps.Phase.FAILED, run.phase());
                assertEquals(1, asked[0]);
            }
        }
    }

    @Test
    void anAttemptsEndCellIsPartOfTheSameTry() {
        var id = io.github.mojolowjo.entropybot.aa.moves.StepId.of(new Step.Walk(1, 2, 3, 0));
        var bag = new java.util.TreeMap<String, Integer>();
        List<Integer> a = List.of(1, 2, 3), b = List.of(4, 5, 6);
        assertTrue(new Steps.Attempt(id, "k", a, bag, b, null).sameAs(new Steps.Attempt(id, "k", a, bag, b, null)));
        assertFalse(new Steps.Attempt(id, "k", a, bag, b, null).sameAs(new Steps.Attempt(id, "k", a, bag, a, null)));
        assertFalse(new Steps.Attempt(id, "k", a, bag, b, "x").sameAs(new Steps.Attempt(id, "k", a, bag, b, "y")));
        assertTrue(new Steps.Attempt(id, "k", a, bag).sameAs(new Steps.Attempt(id, "k", a, bag)));
    }
}
