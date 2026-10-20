package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4 B1 (danger where the work is) and B2 (the torches near, not the feet), B3 (Tuning). */
class BrainNightTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    Desire d(String id, double v, Site site) {
        return new Desire(id, Desire.Kind.UPKEEP, RunJob.of(id + ".job", TaskKind.WAIT, "do " + id, "brain"), b -> v).cost(0).site(b -> site);
    }

    void normalNight() {
        bb.in.dayTime = 18000;
        bb.in.hungerDrains = true;
        bb.in.difficulty = 2;
        bb.in.pos = new int[]{0, 64, 0};
        bb.fact(Brain.SURFACE_Y_FACT, (java.util.function.IntBinaryOperator) (x, z) -> 64);
    }

    @AfterEach
    void defaults() { Tuning.reset(); }

    @Test
    void theSiteDecidesSurfaceOrUnderNotTheLabel() {
        normalNight();
        Brain br = new Brain("brain", "t", d("surface work", 60, Site.at(10, 64, 10)), d("mine", 40, Site.at(10, 50, 10)),
                d("shelter", 85, null).trumps());
        var s = br.scoreAll(bb);
        double danger = Brain.danger(bb);
        assertEquals(1.0, danger, 1e-9, "Normal, full health, no armour, no torches, no hostiles");
        assertEquals(60 / (1 + danger), s.get(0).score(), 1e-9, "a site at the surface: x 1/(1+danger) " + s.get(0).parts());
        assertEquals(40 * (1 + danger / 2), s.get(1).score(), 1e-9, "14 under the surface there: x (1+danger/2)");
        assertEquals(85, s.get(2).score(), 1e-9, "a trumping desire is never discounted");
        // the same desires by day: as they are
        bb.in.dayTime = 6000;
        var day = br.scoreAll(bb);
        assertEquals(60, day.get(0).score(), 1e-9);
        assertEquals(40, day.get(1).score(), 1e-9);
    }

    @Test
    void underASurfaceSiteWhileTheBotIsDeepStillCountsTheSite() {
        normalNight();
        bb.in.pos = new int[]{0, 20, 0};
        bb.in.underDepth = 44;
        Brain br = new Brain("brain", "t", d("wood", 50, Site.at(30, 64, 30)), d("no site", 40, null));
        var s = br.scoreAll(bb);
        assertEquals(25, s.get(0).score(), 1e-9, "the work is at the surface, wherever the bot stands now");
        assertEquals(60, s.get(1).score(), 1e-9, "no site, no label: where the bot is (deep under)");
        // no heightmap: the bot's surface (its y + the ground over it) stands in
        bb.fact(Brain.SURFACE_Y_FACT, null);
        assertEquals(25, br.scoreAll(bb).get(0).score(), 1e-9);
    }

    @Test
    void oneTorchCutsTheDangerFivePercentAndTuningMovesIt() {
        normalNight();
        Brain br = new Brain("brain", "t", d("surface work", 60, Site.at(10, 64, 10)));
        double dark = br.scoreAll(bb).get(0).score();
        bb.in.torchesNear = 1;
        assertEquals(0.95, Brain.danger(bb), 1e-9);
        assertTrue(br.scoreAll(bb).get(0).score() > dark, "a lit place is cheaper to work in");
        bb.in.torchesNear = 0;
        assertTrue(Tuning.set("shelterAt1", 70).startsWith("ok"));
        assertEquals(70, Costs.shelterScore(1), 1e-9);
        assertTrue(Tuning.set("nope", 1).startsWith("error"));
        assertEquals(java.util.Map.of("shelterAt1", 70.0), Tuning.changed());
        assertEquals(1, Tuning.load(java.util.Map.of("bad.name", 3.0)).size(), "an unknown name is reported, the rest load");
        assertEquals(85, Costs.shelterScore(1), 1e-9, "load resets to the defaults first");
    }
}
