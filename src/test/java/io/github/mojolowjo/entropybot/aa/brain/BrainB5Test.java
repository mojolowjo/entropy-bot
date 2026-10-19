package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** BRAIN_PLAN B5: the resolver uses heights the chunk pass learned, in the plan's order. */
class BrainB5Test {
    static Thing.Catalog withLearned(Map<String, Thing.Gen> gen, int n) {
        var b = BrainB4Test.cat(null);
        return new Thing.Catalog(b.items(), b.blocks(), b.names(), b.tiers(), gen, f -> new Thing.Learned(Thing.OVERWORLD, 10, 50, 30, n));
    }

    @Test
    void fewSamplesOnlyReplaceAnUnknownY() {
        Thing.Where w = Thing.resolve("osmium", withLearned(null, 5)).where();
        assertTrue(w.yKnown());
        assertTrue(w.source().startsWith("learned from 5 ores"), w.text());
        assertEquals("the vanilla table", Thing.resolve("iron", withLearned(null, 5)).where().source());
    }

    @Test
    void enoughSamplesBeatTheVanillaTableButNotWorldgen() {
        Thing.Where m = Thing.resolve("osmium", withLearned(null, 80)).where();
        assertEquals(10, m.minY());
        assertEquals(50, m.maxY());
        assertTrue(m.source().contains("80 ores"), m.source());
        assertTrue(Thing.resolve("iron", withLearned(null, 80)).where().source().startsWith("learned"));
        var gen = Map.of("minecraft:iron_ore", new Thing.Gen(Set.of(Thing.OVERWORLD), -64, 72));
        assertEquals("from worldgen", Thing.resolve("iron", withLearned(gen, 999)).where().source());
    }

    @Test
    void noLearningMeansExploreWhenTheHeightIsUnknown() {
        var bb = new io.github.mojolowjo.entropybot.aa.core.Blackboard(new io.github.mojolowjo.entropybot.aa.core.Clock.Fake(1),
                new io.github.mojolowjo.entropybot.aa.core.FakePort(), new io.github.mojolowjo.entropybot.aa.core.Tracer());
        bb.in.pickTier = 4;
        var g = Goals.add(bb, Thing.resolve("mythril", BrainB4Test.cat(null)), 3, 0, 1);
        assertEquals("explore 5", Goals.chain(bb, g));
        var g2 = Goals.add(bb, Thing.resolve("mythril", withLearned(null, 5)), 3, 0, 1);
        assertEquals("mine strip mythril 3", Goals.chain(bb, g2));
    }
}
