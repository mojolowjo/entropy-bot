package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.CollectHand;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-sight (run JAH 00:29: "dig cobblestone seen 5 m" was stone under grass; Baritone's mine ended "none in sight"): a source
 * carries its cover fact (exposed or N blocks over it), a covered one is priced and stepped as a dig to it, and a "none in
 * sight" end at a spot called exposed marks it covered once.
 */
class SightCoverTest {
    static final int[] ME = {0, 64, 0};
    static final String STONE = "minecraft:stone", GRASS = "minecraft:grass_block", DIRT = "minecraft:dirt";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** Stone at 5 64 0 with air on its top: exposed. */
    @Test
    void exposedStoneFromAHandBuiltGrid() {
        NearbyExposureTest.World w = new NearbyExposureTest.World().put(5, 64, 0, STONE).air(5, 65, 0);
        Spot sp = Nearby.source(w, ME, Set.of(STONE), () -> null).spot();
        assertNotNull(sp);
        assertEquals(0, sp.cover());
        assertFalse(sp.isCovered());
    }

    /** Stone under one grass block (air above that): covered, 1 block of grass_block over it. */
    @Test
    void coveredStoneFromAHandBuiltGrid() {
        NearbyExposureTest.World w = new NearbyExposureTest.World().put(5, 64, 0, STONE).put(5, 65, 0, GRASS).air(5, 66, 0);
        Spot sp = Nearby.source(w, ME, Set.of(STONE), () -> null).spot();
        assertNotNull(sp);
        assertTrue(sp.isCovered());
        assertEquals(1, sp.cover());
        assertEquals(GRASS, sp.coverBlock());
        assertEquals("dig to cobblestone at 5 64 0, 1 block of grass_block over it", NeedChain.coverText("minecraft:cobblestone", sp));
    }

    /** job-lookup: deeper than sight.coverMax is kept with its depth; the cover not loaded: the spot as before (rule 15). */
    @Test
    void tooDeepIsKeptAndUnknownCoverStaysAnEstimate() {
        NearbyExposureTest.World w = new NearbyExposureTest.World().put(5, 64, 0, STONE);
        for (int y = 65; y <= 65 + Nearby.coverMax(); y++) w.put(5, y, 0, DIRT);
        w.air(5, 66 + Nearby.coverMax(), 0);
        Map<String, Integer> buried = new HashMap<>();
        assertEquals(Nearby.coverMax() + 1, Nearby.withCover(w, new Spot(5, 64, 0, 5, STONE), buried).cover());
        assertTrue(buried.isEmpty());
        Spot far = new Spot(1000, 64, 0, 5, STONE);
        assertEquals(Spot.COVER_UNKNOWN, Nearby.withCover(w, far, buried).cover(), "not loaded: priced by estimate as today");
    }

    /**
     * The two prices differ by the dig part only: the covered way costs the cover's break by its depth more, and digs first.
     * (forecast: 2 blocks dig 1 level past the spot - under forecast.climbFromLevels, so no way back up is priced here.)
     */
    @Test
    void thePricesDifferByTheDigPart() {
        Spot open = new Spot(5, 64, 0, 5, STONE).covered(0, null);
        Spot one = open.covered(1, STONE), two = open.covered(2, STONE);
        Chain a = NeedChain.resolve("cobblestone", 2, Z77StoneHereTest.world(word -> List.of(open)));
        Chain b = NeedChain.resolve("cobblestone", 2, Z77StoneHereTest.world(word -> List.of(one)));
        Chain c = NeedChain.resolve("cobblestone", 2, Z77StoneHereTest.world(word -> List.of(two)));
        assertTrue(a.ok() && b.ok() && c.ok(), a.why() + " / " + b.why() + " / " + c.why());
        double dig1 = b.seconds() - a.seconds(), dig2 = c.seconds() - a.seconds();
        assertTrue(dig1 > 0, a.why() + " / " + b.why());
        assertEquals(2 * dig1, dig2, 1e-6, "the depth times one cover block's break");
        assertTrue(b.why().contains("dig to cobblestone at 5 64 0, 1 block of stone over it"), b.why());
        assertTrue(a.why().contains("dig cobblestone seen 5 m"), a.why());
        int dig = -1, collect = -1;
        for (int i = 0; i < b.steps().size(); i++) {
            if (b.steps().get(i) instanceof Step.Dig d) { dig = i; assertArrayEquals(new int[]{5, 65, 0}, d.from()); assertArrayEquals(new int[]{5, 65, 0}, d.to()); }
            if (b.steps().get(i) instanceof Step.Collect) collect = i;
        }
        assertTrue(dig >= 0 && dig < collect, b.steps().toString());
        assertTrue(a.steps().stream().noneMatch(s -> s instanceof Step.Dig), a.steps().toString());
    }

    /** "none in sight" at a spot called exposed: marked covered once (a price fact); the second time the near-collect fact as before. */
    @Test
    void noneInSightMarksTheSightingCoveredOnce() {
        Sightings sg = new Sightings();
        CollectHand.Cover cover = (x, y, z, why) -> sg.markCovered("ow", x, y, z, why);
        Step.Collect k = new Step.Collect("cobblestone", 3, new int[]{5, 64, 0});
        String end = "none in sight (10 blocks of the feet)";
        String why = CollectHand.mineNoneKnown(k, end, new int[]{0, 64, 0}, "collect cobblestone 3", 0, 3, cover);
        assertTrue(why.contains("marked covered"), why);
        FactChange.Fact f = FactChange.take();
        assertTrue(f.price(), f.key());
        assertEquals("price covered 5 64 0", f.key());
        assertEquals("none in sight: " + end, sg.coveredWhy("ow", 5, 64, 0));
        String again = CollectHand.mineNoneKnown(k, end, new int[]{0, 64, 0}, "collect cobblestone 3", 0, 3, cover);
        assertFalse(again.contains("marked covered"), again);
        assertTrue(FactChange.take().key().startsWith("source "), "already marked: the near-collect fact, no new price");
        // saved and loaded with the rest
        Sightings back = Sightings.fromJson(sg.toJson(), new int[1]);
        assertEquals("none in sight: " + end, back.coveredWhy("ow", 5, 64, 0));
    }

    /** jbf-1 (run JBF-T2): a covered spot beyond the mine's sight is walked to the TOP of its column, near sight.coverNear, then dug down. */
    @Test
    void theWalkToACoveredSpotEndsOnTopOfItsColumn() {
        Spot deep = new Spot(5, 48, 0, 13, STONE).covered(13, STONE);
        assertEquals(62, NeedChain.columnTop(deep), "48 + 13 of cover + 1: the first open cell");
        Chain c = NeedChain.resolve("cobblestone", 3, Z77StoneHereTest.world(word -> List.of(deep)));
        assertTrue(c.ok(), c.why());
        Step.Walk walk = null;
        Step.Dig dig = null;
        for (Step s : c.steps()) {
            if (s instanceof Step.Walk w && walk == null) walk = w;
            if (s instanceof Step.Dig d) dig = d;
        }
        assertNotNull(walk, c.steps().toString());
        assertEquals(62, walk.y(), "never the buried cell: " + walk.text());
        assertEquals(5, walk.x());
        assertEquals(NeedChain.coverNear(), walk.near(), walk.text());
        assertNotNull(dig, c.steps().toString());
        assertArrayEquals(new int[]{5, 49, 0}, dig.from());
        assertArrayEquals(new int[]{5, 61, 0}, dig.to());
    }

    /** jbf-1 (run JBF-T2: stone under the spawn lake): a column with water in it is no source, said in the buried words. */
    @Test
    void aWaterColumnIsNoSource() {
        NearbyExposureTest.World w = new NearbyExposureTest.World().put(5, 48, 0, STONE);
        for (int y = 49; y <= 61; y++) w.put(5, y, 0, "minecraft:water");
        w.air(5, 62, 0);
        Map<String, Integer> buried = new HashMap<>();
        assertNull(Nearby.withCover(w, new Spot(5, 48, 0, 13, STONE), buried));
        assertEquals(1, buried.getOrDefault(STONE + Nearby.FLUID_COLUMN, 0), buried.toString());
        // the same column of dirt is a source with its cover
        NearbyExposureTest.World d = new NearbyExposureTest.World().put(5, 48, 0, STONE);
        for (int y = 49; y <= 61; y++) d.put(5, y, 0, DIRT);
        d.air(5, 62, 0);
        assertEquals(13, Nearby.withCover(d, new Spot(5, 48, 0, 13, STONE), buried).cover());
    }

    /** The store's mark makes the chunk read's "exposed" a covered spot; a refused dig makes it no source, said. */
    @Test
    void theMarkIsReadByTheLookup() {
        Map<String, String> marks = new HashMap<>();
        NearbyExposureTest.World w = new NearbyExposureTest.World() {
            @Override public String coveredMark(int x, int y, int z) { return marks.get(x + " " + y + " " + z); }
        };
        w.put(5, 64, 0, STONE).put(5, 65, 0, GRASS).air(5, 66, 0).air(6, 64, 0);
        assertEquals(0, Nearby.source(w, ME, Set.of(STONE), () -> null).spot().cover(), "a side face of air: exposed");
        marks.put("5 64 0", "none in sight: test");
        Spot sp = Nearby.source(w, ME, Set.of(STONE), () -> null).spot();
        assertEquals(1, sp.cover(), "marked: covered, the column over it is dug");
        marks.put("5 64 0", Sightings.DIG_REFUSED + "error: that box touches a keep-out or no-break area (home)");
        Nearby.Source s = Nearby.source(w, ME, Set.of(STONE), () -> null);
        assertNull(s.spot());
        assertTrue(s.line().contains("dig refused"), s.line());
    }
}
