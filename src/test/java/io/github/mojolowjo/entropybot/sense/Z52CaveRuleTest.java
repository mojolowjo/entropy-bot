package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z52: the pocket floor holds once patterns exist, the darkest sky is the second dark proof (pocket OR darkest sky),
 * aa cave list for the owner's labelling walk. Fact states (rule 15): no pattern (defaults), patterns but no cave (defaults),
 * cave patterns loaded (derived, floored). 0.39.z54 A5: hand-made patterns ({@link Z50CavePatternTest#fakes()}), no seed.
 */
class Z52CaveRuleTest {

    static CavePatterns.Hole hole(int depth, int pocket, int darkSky) {
        return new CavePatterns.Hole(0, 0, 70, 70 - depth, depth, 15, pocket, darkSky, false);
    }

    static final CavePatterns.Vote CAVE = new CavePatterns.Vote(true, 1, 0, "test");

    @Test
    void pocketReachDefaultIsTen() {
        assertEquals(10, Tuning.i("cave.pocketReach"));
    }

    @Test
    void noPatternsTheDefaults() {
        CavePatterns.Rule r = new CavePatterns().rule();
        assertFalse(r.derived());
        assertEquals(Tuning.i("cave.darkPocketCells"), r.pocket());
    }

    @Test
    void patternsWithoutACaveTheDefaults() {
        CavePatterns p = new CavePatterns();
        p.add(new CavePatterns.Patch(0, 64, 0, "cliff", "owner", 9, 0, 12, new int[81], null));
        assertEquals(CavePatterns.defaults(), p.rule(), "no cave labelled yet: empty, not a pocket of 0");
    }

    @Test
    void derivedPocketNeverBelowTheFloor() {
        CavePatterns.Rule r = Z50CavePatternTest.fakes().rule();
        assertTrue(r.derived());
        assertEquals(Tuning.i("cave.darkPocketCells"), r.pocket(), "caves B and C show pocket 0; the floor holds");
        assertEquals(11, r.darkSky(), "the loosest cave's darkest sky (B)");
    }

    @Test
    void eitherDarkProofPassesTheVerdict() {
        CavePatterns.Rule r = Z50CavePatternTest.fakes().rule();
        assertNull(CavePatterns.verdict(hole(9, 0, 3), r, CAVE), "darkest sky 3 passes the verdict without a pocket");
        assertNull(CavePatterns.verdict(hole(9, 7, 12), r, CAVE), "a pocket of 7 passes with darkest sky 12");
        String no = CavePatterns.verdict(hole(9, 2, 12), r, CAVE);
        assertNotNull(no);
        assertTrue(no.startsWith("no dark proof: pocket 2 < 6"), no);
        assertTrue(no.contains("darkest sky 12 > 11"), no);
    }

    // ---- aa cave list ----

    @Test
    void listNearestFirstWithVerdictAndLabel() {
        CavePatterns p = new CavePatterns();
        p.add(new CavePatterns.Patch(100, 64, 0, "cave", "owner", 9, 0, 3, new int[81], null));
        List<CaveList.Entry> e = List.of(
                CaveList.candidate(100, 64, 0, "cliff E 7", null),
                CaveList.candidate(10, 64, 0, "cliff N 6", "no dark proof: pocket 0 < 6"),
                CaveList.mouth(40, 60, 0, 12, 40, 59, 0, false));
        String t = CaveList.text("RULE", e, p.all(), 0.5, 64.5, 0.5);
        String[] lines = t.split("\n");
        assertEquals("RULE", lines[0]);
        assertTrue(lines[2].startsWith("10 64 0 (10 m): dropped: no dark proof"), lines[2]);
        assertTrue(lines[2].endsWith("not labelled"), lines[2]);
        assertTrue(lines[3].startsWith("40 60 0 (40 m): mouth"), lines[3]);
        assertTrue(lines[3].contains("12 pocket cells"), lines[3]);
        assertTrue(lines[4].startsWith("100 64 0 (100 m): candidate"), lines[4]);
        assertTrue(lines[4].endsWith("labelled cave by owner"), lines[4]);
    }

    @Test
    void listEmptySaysSo() {
        String t = CaveList.text("RULE", List.of(), List.of(), 0, 64, 0);
        assertTrue(t.contains("no candidate or mouth known"), t);
    }
}
