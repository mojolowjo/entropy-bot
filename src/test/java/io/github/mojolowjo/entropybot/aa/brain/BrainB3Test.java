package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** BRAIN_PLAN B3: personality, wants with boredom, day and night. */
class BrainB3Test {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void personalityDefaultsAndBadValuesFallBack() {
        Personality p = Personality.of(Map.of("build", 30, "explore", 500, "nonsense", 1, "tidy", "x"));
        assertEquals(30, p.get("build"));
        assertEquals(15, p.get("explore"), "out of range: the default");
        assertEquals(10, p.get("tidy"));
        assertEquals(3, p.errors.size(), p.errors.toString());
        assertEquals(15, new Personality().get("margin"));
        assertNull(p.set("ownerbase", 80));
        assertEquals(80, p.get("ownerBase"));
        assertTrue(p.toJson().contains("\"build\": 30"), p.toJson());
        assertTrue(Personality.of(Map.of("margin", 20)).errors.isEmpty());
        assertNotNull(p.set("food", 3), "upkeep factor capped at 2");
    }

    @Test
    void boredomRisesOverADayAndResetsWhenDone() {
        assertEquals(-1, Wants.score(bb, "want.build", "build"), "not bored at first");
        clock.advance(Wants.DAY_MS / 2);
        assertEquals(10, Wants.score(bb, "want.build", "build"), 1e-9, "20 x 0.5");
        clock.advance(Wants.DAY_MS);
        assertEquals(20, Wants.score(bb, "want.build", "build"), 1e-9, "capped at the weight");
        Wants.done(bb, "want.build", clock.now());
        assertEquals(-1, Wants.score(bb, "want.build", "build"));
    }

    @Test
    void duskRampsAndNightHolds() {
        assertEquals(0, Night.dusk(6000, false));
        assertEquals(0.5, Night.dusk(Night.DUSK + (Night.BED_FROM - Night.DUSK) / 2, false), 0.01);
        assertEquals(1, Night.dusk(18000 + 24000 * 3, false), "day count ignored");
        assertEquals(0, Night.dusk(23500, true));
        assertEquals(1, Night.dusk(-1, true), "unknown time: the flag");
    }

    Desire d(String id, Desire.Kind k, double v, int night) {
        return new Desire(id, k, RunJob.of(id + ".job", TaskKind.WAIT, "do " + id, "brain"), b -> v).cost(0).night(night);
    }

    @Test
    void nightShiftsMiningUpAndSurfaceDownButNotInPeaceful() {
        Brain br = new Brain("brain", "t", d("mine", Desire.Kind.UPKEEP, 30, +1), d("wood", Desire.Kind.UPKEEP, 35, -1));
        bb.in.dayTime = 18000;
        bb.in.hungerDrains = true;
        var s = br.scoreAll(bb);
        assertEquals(40, s.get(0).score(), 1e-9);
        assertEquals(25, s.get(1).score(), 1e-9);
        assertEquals(Status.RUNNING, br.tick(bb));
        assertEquals("do mine", port.started.get(0).chain());
        bb.in.hungerDrains = false;                      // Peaceful: no shift
        assertEquals(30, br.scoreAll(bb).get(0).score(), 1e-9);
    }

    @Test
    void marginComesFromThePersonality() {
        Personality p = new Personality();
        p.set("margin", 0);
        bb.fact(Personality.FACT, p);
        double[] v = {70, 0};
        Brain br = new Brain("brain", "t",
                new Desire("a", Desire.Kind.UPKEEP, RunJob.of("a.job", TaskKind.WAIT, "do a", "brain"), b -> v[0]),
                new Desire("b", Desire.Kind.UPKEEP, RunJob.of("b.job", TaskKind.WAIT, "do b", "brain"), b -> v[1]));
        br.tick(bb);
        v[1] = 80;                                       // urgent, beats 70 + 0 (the default 15 would hold it)
        br.tick(bb);
        assertEquals("do b", port.started.get(port.started.size() - 1).chain());
    }

    @Test
    void aWantThatSucceedsIsBoredAgainFromZero() {
        Desire w = new Desire("want.tidy", Desire.Kind.WANT, RunJob.of("t.job", TaskKind.DEPOSIT, "deposit", "want"),
                b -> Wants.score(b, "want.tidy", "tidy"));
        Brain br = new Brain("brain", "t", w);
        br.scoreAll(bb);                                 // first seen now
        clock.advance(Wants.DAY_MS);
        assertEquals(Status.RUNNING, br.tick(bb));
        port.done(1, "done");
        br.tick(bb);
        assertEquals(-1, Wants.score(bb, "want.tidy", "tidy"));
    }
}
