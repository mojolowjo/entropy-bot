package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.IronOptions;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AA round 2 item 5: an ore goal names its nearest takeable ore ("mine coal 1 at x y z dig|walk"). */
class GoalsTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    @Test
    void anOreGoalMinesItsNearestOreByName() {
        BrainB5Test.withLearned(null, 80);
        bb.in.pickTier = 2;
        Goals.Goal g = Goals.add(bb, AaFix3Test.COAL, 16, 0, clock.now());
        bb.fact(Goals.KNOWN_FACT, Map.of(g.r.key(), 5));
        assertEquals("mine coal 16 dig", Goals.chain(bb, g), "no nearest fact: as before");
        bb.fact(Goals.NEAREST_FACT, Map.of(g.r.key(), new int[]{4, 60, -2, 0}));
        assertEquals("mine coal 1 at 4 60 -2 walk", Goals.chain(bb, g));
        bb.fact(Goals.NEAREST_FACT, Map.of(g.r.key(), new int[]{4, 50, -2, 1}));
        assertEquals("mine coal 1 at 4 50 -2 dig", Goals.chain(bb, g));
    }

    @Test
    void pickOreSkipsDeniedWetAndTooDeep() {
        var denied = new IronOptions.Ore(1, 60, 0, 1, true, true, true, 1, false, "no resources taken", false);
        var wet = new IronOptions.Ore(2, 40, 0, 2, true, false, false, 5, true, null, false);
        var deep = new IronOptions.Ore(3, 10, 0, 3, true, false, false, 40, false, null, false);
        var buried = new IronOptions.Ore(4, 55, 0, 4, true, false, false, 6, false, null, false);
        var open = new IronOptions.Ore(5, 62, 0, 5, true, true, true, 1, false, null, false);
        assertArrayEquals(new int[]{4, 55, 0, 1}, Goals.pickOre(List.of(denied, wet, deep, buried, open), IronOptions.DIG_MAX));
        assertArrayEquals(new int[]{5, 62, 0, 0}, Goals.pickOre(List.of(denied, open), IronOptions.DIG_MAX));
        assertNull(Goals.pickOre(List.of(denied, wet, deep), IronOptions.DIG_MAX));
    }
}
