package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.IronOptions;
import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** one-planner: an owner's goal is its chain (the dig of the known ore the loot data names); the owner's mine verb's ore pick stays. */
class GoalsTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    @Test
    void anItemGoalIsItsChain() {
        TestData d = TestData.planner().tag("wooden_pickaxe", "minecraft:pickaxes").install();
        try {
            NeedChain.Spot ore = new NeedChain.Spot(4, 60, -2, 8, "minecraft:coal_ore", 20);
            bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<NeedChain.World>) () -> io.github.mojolowjo.entropybot.aa.FakePaths.paths(new NeedChain.World(Map.of("minecraft:wooden_pickaxe", 1), false,
                    w -> w.equals("minecraft:coal") ? ore : null)));
            Goals.Goal g = Goals.add(bb, AaFix3Test.COAL, 16, 0, clock.now());
            assertEquals("collect coal 20", io.github.mojolowjo.entropybot.aa.TS.label(Goals.steps(bb, g)), "chop B2: no walk before a block source; S26: the 20-ore cluster is one job");
            assertTrue(Goals.chain(bb, g).why().startsWith("coal <- dig coal seen 8 m"), Goals.chain(bb, g).why());
            assertTrue(Goals.line(bb, g, "70").contains(" - chain: coal <- dig coal"), Goals.line(bb, g, "70"));
        } finally { TestData.clear(); }
    }

    @Test
    void pickOreSkipsDeniedWetAndTooDeep() {
        var denied = new IronOptions.Ore(1, 60, 0, 1, true, true, true, 1, false, "no resources taken", false);
        var wet = new IronOptions.Ore(2, 40, 0, 2, true, false, false, 5, true, null, false);
        var deep = new IronOptions.Ore(3, 10, 0, 3, true, false, false, 40, false, null, false);
        var buried = new IronOptions.Ore(4, 55, 0, 4, true, false, false, 6, false, null, false);
        var open = new IronOptions.Ore(5, 62, 0, 5, true, true, true, 1, false, null, false);
        assertArrayEquals(new int[]{4, 55, 0, 1}, Goals.pickOre(List.of(denied, wet, deep, buried, open), IronOptions.digMax()));
        assertArrayEquals(new int[]{5, 62, 0, 0}, Goals.pickOre(List.of(denied, open), IronOptions.digMax()));
        assertNull(Goals.pickOre(List.of(denied, wet, deep), IronOptions.digMax()));
    }
}
