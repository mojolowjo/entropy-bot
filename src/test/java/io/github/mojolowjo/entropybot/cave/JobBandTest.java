package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Job-band (run JAQ 2026-11-08): the band way priced in seconds (BandPrice). Test inputs, not game facts: densities
 * 0.0001 (a surface cave's iron a cell) and 0.004 (the band's ore per exposed block), a 0.4 s break, 1.0 s climb a level.
 */
class JobBandTest {
    @BeforeEach
    void reset() { Tuning.reset(); }

    static BandPrice.Price band(int fromY, double perBlock, int n) {
        int lv = Math.abs(fromY - 16);
        return BandPrice.dig(fromY, 16, perBlock, "game data: test", n, lv * 0.4, lv * 1.0, 0.4, 3, 0.75, Tuning.get("cave.orePerCellNotKnown"));
    }

    @Test
    void surfaceCaveLosesToTheBandWay() {
        var cave = CaveWalk.findSeconds(List.of(JobPriceTest.at(0, 64, 4, 100, 200, 0)), 3, 0.5, CaveWalk.Density.flat(0.0001, "test"));
        var b = band(64, 0.004, 3);
        assertTrue(b.seconds() < cave.seconds(), b.line() + " vs " + cave.line());
    }

    @Test
    void aCaveKnownInTheBandBeatsAFreshDig() {
        var cave = CaveWalk.findSeconds(List.of(JobPriceTest.at(0, 16, 4, 100, 400, 0)), 3, 0.5, CaveWalk.Density.flat(0.01, "test"));
        var b = band(64, 0.004, 3);
        assertTrue(cave.seconds() < b.seconds(), cave.line() + " vs " + b.line());
    }

    @Test
    void theLineSaysItsParts() {
        var b = band(64, 0.004, 3);
        // faces a second 3 / 0.4 = 7.5; an ore 1 / (0.004 x 7.5) + 0.75 = 34.08 s; down 48 x 0.4 = 19.2, back 48
        assertEquals(19.2 + 48 + 3 * (1 / 0.03 + 0.75), b.seconds(), 1e-9);
        assertEquals("down to y 16: 48 levels, 19 s (dig) + 48 s back + 3 ore at 0.00400 ore per exposed block, 7.5 faces per second (34.1 s per ore) -> 169 s (game data: test)", b.line());
        assertTrue(b.known());
    }

    @Test
    void densityNotKnownSaysTheFallback() {
        var b = band(16, Double.NaN, 1);
        assertFalse(b.known());
        assertEquals(Tuning.get("cave.orePerCellNotKnown"), b.perBlock(), 1e-12);
        assertTrue(b.line().startsWith("at the band (y 16) + 1 ore"), b.line());
        assertTrue(b.line().contains("density not known: the fallback cave.orePerCellNotKnown"), b.line());
    }

    @Test
    void aPricedExploreKeepsItsMark() {
        Step.Explore e = new Step.Explore(5, "raw_iron", false, true);
        assertTrue(e.priced());
        assertFalse(new Step.Explore(5, "raw_iron").priced());
        assertEquals(new Step.Explore(5, "raw_iron").text(), e.text(), "the step's words are the same");
    }
}
