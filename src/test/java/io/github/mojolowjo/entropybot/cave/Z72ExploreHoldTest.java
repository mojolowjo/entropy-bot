package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.cave.ExploreRules.Target;
import io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold;
import io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.Held;
import io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z72 (design D2 3.3, 6 test 13, Job C; run P 00:04:15-54: a new explore target -264 24, -248 24, -232 24 ... one per
 * CALC_FAILED, the feet at -148 -33 -83 for 36+ s, each unreached chunk marked explored). Ticks at 20 a second (game fact);
 * Tuning defaults monitor.noPathFails 3, monitor.stallS 30, failures.moved 16.
 */
class Z72ExploreHoldTest {
    @AfterEach
    void clear() { FactChange.clear(); }

    static final int[] RUN_P = {-148, -33, -83};
    static final Target A = new Target(-264, 24), B = new Target(-248, 24);

    /** Baritone's failed searches by second: 5, 11, 17 (A), then 22, 28, 34 (B). */
    static long calc(int s) {
        int n = 0;
        for (int at : new int[]{5, 11, 17, 22, 28, 34}) if (s >= at) n++;
        return n;
    }

    /**
     * Test 13 (run P): A fails at 17 s on three failed searches (not marked explored), B is chosen and would fail at 34 s;
     * the explore step's monitor REPLANs at 30 s (no chunk, no new best since 0 s: B's goal is nearer than A's by 11 m, but
     * another goal is no progress). One target change before the REPLAN (run P: ~36 in 36 s). 0.39.z76 send-back 2: the
     * failed searches count across the re-target, so the REPLAN is at 17 s ("no path 3 times").
     */
    @Test
    void t13_runPTheHeldTargetFailsOnceAndTheStepRePlans() {
        // the hold, second by second, as Mining.exploreStep runs it
        List<Integer> changes = new ArrayList<>();
        List<String> said = new ArrayList<>();
        Held h = TargetHold.start(A, 0, RUN_P, calc(0));
        Target[] goal = new Target[61];
        Target cur = A;
        for (int s = 1; s <= 60; s++) {
            h = TargetHold.look(h, s * 20L, RUN_P, calc(s), false);
            if (h.state() == State.FAILED) {
                assertFalse(h.marksExplored(false), "rule 15: never reached is never explored");
                said.add(TargetHold.failedLine(h));
                if (cur == A) {
                    cur = B;
                    changes.add(s);
                    h = TargetHold.start(B, s * 20L, RUN_P, calc(s));
                } else cur = null;
            }
            goal[s] = cur;
        }
        goal[0] = A;
        assertEquals("explore: target -264 24 failed: Baritone found no path 3 times from -148 -33 -83", said.get(0));
        assertEquals("explore: target -248 24 failed: Baritone found no path 3 times from -148 -33 -83", said.get(1));
        assertEquals(List.of(17), changes);

        // the step monitor on the explore step: the goal distance of the held target, its own id; no chunk gained
        int[] second = {0};
        List<FactChange.Fact> facts = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) { return Car.Status.running("walking to target " + goal[second[0]]); }
            @Override public void cancel(String why) {}
            @Override public int[] feet() { return RUN_P; }
            @Override public StepSignals signals(Step s) {
                Target t = goal[second[0]];
                double d = t == null ? Double.NaN : Math.hypot(RUN_P[0] - t.x(), RUN_P[2] - t.z());
                return new StepSignals(true, null, RUN_P, d, t == null ? null : "GoalXZ{x=" + t.x() + ",z=" + t.z() + "}", false, false, false, calc(second[0]),
                        -1, -1, null, 0, 0, null);
            }
        });
        run.start("goal.iron", List.of(new Step.Explore(5, "minecraft:iron_ore")), 0, (failed, fact, why) -> {
            facts.add(fact);
            at.add(second[0]);
            return new Steps.Routine(List.of(new Step.Wait(5)), null);
        });
        for (int s = 1; s <= 40 && facts.isEmpty(); s++) {
            second[0] = s;
            run.tick(s * 20L);
        }
        // 0.39.z76 send-back 2 (run AO 20:37; was: the REPLAN at 30 s, the count started again on B): a re-target without a
        // move is no new question - the failed searches count on (5, 11, 17 s), so the REPLAN comes at 17 s on "no path 3
        // times", keyed by the position (the frozen explore held there)
        assertEquals(List.of(17), at, "the REPLAN at 17 s: " + facts);
        assertTrue(facts.get(0).text().startsWith("Baritone found no path 3 times from -148 -33 -83"), facts.get(0).text());
        assertEquals("source " + io.github.mojolowjo.entropybot.aa.NeedChain.EXPLORE_FROZEN + "-148 -33 -83", facts.get(0).key());
        assertEquals(1, changes.stream().filter(c -> c <= 17).count(), "one target change by the REPLAN");
    }

    /** Test 13, the skip: A is passed over from where it failed until the bot stands failures.moved (16) blocks away. */
    @Test
    void t13_aFailedTargetIsSkippedUntilTheBotMoved16() {
        List<TargetHold.Failed> failed = List.of(new TargetHold.Failed(A.x(), A.z(), RUN_P, "Baritone found no path 3 times from -148 -33 -83"));
        int cx = A.x() >> 4, cz = A.z() >> 4;
        assertEquals(-17, cx);
        assertEquals(1, cz);
        double moved = io.github.mojolowjo.entropybot.aa.brain.Failures.movedV();
        assertEquals(16, moved);
        assertTrue(TargetHold.skip(failed, cx, cz, RUN_P, moved));
        assertTrue(TargetHold.skip(failed, cx, cz, new int[]{-148 + 15, -33, -83}, moved), "15 blocks: still from about the same place");
        assertFalse(TargetHold.skip(failed, cx, cz, new int[]{-148 + 16, -33, -83}, moved), "16 blocks away: another question");
        assertFalse(TargetHold.skip(failed, B.x() >> 4, B.z() >> 4, RUN_P, moved), "another chunk is not skipped");
        assertTrue(TargetHold.skip(failed, cx, cz, null, moved), "rule 15: unknown feet - still skipped");
    }

    /** A REACHED target still marks its chunk explored (as before). */
    @Test
    void aReachedTargetMarksExplored() {
        Held h = TargetHold.start(A, 0, RUN_P, 0);
        h = TargetHold.look(h, 20, new int[]{A.x() - 30, 64, A.z()}, 0, false);
        assertEquals(State.HELD, h.state());
        h = TargetHold.look(h, 40, new int[]{A.x() - ExploreRules.arrivedV(), 64, A.z()}, 0, false);
        assertEquals(State.REACHED, h.state());
        assertTrue(h.marksExplored(false));
        assertFalse(h.marksExplored(true), "review 1: a cave candidate reached ends as visited, its chunk not marked (as before z72)");
        assertEquals(h, TargetHold.look(h, 60, RUN_P, 9, false), "an ended hold stays as it is");
    }

    /**
     * Baritone idle alone does not fail a held target (the adapter sets the same goal again): the feet still, no failed
     * search - held through 29 s, FAILED at 30 s by monitor.stallS only ("no new best for 30 s").
     */
    @Test
    void baritoneIdleAloneDoesNotFailAHeldTarget() {
        Held h = TargetHold.start(A, 0, RUN_P, 7);
        for (int s = 1; s <= 29; s++) {
            h = TargetHold.look(h, s * 20L, RUN_P, 7, false);
            assertEquals(State.HELD, h.state(), "at " + s + " s");
        }
        h = TargetHold.look(h, 600, RUN_P, 7, false);
        assertEquals(State.FAILED, h.state());
        assertEquals("no new best for 30 s from -148 -33 -83 (best 158 m)", h.why());
    }

    /** Review 2: failed searches while Baritone still executes a path (another goal's, the count is global) never fail the target. */
    @Test
    void failedSearchesWhileExecutingDoNotFailTheTarget() {
        Held h = TargetHold.start(A, 0, RUN_P, 0);
        h = TargetHold.look(h, 20, RUN_P, 5, true);
        assertEquals(State.HELD, h.state(), h.why());
        assertEquals(5, h.noPathFails());
        h = TargetHold.look(h, 40, RUN_P, 5, false);
        assertEquals(State.FAILED, h.state(), "nothing executing now: the same count fails it");
    }

    /** A new best (1 m nearer) starts the failed-search count again. */
    @Test
    void aNewBestStartsTheNoPathCountAgain() {
        Held h = TargetHold.start(A, 0, RUN_P, 0);
        h = TargetHold.look(h, 20, RUN_P, 2, false);
        h = TargetHold.look(h, 40, new int[]{-150, -33, -83}, 2, false);       // 157.8 -> 156.3 m: more than 1 block nearer
        assertEquals(State.HELD, h.state());
        h = TargetHold.look(h, 60, new int[]{-150, -33, -83}, 4, false);
        assertEquals(State.HELD, h.state(), "2 since the best");
        assertEquals(2, h.noPathFails());
        h = TargetHold.look(h, 80, new int[]{-150, -33, -83}, 5, false);
        assertEquals(State.FAILED, h.state());
    }
}
