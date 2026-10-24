package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.sense.CaveMouths;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** docs/SCENARIOS.md S54-S55 (0.39.z44): cave mouths from the heightmap, confirmed by sight; the way "mine in the cave". */
class Z44CaveMouthTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** A flat chunk at y 64 with a square hole of side {@code w} at (x0, z0) going down {@code depth}. */
    static int[] flatWithHole(int x0, int z0, int w, int depth) {
        int[] top = new int[256];
        Arrays.fill(top, 64);
        for (int z = z0; z < z0 + w; z++) for (int x = x0; x < x0 + w; x++) top[x + 16 * z] = 64 - depth;
        return top;
    }

    // ---- the candidate rule ----

    @Test
    void s54_aSixDeepHoleIsOneCandidateAtItsLowestOpenCell() {
        int[] top = flatWithHole(6, 6, 3, 6);
        top[7 + 16 * 7] = 56;                                   // its deepest column
        List<CaveMouths.Candidate> c = CaveMouths.candidates(2, -1, top);
        assertEquals(1, c.size(), c.toString());
        assertEquals(new CaveMouths.Candidate(32 + 7, 57, -16 + 7, "hole"), c.get(0), "the air over the deepest floor");
    }

    @Test
    void aShallowDipDoesNotCount() {
        assertTrue(CaveMouths.candidates(0, 0, flatWithHole(6, 6, 3, 3)).isEmpty(), "3 deep < cave.holeDepth 4");
        assertFalse(CaveMouths.candidates(0, 0, flatWithHole(6, 6, 3, 4)).isEmpty(), "4 deep counts");
        Tuning.set("cave.holeDepth", 8);
        assertTrue(CaveMouths.candidates(0, 0, flatWithHole(6, 6, 3, 6)).isEmpty(), "the depth is Tuning's");
    }

    @Test
    void aRavineRunCountsAndAOneColumnNotchAlongACliffDoesNot() {
        int[] top = new int[256];
        Arrays.fill(top, 70);
        for (int z = 2; z < 12; z++) top[8 + 16 * z] = 60;     // a 1-wide trench along z, walled along x
        List<CaveMouths.Candidate> c = CaveMouths.candidates(0, 0, top);
        assertEquals(1, c.size(), "one candidate for the run (merged): " + c);
        assertEquals("ravine", c.get(0).shape());
        int[] notch = new int[256];
        Arrays.fill(notch, 70);
        for (int z = 0; z < 16; z++) for (int x = 9; x < 16; x++) notch[x + 16 * z] = 60;   // a cliff: low ground on one side only
        notch[8 + 16 * 5] = 60;
        assertTrue(CaveMouths.candidates(0, 0, notch).isEmpty(), "walled on one side only: no hole, no ravine");
        Tuning.set("cave.ravineRun", 11);
        assertTrue(CaveMouths.candidates(0, 0, top).isEmpty(), "a run shorter than cave.ravineRun: no ravine");
    }

    // ---- the confirmation and the states (rule 15) ----

    @Test
    void s54_seenAndDarkIsAMouthUnseenIsACandidateOnly() {
        assertTrue(CaveMouths.goesUnder(true, true, true, 0, false, 15), "the ray reached it, air, sky 0");
        assertTrue(CaveMouths.goesUnder(true, true, true, 9, true, 0), "the air under it is dark");
        assertFalse(CaveMouths.goesUnder(false, true, true, 0, true, 0), "not reached by a ray: no fact (rule 17)");
        assertFalse(CaveMouths.goesUnder(true, true, true, 12, false, 0), "sky-lit: a surface dip");
        assertFalse(CaveMouths.goesUnder(true, false, true, 0, true, 0), "open to the sky beside the hole: not inside it");

        Sightings sg = new Sightings();
        assertTrue(sg.mouths("o", 0, 0, 0).isEmpty(), "a candidate is not a source");
        assertEquals(CaveMouths.State.CANDIDATE, CaveMouths.state(false, false, true));
        assertNotNull(sg.sawMouth("o", 39, 57, -9, 18, 1000));
        assertNull(sg.sawMouth("o", 40, 57, -9, 18, 2000), "the same mouth merges");
        assertEquals(1, sg.mouths("o", 0, 0, 0).size());
        assertEquals(18, sg.mouths("o", 0, 0, 0).get(0).count(), "seen from 18 m");
        assertEquals(CaveMouths.State.SEEN, CaveMouths.state(true, true, true));
        assertEquals(CaveMouths.State.GONE, CaveMouths.state(true, true, false), "standing there, the cell filled");
        assertTrue(sg.forgetMouth("o", 39, 57, -9));
        assertTrue(sg.mouths("o", 0, 0, 0).isEmpty());
        // the caves map in sightings.json keeps it
        sg.sawMouth("o", 1, 2, 3, 7, 5);
        Sightings back = Sightings.fromJson(sg.toJson(), new int[1]);
        assertEquals(1, back.mouths("o", 0, 0, 0).size());
    }

    @Test
    void theCandidateStoreGoesWithItsChunk() {
        CaveMouths m = new CaveMouths();
        m.put(0, 0, CaveMouths.candidates(0, 0, flatWithHole(6, 6, 3, 6)));
        assertEquals(1, m.count());
        assertEquals(1, m.near(7, 64, 7, 24).size());
        assertTrue(m.near(200, 64, 7, 24).isEmpty());
        m.drop(0, 0);
        assertEquals(0, m.count(), "unloaded: gone (not loaded is not a fact)");
    }

    // ---- the way's price ----

    static CaveWay mouth(double dist, double perMin, boolean measured) {
        return new CaveWay(40, 60, 0, dist, "minecraft:iron_ore", "iron", perMin, measured);
    }

    static NeedChain.World caveWorld(CaveWay cw) {
        return ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), true, true, Map.of()).withCave(word -> word.contains("raw_iron") || word.contains("iron_ore") ? cw : null);
    }

    @Test
    void theWayIsTheWalkPlusTheYieldsMinutesPlusTheBreaks() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("raw_iron", 3, caveWorld(mouth(38, 1.5, true)));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("cave mouth 38 m, yield ~1.50 ore/min (measured) x 3 ore ->"), c.why());
        int walk = -1, mine = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (s instanceof Step.Walk k && k.x() == 40 && k.z() == 0) walk = i;
            if (s instanceof Step.Mine m && m.how() == Step.Mine.How.CAVE) {
                mine = i;
                assertEquals(3, m.n());
                assertEquals(10, m.minutes(), "bounded by cave.exploreMinutes");
                assertEquals("mine cave iron 3 10m", m.text());
            }
        }
        assertTrue(walk >= 0 && mine > walk, c.steps().toString());
        assertTrue(c.seconds() >= FakePaths.walkS(38) + 120, "2 min of caving for 3 ore at 1.5/min: " + c.seconds());
    }

    @Test
    void aYieldPastTheBoundDoesNotStandAndNoMouthIsNoWay() {
        ScenariosTest.world("wooden", "stone");
        Chain slow = NeedChain.resolve("raw_iron", 3, caveWorld(mouth(38, 0.2, false)));
        assertTrue(slow.explore(), "15 min > 10: " + slow.why());
        Chain none = NeedChain.resolve("raw_iron", 3, caveWorld(null));
        assertTrue(none.explore(), none.why());
    }

    /** S55: iron wanted, a mouth 40 m away with a measured yield, and the band strip: the cheaper wins, both are shown. */
    @Test
    void s55_theCaveAndTheStripCompeteOnPrice() {
        ScenariosTest.world("wooden", "stone");
        NeedChain.World strip = Z43StripWayTest.stripWorld(Map.of("minecraft:stone_pickaxe", 1), Map.of());
        double stripS = NeedChain.resolve("raw_iron", 3, strip).seconds();
        Chain fast = NeedChain.resolve("raw_iron", 3, strip.withCave(w -> w.contains("iron") ? mouth(40, 3, true) : null));
        Chain slow = NeedChain.resolve("raw_iron", 3, strip.withCave(w -> w.contains("iron") ? mouth(40, 0.35, true) : null));
        double fastCave = NeedChain.resolve("raw_iron", 3, caveWorld(mouth(40, 3, true))).seconds();
        double slowCave = NeedChain.resolve("raw_iron", 3, caveWorld(mouth(40, 0.35, true))).seconds();
        assertTrue(fastCave < stripS && slowCave > stripS, "the fixture: " + fastCave + " / " + slowCave + " vs strip " + stripS);
        assertTrue(fast.steps().stream().anyMatch(s -> s instanceof Step.Mine m && m.how() == Step.Mine.How.CAVE), fast.why());
        assertTrue(fast.why().contains("strip at y 16"), "the loser is shown: " + fast.why());
        assertTrue(slow.steps().stream().anyMatch(s -> s instanceof Step.Strip), slow.why());
        assertTrue(slow.why().contains("cave mouth 40 m"), "the loser is shown: " + slow.why());
    }

    @Test
    void aMouthWithNoPathIsDroppedForTheOrder() {
        ScenariosTest.world("wooden", "stone");
        var w = caveWorld(mouth(38, 1.5, true));
        Chain again = NeedChain.resolve("raw_iron", 3, w.withoutSources(Map.of(mouth(38, 1.5, true).key(), "unreachable")));
        assertTrue(again.explore(), again.why());
    }
}
