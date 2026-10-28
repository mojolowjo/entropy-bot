package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run EH (the owner's ruling; 00:40:19-21: the bot at the bottom of its own 5-deep 1x1 shaft, open to the sky,
 * 20 no-break walks with no path, the explore frozen, every iron order stopped): the ONE "below the surface" fact counts a
 * sky-open shaft; the explore's plan walks up to the surface first (an ordinary walk: Baritone may dig steps out); a no-break
 * explore boxed in ends at once with that fact; a freeze or boxed-in is answered by the walk up, never a hold the bot
 * cannot lift.
 */
class J6bEhTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int K = SurfaceHeight.NOT_KNOWN;

    @Test
    void theHoleRoundTheFeet_fromTheNeighbouringColumns() {
        int[] shaft = {67, 67, 67, 67, 67, 67, 67, 67};
        assertEquals(5, SurfaceHeight.holeDepth(62, shaft), "the 5-deep shaft: every neighbour's ground 5 over the feet");
        assertEquals(0, SurfaceHeight.holeDepth(66, shaft), "one step below: a player walks out");
        assertEquals(2, SurfaceHeight.holeDepth(65, shaft), "two below on every side: walled in");
        assertEquals(0, SurfaceHeight.holeDepth(62, new int[]{67, 67, 63, 67, 67, 67, 67, 67}), "one side a step up: the way out");
        assertEquals(0, SurfaceHeight.holeDepth(62, new int[]{K, K, K, K, K, K, K, K}), "no neighbour known: no depth claimed (rule 15)");
        assertEquals(5, SurfaceHeight.belowSurface(0, 5), "open to the sky (no rock over the head), 5 in the hole: 5 below the surface");
        assertEquals(20, SurfaceHeight.belowSurface(20, 0));
    }

    /** 48c2209b review 1: a climb started at the bottom of a 5-deep sky-open shaft is not done until the feet stand on the surface. */
    @Test
    void aClimbInASkyOpenShaft_isNotDoneUntilTheFeetStandOnTheSurface() {
        int[] around = {67, 67, 67, 67, 67, 67, 67, 67};
        int inShaft = io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbDepth(0, SurfaceHeight.belowSurface(0, SurfaceHeight.holeDepth(62, around)));
        assertEquals(5, inShaft, "no rock over the head, 5 below the ground round it");
        assertNull(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(inShaft, true, "8 62 1"), "climbing on (was: done at once, 'already under open sky')");
        assertFalse(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(inShaft, false, "8 62 1").startsWith("ok"), "Baritone stopped in the shaft: not done");
        int onTop = io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbDepth(0, SurfaceHeight.belowSurface(0, SurfaceHeight.holeDepth(67, around)));
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(onTop, true, "8 67 1").startsWith("ok: on the surface"), "the feet on the surface: done");
        assertEquals(-1, io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbDepth(-1, 0), "the surface not known: never done by it (rule 15)");
    }

    /**
     * Run EI (the manager's fix): the EI pit - the bot at 9 65 4, our own walk's cut lowered one neighbour (9 _ 3) to 65, the
     * rim round it at 67: the surface leg's target is the ring's highest standable cell (the rim, never the notch), and the
     * leg is priced though the old "below the surface" test reads 0 there (the gate is gone).
     */
    @Test
    void theEiPit_theLegTargetsTheRim_andIsPricedWithoutTheGate() {
        int[] feet = {9, 65, 4};
        java.util.function.IntBinaryOperator surface = (x, z) -> (x == 9 && (z == 4 || z == 3)) ? 65 : 67;
        int[] t = SurfaceHeight.ringTarget(feet, surface, (x, y, z) -> true);
        assertEquals(67, t[1], "the rim's height, never the notch at 65");
        assertEquals(List.of(8, 67, 4), List.of(t[0], t[1], t[2]), "the nearest rim cell");
        int[] around = new int[8];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) around[i++] = surface.applyAsInt(9 + dx, 4 + dz);
        assertEquals(0, SurfaceHeight.belowSurface(0, SurfaceHeight.holeDepth(65, around)), "the old gate: 'on the surface' (the notch fooled it)");
        Chain c = NeedChain.resolve("oak_log", 4, Z68ExploreWhereTest.logsWorld(new NeedChain.Surface(0, t[0], t[1], t[2], Math.sqrt(5), false)));
        assertEquals(new Step.ToSurface(8, 67, 4), c.steps().get(0), "the leg to the rim is priced at depth 0: " + c.steps());
        int[] flat = SurfaceHeight.ringTarget(new int[]{0, 64, 0}, (x, z) -> 64, (x, y, z) -> true);
        assertArrayEquals(new int[]{0, 64, 0}, flat, "flat ground: the feet's own cell - no leg");
        assertNull(SurfaceHeight.ringTarget(feet, (x, z) -> SurfaceHeight.NOT_KNOWN, (x, y, z) -> true), "nothing known: no target (rule 15)");
    }

    /** 857599bc review (cost): on a slope of 1-block steps no leg (the no-break explore walks up itself); in the EI pit a leg. */
    @Test
    void aSlopeOfOneBlockSteps_hasNoLeg_theEiPitHasOne() {
        int[] feet = {0, 64, 0};
        java.util.function.IntBinaryOperator slope = (x, z) -> 64 + x;   // one block up per column east
        int[] top = SurfaceHeight.ringTarget(feet, slope, (x, y, z) -> true);
        assertEquals(67, top[1], "the ring's local top, 3 up");
        assertFalse(SurfaceHeight.legNeeded(feet, slope, top), "1-block steps: the no-break walk climbs them - no hops before the explore");
        int[] pitFeet = {9, 65, 4};
        java.util.function.IntBinaryOperator pit = (x, z) -> (x == 9 && (z == 4 || z == 3)) ? 65 : 67;
        assertTrue(SurfaceHeight.legNeeded(pitFeet, pit, SurfaceHeight.ringTarget(pitFeet, pit, (x, y, z) -> true)), "the EI pit: a 2-block rise - the leg");
        assertFalse(SurfaceHeight.legNeeded(pitFeet, pit, null), "no target: no leg");
    }

    /** Run EI (2): the probe nobreak line with the fork's calc detail (lastNoBreakCalc); none kept, or not Baritone's A*, said so. */
    @Test
    void theProbeNobreakLine() {
        String l = ExploreRules.noBreakLine(new int[]{9, 65, 4}, "the surface cell of -56 88", "no path (no-break walk)", null, null, null);
        assertEquals("probe nobreak: feet 9 65 4 | goal the surface cell of -56 88 | lastEnd no path (no-break walk) | calc none (the fork kept no no-break calc)"
                + " | nodes opened - | best node unknown", l);
        assertTrue(ExploreRules.noBreakLine(null, "g", "e", "FAILURE", 12, "9 66 4").endsWith("calc FAILURE | nodes opened 12 | best node 9 66 4"));
        assertTrue(ExploreRules.noBreakLine(null, "g", "e", "unknown", -1, null).endsWith("nodes opened unknown (not Baritone's A*) | best node unknown"));
    }

    static NeedChain.World iron(NeedChain.Surface over, Map<String, String> held) {
        ScenariosTest.world("wooden", "stone");
        var w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), false, false, Map.of())
                .withMobs(x -> List.of(), Z77ExploreTest.book()).withStrip(word -> null)
                .withWhere(new NeedChain.WhereFacts(Z77ExploreTest::where, () -> over, () -> new int[]{8, 62, 1}));
        return held.isEmpty() ? w : w.withoutSources(held);
    }

    /** The scenario: the bot at the bottom of a 5-deep 1x1 shaft open to the sky - the surface walk first, then the no-break explore. */
    @Test
    void aFiveDeepSkyOpenShaft_theExploresPlanIsASurfaceWalkThenTheExplore() {
        Chain c = NeedChain.resolve("raw_iron", 3, iron(new NeedChain.Surface(5, 9, 67, 1, 5.1, true), Map.of()));
        assertTrue(c.ok() && c.explore(), c.why());
        assertEquals(new Step.ToSurface(9, 67, 1), c.steps().get(0), "the walk up to the surface first: " + c.steps());
        assertTrue(c.steps().get(1) instanceof Step.Explore, c.steps().toString());
    }

    /** A 2-deep hole is below the surface too (more than a step); 2 blocks of rock over the head is not (aaLadder.surfaceDepth 3). */
    @Test
    void aTwoDeepHoleWalksUpFirst_twoOfRockDoesNot() {
        Chain hole = NeedChain.resolve("raw_iron", 3, iron(new NeedChain.Surface(2, 9, 64, 1, 2.2, true), Map.of()));
        assertEquals(new Step.ToSurface(9, 64, 1), hole.steps().get(0), hole.steps().toString());
        Chain rock = NeedChain.resolve("raw_iron", 3, iron(new NeedChain.Surface(2, 9, 64, 1, 2.2, false), Map.of()));
        assertTrue(rock.steps().stream().noneMatch(s -> s instanceof Step.ToSurface), "as before: " + rock.steps());
    }

    /** (2) and (3): a boxed-in or frozen explore here is answered by the walk up first - never "held until the bot moves". */
    @Test
    void aBoxedInOrFrozenExplore_isAnsweredByTheWalkUp_neverAHoldTheBotCannotLift() {
        assertTrue(ExploreRules.boxedIn(ExploreRules.BOXED_TRIES) && !ExploreRules.boxedIn(ExploreRules.BOXED_TRIES - 1));
        int[] feet = {8, 62, 1};
        var shallow = new NeedChain.Surface(2, 9, 64, 1, 2.2, false);
        Chain frozen = NeedChain.resolve("raw_iron", 3, iron(shallow, Map.of(NeedChain.exploreFrozenKey(feet), "Baritone found no path 20 times")));
        assertTrue(frozen.ok(), "not stopped: " + frozen.why());
        assertTrue(frozen.steps().get(0) instanceof Step.ToSurface || frozen.steps().get(0) instanceof Step.ToBand, "a leg that moves the bot first (the walk up, or the band leg): " + frozen.steps());
        Chain boxed = NeedChain.resolve("raw_iron", 3, iron(shallow, Map.of(NeedChain.boxedInKey(feet), "3 no-break walks found no path")));
        assertTrue(boxed.ok(), boxed.why());
        assertTrue(boxed.steps().get(0) instanceof Step.ToSurface || boxed.steps().get(0) instanceof Step.ToBand, boxed.steps().toString());
        Chain deepBoxed = NeedChain.resolve("raw_iron", 3, iron(new NeedChain.Surface(5, 9, 67, 1, 5.1, true), Map.of(NeedChain.boxedInKey(feet), "boxed")));
        assertEquals(new Step.ToSurface(9, 67, 1), deepBoxed.steps().get(0), "boxed in the shaft: the walk up first - " + deepBoxed.steps());
        Chain flat = NeedChain.resolve("raw_iron", 3, iron(new NeedChain.Surface(0, 8, 62, 1, 0), Map.of(NeedChain.boxedInKey(feet), "walls")));
        assertFalse(flat.ok() && flat.steps().get(0) instanceof Step.Explore, "boxed in: never the no-break explore from here first - " + flat.steps());
        FactChange.boxedIn(feet, "x");
        assertEquals(NeedChain.boxedInKey(feet), FactChange.ownSourceKey(FactChange.take()), "the fact keys the feet");
    }
}
