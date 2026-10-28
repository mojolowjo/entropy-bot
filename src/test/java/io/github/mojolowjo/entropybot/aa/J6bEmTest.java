package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run EM (04:01:48-04:06:31: the nearest known cave alone was asked - SMALL ones dropped one after another,
 * the LARGE 183 65 49 never priced; then boxed in at the top of a water column at -4 72 400 at depth 0, no way out
 * priced): every known cave ranked, the short ones dropped with their reason and the next asked; a fluid column is no step;
 * a boxed-in explore is offered the walk to the ring's top at any depth.
 */
class J6bEmTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static CaveWay cw(int x, int y, int z, double dist, CaveIndex.Size size, double unseenMinutes) {
        return new CaveWay(x, y, z, dist, "minecraft:iron_ore", "iron", 0.5, false, size, unseenMinutes, List.of());
    }

    /**
     * EM's nine caves from where the explore was boxed in: the six SMALL ones nearest (each holds 1 min; 3 ore at the
     * 0.5/min prior take 6), then 187 60 65 (its fed record holds ~0 min), then the LARGE 183 65 49 and -59 63 71: the
     * short seven are dropped with their reasons, the two LARGE priced, and 183 65 49 (the nearer) is picked.
     */
    @Test
    void emsNineCaves_theSmallOnesDropped_183_65_49_pricedAndPicked() {
        List<CaveWay> ranked = List.of(
                cw(-4, 76, 404, 5, CaveIndex.Size.SMALL, -1), cw(-3, 75, 420, 20, CaveIndex.Size.SMALL, -1), cw(-9, 65, 393, 12, CaveIndex.Size.SMALL, -1),
                cw(144, 70, 351, 30, CaveIndex.Size.SMALL, -1), cw(179, 68, 361, 40, CaveIndex.Size.SMALL, -1), cw(193, 64, 307, 50, CaveIndex.Size.SMALL, -1),
                cw(187, 60, 65, 60, CaveIndex.Size.SMALL, 0.02), cw(183, 65, 49, 70, CaveIndex.Size.LARGE, -1), cw(-59, 63, 71, 300, CaveIndex.Size.LARGE, -1));
        CaveWay all = ranked.get(0).withMore(ranked.subList(1, ranked.size()));
        assertEquals(9, all.all().size());
        Chain c = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(all));
        assertTrue(Z77CaveWayTest.caveMined(c), "a cave way runs: " + c.why());
        String why = c.why();
        // 0.39.z78 J6c run ET (B, rule 23 (1)): the SMALL caves are no longer dropped - each gives its share (0.5 / min over
        // its minute: 1 ore) and the rest comes from the next way; three of them (5 m, 20 m, 12 m: 374.6 s) beat 183 65 49's
        // 3 ore at 0.5 / min (6 min) on price - the LARGE one is still priced (rule 1), never ranked out by the shares
        List<Step.Cave> legs = c.steps().stream().filter(s -> s instanceof Step.Cave).map(s -> (Step.Cave) s).toList();
        assertEquals(List.of(-4, 76, 404), List.of(legs.get(0).x(), legs.get(0).y(), legs.get(0).z()), "the nearest small cave first: " + why);
        assertTrue(legs.stream().allMatch(l -> l.n() == 1), "a share each: " + c.steps());
        assertTrue(why.contains("1 of 3 ore from the cave, the other 2 from: "), why);
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("known cave 70 m: ") && d.contains(" s (dearer than")), "183 65 49 priced, lost on price: " + c.dropped());
        assertTrue(why.contains("known cave 60 m at 187 60 65") && why.contains("min its unseen part holds - the next cave is asked"), "the fed record's ~0 min: still out, said: " + why);
    }

    /** More caves holding the need than the top two: the rest are ranked out, said (rule 18 as amended). */
    @Test
    void pastTheTopTwo_theRestAreRankedOut() {
        List<CaveWay> ranked = List.of(cw(10, 40, 0, 30, CaveIndex.Size.LARGE, -1), cw(20, 40, 0, 60, CaveIndex.Size.LARGE, -1), cw(30, 40, 0, 90, CaveIndex.Size.LARGE, -1));
        Chain c = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(ranked.get(0).withMore(ranked.subList(1, 3))));
        Step.Cave k = (Step.Cave) c.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertEquals(10, k.x(), "the nearest of the two priced: " + c.why());
        assertTrue(c.why().contains("known cave 90 m at 30 40 0: ranked out - the 2 nearest that hold the need are priced"), c.why());
    }

    /** A cave with every opening held is out; the next cave leads (run EF's holds per opening, run EM's list). */
    @Test
    void aHeldCave_theNextLeads() {
        CaveWay two = cw(10, 40, 0, 30, CaveIndex.Size.LARGE, -1).withMore(List.of(cw(20, 40, 0, 60, CaveIndex.Size.LARGE, -1)));
        CaveWay left = two.without(k -> k.equals(CaveWay.keyOf(new int[]{10, 40, 0})));
        assertEquals(List.of(20, 40, 0), List.of(left.x(), left.y(), left.z()));
        assertNull(two.without(k -> true));
    }

    /** EM's -4 73 400: the feet at the top of a water column, the rim one up - a fluid is no step, so the leg is needed. */
    @Test
    void emsWaterColumn_isNoStep_theLegIsNeeded() {
        int[] feet = {-4, 73, 400};
        java.util.function.IntBinaryOperator surface = (x, z) -> (x == -4 && z == 400) ? 73 : 74;
        int[] top = SurfaceHeight.ringTarget(feet, surface, (x, y, z) -> !(x == -4 && z == 400));
        assertNotNull(top);
        assertEquals(74, top[1]);
        assertFalse(SurfaceHeight.legNeeded(feet, surface, top), "read as ground, the water's top is one step under the rim (run EM's no leg)");
        assertTrue(SurfaceHeight.legNeeded(feet, surface, top, (x, z) -> x == -4 && z == 400), "a fluid column: no step out - the leg");
        assertFalse(SurfaceHeight.legNeeded(new int[]{0, 64, 0}, (x, z) -> 64, new int[]{0, 64, 0}, (x, z) -> x == 0 && z == 0), "the top is the feet's own column: no leg");
    }

    /** Boxed in at depth 0 (EM's -4 72 400): the walk to the ring's top is offered, never "no walk up, no climb". */
    @Test
    void boxedInAtDepthZero_theWalkToTheRingsTopIsOffered() {
        int[] feet = {8, 62, 1};
        var over = NeedChain.Surface.noLeg(0, feet, false, new int[]{9, 64, 1}, 2.2);
        // EM: the band's walk down had no path ("walk to in:-4 15 400: UNREACHABLE"), so the band leg drops
        PathPrice noBandWalk = (x, y, z, d) -> y < 40 ? PathPrice.Answer.unreachable("no path: the complete search found none")
                : PathPrice.Answer.priced(new PathPrice.Walk(FakePaths.walkS(d)));
        Chain boxed = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of(NeedChain.boxedInKey(feet), "3 no-break walks found no path")).withPath(noBandWalk));
        assertTrue(boxed.ok(), boxed.why());
        assertEquals(new Step.ToSurface(9, 64, 1), boxed.steps().get(0), "the walk to the ring's top first: " + boxed.steps());
        Chain free = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()));
        assertTrue(free.steps().stream().noneMatch(s -> s instanceof Step.ToSurface), "not boxed: 1-block steps, no leg - " + free.steps());
    }
}
