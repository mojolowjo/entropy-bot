package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.StripAreas;
import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 dz (runs DZ 19:09:51-19:28:38 and DP 12:55:37; to-do/dydz-heading-held.md): a hold is a fact, and the planner
 * prices the NEXT option from it (rules 23 and 1). DZ: water ahead held west, north (6 16 0), east (8 16 0) and south
 * (4 16 3) at y 16 for a strip from the feet - the strip way was gone from anywhere and two orders explored overland for
 * 13 min. DP: the walk into y 53 from the low spot failed, was held for the order, and the same dig down was priced again
 * (the same way twice: parked).
 */
class DzHeadingHeldTest {
    static final Map<String, Integer> BAG = Map.of("minecraft:stone_pickaxe", 1);

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** DZ's start: the feet 6 16 2 at the band's Y, every heading open, west first (the strip west was the first). */
    static StripBand dz(Map<Integer, Double> otherYs) {
        List<OreBand.Feature> fs = Z43StripWayTest.iron();
        return new StripBand(Z62RunSTest.IRON, -64, 384, 16, OreBand.perSlab(fs, 16), 6, 2, 16, "west", "minecraft:stone",
                StripOrder.headingOrder(null, "west"), 0, otherYs, null);
    }

    /** The run's water holds, as StripStep's ends give them (Goals.replan keeps a cause-cell hold unscoped). */
    static Map<String, String> water(List<String> headings) {
        Map<String, String> held = new HashMap<>();
        Map<String, String> cell = Map.of("west", "6 16 0", "north", "6 16 0", "east", "8 16 0", "south", "4 16 3");
        for (String h : headings) held.put(NeedChain.headingKey(16, h), "strip: water ahead at " + cell.get(h) + " (strip " + h + " feet y 16)");
        return held;
    }

    /** Three headings held by water: the fourth is the way, from the feet (as before). */
    @Test
    void threeHeadingsHeldByWater_theFourthIsTheStrip() {
        ScenariosTest.world("wooden", "stone");
        var w = Z62RunSTest.world(BAG, dz(Map.of())).withoutSources(water(List.of("west", "north", "east")));
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        Step.Strip s = Z62RunSTest.strip(c);
        assertEquals("south", s.heading());
        assertNull(s.from(), "from the feet");
    }

    /** DZ 19:10:15: all four held at the start - the next plan strips from another cell (fresh ground), priced, no explore, no park. */
    @Test
    void dz_everyHeadingHeldByWater_theNextPlanStripsFromAnotherCell() {
        ScenariosTest.world("wooden", "stone");
        var w = Z62RunSTest.world(BAG, dz(Map.of())).withoutSources(water(StripOrder.HEADINGS));
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        Step.Strip s = Z62RunSTest.strip(c);
        assertNotNull(s.from(), "another start cell (its own tunnel): " + s.text());
        assertTrue(c.steps().stream().anyMatch(x -> x instanceof Step.ToStrip), "a priced walk there first: " + c.steps());
        Step.ToStrip to = (Step.ToStrip) c.steps().stream().filter(x -> x instanceof Step.ToStrip).findFirst().orElseThrow();
        double away = Math.hypot(to.x() - 6, to.z() - 2);
        assertTrue(away >= Failures.movedV(), "at least failures.moved from the held start: " + away);
        assertTrue(c.why().contains("strip for raw_iron at y 16 held: west: strip: water ahead at 6 16 0"), "the held start said (rule 7): " + c.why());
        // the batch: no strip from ITS start - never "no strip way here" (the order's own chain prices the rest)
        Playbook pb = Playbook.derive(List.of(new Playbook.Target("minecraft:raw_iron", 3)), Playbook.facts(w, null));
        assertFalse(pb.ok());
        assertFalse(pb.why().contains("no strip way here"), pb.why());
    }

    /** Every start cell held too: the band's other level from the same cell is the strip. */
    @Test
    void everyStartCellHeld_theBandsOtherLevelIsTheStrip() {
        ScenariosTest.world("wooden", "stone");
        List<OreBand.Feature> fs = Z43StripWayTest.iron();
        Map<Integer, Double> other = OreBand.otherYs(fs, 16, 16);
        assertFalse(other.isEmpty(), "the band has another level under y 16");
        int y2 = other.keySet().iterator().next();
        assertTrue(y2 <= 16 - OreBand.slab(), "a slab apart, at or below the feet: " + y2);
        StripBand band = dz(other);
        Map<String, String> held = water(StripOrder.HEADINGS);
        held.putAll(freshHeld(band));
        Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(BAG, band).withoutSources(held));
        assertTrue(c.ok() && !c.explore(), c.why());
        Step.Strip s = Z62RunSTest.strip(c);
        assertEquals(y2, s.y(), "the band's other level: " + s.text());
        assertNull(s.from(), "from this cell, dug down to");
        assertTrue(c.why().contains("fresh ground north from area"), "each held fresh start said: " + c.why());
    }

    /** Every start cell and level held: no strip way (and only then), every reason said. */
    @Test
    void everyCellAndLevelHeld_noStripWay_saidWithEachReason() {
        ScenariosTest.world("wooden", "stone");
        StripBand band = dz(Map.of());
        Map<String, String> held = water(StripOrder.HEADINGS);
        held.putAll(freshHeld(band));
        Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(BAG, band).withoutSources(held));
        assertTrue(c.steps().stream().noneMatch(x -> x instanceof Step.Strip), c.steps().toString());
        for (String h : StripOrder.HEADINGS) assertTrue(c.why().contains("fresh ground " + h + " from area"), h + ": " + c.why());
    }

    /**
     * dz review (rule 16): a fresh start whose cell or first tunnel cell lies in a keep-out / no-break box is never offered
     * (never priced and walked to, then refused by the Guard at the dig); a box over every start of a heading leaves that
     * heading out, said; a box over the nearer cells moves the start past it, the skipped cells said.
     */
    @Test
    void aFreshStartInAKeepOutBoxIsNotOffered() {
        ScenariosTest.world("wooden", "stone");
        io.github.mojolowjo.entropybot.guard.Policy p = io.github.mojolowjo.entropybot.guard.Policy.parse(
                "{\"areas\":[{\"name\":\"vault\",\"type\":\"keep-out\",\"x1\":-10,\"y1\":0,\"z1\":-200,\"x2\":20,\"y2\":40,\"z2\":-10},"
                        + "{\"name\":\"shrine\",\"type\":\"no-break\",\"x1\":20,\"y1\":0,\"z1\":-5,\"x2\":30,\"y2\":40,\"z2\":9}],\"protect\":[]}");
        java.util.function.Function<int[], String> refused = c -> StripOrder.protectedAt(p, "minecraft:overworld", c[0], c[1], c[2]);
        assertEquals("in vault (keep-out)", refused.apply(new int[]{6, 16, -20}));
        StripAreas.refusal(refused);
        try {
            Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(BAG, dz(Map.of())).withoutSources(water(StripOrder.HEADINGS)));
            for (Step s : c.steps())
                if (s instanceof Step.ToStrip t) {
                    assertNull(refused.apply(new int[]{t.x(), t.y(), t.z()}), "the walk's start is outside every box: " + t.text());
                    assertNull(StripOrder.startRefused(refused, t.x(), t.y(), t.z(), Step.class.cast(c.steps().get(c.steps().indexOf(s) + 1)) instanceof Step.Strip st ? st.heading() : "north"), t.text());
                }
            assertTrue(c.why().contains("fresh ground north: every area square within strip.freshStartBlocks past 16 blocks is seen or in a keep-out / no-break box"), c.why());
            assertTrue(c.why().contains("fresh ground east (") && c.why().contains("nearer start cells): in a keep-out / no-break box, skipped (the break policy)"), c.why());
            int[] east = StripAreas.freshStart(6 + 15, 16, 2, "east");
            assertNotNull(east);
            assertTrue(east[0] > 30 && east[3] > 0, "east starts past the shrine: " + java.util.Arrays.toString(east));
        } finally {
            StripAreas.refusal(null);
        }
    }

    /** The fresh starts shutAlts asks (failures.moved on, the first unseen square), each held by its own heading key. */
    static Map<String, String> freshHeld(StripBand band) {
        Map<String, String> out = new HashMap<>();
        int away = (int) Math.ceil(Failures.movedV());
        for (String h : StripOrder.HEADINGS) {
            int[] d = WayUp.dir(h);
            int[] at = StripAreas.freshStart(band.x() + (away - 1) * d[0], band.y(), band.z() + (away - 1) * d[1], h);
            if (at == null) continue;
            StripBand f = band.freshAt(at[0], at[1], at[2] + away - 1, h);
            out.put(NeedChain.headingFrom(NeedChain.headingKey(16, h), f.from()), "lava ahead (test)");
        }
        return out;
    }

    /** DP 12:55:37: the walk into y 66 from this spot is held - the dig down is out from here, the next option is priced. */
    @Test
    void dp_aHeldBandEntryIsNotPricedAgain_theNextOptionIs() {
        Z74RunACTest.data();
        var w = Z74RunACTest.baseWorld(Map.of("minecraft:wooden_pickaxe", 1));
        Chain first = NeedChain.resolve("cobblestone", 8, w);
        assertEquals(new Step.ToBand(66, 66), first.steps().get(0), first.steps().toString());
        String why = "no path into the band from y 70 (not known after 30 s from here (no progress))";
        Chain again = NeedChain.resolve("cobblestone", 8, w.withoutSources(Map.of(FactChange.bandKey(66, 66), why)));
        assertTrue(again.steps().stream().noneMatch(s -> s instanceof Step.ToBand t && t.minY() == 66), "never the same way again: " + again.steps());
        assertTrue(again.why().contains("the walk into y 66 is held here (" + why + ")"), again.why());
    }
}
