package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** BRAIN_PLAN B7 (2.12): aa build words, the skip list, missing materials, the layer and the next step. */
class BlueprintTest {
    final Blackboard bb = new Blackboard(new Clock.Fake(10_000_000), new FakePort(), new Tracer());

    static Blueprint.Build build(Blueprint.Scan s) {
        Blueprint.Build b = new Blueprint.Build("house", "house.litematic", new int[]{10, 64, -5}, 0, 0,
                Map.of("minecraft:oak_planks", 100, "minecraft:cobblestone", 50), List.of());
        b.scan = s;
        return b;
    }

    @Test
    void parseNeedsAPlace() {
        Blueprint.Args a = Blueprint.parse("house.litematic here high");
        assertNull(a.err());
        assertEquals("house", a.name());
        assertTrue(a.here());
        assertEquals(1, a.prio());
        a = Blueprint.parse("tower at 1 -60 2");
        assertArrayEquals(new int[]{1, -60, 2}, a.at());
        assertNotNull(Blueprint.parse("tower").err());
        assertNotNull(Blueprint.parse("tower at 1 2").err());
        assertNotNull(Blueprint.parse("tower here at 1 2 3").err());
        assertNotNull(Blueprint.parse("").err());
    }

    @Test
    void airLiquidsAndFireAreSkipped() {
        assertTrue(Blueprint.skipped("minecraft:air"));
        assertTrue(Blueprint.skipped("minecraft:water"));
        assertTrue(Blueprint.skipped("fire"));
        assertFalse(Blueprint.skipped("minecraft:oak_planks"));
        assertFalse(Blueprint.skipped("othermod:water"));
    }

    @Test
    void layerIsTheFirstUnfinished() {
        assertEquals(1, Blueprint.currentLayer(new int[]{4, 4, 4}, new int[]{4, 3, 0}));
        assertEquals(3, Blueprint.currentLayer(new int[]{4, 4, 4}, new int[]{4, 4, 4}));
        assertEquals(0, Blueprint.currentLayer(new int[]{2}, new int[]{0}));
    }

    @Test
    void missingIsLargestFirstAndUsesTheBag() {
        Map<String, Integer> m = Blueprint.missing(Map.of("minecraft:oak_planks", 100, "minecraft:cobblestone", 50, "minecraft:glass", 4),
                Map.of("oak_planks", 90, "glass", 10));
        assertEquals(List.of("cobblestone", "oak_planks"), m.keySet().stream().map(Blueprint::bagKey).toList());
        assertEquals(10, m.get("minecraft:oak_planks"));
    }

    @Test
    void gathersWhatTheLayerLacksElseBuilds() {
        Blueprint.Scan s = new Blueprint.Scan(Map.of("minecraft:oak_planks", 100, "minecraft:cobblestone", 50),
                Map.of("minecraft:cobblestone", 20), 0, 150, 0, 0, 6);
        Blueprint.Build b = build(s);
        bb.in.bag = Map.of("oak_planks", 64);
        assertEquals("goal cobblestone 50", Blueprint.chain(bb, b));         // the whole build's cobblestone, one trip
        bb.in.bag = Map.of("cobblestone", 5);
        assertEquals("build schematic house 10 64 -5", Blueprint.chain(bb, b));   // some in the bag: place it first
        String line = Blueprint.line(bb, b, "score 70");
        assertTrue(line.startsWith("house: layer 1/6, 0/150 placed, missing 100 oak_planks, 45 cobblestone"), line);
        assertEquals(70, Blueprint.score(bb, b));
    }

    @Test
    void doneAndParked() {
        Blueprint.Build b = build(null);
        assertEquals("not scanned yet", Blueprint.blocked(bb, b));
        assertNull(Blueprint.chain(bb, b));
        b.scan = new Blueprint.Scan(Map.of(), Map.of(), 140, 150, 10, 6, 6);
        assertTrue(b.scan.done());
        assertNull(Blueprint.chain(bb, b));
        assertEquals(-1, Blueprint.score(bb, b));
        b.dim = "minecraft:the_nether";
        bb.in.dimension = "minecraft:overworld";
        assertNotNull(Blueprint.blocked(bb, b));
    }

    @Test
    void noPlanItemIsSkippedAndThreeEmptyRunsPark() {
        Blueprint.Build b = build(new Blueprint.Scan(Map.of("minecraft:glass", 4), Map.of("minecraft:glass", 4), 0, 4, 0, 0, 1));
        bb.in.bag = Map.of();
        assertEquals("goal glass 4", Blueprint.chain(bb, b));
        b.unresolved.add("glass");
        assertEquals("build schematic house 10 64 -5", Blueprint.chain(bb, b));
        b.fails = Blueprint.MAX_FAILS;
        assertTrue(Blueprint.blocked(bb, b).startsWith("placed nothing in 3 runs"));
        assertNotNull(Blueprint.parse("../x here").err());
    }

    @Test
    void materialsLine() {
        assertEquals("150 blocks: 100 oak_planks, 50 cobblestone", Blueprint.materials(Map.of("minecraft:oak_planks", 100, "minecraft:cobblestone", 50)));
    }
}
