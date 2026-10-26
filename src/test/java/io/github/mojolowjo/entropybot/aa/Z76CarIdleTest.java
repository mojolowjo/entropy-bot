package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z76 J3-mod item 0 ("car has no goal"; run AL 21:06:51 "probe stall: process INACTIVE, goal null", run AK
 * 20:49:42-20:50:10: the goal null 1-5 ticks after each CALC_FAILED, set again by the explore): a step that drives the car
 * and whose hands report the car idle with no goal for steps.carIdleTicks (80) in a row ends at once with Baritone's own
 * reason and re-plans on its fact - not after the monitor's 30 s; a car between paths for 1-2 ticks never ends a step; a
 * craft is never judged. Ticks are client ticks (20 a second, game fact); the fake hands give no monitor signals.
 */
class Z76CarIdleTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final String NEVER = Steps.carIdleReason(null, null, 0, null);

    /** Fake hands: every step runs; the car's idle reason at tick t from {@code idle} (null: it drives). */
    static final class Hands implements Steps.Hands {
        LongFunction<String> idle = t -> null;
        long tick;
        final List<String> cancelled = new ArrayList<>(), logged = new ArrayList<>();

        @Override public String start(Step s) { return null; }
        @Override public Car.Status status(Step s) { return Car.Status.running("on it"); }
        @Override public void cancel(String why) { cancelled.add(why); }
        @Override public void log(String line) { logged.add(line); }
        @Override public String carIdle(Step s) { return idle.apply(tick); }
    }

    static final class Planner implements Steps.Replanner {
        final List<FactChange.Fact> facts = new ArrayList<>();
        final List<Long> at = new ArrayList<>();
        final Hands h;

        Planner(Hands h) { this.h = h; }

        @Override public Steps.Routine replan(Step failed, FactChange.Fact fact, String why) {
            facts.add(fact);
            at.add(h.tick);
            return new Steps.Routine(List.of(new Step.Wait(60)), null);
        }
    }

    /** Ticks the run from 1 to {@code to} (each client tick) or until the planner was asked. */
    static void run(Steps run, Hands h, Planner p, long to) {
        for (long t = h.tick + 1; t <= to && run.running() && p.facts.isEmpty(); t++) {
            h.tick = t;
            run.tick(t);
        }
    }

    /**
     * A walk whose car has no goal from tick 100 on (the goal never set): idle at 100, ended at 100 + 80 = 180 (9 s into the
     * step, not 30 s), the hands cancelled once, the fact keyed by the walk's StepId, Baritone's reason in its words.
     */
    @Test
    void aCarIdleWithNoGoalEndsTheStepAtOnce() {
        Hands h = new Hands();
        h.idle = t -> t >= 100 ? NEVER : null;
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(-27, 16, -55, 0)), 0, p);
        run(run, h, p, 179);
        assertTrue(p.facts.isEmpty(), "idle 79 ticks: no end yet " + h.logged);
        run(run, h, p, 2000);
        assertEquals(List.of(180L), p.at, "idle at 100 + steps.carIdleTicks 80");
        FactChange.Fact f = p.facts.get(0);
        assertEquals("step walk -27 16 -55 near 0", f.key(), "a walk has no source: keyed by its StepId");
        assertTrue(f.text().startsWith("the car has no goal: Baritone idle with no goal for 80 ticks (steps.carIdleTicks 80): the goal was never set"), f.text());
        assertTrue(f.text().endsWith(" at goto -27 16 -55"), f.text());
        assertEquals(1, h.cancelled.size(), h.cancelled.toString());
        assertTrue(h.logged.stream().anyMatch(l -> l.contains("the car has no goal")), h.logged.toString());
    }

    /** The car between paths: idle 2 ticks in every 40 (a CALC_FAILED, the explore's goal set again) for 100 s - never an end. */
    @Test
    void aCarBetweenPathsForTwoTicksNeverEnds() {
        Hands h = new Hands();
        String dropped = Steps.carIdleReason("GoalXZ{x=152,z=-248}", "CustomGoalProcess (inactive)", 1, "CALC_FAILED 1 ticks ago");
        h.idle = t -> t % 40 == 0 || t % 40 == 1 ? dropped : null;
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Explore(5, "raw_iron")), 0, p);
        run(run, h, p, 2000);
        assertTrue(p.facts.isEmpty(), "never ended: " + h.logged);
        assertTrue(run.running());
        assertTrue(h.cancelled.isEmpty(), h.cancelled.toString());
    }

    /** One tick short of the bound, then driving again, then idle again: the clock starts again each time (no sum). */
    @Test
    void theIdleClockStartsAgainWhenTheCarDrives() {
        Hands h = new Hands();
        h.idle = t -> (t >= 10 && t < 89) || (t >= 100 && t < 179) ? NEVER : null;      // 79 ticks idle twice
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Walk(1, 64, 1, 0)), 0, p);
        run(run, h, p, 400);
        assertTrue(p.facts.isEmpty(), "79 + 79 idle ticks with a drive between: no end " + h.logged);
    }

    /** A craft is an item job (no car): its hands' idle report is never judged. */
    @Test
    void aCraftIsNeverJudged() {
        Hands h = new Hands();
        h.idle = t -> NEVER;
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Craft("iron_pickaxe", 1)), 0, p);
        run(run, h, p, 1000);
        assertTrue(p.facts.isEmpty() && run.running(), h.logged.toString());
    }

    /** A cut's end carries its tree as the source fact (the order re-plans without that tree). */
    @Test
    void aCutWithAnIdleCarIsItsTreesFact() {
        Hands h = new Hands();
        h.idle = t -> NEVER;
        Planner p = new Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.ChopTree(new int[]{10, 64, 0}, 4, null)), 0, p);
        run(run, h, p, 1000);
        assertEquals(List.of(81L), p.at, "idle from tick 1: ended at 81");
        assertEquals(FactChange.noProgress("cut at 10 64 0", "x").key(), p.facts.get(0).key());
    }

    /** Baritone's reason, three ways: never set; dropped after a failed search; ended with no failed search. */
    @Test
    void baritonesOwnReason() {
        assertEquals("the goal was never set (no Baritone process took this step; no search result seen)", NEVER);
        assertEquals("CustomGoalProcess (inactive) dropped its goal GoalXZ{x=-771,z=-277} after a failed search (3 CALC_FAILED since it was set) and nothing set it again",
                Steps.carIdleReason("GoalXZ{x=-771,z=-277}", "CustomGoalProcess (inactive)", 3, "CALC_FAILED 2 ticks ago"));
        assertEquals("ExploreProcess (inactive) ended with its goal GoalXZ{x=1,z=2} and nothing set it again (no failed search; Baritone's last search result CALC_FINISHED_NOW_EXECUTING 40 ticks ago)",
                Steps.carIdleReason("GoalXZ{x=1,z=2}", "ExploreProcess (inactive)", 0, "CALC_FINISHED_NOW_EXECUTING 40 ticks ago"));
    }
}
