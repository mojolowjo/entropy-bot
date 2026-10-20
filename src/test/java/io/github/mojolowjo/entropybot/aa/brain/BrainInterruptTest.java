package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import io.github.mojolowjo.entropybot.aa.Rung;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND2 2: interrupt only what can start; a false interrupt cools its challenger; 2 interrupts a minute at most. */
class BrainInterruptTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Tracer tracer = new Tracer();
    final Blackboard bb = new Blackboard(clock, port, tracer);
    final double[] s = new double[4];

    Desire d(int i, String id, Desire.Kind k) {
        return new Desire(id, k, RunJob.of(id + ".job", TaskKind.WAIT, "do " + id, "brain"), b -> s[i]).cost(0);
    }

    String last() { return port.started.get(port.started.size() - 1).chain(); }

    @Test
    void aChallengerThatCannotStartNeverHalts() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS), d(1, "food", Desire.Kind.UPKEEP).probe(b -> "no plan"));
        s[0] = 40;
        br.tick(bb);
        s[1] = 80;
        for (int i = 0; i < 20; i++) {
            clock.advance(250);
            assertEquals(Status.RUNNING, br.tick(bb));
        }
        assertEquals(0, br.interrupts);
        assertEquals(1, port.started.size());
        assertTrue(String.join("\n", br.whyLines(5)).contains("would interrupt, but: no plan"), br.whyLines(5).toString());
    }

    @Test
    void aFalseInterruptCoolsTheChallengerAndTheOldDesireRunsAgain() {
        Node fails = new Node("food.node", "fails") {
            @Override protected Status run(Blackboard b) { return fail("no food anywhere"); }
        };
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS), new Desire("food", Desire.Kind.UPKEEP, fails, b -> s[1]));
        s[0] = 40;
        br.tick(bb);
        s[1] = 80;
        assertEquals(Status.RUNNING, br.tick(bb));
        assertEquals(1, br.interrupts);
        assertEquals(1, br.falseInterrupts);
        assertEquals(1, tracer.counts.pointless);
        assertEquals(1, br.streak("food"));
        assertEquals("do a", last(), "the interrupted desire runs again");
        assertEquals(2, port.started.size());
        assertTrue(port.log.stream().anyMatch(l -> l.startsWith("brain: false interrupt by food")), port.log.toString());
        for (int i = 0; i < 20; i++) {
            clock.advance(250);
            br.tick(bb);
        }
        assertEquals(1, br.interrupts, "the challenger cools down");
    }

    @Test
    void aThirdInterruptWithinAMinuteWaits() {
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS), d(1, "food", Desire.Kind.UPKEEP));
        s[0] = 40;
        br.tick(bb);
        for (int k = 0; k < 2; k++) {
            s[1] = 80;
            br.tick(bb);                              // food interrupts a
            assertEquals("do food", last());
            port.done(port.nextId - 1, "done");
            s[1] = -1;
            br.tick(bb);                              // food ends
            br.tick(bb);                              // a runs again
            assertEquals("do a", last());
        }
        assertEquals(2, br.interrupts);
        s[1] = 80;
        br.tick(bb);
        assertEquals("do a", last(), "over the cap: waits");
        assertTrue(String.join("\n", br.whyLines(5)).contains("interrupt cap"), br.whyLines(5).toString());
        for (int i = 0; i < 60; i++) {
            clock.advance(1000);
            port.progress.put(port.nextId - 1, (long) i);
            br.tick(bb);
        }
        assertEquals(3, br.interrupts, "a minute later it may");
        assertEquals("do food", last());
    }

    @Test
    void starvingUpkeepIsExemptFromTheCapButNotFromTheProbe() {
        String[] probe = {null};
        Brain br = new Brain("brain", "t", d(0, "a", Desire.Kind.PROGRESS), d(1, "food", Desire.Kind.UPKEEP).probe(b -> probe[0]));
        s[0] = 40;
        br.tick(bb);
        for (int k = 0; k < 2; k++) {
            s[1] = 80;
            br.tick(bb);
            port.done(port.nextId - 1, "done");
            s[1] = -1;
            br.tick(bb);
            br.tick(bb);
        }
        assertEquals(2, br.interrupts);
        probe[0] = "no food source";
        s[1] = 95;
        clock.advance(Brain.PROBE_HOLD_MS);
        br.tick(bb);
        assertEquals(2, br.interrupts, "exempt from the cap, still probed");
        assertEquals("do a", last());
        probe[0] = null;
        clock.advance(Brain.PROBE_HOLD_MS);
        port.progress.put(port.nextId - 1, 1L);
        br.tick(bb);
        assertEquals(3, br.interrupts, "starving passes the cap");
        assertEquals("do food", last());
    }

    /** The overnight 2026-10-19 thrash: food 77 with no source interrupted explore every 0.25 s. Now: never. */
    @Test
    void overnightFoodWithNoSourceNeverInterruptsExplore() {
        port.noPlan = "no food source known";
        Rung food = new Rung("needs.food", TaskKind.FOOD, b -> true, b -> null, b -> "goal food 8");
        Desire foodD = AaDesires.wire(new Desire("needs.food", Desire.Kind.UPKEEP, food, b -> 77), food);
        Desire explore = new Desire("idle.explore", Desire.Kind.UPKEEP, RunJob.of("explore.job", TaskKind.EXPLORE, "explore 5", "brain"), b -> 15);
        Brain br = new Brain("brain", "t", explore, foodD);
        // explore is running when food turns urgent (the food rung cannot plan: it is passed over at the first pick)
        br.tick(bb);
        assertEquals("explore 5", last());
        for (int i = 0; i < 600; i++) {
            clock.advance(250);
            port.progress.put(port.nextId - 1, (long) i);
            assertEquals(Status.RUNNING, br.tick(bb));
        }
        assertEquals(0, br.interrupts);
        assertEquals(0, br.falseInterrupts);
        assertEquals(1, port.started.size());
    }

    @Test
    void aRunningDesireWhoseReasonIsGoneEnds() {
        Brain br = new Brain("brain", "t", d(0, "shelter", Desire.Kind.UPKEEP), d(1, "b", Desire.Kind.PROGRESS));
        s[0] = 30;
        s[1] = 0;
        br.tick(bb);
        assertEquals("do shelter", last());
        s[0] = 0;
        clock.advance(250);
        br.tick(bb);
        assertTrue(port.log.stream().anyMatch(l -> l.startsWith("brain: ends shelter")), port.log.toString());
    }

    @Test
    void reasonGonePureLogic() {
        Desire up = d(0, "x", Desire.Kind.UPKEEP);
        assertTrue(Brain.reasonGone(new Brain.Scored(up, 0, 0, "", null)));
        assertTrue(Brain.reasonGone(new Brain.Scored(up, 0, 0, "", "not wanted")));
        assertFalse(Brain.reasonGone(new Brain.Scored(up, 5, 5, "", null)));
        assertFalse(Brain.reasonGone(new Brain.Scored(up, 0, 0, "", "cooling 5s")));
        assertFalse(Brain.reasonGone(new Brain.Scored(d(1, "o", Desire.Kind.OWNER), 0, 0, "", null)));
    }}
