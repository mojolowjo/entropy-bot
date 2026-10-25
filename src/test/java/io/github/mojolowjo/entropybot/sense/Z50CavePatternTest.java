package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z50 scenarios (docs/SCENARIOS.md): S69 the hole walk descends and a mouth needs a pocket, not a cell; S70 the cave
 * rule comes from the labelled patterns; S71 the owner labels spots, the bot's labels weigh less (z54: nothing by
 * default), one feature is one candidate. 0.39.z54 A5 (owner: no label data in the public repo or jar): the bundled
 * seed is gone; the patterns here are hand-made fakes ({@link #fakes()}).
 */
class Z50CavePatternTest {

    @AfterEach
    void defaults() { Tuning.reset(); }

    /** A test world: solid below a height map, air above; extra air / dark cells set by hand; sky 15 unless set. */
    static final class Grid implements CavePatterns.Cells {
        final Map<Long, Integer> top = new HashMap<>();
        final Map<Long, Boolean> air = new HashMap<>();
        final Map<Long, Integer> sky = new HashMap<>();
        int base = 62;
        int top(int x, int z) { return top.getOrDefault(Seen.key(x, 0, z), base); }
        public boolean air(int x, int y, int z) { return air.getOrDefault(Seen.key(x, y, z), y > top(x, z)); }
        public boolean water(int x, int y, int z) { return false; }
        public int sky(int x, int y, int z) { return sky.getOrDefault(Seen.key(x, y, z), 15); }
    }

    /**
     * A hand-made 9 x 9 patch: the +x side rises {@code cliff} from the first column on, the -x side rises evenly to
     * {@code slope} at the third column (CavePatterns.shape reads cliff within cave.cliffReach 2, slope within cave.slopeLen 3).
     */
    static int[] mouth(int cliff, int slope) {
        int[] ws = new int[CavePatterns.SIDE * CavePatterns.SIDE];
        for (int dz = -CavePatterns.R; dz <= CavePatterns.R; dz++)
            for (int dx = -CavePatterns.R; dx <= CavePatterns.R; dx++) {
                int v = dx > 0 ? cliff : dx < 0 ? (int) Math.min(slope, Math.round(-dx * slope / 3.0)) : 0;
                ws[(dz + CavePatterns.R) * CavePatterns.SIDE + dx + CavePatterns.R] = v;
            }
        return ws;
    }

    /**
     * Hand-made patterns (no owner data): caves A (cliff 8, slope 3, depth 9, pocket 228, sky 0), B (6, 2, depth 7, pocket 0,
     * sky 11) and C (2, 1, depth 3, pocket 0, sky 3, a low one that drags the derived rule under its floors); a cliff D, a
     * hill E; a bot cave F (weighs 0 by default) and an unknown G (votes for neither side).
     */
    static CavePatterns fakes() {
        CavePatterns p = new CavePatterns();
        p.add(new CavePatterns.Patch(0, 70, 0, "cave", "owner", 9, 228, 0, mouth(8, 3), null));
        p.add(new CavePatterns.Patch(100, 70, 0, "cave", "owner", 7, 0, 11, mouth(6, 2), null));
        p.add(new CavePatterns.Patch(200, 70, 0, "cave", "owner", 3, 0, 3, mouth(2, 1), null));
        p.add(new CavePatterns.Patch(300, 70, 0, "cliff", "owner", 9, 0, 12, mouth(9, 0), null));
        p.add(new CavePatterns.Patch(400, 70, 0, "hill", "owner", 2, 0, 15, mouth(1, 1), null));
        p.add(new CavePatterns.Patch(500, 70, 0, "cave", "bot", 1, 0, 14, mouth(1, 0), null));
        p.add(new CavePatterns.Patch(600, 70, 0, "unknown", "owner", 4, 0, 15, mouth(3, 3), null));
        return p;
    }

    static CavePatterns.Patch at(CavePatterns p, int x, int z) {
        for (CavePatterns.Patch q : p.all()) if (q.x() == x && q.z() == z) return q;
        throw new AssertionError("no pattern at " + x + " " + z);
    }

    // ---- S69 ----

    @Test
    void s69TheWalkDescendsFromTheRim() {
        // a cliff 7 high on +x of the candidate; the old walk started at the candidate cell (over its own top): depth 0
        Grid g = new Grid();
        for (int z = -5; z <= 5; z++) for (int x = 1; x <= 5; x++) g.top.put(Seen.key(x, 0, z), 69);
        CavePatterns.Hole h = CavePatterns.walk(0, 0, g::top, g);
        assertEquals(69, h.rim());
        assertEquals(62, h.floorY());
        assertEquals(7, h.depth(), "rim 69 to the floor 62");
        assertEquals(15, h.sky());
    }

    @Test
    void s69APocketNotACell() {
        Grid g = new Grid();
        for (int z = -5; z <= 5; z++) for (int x = 1; x <= 5; x++) g.top.put(Seen.key(x, 0, z), 69);
        // an undercut (#11): one dark air cell under the cliff
        g.air.put(Seen.key(1, 63, 0), true);
        g.sky.put(Seen.key(1, 63, 0), 0);
        CavePatterns.Hole one = CavePatterns.walk(0, 0, g::top, g);
        assertEquals(1, one.pocket());
        CavePatterns.Rule d = CavePatterns.defaults();
        CavePatterns.Vote none = new CavePatterns().vote(new int[81], 0, 0, 0, true, null);
        // z52: two dark proofs, either passes the verdict; under the defaults (cave.maxSky 0) the darkest-sky rule passes
        // one sky-0 cell, and z54 A4 then holds it as a candidate (no pocket, no vote margin)
        assertNull(CavePatterns.verdict(one, d, none), "z52: one sky-0 cell meets the default darkest-sky rule");
        assertNotNull(CavePatterns.hold(one, d, none), "z54 A4: the darkest sky alone with no vote margin stays a candidate");
        // a tunnel into the cliff: 2 high, 4 deep, dark past its first cell
        for (int x = 1; x <= 4; x++) for (int y = 63; y <= 64; y++) { g.air.put(Seen.key(x, y, 0), true); if (x > 1) g.sky.put(Seen.key(x, y, 0), 0); }
        CavePatterns.Hole tunnel = CavePatterns.walk(0, 0, g::top, g);
        assertEquals(7, tunnel.pocket());
        assertEquals(0, tunnel.darkSky());
        assertNull(CavePatterns.verdict(tunnel, d, none), "a pocket of 7 dark cells is a mouth");
        assertNull(CavePatterns.hold(tunnel, d, none), "the pocket proof confirms alone");
    }

    // ---- S70 ----

    @Test
    void s70TheRuleComesFromThePatterns() {
        CavePatterns p = fakes();
        assertEquals(7, p.size());
        CavePatterns.Rule r = p.rule();
        assertTrue(r.derived());
        assertEquals(5, r.patterns(), "A B C D E: the unknown G and the 0-weight bot F do not count");
        assertEquals("cave rule from 5 patterns: slope >= 1, cliff >= 5, depth >= 5, pocket >= 6 or darkest sky <= 11"
                + " (floors hold: cliff 5 (derived 2), depth 5 (derived 3), pocket 6 (derived 0))", r.line());
        assertFalse(new CavePatterns().rule().derived(), "no pattern: the Tuning defaults");
        assertEquals(CavePatterns.defaults(), new CavePatterns().rule());
    }

    @Test
    void s70TheSameSpotVotesItsOwnSide() {
        CavePatterns p = fakes();
        CavePatterns.Patch a = at(p, 0, 0), d = at(p, 300, 0);
        assertTrue(p.vote(a.ws(), a.depth(), a.pocket(), a.darkSky(), true, null).cave(), "cave A's own numbers");
        CavePatterns.Vote v = p.vote(d.ws(), d.depth(), d.pocket(), d.darkSky(), true, null);
        assertTrue(v.nearest().startsWith("cliff at 300 70 0"), v.line());
    }

    @Test
    void s70ATurnedPatchIsTheSamePatch() {
        int[] a = mouth(8, 3);
        for (int t = 0; t < 8; t++) assertEquals(0, CavePatterns.patchD2(a, CavePatterns.turn(a, t)), 1e-9);
    }

    // ---- S71 ----

    @Test
    void s71OwnerLabelsAndBotLabelsWeighLess() {
        CavePatterns p = new CavePatterns();
        int[] flat = new int[81];
        p.add(new CavePatterns.Patch(0, 63, 0, "cave", "bot", 5, 10, 0, flat, null));
        p.add(new CavePatterns.Patch(9, 63, 9, "cliff", "owner", 5, 10, 0, flat, null));
        // z54 A1 (owner's ruling): the bot's patch weighs 0 and takes no place in the vote
        CavePatterns.Vote v = p.vote(flat, 5, 10, 0, true, null);
        assertEquals(0.0, v.caveW(), 1e-9);
        assertEquals(1.0, v.otherW(), 1e-9);
        assertFalse(v.cave(), "the owner's cliff beats the bot's cave");
        // the z50 weight still works when the owner tunes it back
        Tuning.set("cave.botLabelWeight", 0.5);
        CavePatterns.Vote half = p.vote(flat, 5, 10, 0, true, null);
        assertEquals(0.5, half.caveW(), 1e-9);
        assertEquals(1.0, half.otherW(), 1e-9);
        String json = p.toJson();
        CavePatterns back = new CavePatterns();
        int[] sk = new int[1];
        back.read(json, sk);
        assertEquals(0, sk[0]);
        assertEquals(2, back.size(), "z54 A1: the bot's entry stays in the file (read, never rewritten away)");
        assertEquals("bot", back.all().get(0).by());
        assertEquals(1, back.botEntries());
    }

    @Test
    void s71OneFeatureOneCandidate() {
        // #15/16/17 and #13/14 of 2026-10-24
        List<CaveMouths.Candidate> in = List.of(
                new CaveMouths.Candidate(-53, 63, 16, "ravine"), new CaveMouths.Candidate(-58, 63, 13, "mouth"), new CaveMouths.Candidate(-48, 63, 18, "ravine"),
                new CaveMouths.Candidate(-76, 63, 3, "ravine"), new CaveMouths.Candidate(-81, 63, 3, "mouth"), new CaveMouths.Candidate(-104, 80, -58, "mouth"));
        List<CaveMouths.Candidate> out = CavePatterns.merge(in);
        assertEquals(3, out.size(), out.toString());
        // the lowest point is kept
        List<CaveMouths.Candidate> two = CavePatterns.merge(List.of(new CaveMouths.Candidate(0, 70, 0, "mouth"), new CaveMouths.Candidate(5, 66, 0, "mouth")));
        assertEquals(1, two.size());
        assertEquals(66, two.get(0).y());
        // across chunk borders too (the loaded candidates' list)
        CaveMouths m = new CaveMouths();
        m.put(-4, 1, List.of(new CaveMouths.Candidate(-53, 63, 16, "ravine")));
        m.put(-3, 1, List.of(new CaveMouths.Candidate(-48, 63, 18, "ravine")));
        assertEquals(1, m.near(-50, 63, 17, 30).size());
    }
}
