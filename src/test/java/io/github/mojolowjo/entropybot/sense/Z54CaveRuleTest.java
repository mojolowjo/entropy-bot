package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z54 A1-A5 scenarios (labelling walk on z53 and the owner's flight on a new seed, docs/runs/night-2026-10-23.md):
 * the bot's verdicts weigh nothing while calibrating, floors for the derived cliff and depth, leaves and logs are not
 * ground, the darkest sky alone needs a vote margin, no bundled seed. Hand-made data only (no owner labels in the repo).
 */
class Z54CaveRuleTest {

    @AfterEach
    void defaults() { Tuning.reset(); }

    static CavePatterns.Hole hole(int depth, int pocket, int darkSky) {
        return new CavePatterns.Hole(0, 0, 70, 70 - depth, depth, 15, pocket, darkSky, false);
    }

    // ---- A1: bot verdicts weigh nothing ----

    @Test
    void a1BotPatchesShapeNoRuleAndTakeNoPlaceInTheVote() {
        assertEquals(0.0, Tuning.get("cave.botLabelWeight"), "owner's ruling 2026-10-24: 0 while calibrating");
        CavePatterns p = new CavePatterns();
        int[] spot = Z50CavePatternTest.mouth(6, 2);
        // three bot caves exactly like the spot, with tiny numbers that would drag the rule down
        for (int i = 0; i < 3; i++) p.add(new CavePatterns.Patch(i, 70, 0, "cave", "bot", 1, 0, 14, spot, null));
        assertEquals(CavePatterns.defaults(), p.rule(), "bot patches only: no cave pattern, the Tuning defaults");
        p.add(new CavePatterns.Patch(500, 70, 0, "cliff", "owner", 9, 0, 12, Z50CavePatternTest.mouth(9, 0), null));
        CavePatterns.Vote v = p.vote(spot, 7, 0, 11, true, null);
        assertEquals(0.0, v.caveW(), 1e-9, "the bot caves fill none of the k nearest places");
        assertEquals(1.0, v.otherW(), 1e-9);
        assertFalse(v.cave());
        assertEquals(3, p.botEntries(), "the bot entries stay readable (counted at load)");
    }

    // ---- A2: floors ----

    @Test
    void a2TheDerivedCliffAndDepthNeverFallBelowTheirFloors() {
        assertEquals(Tuning.get("cave.cliffDrop"), Tuning.get("cave.cliffFloor"), "the cliff floor is the template's cliff");
        CavePatterns p = new CavePatterns();
        p.add(new CavePatterns.Patch(0, 70, 0, "cave", "owner", 2, 10, 0, Z50CavePatternTest.mouth(1, 1), null));
        CavePatterns.Rule r = p.rule();
        assertEquals(Tuning.i("cave.cliffFloor"), r.cliff());
        assertEquals(Tuning.i("cave.depthFloor"), r.depth());
        assertTrue(r.line().contains("(floors hold: cliff 5 (derived 1), depth 5 (derived 2))"), r.line());
        // a bump shape no longer passes the derived rule as a candidate (run label: +1/+1 "ravines")
        int[] top = new int[256];
        java.util.Arrays.fill(top, 62);
        for (int lz = 0; lz < 16; lz++) { top[6 + 16 * lz] = 63; top[8 + 16 * lz] = 63; }
        assertTrue(CaveMouths.shapes(0, 0, top, null, r).candidates().isEmpty(), "a +1/+1 cut is no candidate under the floor");
    }

    @Test
    void a2NoFloorLineWhenTheDerivedRuleIsAbove() {
        CavePatterns p = new CavePatterns();
        p.add(new CavePatterns.Patch(0, 70, 0, "cave", "owner", 9, 10, 0, Z50CavePatternTest.mouth(8, 3), null));
        CavePatterns.Rule r = p.rule();
        assertEquals(8, r.cliff());
        assertEquals(9, r.depth());
        assertEquals("", r.floors());
        assertFalse(r.line().contains("floors hold"), r.line());
    }

    // ---- A3: leaves and logs are not ground ----

    /** A fake chunk: flat dirt to y 62; two trees (logs 63..67, a 5 x 5 leaf crown 66..70) with a 2-column gap between. */
    static final class Forest {
        final Map<Long, String> blocks = new HashMap<>();
        Forest tree(int x, int z) {
            for (int y = 63; y <= 67; y++) blocks.put(Seen.key(x, y, z), "log");
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = 66; y <= 70; y++)
                blocks.putIfAbsent(Seen.key(x + dx, y, z + dz), "leaves");
            return this;
        }
        String at(int x, int y, int z) { return y <= 62 ? "dirt" : blocks.getOrDefault(Seen.key(x, y, z), "air"); }
        int heightmap(int x, int z) { int y = 80; while (y > 62 && at(x, y, z).equals("air")) y--; return y; }
        IntPredicate canopy(int x, int z) { return y -> { String b = at(x, y, z); return b.equals("air") || b.equals("leaves") || b.equals("log"); }; }
    }

    @Test
    void a3ATreeOnFlatGroundIsNoCliff() {
        Forest f = new Forest().tree(4, 8).tree(11, 8);
        int[] raw = new int[256], ground = new int[256];
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                raw[lx + 16 * lz] = f.heightmap(lx, lz);
                ground[lx + 16 * lz] = CaveMouths.ground(raw[lx + 16 * lz], f.canopy(lx, lz));
            }
        assertEquals(70, raw[4 + 16 * 8], "the heightmap counts the leaves");
        assertEquals(62, ground[4 + 16 * 8], "the ground under the trunk is the dirt");
        assertEquals(62, ground[7 + 16 * 8]);
        assertFalse(CaveMouths.shapes(0, 0, raw, null).candidates().isEmpty(), "the canopies read as a ravine on the raw heightmap (the z53 forest spots)");
        assertTrue(CaveMouths.shapes(0, 0, ground, null).candidates().isEmpty(), "on the ground heights: flat, no candidate");
    }

    @Test
    void a3TheWalkIsBoundedAndSaysSo() {
        IntPredicate allLeaves = y -> true;
        int g = CaveMouths.ground(200, allLeaves);
        assertEquals(200, g, "past cave.canopyMaxDepth the heightmap top is kept");
        assertTrue(CaveMouths.canopyCapped(200, g, allLeaves));
        IntPredicate grass = y -> false;
        assertEquals(64, CaveMouths.ground(64, grass), "a top that is no leaf, log or air is the ground itself");
        assertFalse(CaveMouths.canopyCapped(64, 64, grass));
    }

    // ---- A4: the darkest sky alone needs a vote margin ----

    @Test
    void a4DarkestSkyAloneStaysACandidateWithoutTheMargin() {
        CavePatterns.Rule r = Z50CavePatternTest.fakes().rule();
        CavePatterns.Hole skyOnly = hole(9, 0, 3);
        CavePatterns.Vote twoToOne = new CavePatterns.Vote(true, 2, 1, "test");
        assertNull(CavePatterns.verdict(skyOnly, r, twoToOne), "not dropped: the darkest sky passes the dark proof");
        String wait = CavePatterns.hold(skyOnly, r, twoToOne);
        assertNotNull(wait);
        assertTrue(wait.startsWith("darkest sky only, pocket 0, vote cave 2.0 vs not 1.0"), wait);
        assertNull(CavePatterns.hold(skyOnly, r, new CavePatterns.Vote(true, 3, 0, "test")), "the nearest three owner labels all say cave: confirmed");
        assertNull(CavePatterns.hold(hole(9, 7, 12), r, twoToOne), "the pocket proof confirms alone, as before");
        assertNotNull(CavePatterns.hold(skyOnly, r, new CavePatterns.Vote(true, 0, 0, "none")), "no patterns: no vote margin, the darkest sky alone waits");
        CaveList.Entry e = CaveList.held(1, 2, 3, "ravine", wait);
        assertTrue(e.verdict().startsWith("candidate: darkest sky only"), e.verdict());
    }

    // ---- A5: no bundled seed ----

    @Test
    void a5NoLabelDataShipsInTheJar() {
        assertNull(CavePatterns.class.getResource("/entropybot/cave-patterns-seed.json"), "owner: no label data in the public jar");
        assertFalse(new CavePatterns().rule().derived(), "a fresh install: the Tuning defaults");
    }
}
