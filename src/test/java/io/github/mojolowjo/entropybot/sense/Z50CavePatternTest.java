package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z50 scenarios (docs/SCENARIOS.md): S69 the hole walk descends and a mouth needs a pocket, not a cell; S70 the cave
 * rule comes from the labelled patterns (the owner's 16 verdicts of 2026-10-24, heights read from the cave-z49 save); S71
 * the owner labels spots, the bot's labels weigh less, one feature is one candidate.
 */
class Z50CavePatternTest {

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

    static CavePatterns seeded() {
        CavePatterns p = new CavePatterns();
        int[] skipped = new int[1];
        p.read(CavePatterns.seed(), skipped);
        assertEquals(0, skipped[0]);
        return p;
    }

    static CavePatterns.Patch at(CavePatterns p, int x, int z) {
        for (CavePatterns.Patch q : p.all()) if (q.x() == x && q.z() == z) return q;
        throw new AssertionError("no seed at " + x + " " + z);
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
        // z52: two dark proofs, either confirms; under the defaults (cave.maxSky 0) the darkest-sky rule passes one sky-0 cell
        assertNull(CavePatterns.verdict(one, d, none), "z52: one sky-0 cell meets the default darkest-sky rule");
        // a tunnel into the cliff: 2 high, 4 deep, dark past its first cell
        for (int x = 1; x <= 4; x++) for (int y = 63; y <= 64; y++) { g.air.put(Seen.key(x, y, 0), true); if (x > 1) g.sky.put(Seen.key(x, y, 0), 0); }
        CavePatterns.Hole tunnel = CavePatterns.walk(0, 0, g::top, g);
        assertEquals(7, tunnel.pocket());
        assertEquals(0, tunnel.darkSky());
        assertNull(CavePatterns.verdict(tunnel, d, none), "a pocket of 7 dark cells is a mouth");
    }

    @Test
    void s69TheThreeRealCavesFromTheSave() {
        CavePatterns p = seeded();
        // read from the cave-z49 region files (WORLD_SURFACE heightmap, block states, sky light) with the same walk
        CavePatterns.Patch c7 = at(p, -104, -58), c9 = at(p, -127, -88), c15 = at(p, -53, 16);
        assertEquals(7, c7.depth());
        assertEquals(228, c7.pocket(), "z52: re-measured at cave.pocketReach 10");
        assertEquals(27, c9.depth());
        assertEquals(5, c9.pocket(), "#9 at reach 10 (z52): 5 sky-0 cells");
        assertEquals(0, c9.darkSky());
        assertEquals(9, c15.depth());
        assertEquals(0, c15.pocket(), "#15: the darkest sky within 10 is 3 (z52)");
        assertEquals(3, c15.darkSky());
        for (CavePatterns.Patch q : p.all()) assertTrue(q.depth() > 0, "every spot has a depth now: " + q.x() + " " + q.z());
    }

    // ---- S70 ----

    @Test
    void s70TheRuleComesFromThePatterns() {
        CavePatterns p = seeded();
        assertEquals(17, p.size());
        CavePatterns.Rule r = p.rule();
        System.out.println("z50 " + r.line());
        int hm = 0;
        for (CavePatterns.Patch q : p.all()) if (q.cave() && p.vote(q.ws(), 0, 0, 0, false, q).cave()) hm++;
        System.out.println("z50 heightmap-stage leave-one-out: caves kept " + hm + " of 5");
        assertTrue(r.derived());
        assertEquals(16, r.patterns(), "the unknown #2 does not count");
        assertEquals("cave rule from 16 patterns: slope >= " + r.slope() + ", cliff >= " + r.cliff() + ", depth >= 7, pocket >= 6 or darkest sky <= 11", r.line());
        assertFalse(new CavePatterns().rule().derived(), "no pattern: the Tuning defaults");
        assertEquals(CavePatterns.defaults(), new CavePatterns().rule());
    }

    @Test
    void s70LeaveOneOutVote() {
        CavePatterns p = seeded();
        int right = 0, n = 0;
        StringBuilder b = new StringBuilder();
        for (CavePatterns.Patch q : p.all()) {
            if (!q.cave() && !q.notCave()) continue;
            CavePatterns.Vote v = p.vote(q.ws(), q.depth(), q.pocket(), q.darkSky(), true, q);
            n++;
            if (v.cave() == q.cave()) right++;
            b.append(q.label()).append(' ').append(q.x()).append(' ').append(q.z()).append(" -> ").append(v.cave() ? "cave" : "not").append("; ");
        }
        System.out.println("z50 leave-one-out: " + right + " of " + n + ": " + b);
        assertTrue(right >= n / 2, b.toString());
    }

    @Test
    void s70ATurnedPatchIsTheSamePatch() {
        CavePatterns p = seeded();
        CavePatterns.Patch c9 = at(p, -127, -88);
        for (int t = 0; t < 8; t++) assertEquals(0, CavePatterns.patchD2(c9.ws(), CavePatterns.turn(c9.ws(), t)), 1e-9);
    }

    // ---- S71 ----

    @Test
    void s71OwnerLabelsAndBotLabelsWeighLess() {
        CavePatterns p = new CavePatterns();
        int[] flat = new int[81];
        p.add(new CavePatterns.Patch(0, 63, 0, "cave", "bot", 5, 10, 0, flat, null));
        p.add(new CavePatterns.Patch(9, 63, 9, "cliff", "owner", 5, 10, 0, flat, null));
        CavePatterns.Vote v = p.vote(flat, 5, 10, 0, true, null);
        assertEquals(0.5, v.caveW(), 1e-9);
        assertEquals(1.0, v.otherW(), 1e-9);
        assertFalse(v.cave(), "the owner's cliff beats the bot's cave");
        String json = p.toJson();
        CavePatterns back = new CavePatterns();
        int[] sk = new int[1];
        back.read(json, sk);
        assertEquals(0, sk[0]);
        assertEquals(2, back.size());
        assertEquals("bot", back.all().get(0).by());
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
