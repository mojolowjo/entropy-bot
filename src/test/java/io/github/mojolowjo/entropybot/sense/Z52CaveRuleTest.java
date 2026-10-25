package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z52: the pocket floor holds once patterns exist, the darkest sky is the second dark proof (pocket OR darkest sky),
 * aa cave list for the owner's labelling walk. Fact states (rule 15): no pattern (defaults), patterns but no cave (defaults),
 * cave patterns loaded (derived, floored).
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
        CavePatterns p = Z50CavePatternTest.seeded();
        CavePatterns.Rule r = p.rule();
        assertTrue(r.derived());
        assertEquals(Tuning.i("cave.darkPocketCells"), r.pocket(), "seed caves #15-#17 show pocket 0; the floor holds");
        assertEquals(11, r.darkSky(), "the loosest seed cave's darkest sky");
    }

    @Test
    void eitherDarkProofConfirms() {
        CavePatterns.Rule r = Z50CavePatternTest.seeded().rule();
        assertNull(CavePatterns.verdict(hole(9, 0, 3), r, CAVE), "darkest sky 3 passes without a pocket");
        assertNull(CavePatterns.verdict(hole(9, 7, 12), r, CAVE), "a pocket of 7 passes with darkest sky 12");
        String no = CavePatterns.verdict(hole(9, 2, 12), r, CAVE);
        assertNotNull(no);
        assertTrue(no.startsWith("no dark proof: pocket 2 < 6"), no);
        assertTrue(no.contains("darkest sky 12 > 11"), no);
    }

    @Test
    void whichSeedCavesPass() {
        CavePatterns p = Z50CavePatternTest.seeded();
        CavePatterns.Rule r = p.rule();
        int pocket = 0, sky = 0, n = 0;
        for (CavePatterns.Patch q : p.all()) {
            if (!q.cave()) continue;
            n++;
            if (q.pocket() >= r.pocket()) pocket++;
            if (q.darkSky() <= r.darkSky()) sky++;
            assertNull(CavePatterns.verdict(hole(q.depth(), q.pocket(), q.darkSky()), r, CAVE), "seed cave " + q.x() + " " + q.z());
        }
        assertEquals(5, n);
        assertEquals(1, pocket, "only #7 has a pocket of 6+ at reach 10");
        assertEquals(5, sky);
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
