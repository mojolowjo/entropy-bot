package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z56 item 2a (run O 22:15:20-22:17:43: "probe walk:" lines went on after the iron order stopped, 790 -> 3197): the
 * strip leg's walk step FAILED twice ("walk ended at y 61 short of y 16"), the run ended on "the same step failed again"
 * with no cancel, and the walk probe's step pointer stayed set. The step machine now tells its hands on every end path;
 * a fake hand keeps a pointer like the probe's (set at start, dropped at ended) and it is null after every end.
 */
class Z56StepEndTest {
    /** Fake hands with a probe-like pointer: the running step's text, null once told the step ended. */
    static final class Hands implements Steps.Hands {
        String pointer;
        Car.Status next = Car.Status.running("on it");
        String refuse;
        final List<String> ends = new ArrayList<>();

        @Override public String start(Step s) {
            pointer = s.text();                  // StepHands.start0 sets the walk probe's step before anything can refuse
            return refuse;
        }

        @Override public Car.Status status(Step s) { return next; }

        @Override public void cancel(String why) {}

        @Override public void ended(String how) {
            pointer = null;
            ends.add(how);
        }
    }

    static final Step WALK = new Step.Walk(-27, 16, -55, 0), CRAFT = new Step.Craft("furnace", 1);

    @Test
    void aFailedStepWithNoCancelDropsThePointer() {
        Hands h = new Hands();
        Steps s = new Steps(h);
        s.start("strip", List.of(WALK, CRAFT), 0);
        assertEquals(WALK.text(), h.pointer);
        h.next = Car.Status.failed("Baritone's walk ended at y 61 short of y 16 (no path down to the band)");
        s.tick(1);
        assertEquals(Steps.Phase.FAILED, s.phase());
        assertNull(h.pointer, "run O: the failed walk step's pointer stayed set and its probe kept writing");
        assertTrue(h.ends.contains("failed"), h.ends.toString());
    }

    @Test
    void aDoneStepDropsItAndTheNextTakesIt() {
        Hands h = new Hands();
        Steps s = new Steps(h);
        s.start("k", List.of(WALK, CRAFT), 0);
        h.next = Car.Status.done("arrived");
        s.tick(1);
        assertEquals(CRAFT.text(), h.pointer, "done, then the next step started");
        s.tick(2);
        assertEquals(Steps.Phase.DONE, s.phase());
        assertNull(h.pointer);
    }

    @Test
    void aRefusedStartDropsIt() {
        Hands h = new Hands();
        h.refuse = "error: not in a world";
        Steps s = new Steps(h);
        assertEquals(Steps.Phase.FAILED, s.start("k", List.of(WALK), 0));
        assertNull(h.pointer);
    }

    @Test
    void cancelNoProgressAndTimeoutDropIt() {
        Hands h = new Hands();
        Steps s = new Steps(h);
        s.start("k", List.of(WALK), 0);
        s.cancel("aa off");
        assertNull(h.pointer, "the owner's stop / aa off");

        s.start("k", List.of(WALK), 0);
        s.noProgress("no progress 180 s");
        assertNull(h.pointer, "the no-progress watch");

        s.start("k", List.of(WALK), 0);
        s.tick(Steps.stepTicks() + 5);
        assertEquals(Steps.Phase.FAILED, s.phase(), s.why());
        assertNull(h.pointer, "timed out");

        s.start("k", List.of(WALK), 0);
        s.clear();
        assertNull(h.pointer, "cleared");
        assertTrue(Tuning.get("steps.stepMaxS") > 0);
    }
}
