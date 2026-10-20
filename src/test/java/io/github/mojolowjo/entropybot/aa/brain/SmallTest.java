package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaLadder;
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

/** aa-small: surface gate by exposure, the two-clock watchdog, the valued ore detour, the idle wait length. */
class SmallTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void surfaceGateBindsOnlySurfaceDesires() {
        bb.in.underDepth = 12;                 // deep, no table/furnace yet: needs open ground
        assertEquals("needs.surface", AaLadder.openGate(bb, Desire.Exposure.SURFACE));
        assertNull(AaLadder.openGate(bb, Desire.Exposure.UNDER), "under ground work never waits for the surface");
        assertNull(AaLadder.openGate(bb, Desire.Exposure.ANY));
        bb.in.underDepth = 0;
        assertNull(AaLadder.openGate(bb, Desire.Exposure.SURFACE), "AREAS: no area gate; at the surface nothing binds");
    }

    @Test
    void movingOrBaggingWorkKeepsTheShortClockQuiet() {
        Brain br = new Brain("brain", "t", new Desire("a", Desire.Kind.PROGRESS, RunJob.of("a.job", TaskKind.WAIT, "do a", "brain"), b -> 40).cost(0));
        br.tick(bb);
        for (int i = 0; i < 100; i++) {
            clock.advance(1000);
            if (i % 30 == 0) bb.in.pos = new int[]{i, 64, 0};      // moves every 30 s only
            assertEquals(Status.RUNNING, br.tick(bb), "t=" + i);
        }
        assertEquals(0, br.stuck);
    }

    @Test
    void waitEndsWithTheNextHold() {
        assertEquals(60, Brain.waitSeconds(1000, 0));
        assertEquals(60, Brain.waitSeconds(1000, 500));
        assertEquals(60, Brain.waitSeconds(1000, 1000 + 600_000));
        assertEquals(12, Brain.waitSeconds(1000, 1000 + 11_500));
        assertEquals(3, Brain.waitSeconds(1000, 1000 + 400), "never under 3 s");
        Failures f = new Failures();
        assertEquals(0, f.nextLapse(1000));
        f.rest("a", 30_000, 1000, "rest");
        f.rest("b", 10_000, 1000, "rest");
        assertEquals(11_000, f.nextLapse(1000));
        assertEquals(31_000, f.nextLapse(12_000), "the lapsed hold is not counted");
    }

    @Test
    void oreDetourIsValuedNotRadiusBased() {
        // no tree, no goal: worth nothing, never taken
        assertEquals(0, OreValue.perBlock("minecraft:copper_ore", null, java.util.Set.of(), Map.of(), Map.of()));
        assertFalse(OreValue.worthIt(0, 3, 2));
        // an open goal for the drop: 60 s a drop, copper 3.5 drops a block
        double v = OreValue.perBlock("minecraft:deepslate_copper_ore", null, java.util.Set.of(), Map.of("raw_copper", 16), Map.of("raw_copper", 4));
        assertEquals(210, v, 1e-9);
        assertTrue(OreValue.worthIt(v, 3, 8));
        assertFalse(OreValue.worthIt(v, 1, 1000), "too far: the walk costs more than it is worth");
        // the goal is met: no value again
        assertEquals(0, OreValue.perBlock("minecraft:copper_ore", null, java.util.Set.of(), Map.of("raw_copper", 16), Map.of("raw_copper", 16)));
        assertEquals("raw_iron", OreValue.dropOf("minecraft:deepslate_iron_ore"));
        assertEquals("coal", OreValue.dropOf("minecraft:coal_ore"));
    }
}