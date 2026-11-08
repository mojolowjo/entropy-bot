package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.moves.BandLeg;
import io.github.mojolowjo.entropybot.aa.moves.BandLegCount;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.guard.BreakPolicy;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Job-tunnel2 (run JAT 05:14:13-15): the tunnel's lease, the held heading, the loaded edge, the dig leg's whole counts. Test inputs, not game facts. */
class JobTunnel2Test {
    static final int[] START = {-108, 16, -14};

    @Test
    void theTunnelsLeaseAllowsBreaksAsTheDigLegs() {
        assertEquals(BreakPolicy.Intent.TRAVEL, BandLeg.INTENT, "the tunnel walks under the dig leg's intent");
        var snap = BreakPolicy.Snapshot.of(Policy.parse("{\"areas\":[]}"), false);
        var under = new BreakPolicy.Where(false, 0);
        var v = BreakPolicy.judge(snap, new BreakPolicy.Ask(BreakPolicy.Action.BREAK, BandLeg.INTENT, "minecraft:overworld", -108, 16, -20,
                BreakPolicy.BlockFacts.STONE.withDepth(30), under));
        assertTrue(v.allowed(), v.reason());
        assertTrue(Double.isFinite(v.multiplier()) && v.multiplier() > 0, "a finite break price: " + v.multiplier());
    }

    @Test
    void aShortStopHoldsTheHeadingAsASourceFact() {
        var f = FactChange.tunnelHeldFact(START, "north", "Baritone stopped short at -108 16 -14 of -108 16 -30 (the fork: no path)");
        assertFalse(f.price(), "never a price fact (that one kept the running pick and stopped the order)");
        assertTrue(f.key().startsWith("source " + Tunnel.HOLD), f.key());
        assertEquals("tunnel heading -108 16 -14 north", FactChange.ownSourceKey(f));
        assertTrue(f.text().startsWith("tunnel north from -108 16 -14 held: Baritone stopped short at"), f.text());
    }

    @Test
    void theHeldHeadingSendsTheNextPickElsewhereAndAllHeldIsNoPick() {
        List<String> held = List.of("tunnel heading -108 16 -14 north: tunnel north from -108 16 -14 held: x", "strip ores|16|east: y");
        Set<String> h = Tunnel.heldHeadings(held, new int[]{-106, 16, -13}, 8);
        assertEquals(Set.of("north"), h);
        assertTrue(Tunnel.heldHeadings(held, new int[]{-108, 40, -14}, 8).isEmpty(), "another Y: not held");
        assertTrue(Tunnel.heldHeadings(held, new int[]{-60, 16, -14}, 8).isEmpty(), "far away: not held");
        var pk = Tunnel.pick(START, 51, 64, (cx, cz) -> !(cx == -7 && cz == -2), h, null, null);
        assertNotEquals("north", pk.heading().name(), pk.why());
        List<String> all = List.of("tunnel heading -108 16 -14 north: a", "tunnel heading -108 16 -14 east: b",
                "tunnel heading -108 16 -14 south: c", "tunnel heading -108 16 -14 west: d");
        Set<String> every = Tunnel.heldHeadings(all, START, 8);
        assertEquals(Tunnel.HEADINGS.size(), every.size());
        assertNull(Tunnel.pick(START, 51, 64, (cx, cz) -> true, every, null, null).heading(), "every heading held: no pick, said");
    }

    @Test
    void theGoalIsCappedAtTheLoadedEdge() {
        assertEquals(18, Tunnel.loadedSteps(51, i -> i <= 18));
        assertEquals(51, Tunnel.loadedSteps(51, i -> true));
        assertEquals(0, Tunnel.loadedSteps(51, i -> false));
        assertEquals(3, Tunnel.loadedSteps(51, i -> i != 4 && i < 30), "a gap stops the cap");
        var h = Tunnel.heading("north");
        int[] goal = Tunnel.end(START, h, 18);
        assertEquals("to the loaded edge at -108 16 -32 first, 18 of the 51 blocks", Tunnel.edgeLine(goal, 18, 51));
    }

    @Test
    void theDigEndCountsTheWholeLeg() {
        // run JAT: the leg started at y 62, two re-plans restarted it; the last segment dug 1 level and broke 1 block
        var a = BandLegCount.start(null, 0, 62, 16, 0, 600);
        var b = BandLegCount.start(a, 30, 40, 16, 200_000, 600);
        var c = BandLegCount.start(b, 15, 17, 16, 359_000, 600);
        assertEquals(62, c.fromY());
        assertEquals(45, c.broken());
        assertEquals("reached y 16 (46 levels, 360 s, 46 blocks broken)", BandLegCount.reached(c, 16, 360_000, 1));
        var other = BandLegCount.start(c, 0, 17, 5, 361_000, 600);
        assertEquals(17, other.fromY(), "another target Y starts fresh");
        var late = BandLegCount.start(c, 0, 17, 16, 359_000 + 601_000, 600);
        assertEquals(0, late.broken(), "past band.legCarryS: fresh");
    }
}
