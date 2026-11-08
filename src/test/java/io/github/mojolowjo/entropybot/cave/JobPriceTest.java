package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.trail.Trail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Job-price (run JAO 2026-11-08): the cave price by the order's ore density from game data (CaveWalk.Density), and the
 * trail's hysteresis at a cave lip (Trail.offer with a depth). Hand numbers: densities 0.001 (a surface cave's iron) and
 * 0.01 (the iron band at y 16) are test inputs, not game facts; 20 ticks a second.
 */
class JobPriceTest {
    @BeforeEach
    void reset() { Tuning.reset(); }

    static Passages.Passage at(int id, int y, int fromFeet, int len, int unseen, int faces) {
        int[] e = {id * 10, y, 0};
        return new Passages.Passage(id, -1, e, e, len, 1, fromFeet, List.of(), unseen, faces, Passages.EndKind.DEAD_END);
    }

    /** Placements' ore per air-exposed block: 0.001 above y 40, 0.01 below (the iron band). */
    static final java.util.function.IntToDoubleFunction BAND = y -> y > 40 ? 0.001 : 0.01;

    /** Job-freeze (run JAP: 293 results dropped, each CaveWay printing the same): a Density built twice is equal, by value. */
    @Test
    void densityBuiltTwiceIsEqual() {
        var a = CaveWalk.Density.of(BAND, 100, 100, 0, 0);
        var b = CaveWalk.Density.of(y -> y > 40 ? 0.001 : 0.01, 100, 100, 0, 0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(io.github.mojolowjo.entropybot.aa.Deep.equal(a, b), "the planner's fact check");
        assertEquals(CaveWalk.Density.of(null, 0, 0, 5, 500), CaveWalk.Density.of(null, 0, 0, 5, 500));
        assertEquals(CaveWalk.Density.notKnown(), CaveWalk.Density.notKnown());
        assertNotEquals(a, CaveWalk.Density.of(BAND, 100, 50, 0, 0));
        assertEquals(0.01, a.at(16), 1e-12);
        assertEquals(0.001, a.at(70), 1e-12);
        assertEquals(0.01, a.at(-10_000), 1e-12, "below the sampled range: the lowest sample");
    }

    /** Job-freeze: two CaveWays built twice from the same record are equal (the planner's fact check, Deep.equal). */
    @Test
    void caveWayBuiltTwiceIsEqual() {
        java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay> make = () ->
                new io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay(-113, 64, -40, 9.0, "minecraft:deepslate_iron_ore", "iron", 0.91796875, 0.0,
                        null, 1.92, List.of("1,2,3"), List.of(new int[]{-113, 64, -40}))
                        .withPassages(List.of(at(0, 16, 4, 100, 200, 0), at(1, 20, 2, 10, 5, 1)))
                        .withDensity(CaveWalk.Density.of(y -> y > 40 ? 0.001 : 0.01, 100, 100, 0, 0));
        assertTrue(io.github.mojolowjo.entropybot.aa.Deep.equal(make.get(), make.get()));
        assertEquals(io.github.mojolowjo.entropybot.aa.Deep.hash(make.get()), io.github.mojolowjo.entropybot.aa.Deep.hash(make.get()));
    }

    @Test
    void surfaceCavePricesFarAboveTheBand() {
        var d = CaveWalk.Density.of(BAND, 100, 100, 0, 0);     // one air-exposed block a cell
        var surface = CaveWalk.findSeconds(List.of(at(0, 70, 4, 100, 200, 0)), 3, 0.5, d);
        var band = CaveWalk.findSeconds(List.of(at(0, 16, 4, 100, 200, 0)), 3, 0.5, d);
        assertTrue(surface.seconds() > 5 * band.seconds(), surface.line() + " vs " + band.line());
        assertEquals(0.2, surface.expected(), 1e-9);
        assertTrue(surface.line().contains("under 1 ore expected (0.20 along all 1 open passage(s), 52 s): 260 s per ore -> 780 s"), surface.line());
        assertTrue(surface.line().endsWith("(game data: the placements' share at each passage's mean Y x 100 air-exposed blocks over 100 cells)"), surface.line());
    }

    @Test
    void seenOreRateWhenNoPlacementFact() {
        var d = CaveWalk.Density.of(null, 0, 0, 5, 500);       // 5 ore faces seen in 500 cells
        assertEquals(0.01, d.at(70), 1e-12);
        var fp = CaveWalk.findSeconds(List.of(at(0, 70, 0, 10, 100, 0)), 1, 0.5, d);
        assertEquals(5.0, fp.seconds(), 1e-9);
        assertTrue(fp.line().contains("the record's seen rate, 5 ore in 500 cells seen"), fp.line());
    }

    @Test
    void notKnownSaysTheFallback() {
        var d = CaveWalk.Density.of(null, 0, 0, 0, 0);
        assertTrue(d.why().contains("cave.orePerCellNotKnown"), d.why());
        assertTrue(Double.isInfinite(CaveWalk.passagePrice(at(0, 70, 0, 10, 0, 0), 0.5, d, 0)));
    }

    // --- the trail's hysteresis ---
    static final int TPS = 20;

    static Trail fresh() {
        Trail t = new Trail();
        t.loadedEmpty();
        return t;
    }

    static String step(Trail t, int x, long sec, int depth) {
        return t.offer(new Trail.Cell(x, 60 - depth, 0), sec * TPS, depth, 8, 5, 4, 10);
    }

    @Test
    void oneSecondDipDoesNotOpen() {
        Trail t = fresh();
        assertNull(step(t, 0, 0, 0));
        assertNull(step(t, 1, 1, 1));
        assertNull(step(t, 2, 2, 0), "a quiet reset, no clear line");
        assertEquals(Trail.State.CLOSED, t.state());
    }

    @Test
    void sixSecondStayOpens() {
        Trail t = fresh();
        step(t, 0, 0, 0);
        assertNull(step(t, 1, 1, 1));
        assertNull(step(t, 2, 3, 1));
        String o = step(t, 3, 7, 1);
        assertNotNull(o);
        assertTrue(o.startsWith("trail: opened under ground, the way down starts at 0 60 0"), o);
        assertNull(step(t, 4, 8, 1), "said once");
    }

    @Test
    void deepOpensAtOnce() {
        Trail t = fresh();
        step(t, 0, 0, 0);
        assertNotNull(step(t, 1, 1, 4));
    }

    @Test
    void twoSecondPopOutDoesNotClear() {
        Trail t = fresh();
        step(t, 0, 0, 0);
        step(t, 1, 1, 1);
        assertNotNull(step(t, 2, 7, 1));
        assertNull(step(t, 3, 8, 0));
        assertNull(step(t, 4, 10, 0));
        assertNull(step(t, 5, 11, 1), "back under: still open, no new open line");
        assertEquals(Trail.State.OPEN, t.state());
        step(t, 6, 12, 0);
        String c = step(t, 7, 23, 0);
        assertNotNull(c);
        assertTrue(c.startsWith("trail: cleared, out at"), c);
        assertEquals(Trail.State.CLOSED, t.state());
    }
}
