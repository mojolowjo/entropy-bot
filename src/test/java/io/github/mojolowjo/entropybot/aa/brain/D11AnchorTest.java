package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D11, the anchor rule (run EV 2026-10-28: the chestplate's trip was re-priced 2,037 times "while thinking" as the feet's Y
 * moved 31 -> -33 inside one step, every waiting trip's result dropped): a trip is keyed and its legs placed from where the
 * running step ENDS (z75: later steps are priced from where the bot will be), never from the live feet; a step that moves the
 * bot nowhere known keeps the feet.
 */
class D11AnchorTest {
    static final int[] FEET = {-52, 31, -41};

    @Test
    void theAnchorIsWhereTheRunningStepEnds() {
        assertArrayEquals(new int[]{10, 64, 20}, BatchWay.anchorOf(new Step.Walk(10, 64, 20, 0), FEET));
        assertArrayEquals(new int[]{7, 62, 0}, BatchWay.anchorOf(new Step.ToStation("furnace", "furnace", 7, 62, 0), FEET));
        assertArrayEquals(new int[]{62, 94, -354}, BatchWay.anchorOf(new Step.ToCave(62, 94, -354), FEET));
        assertArrayEquals(new int[]{9, 67, 1}, BatchWay.anchorOf(new Step.ToSurface(9, 67, 1), FEET));
        assertArrayEquals(new int[]{-45, 60, 78}, BatchWay.anchorOf(new Step.Cave(-45, 60, 78, "iron", 3, 10), FEET));
        assertArrayEquals(new int[]{-52, 16, -41}, BatchWay.anchorOf(new Step.ToBand(0, 32, null), FEET), "the band leg: the feet's column at the band's middle Y");
        assertSame(FEET, BatchWay.anchorOf(new Step.Craft("minecraft:stick", 4), FEET), "a craft moves the bot nowhere: the feet");
        assertSame(FEET, BatchWay.anchorOf(null, FEET), "no running step: the feet");
        assertNull(BatchWay.anchorOf(new Step.Walk(1, 2, 3, 0), null), "no feet known: none (rule 15)");
    }

    @Test
    void underGroundAtTheAnchor_fromTheStepKind_elseTheLiveReading() {
        assertFalse(BatchWay.anchorUnder(new Step.ToSurface(9, 67, 1), true), "a surface walk ends on the surface");
        assertFalse(BatchWay.anchorUnder(new Step.Climb(), true));
        assertTrue(BatchWay.anchorUnder(new Step.ToBand(0, 32, null), false), "a band leg ends under ground");
        assertTrue(BatchWay.anchorUnder(new Step.ToStrip(1, 16, 1, "s"), false));
        assertTrue(BatchWay.anchorUnder(new Step.Walk(1, 2, 3, 0), true), "a walk: the live reading");
        assertFalse(BatchWay.anchorUnder(null, false));
    }

    /**
     * D11 fix (run FF: "re-planned while thinking: surface" 6,801 times - the live surface fact over the feet changed every
     * block): a trip's surface fact is the surface over its ANCHOR, read once per anchor and held until the anchor changes.
     */
    @Test
    void theSurfaceOverTheAnchor_isReadOncePerAnchor_andNotKnownIsNotHeld() {
        java.util.Map<String, Object[]> cache = new java.util.HashMap<>();
        java.util.List<int[]> reads = new java.util.ArrayList<>();
        var surface = new io.github.mojolowjo.entropybot.aa.NeedChain.Surface(0, 9, 67, 1, 0);
        java.util.function.Function<int[], io.github.mojolowjo.entropybot.aa.NeedChain.Surface> reader = p -> { reads.add(p.clone()); return p[1] < 0 ? null : surface; };
        int[] anchor = {9, 67, 1};
        assertSame(surface, BatchWay.anchorSurface(cache, "b1", anchor, reader));
        assertSame(surface, BatchWay.anchorSurface(cache, "b1", new int[]{9, 67, 1}, reader), "the same anchor asked again: held, not read");
        assertSame(surface, BatchWay.anchorSurface(cache, "b1", anchor, reader));
        assertEquals(1, reads.size(), "one read per anchor");
        assertSame(surface, BatchWay.anchorSurface(cache, "b1", new int[]{20, 64, 5}, reader));
        assertEquals(2, reads.size(), "a new anchor: read again");
        assertNull(BatchWay.anchorSurface(cache, "b1", new int[]{0, -70, 0}, reader), "not known (the chunk not loaded)");
        assertNull(BatchWay.anchorSurface(cache, "b1", new int[]{0, -70, 0}, reader), "not held: asked again next time");
        assertEquals(4, reads.size());
        assertNull(BatchWay.anchorSurface(cache, "b2", anchor, null), "no reader published (tests, replay): none - the caller keeps the live surface");
        assertTrue(cache.isEmpty() || !cache.containsKey("b2"));
    }

    /**
     * FM fix (run FM: every iron order "stopped at step N (stand while paths compute)" within two minutes - the FH stand was
     * handed to the run without being taken as the order's pick, so the next re-price "kept" the old explore and ended the
     * stand with a null-steps answer): a taken standing chain is no priced way, and the next priced way replaces it.
     */
    @Test
    void aTakenStand_isTheOrdersPick_soTheNextPricedWayReplacesIt() {
        Goals.Goal g = Goals.draft(new Thing.Result("minecraft:iron_pickaxe", "iron_pickaxe", false, List.of(), List.of(), 0, null, "iron_pickaxe", null), 1, 0, 1000);
        var explore = new io.github.mojolowjo.entropybot.aa.NeedChain.Chain(List.of(new Step.Explore(5, "raw_iron")), 300, "explore", true, null, List.of(), Map.of(), List.of());
        Goals.took(g, explore, 1000);
        assertTrue(g.pricedWay && g.pricedExplore, "an explore chain is a priced way that ends in an explore");
        var stand = new io.github.mojolowjo.entropybot.aa.NeedChain.Chain(List.of(new Step.AwaitPaths(30)), 0, "stand (paths computing)", true, null, List.of(), Map.of(), List.of());
        Goals.took(g, stand, 2000);
        assertFalse(g.pricedWay, "a stand is no way");
        assertTrue(Double.isNaN(g.pricedS));
        assertFalse(g.pricedExplore);
        assertTrue(Goals.switches(g.pricedWay, Goals.leftS(g, 3000), g.pricedExplore, true, 49, false, 10), "the next priced way replaces the stand");
        assertTrue(Goals.switches(g.pricedWay, Goals.leftS(g, 3000), g.pricedExplore, false, 0, true, 10), "a stand again replaces the stand (never 'kept')");
    }

    /** The EV case: the feet walk 31 -> 20 -> -33 inside one band leg; the trip's key does not move. */
    @Test
    void walkingInsideOneStep_keepsTheTripsKey() {
        Step leg = new Step.ToBand(0, 32, null);
        String a = BatchWay.tripKeyOf("iron kit", List.of(), BatchWay.anchorOf(leg, new int[]{-52, 31, -41}), BatchWay.anchorUnder(leg, false), List.of());
        String b = BatchWay.tripKeyOf("iron kit", List.of(), BatchWay.anchorOf(leg, new int[]{-52, -33, -41}), BatchWay.anchorUnder(leg, true), List.of());
        assertEquals(a, b, "the same anchor (the band's middle, under ground) whatever the live Y");
        assertTrue(a.contains(" under ground"), a);
    }
}
