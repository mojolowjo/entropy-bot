package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepMonitor;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z71 (design D2 3.2, 6 tests 7-12, Job B; plus the design-8 risks): the step monitor in the step machine on fake hands
 * whose signals are scripted per second. Ticks at 20 a second (game fact); Tuning defaults: monitor.everyTicks 20,
 * monitor.stallS 30, monitor.noPathFails 3. Hand numbers: a look each second; the stall is due when (now - last new best)
 * reaches 30 s.
 */
class Z71StepMonitorTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final int[] FEET = {-27, 62, -55};

    /** Signals: goal, feet, executing, failed searches, made, break stage, placed, explored, own wait. */
    static StepSignals sig(double goal, int[] feet, boolean exec, long calcFailed, int made, int stage, long placed, int chunks, String wait) {
        return sigAt(goal, feet, exec, calcFailed, made, stage, null, placed, chunks, wait);
    }

    /** As {@link #sig}, with the cell of the block being broken. */
    static StepSignals sigAt(double goal, int[] feet, boolean exec, long calcFailed, int made, int stage, String cell, long placed, int chunks, String wait) {
        return new StepSignals(true, null, feet, goal, false, exec, false, calcFailed, made, stage, cell, placed, chunks, wait);
    }

    static StepSignals walkSig(double goal) { return sig(goal, FEET, true, 0, -1, -1, 0, -1, null); }

    /** Fake hands: every step runs; its signals at second s from {@code script}; what was cancelled, started, logged. */
    static class Hands implements Steps.Hands {
        IntFunction<StepSignals> script = s -> walkSig(10);
        int second;
        final List<String> started = new ArrayList<>(), cancelled = new ArrayList<>(), logged = new ArrayList<>(), probes = new ArrayList<>();
        Car.Status status = Car.Status.running("on it");

        @Override public String start(Step s) { started.add(s.text()); return null; }
        @Override public Car.Status status(Step s) { return status; }
        @Override public void cancel(String why) { cancelled.add(why); }
        @Override public int[] feet() { return FEET; }
        @Override public void log(String line) { logged.add(line); }
        @Override public void probe(String line) { probes.add(line); }
        @Override public StepSignals signals(Step s) { return script.apply(second); }
        @Override public StepNeeds.Bag bag() {
            return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return 0; }
                @Override public String bestTool(String kind) { return null; }
                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                @Override public int count(String id) { return 0; }
            };
        }
    }

    /** The order's planner in these tests: records each re-plan's fact and step, returns {@code next}. */
    static final class Planner implements Steps.Replanner {
        final List<FactChange.Fact> facts = new ArrayList<>();
        final List<Integer> at = new ArrayList<>();
        final Hands h;
        List<Step> next = List.of(new Step.Wait(60));

        Planner(Hands h) { this.h = h; }

        @Override public Steps.Routine replan(Step failed, FactChange.Fact fact, String why) {
            facts.add(fact);
            at.add(h.second);
            return new Steps.Routine(next, null);
        }
    }

    /** Runs the machine second by second (one look each) from the hands' next second to {@code to} s or until the planner was asked. */
    static void run(Steps run, Hands h, Planner p, int to) {
        for (int s = h.second + 1; s <= to && run.running() && p.facts.isEmpty(); s++) {
            h.second = s;
            run.tick(s * 20L);                   // game fact: 20 ticks a second
        }
    }

    /** Test 7 (walk progress): goal 40, 38, 36 at 0, 1, 2 s, then 36-40 with Baritone executing: CONTINUE to 31 s, REPLAN at 32 s. */
    @Test
    void t7_aWalkStalledAfterItsBestReplansAt32s() {
        Hands h = new Hands();
        h.script = s -> walkSig(s == 0 ? 40 : s == 1 ? 38 : s == 2 ? 36 : 36 + (s % 5));
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        Step walk = new Step.Walk(-27, 16, -55, 0);
        run.start("goal.iron", List.of(walk), 0, p);
        run(run, h, p, 31);
        assertTrue(p.facts.isEmpty(), "no stall through 31 s: " + h.logged);
        run(run, h, p, 40);
        assertEquals(List.of(32), p.at);
        assertEquals("step walk -27 16 -55 near 0", p.facts.get(0).key(), "a walk has no source: keyed by its StepId");
        String why = p.facts.get(0).text();
        assertTrue(why.startsWith("no progress 30 s: the goal "), why);
        assertTrue(why.contains("(best 36 at 2 s)"), why);
        assertTrue(why.endsWith(" at goto -27 16 -55"), why);
        assertEquals(1, h.cancelled.size(), "the walk's hands cancelled once: " + h.cancelled);
        assertTrue(h.logged.stream().anyMatch(l -> l.contains("monitor: no progress 30 s")), h.logged.toString());
    }

    /** Test 8 (no path): CALC_FAILED at 5, 11, 17 s, nothing executing, no new best: REPLAN at 17 s. */
    @Test
    void t8_baritoneFoundNoPathThreeTimes() {
        Hands h = new Hands();
        h.script = s -> sig(20, FEET, false, s >= 17 ? 3 : s >= 11 ? 2 : s >= 5 ? 1 : 0, -1, -1, 0, -1, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.ToStrip(-72, 16, -99, "iron_ore|16|west")), 0, p);
        run(run, h, p, 30);
        assertEquals(List.of(17), p.at);
        assertEquals("source strip iron_ore|16|west", p.facts.get(0).key(), "the strip walk's own source key");
        assertTrue(p.facts.get(0).text().startsWith("Baritone found no path 3 times from -27 62 -55 (goal 20, best 20), nothing executing"), p.facts.get(0).text());
    }

    /** Test 9 (bob, run O): feet y 62 / 63 by turns, goal 7 flat: REPLAN at 30 s (run O: 180 s). */
    @Test
    void t9_aBobIsNoProgress() {
        Hands h = new Hands();
        h.script = s -> sig(7, new int[]{-27, s % 2 == 0 ? 62 : 63, -55}, true, 0, -1, -1, 0, -1, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.torch", List.of(new Step.Craft("furnace", 1)), 0, p);
        run(run, h, p, 40);
        assertEquals(List.of(30), p.at);
        assertEquals("step craft furnace 1", p.facts.get(0).key());
    }

    /** Test 10 (words churn, run P): the status words change every second, nothing else: REPLAN at 30 s. */
    @Test
    void t10_statusWordsAreNoProgress() {
        Hands h = new Hands() {
            @Override public Car.Status status(Step s) { return Car.Status.running("walking to target " + (second * 16) + " 24"); }
        };
        h.script = s -> sig(Double.NaN, FEET, false, 0, -1, -1, 0, 5, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Explore(5, "minecraft:iron_ore")), 0, p);
        run(run, h, p, 40);
        assertEquals(List.of(30), p.at);
        assertTrue(p.facts.get(0).text().contains("no new chunk"), p.facts.get(0).text());
    }

    /** Test 11 (collect complete): 8 cobblestone in the bag by 12 s while the hands still run: COMPLETE at 12 s, one cancel, the next step. */
    @Test
    void t11_aCollectCompletesOnItsCount() {
        Hands h = new Hands();
        h.script = s -> sig(3, FEET, true, 0, Math.min(8, s * 2 / 3), -1, 0, -1, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.furnace", List.of(new Step.Collect("cobblestone", 8), new Step.Craft("furnace", 1)), 0, p);
        for (int s = 1; s <= 11; s++) { h.second = s; run.tick(s * 20L); }
        assertEquals(List.of("collect cobblestone 8"), h.started, "7 of 8 at 11 s: still running");
        h.second = 12;
        run.tick(240);
        assertEquals(List.of("complete: 8 of 8 cobblestone in the bag"), h.cancelled);
        assertEquals(List.of("collect cobblestone 8", "craft furnace 1"), h.started);
        assertTrue(h.logged.contains("step 1/2 collect cobblestone 8: complete: 8 of 8 cobblestone in the bag"), h.logged.toString());
        assertTrue(p.facts.isEmpty());
    }

    /** Test 12 (not loaded, rule 15): not loaded for 60 s: CONTINUE with why, said once, no REPLAN; loaded again: a fresh start. */
    @Test
    void t12_notLoadedIsNeverAStall() {
        Hands h = new Hands();
        h.script = s -> s <= 60 ? StepSignals.notLoaded("Baritone not loaded") : walkSig(10);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(1, 2, 3, 0)), 0, p);
        run(run, h, p, 89);
        assertTrue(p.facts.isEmpty(), "unknown is never a stall; the window starts at the first loaded look (61 s): " + h.logged);
        assertEquals(1, h.logged.stream().filter(l -> l.contains("monitor: signals not loaded: Baritone not loaded")).count(), h.logged.toString());
        run(run, h, p, 100);
        assertEquals(List.of(91), p.at, "61 s + 30 s");
    }

    // ---- design 8 risks ----

    /** A long strip between ores (run U: 251 cells, no ore): only the break stage moves - never cut at 30 s. */
    @Test
    void risk_aStripBetweenOresIsProgressByItsBreakStage() {
        Hands h = new Hands();
        h.script = s -> sigAt(Double.NaN, FEET, false, 0, 0, s % 4 == 3 ? -1 : (s % 4) * 3, "-27 16 " + (-55 - s / 4), 0, -1, null);     // 0, 3, 6, broken; the next cell: 0, ...
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Mine("iron", 3, Step.Mine.How.STRIP)), 0, p);
        run(run, h, p, 120);
        assertTrue(p.facts.isEmpty(), p.facts.toString());
        assertTrue(run.running());
    }

    /** A smelt: the furnace job's own clock (furnaceJobs' due time and stall watch) is its signal; the window starts after it. */
    @Test
    void risk_aSmeltWaitsOnTheFurnacesOwnClock() {
        Hands h = new Hands();
        h.script = s -> sig(Double.NaN, FEET, false, 0, 0, -1, 0, -1, s <= 50 ? "waiting for the furnace at 1 2 3 (iron_ingot 0/3, ready in 20s) (its own watch furnaceJobs.stallMs)" : null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Smelt("minecraft:iron_ingot", 3, false)), 0, p);
        run(run, h, p, 79);
        assertTrue(p.facts.isEmpty(), "no stall while the furnace cooks, nor 30 s after: " + p.facts);
        run(run, h, p, 90);
        assertEquals(List.of(80), p.at, "the last wait at 50 s + 30 s");
    }

    /** COMPLETE never cuts an item job: a craft whose output is in the bag while its job still clicks runs on to its own end. */
    @Test
    void risk_aCraftIsNeverCompletedByTheMonitor() {
        Hands h = new Hands();
        h.script = s -> sig(Double.NaN, FEET, false, 0, 4, -1, 0, -1, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.stick", List.of(new Step.Craft("minecraft:stick", 4)), 0, p);
        run(run, h, p, 10);
        assertTrue(h.cancelled.isEmpty(), h.cancelled.toString());
        assertTrue(run.running());
    }

    /**
     * Composite fork goals may plateau (the priced search goal, DigDownGoal down, ClimbGoal up): the goal heuristic stays 12
     * while the bot digs down (break stage) or pillars up (placed count) - no REPLAN; the same plateau with neither: REPLAN.
     */
    @Test
    void risk_aPlateauedGoalIsCoveredByDiggingAndPlacing() {
        for (int mode = 0; mode < 3; mode++) {
            int m = mode;
            Hands h = new Hands();
            h.script = s -> sigAt(12, FEET, true, 0, -1, m == 0 ? s % 10 : -1, m == 0 ? "-27 " + (61 - s / 10) + " -55" : null, m == 1 ? s : 0, -1, null);     // digging down: a new cell per block
            Planner p = new Planner(h);
            Steps run = new Steps(h);
            run.start("goal.iron", List.of(new Step.ToStrip(-72, 16, -99, "iron_ore|16|west")), 0, p);
            run(run, h, p, 90);
            if (m < 2) assertTrue(p.facts.isEmpty(), "mode " + m + ": " + p.facts);
            else assertEquals(List.of(30), p.at, "nothing digs or places: the plateau is a stall");
        }
    }

    // ---- 0.39.z71 review ----

    /** Review 1: one block's dig that aborts and restarts forever (0, 1, 2, broken off, 0 ...) is no progress after its best (2 at 2 s): REPLAN at 32 s. */
    @Test
    void review_aRestartedBlockIsNoProgress() {
        Hands h = new Hands();
        h.script = s -> sigAt(Double.NaN, FEET, false, 0, 0, s % 4 == 3 ? -1 : s % 4, "5 16 5", 0, -1, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Mine("iron", 3, Step.Mine.How.STRIP)), 0, p);
        run(run, h, p, 60);
        assertEquals(List.of(32), p.at);
        assertTrue(p.facts.get(0).text().contains("last break stage at 2 s"), p.facts.get(0).text());
    }

    /** Review 3: Baritone failed its searches 5 times but still executes a path: no "no path" REPLAN; only the stall at 30 s. */
    @Test
    void review_failedSearchesWhileExecutingAreNoNoPath() {
        Hands h = new Hands();
        h.script = s -> sig(10, FEET, true, s >= 1 ? 5 : 0, -1, -1, 0, -1, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(5, 64, 5, 0)), 0, p);
        run(run, h, p, 40);
        assertEquals(List.of(30), p.at);
        assertTrue(p.facts.get(0).text().startsWith("no progress 30 s"), p.facts.get(0).text());
    }

    /** Review 3: the CALC_FAILED count starts again at a new step: the first walk's 2 failures do not count for the second. */
    @Test
    void review_theNoPathCountStartsAtEachStep() {
        Step a = new Step.Walk(5, 64, 5, 0), b = new Step.Walk(9, 64, 9, 0);
        Hands h = new Hands() {
            @Override public Car.Status status(Step s) { return s.equals(a) && second >= 10 ? Car.Status.done("arrived") : Car.Status.running("walking"); }
        };
        h.script = s -> sig(10, FEET, false, s >= 16 ? 5 : s >= 14 ? 4 : s >= 12 ? 3 : s >= 5 ? 2 : 0, -1, -1, 0, -1, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(a, b), 0, p);
        run(run, h, p, 25);
        assertEquals(List.of(16), p.at, "b started at 10 s with 2 failures behind it: 5 - 2 = 3 at 16 s");
        assertEquals("step walk 9 64 9 near 0", p.facts.get(0).key());
    }

    /** Review 3: the CALC_FAILED count starts again after a pause (the step starts again from where the bot is). */
    @Test
    void review_theNoPathCountStartsAgainAfterAPause() {
        Hands h = new Hands();
        h.script = s -> sig(10, FEET, false, s >= 12 ? 5 : s >= 10 ? 4 : s >= 8 ? 3 : s >= 5 ? 2 : 0, -1, -1, 0, -1, null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(5, 64, 5, 0)), 0, p);
        run(run, h, p, 6);
        run.pause("a creeper near");
        h.second = 7;
        run.resume();                                    // the bests (and the count's start, 2) from here
        run(run, h, p, 20);
        assertEquals(List.of(12), p.at, "without the restart the count would be 3 at 8 s");
    }

    /** Review 3: the explore's first-answer wait in place holds the stall clock; once it ends (its own bound) the window runs. */
    @Test
    void review_theFirstAnswerWaitHoldsTheClockUntilItsBound() {
        Hands h = new Hands();
        h.script = s -> sig(Double.NaN, FEET, false, 0, -1, -1, 0, 5, s <= 30 ? "waiting in place while the first path answers compute (its own bound pathCost.firstAnswerWaitS)" : null);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Explore(5, null, true, 30)), 0, p);
        run(run, h, p, 59);
        assertTrue(p.facts.isEmpty(), p.facts.toString());
        run(run, h, p, 70);
        assertEquals(List.of(60), p.at, "the wait's end at 30 s + 30 s");
    }

    /** Review 3: the band walk's path still computing holds the monitor; the step's own bound (steps.stepMaxS 600 s) ends it as the band's fact. */
    @Test
    void review_theBandsComputingPathEndsAtTheStepsBound() {
        Hands h = new Hands();
        h.script = s -> sig(Double.NaN, FEET, false, 0, -1, -1, 0, -1, "the priced path to -27 16 -55 is not known yet (its own bound steps.stepMaxS)");
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.ToBand(-64, 72)), 0, p);
        run(run, h, p, 600);
        assertTrue(p.facts.isEmpty(), "no monitor verdict while it computes: " + p.facts);
        run(run, h, p, 610);
        assertEquals(List.of(601), p.at);
        assertTrue(p.facts.get(0).key().startsWith("source "), p.facts.get(0).key());
        assertEquals("timed out after 600 s", p.facts.get(0).text());
    }

    /** An exempt kind (a wait stands still on purpose) is never judged. */
    @Test
    void anExemptKindIsNeverJudged() {
        Hands h = new Hands();
        h.script = s -> walkSig(10);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("idle", List.of(new Step.Wait(300)), 0, p);
        run(run, h, p, 120);
        assertTrue(p.facts.isEmpty());
        assertEquals(StepMonitor.Kind.EXEMPT, StepMonitor.kind(new Step.Eat()));
    }

    /** The monitor's REPLAN goes through the honest-loop rule: the same stall from the same cell twice parks the run. */
    @Test
    void theSameStallTwiceParksHonestly() {
        Hands h = new Hands();
        h.script = s -> walkSig(10);
        Step walk = new Step.Walk(5, 64, 5, 0);
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(walk), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(walk), null); });
        for (int s = 1; s <= 70 && run.running(); s++) { h.second = s; run.tick(s * 20L); }
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertEquals(1, asked[0]);
        assertTrue(run.why().contains("the same way failed twice from the same place with the same bag: goto 5 64 5 - no progress 30 s: "), run.why());
    }

    /** debug.monitorProbe 1: a line per verdict change and per stalled look; off: none. */
    @Test
    void theProbeLines() {
        Hands h = new Hands();
        h.script = s -> walkSig(10);
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(5, 64, 5, 0)), 0, p);
        run(run, h, p, 3);
        assertTrue(h.probes.isEmpty(), "off by default");
        io.github.mojolowjo.entropybot.aa.brain.Tuning.set("debug.monitorProbe", 1);
        try {
            run(run, h, p, 5);
            assertEquals(2, h.probes.size(), h.probes.toString());
            assertTrue(h.probes.get(0).startsWith("t=80 feet -27 62 -55 | goto goto 5 64 5 | CONTINUE | goal 10 best 10 at 0 s | made -1 | stage -1 | placed 0 | calcFailed 0 | executing y - no new best for 4 s"), h.probes.get(0));
        } finally {
            io.github.mojolowjo.entropybot.aa.brain.Tuning.set("debug.monitorProbe", 0);
        }
    }

    /** Fake hands with no signals (every test before z71): the monitor is off, nothing changes. */
    @Test
    void noSignalsMeansTheMonitorIsOff() {
        List<String> cancelled = new ArrayList<>();
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) { return Car.Status.running("on it"); }
            @Override public void cancel(String why) { cancelled.add(why); }
        });
        run.start("k", List.of(new Step.Walk(1, 2, 3, 0)), 0, (f, fact, why) -> fail("no re-plan without signals"));
        for (int s = 1; s <= 120; s++) run.tick(s * 20L);
        assertTrue(run.running());
        assertTrue(cancelled.isEmpty());
    }
}
