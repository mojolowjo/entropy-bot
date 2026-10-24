package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** needs-ruling 0.39.z32 (NEEDS_RULING 1, SCENARIOS S32/S33): only food and danger over their worry line break an order. */
class WorryTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());
    final double[] s = new double[4];

    @AfterEach
    void off() { Only.set("off"); Tuning.reset(); }

    Desire d(int i, String id, Desire.Kind k) {
        return new Desire(id, k, RunJob.of(id + ".job", TaskKind.WAIT, java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Obtain("do " + id, 1)), "brain"), b -> s[i]).cost(0);
    }

    String last() { return port.started.get(port.started.size() - 1).chain(); }

    Brain withOrderRunning() {
        Brain br = new Brain("brain", "t", d(0, "goal.planks", Desire.Kind.OWNER), d(1, "needs.food", Desire.Kind.UPKEEP).preemptsOrder("needs.worryFood"),
                d(2, AaDesires.DANGER_ID, Desire.Kind.UPKEEP).preemptsOrder("needs.worryDanger"), d(3, "needs.torches", Desire.Kind.UPKEEP));
        s[0] = 60;
        s[1] = -1;
        s[2] = -1;
        s[3] = -1;
        br.tick(bb);
        assertEquals("obtain 1 do goal.planks", last());
        return br;
    }

    /** S33: hunger under the worry line (and a torch need, danger under its line) never interrupts the order. */
    @Test
    void s33BelowTheWorryLineNothingInterruptsTheOrder() {
        Brain br = withOrderRunning();
        s[1] = 59;          // needs.worryFood 60
        s[2] = 74;          // needs.worryDanger 75
        s[3] = 90;
        for (int i = 0; i < 5; i++) { clock.advance(1000); br.tick(bb); }
        assertEquals(1, port.started.size());
        var all = br.scoreAll(bb);
        assertEquals("not wanted: an order runs (worry needs.food 59 < 60)", all.get(1).skip());
        assertEquals("not wanted: an order runs (worry danger 74 < 75)", all.get(2).skip());
        assertEquals("not wanted: an order runs (only food and danger preempt it)", all.get(3).skip());
        assertTrue(br.orderGated());
    }

    /** S32: hunger crosses the line: the food need preempts the step; its task done, the order is picked again. */
    @Test
    void s32HungerOverTheLinePreemptsAndTheOrderResumes() {
        Brain br = withOrderRunning();
        s[1] = 65;
        br.tick(bb);
        assertEquals("obtain 1 do needs.food", last());
        assertTrue(br.scoreAll(bb).get(1).parts().contains("worry needs.food 65 >= 60"), br.scoreAll(bb).get(1).parts());
        s[1] = -1;          // ate: the bar is up
        port.done(2, "done");
        br.tick(bb);
        br.tick(bb);
        assertEquals("obtain 1 do goal.planks", last(), "the order runs again (its chain priced from the bag of now)");
    }

    @Test
    void dangerOverItsLinePreemptsTheOrder() {
        Brain br = withOrderRunning();
        s[2] = 80;
        br.tick(bb);
        assertEquals("obtain 1 do danger", last());
    }

    /** 1b: no order: the needs score on their own as before. */
    @Test
    void withNoOrderTheNeedsRunOnTheirOwn() {
        Brain br = new Brain("brain", "t", d(0, "goal.planks", Desire.Kind.OWNER), d(3, "needs.torches", Desire.Kind.UPKEEP));
        s[0] = -1;
        s[3] = 30;
        br.tick(bb);
        assertEquals("obtain 1 do needs.torches", last());
        assertFalse(br.orderGated());
    }

    /** 1c: aa needs off (orders only) is Only's as before: food at 80 is silent there, the worry line plays no part. */
    @Test
    void needsOffKeepsOrdersOnly() {
        Only.set(Only.ORDERS);
        assertFalse(Worry.gates(true));
        assertEquals(0, Only.apply("needs.food", Desire.Kind.UPKEEP, 80), 1e-9);
    }

    @Test
    void theLinesAreTuningKnobs() {
        Tuning.set("needs.worryFood", 30);
        assertTrue(Worry.over(d(1, "needs.food", Desire.Kind.UPKEEP).preemptsOrder("needs.worryFood"), 30));
        assertFalse(Worry.over(d(3, "needs.torches", Desire.Kind.UPKEEP), 99));
        assertNull(Worry.silences(d(0, "goal.x", Desire.Kind.OWNER), 50));
        assertNull(Worry.silences(d(3, "needs.bed", Desire.Kind.PROGRESS), 100), "the owner's aa do of a need stays");
    }
}
