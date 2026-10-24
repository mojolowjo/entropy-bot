package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.brain.FastEnd;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** docs/SCENARIOS.md S51-S53 (0.39.z43): a fact that arrives ends a job as a success; mine at the ore's level. */
class Z43StripWayTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    // vanilla 1.21.1 data/minecraft/worldgen/placed_feature (read from the client jar), the configured features' sizes 9 and 4
    static final String MIDDLE = "{\"feature\":\"minecraft:ore_iron\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":10},{\"type\":\"minecraft:in_square\"},"
            + "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:trapezoid\",\"max_inclusive\":{\"absolute\":56},\"min_inclusive\":{\"absolute\":-24}}},{\"type\":\"minecraft:biome\"}]}";
    static final String SMALL = "{\"feature\":\"minecraft:ore_iron_small\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":10},{\"type\":\"minecraft:in_square\"},"
            + "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:uniform\",\"max_inclusive\":{\"absolute\":72},\"min_inclusive\":{\"above_bottom\":0}}},{\"type\":\"minecraft:biome\"}]}";
    static final String UPPER = "{\"feature\":\"minecraft:ore_iron\",\"placement\":[{\"type\":\"minecraft:count\",\"count\":90},{\"type\":\"minecraft:in_square\"},"
            + "{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:trapezoid\",\"max_inclusive\":{\"absolute\":384},\"min_inclusive\":{\"absolute\":80}}},{\"type\":\"minecraft:biome\"}]}";

    static OreBand.Feature feature(String placed, int size) {
        return OreBand.of(JsonParser.parseString(placed).getAsJsonObject().getAsJsonArray("placement"), size, -64, 320);
    }

    static List<OreBand.Feature> iron() { return List.of(feature(MIDDLE, 9), feature(SMALL, 4), feature(UPPER, 9)); }

    /** S51: the explore while paths compute ends on "a walk's path is known now": a success, never "changing nothing". */
    @Test
    void s51_anEndOnAFactThatArrivedIsASuccess() {
        String runD = "ok: done exploring for 5 min; a walk's path is known now (walk to -28 70 -51: 14 s, complete); " + FastEnd.FACT_ARRIVED;
        assertNull(FastEnd.verdict(runD, 375, Map.of(), Map.of(), 0, null), "a landed path is the end it waited for");
        String source = "ok: a source of raw_iron is known: iron_ore at 1 2 3 (4 away); " + FastEnd.FACT_ARRIVED;
        assertNull(FastEnd.verdict(source, 10, Map.of(), Map.of(), 0, null));
        // the guard keeps catching the real no-op
        assertNotNull(FastEnd.verdict("done: bootstrap", 375, Map.of(), Map.of(), 0, null));
    }

    @Test
    void theBandPeakComesFromTheTrapezoidAndTheUniformRange() {
        OreBand.Feature m = feature(MIDDLE, 9), s = feature(SMALL, 4);
        assertEquals(new OreBand.Feature(-24, 56, 0, 10, 9), m);
        assertEquals(16, m.peak());
        assertEquals(new OreBand.Feature(-64, 72, 136, 10, 4), s);
        assertEquals(4, s.peak(), "uniform: the middle");
        assertEquals(10 * 9 * 2.0 / 80, m.perY(16), 1e-9, "a trapezoid of base 80 peaks at 2/80");
        assertEquals(10 * 4 / 136.0, s.perY(-30), 1e-9, "uniform: flat");
        assertEquals(0, m.perY(57));
        assertNull(OreBand.of(JsonParser.parseString("[{\"type\":\"minecraft:count\",\"count\":3}]").getAsJsonArray(), 9, -64, 320), "no height range: not a height-placed ore");
    }

    @Test
    void theBlocksPerOreComeFromTheCountAndTheSize() {
        List<OreBand.Feature> fs = iron();
        assertEquals(16, OreBand.bestY(fs, 63), "the upper feature is above the feet: a strip digs down");
        double slab = OreBand.perSlab(fs, 16);
        assertEquals(32.4 + 16 * 40 / 136.0, slab, 1e-6);
        assertEquals(256 * 16 / slab, OreBand.blocksPerOre(slab), 1e-6);
        assertEquals(110, Math.round(OreBand.blocksPerOre(slab)));
        assertEquals(Tuning.get("strip.maxBlocksPerOre"), OreBand.blocksPerOre(0), "an empty band: the cap");
        Tuning.set("strip.maxBlocksPerOre", 50);
        assertEquals(50, OreBand.blocksPerOre(slab), "capped");
    }

    static StripBand ironBand() {
        List<OreBand.Feature> fs = iron();
        return new StripBand(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), -64, 384, OreBand.bestY(fs, 63), OreBand.perSlab(fs, 16), 0, 0, 63, "east", "minecraft:stone");
    }

    static NeedChain.World stripWorld(Map<String, Integer> bag, Map<String, List<NeedChain.Spot>> seen) {
        return ScenariosTest.w(bag, true, true, seen).withStrip(word -> word.contains("raw_iron") || word.contains("iron_ore") ? ironBand() : null);
    }

    /** S52: iron with no exposed source, a stone pickaxe in the bag, feet at y 63: the strip at y 16 is picked over the explore. */
    @Test
    void s52_noExposedIronStripsAtTheBandPeak() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("raw_iron", 3, stripWorld(Map.of("minecraft:stone_pickaxe", 1), Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("strip at y 16 (band -64..384) ~110 blocks/ore"), c.why());
        assertTrue(c.why().contains("x 3 ore = 332 blocks"), c.why());
        Step.Strip st = (Step.Strip) c.steps().stream().filter(s -> s instanceof Step.Strip).findFirst().orElseThrow();
        assertEquals(16, st.y());
        assertEquals("east", st.heading());
        assertEquals(Map.of("iron", 3), st.amounts(), "the amount is the strip's end rule");
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Deposit), "bag full: the deposit leg: " + c.steps());
        assertEquals("strip " + st.key(), NeedChain.sourceKey(st));
    }

    /** S52: no strip facts (multiplayer, no placement read): the surface explore stays the way. */
    @Test
    void s52_withoutStripFactsTheExploreStays() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("raw_iron", 3, ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), true, true, Map.of()));
        assertTrue(c.explore(), c.why());
    }

    /** S52 / rule 3: a strip that ended lava / no path / no pickaxe is the source fact: the order re-plans without it. */
    @Test
    void s52_aFailedStripIsDroppedForTheOrder() {
        ScenariosTest.world("wooden", "stone");
        var w = stripWorld(Map.of("minecraft:stone_pickaxe", 1), Map.of());
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        Step st = c.steps().stream().filter(s -> s instanceof Step.Strip).findFirst().orElseThrow();
        Chain again = NeedChain.resolve("raw_iron", 3, w.withoutSources(Map.of(NeedChain.sourceKey(st), "strip: lava ahead")));
        assertTrue(again.explore(), again.why());
        assertTrue(again.steps().stream().noneMatch(s -> s instanceof Step.Strip), again.steps().toString());
    }

    /** S53: the bag lacks a pickaxe: the chain makes the stone pickaxe first, then strips. */
    @Test
    void s53_noPickaxePutsThePickaxeFirst() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("raw_iron", 3, stripWorld(Map.of(),
                Map.of("oak_log", List.of(ScenariosTest.tree(10, 6)), "cobblestone", List.of(ScenariosTest.STONE_12))));
        assertTrue(c.ok() && !c.explore(), c.why());
        int pick = -1, strip = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (s instanceof Step.Craft k && k.item().equals("stone_pickaxe")) pick = i;
            if (s instanceof Step.Strip && strip < 0) strip = i;
        }
        assertTrue(pick >= 0 && strip > pick, c.steps().toString());
        assertTrue(c.why().contains("with stone_pickaxe"), c.why());
    }
}
