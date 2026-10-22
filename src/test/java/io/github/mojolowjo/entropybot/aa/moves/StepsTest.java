package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** rm-execution: the one step machine: a failed step ends the option FAILED with its stage, reason and key; no retry. */
class StepsTest {
    /** Fake hands: each step's scripted statuses, the starts and cancels it saw. */
    static final class Fake implements Steps.Hands {
        final Map<String, List<Car.Status>> script = new HashMap<>();
        final Map<String, String> refuse = new HashMap<>();
        final List<String> started = new ArrayList<>(), cancelled = new ArrayList<>();

        @Override public String start(Step s) {
            started.add(s.text());
            return refuse.get(s.text());
        }

        @Override public Car.Status status(Step s) {
            List<Car.Status> l = script.get(s.text());
            if (l == null || l.isEmpty()) return Car.Status.running("on it");
            return l.size() == 1 ? l.get(0) : l.remove(0);
        }

        @Override public void cancel(String why) { cancelled.add(why); }
    }

    static final Step WALK = new Step.Walk(1, 64, 2, 0), CUT = new Step.ChopTree(new int[]{1, 64, 2}, 8, null), CRAFT = new Step.Craft("oak_planks", 4);

    @Test
    void allStepsDoneEndsDone() {
        Fake h = new Fake();
        h.script.put(WALK.text(), new ArrayList<>(List.of(Car.Status.done("arrived"))));
        h.script.put(CUT.text(), new ArrayList<>(List.of(Car.Status.running("2 of 8"), Car.Status.done("got 8 logs"))));
        h.script.put(CRAFT.text(), new ArrayList<>(List.of(Car.Status.done("crafted"))));
        Steps s = new Steps(h);
        assertEquals(Steps.Phase.RUNNING, s.start("chop at 1 64 2", List.of(WALK, CUT, CRAFT), 0));
        for (int t = 1; t < 10 && s.running(); t++) s.tick(t);
        assertEquals(Steps.Phase.DONE, s.phase());
        assertEquals(List.of(WALK.text(), CUT.text(), CRAFT.text()), h.started);
        assertNull(s.failedKey());
    }

    @Test
    void aFailedStepEndsTheOptionWithItsKeyAndNoRetry() {
        Fake h = new Fake();
        h.script.put(WALK.text(), new ArrayList<>(List.of(Car.Status.done("arrived"))));
        h.script.put(CUT.text(), new ArrayList<>(List.of(Car.Status.failed("Baritone's mine process ended with no log in reach"))));
        Steps s = new Steps(h);
        s.start("chop at 1 64 2", List.of(WALK, CUT, CRAFT), 0);
        for (int t = 1; t < 20; t++) s.tick(t);
        assertEquals(Steps.Phase.FAILED, s.phase());
        assertEquals("chop at 1 64 2", s.failedKey(), "the option's key is charged in Failures");
        assertTrue(s.why().startsWith("stopped at step 2 (cut 8 at 1 64 2): "), s.why());
        assertTrue(s.why().contains("no log in reach"), s.why());
        assertEquals(1, h.started.stream().filter(CUT.text()::equals).count(), "no retry of the failed step");
        assertFalse(h.started.contains(CRAFT.text()), "nothing after a failed step runs");
    }

    @Test
    void aRefusedStartFailsAtOnce() {
        Fake h = new Fake();
        h.refuse.put(WALK.text(), "error: the policy refuses the walk there");
        Steps s = new Steps(h);
        assertEquals(Steps.Phase.FAILED, s.start("k", List.of(WALK, CUT), 0));
        assertEquals("stopped at step 1 (goto 1 64 2): the policy refuses the walk there", s.why());
        assertEquals(List.of(WALK.text()), h.started);
        assertEquals("k", s.failedKey());
    }

    @Test
    void aStepPastItsLimitTimesOut() {
        Fake h = new Fake();
        Steps s = new Steps(h);
        s.start("k", List.of(CUT), 0);
        s.tick(Steps.stepTicks() + 1);
        assertEquals(Steps.Phase.FAILED, s.phase());
        assertTrue(s.why().contains("timed out"), s.why());
        assertEquals(1, h.cancelled.size());
    }

    @Test
    void cancelEndsFailedAndCallsTheHands() {
        Fake h = new Fake();
        Steps s = new Steps(h);
        s.start("k", List.of(WALK), 0);
        s.cancel("the owner's stop");
        assertEquals(Steps.Phase.FAILED, s.phase());
        assertTrue(s.why().endsWith("cancelled: the owner's stop"));
        assertEquals(List.of("the owner's stop"), h.cancelled);
    }

    @Test
    void theLabelIsNoChainText() {
        String l = Step.label(List.of(WALK, CUT));
        assertFalse(l.contains(" then "), l);
        assertEquals("goto 1 64 2 > cut 8 at 1 64 2", l);
    }
}
