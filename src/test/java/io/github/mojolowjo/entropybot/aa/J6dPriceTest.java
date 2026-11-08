package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6d (to-do/j6d-cave-price-by-walk.md; owner 2026-10-28: "cost should be distance from cave, how much have we
 * explored; leave time per block out for now; if a vein is close it should just mine that"): a known cave is priced by its
 * walks - Baritone's walk to the lip + (D15 pass two) the walk inside along the record's passages (ranked by the estimate
 * while it is not priced, rule 18 as amended). No time per ore, no density. The world-gen estimate is information (and the
 * share's count). The fake walk: FakePaths, the chain's straight metres x 1.3 / 4.317.
 */
class J6dPriceTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final double TOL = 0.05;

    static CaveWay cave(int x, int y, int z, double dist, double expected, double explored, CaveIndex.Size size, List<io.github.mojolowjo.entropybot.cave.Passages.Passage> passages) {
        return new CaveWay(x, y, z, dist, "minecraft:iron_ore", "iron", expected, explored, size, -1, List.of(), List.of(new int[]{x, y, z})).withPassages(passages);
    }

    static double d(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Scenario 1, ET's six caves (j6c "## Run ET" (1): the world-gen ore in each, from the feet at -80 99 -233 at 09:44:11,
     * when the explore won): -77 94 -236 (7 m, 0.7 iron), -93 72 -244 (1.3), -103 76 -278 (0.1), -213 74 -194 (1.1), -14 73
     * -415 (0.4) and the large 102 15 -384 (677.7). For 3 iron the 7 m one wins for its share (1): its walk is the cheapest
     * leg, and the rest is priced from the next ways; the ~0 ones are out, said; the large one is priced and loses on price.
     */
    @Test
    void scenario1_etsSixCaves_the7mOneWinsForItsShare() {
        int[] feet = {-80, 99, -233};
        int[][] at = {{-77, 94, -236}, {-93, 72, -244}, {-103, 76, -278}, {-213, 74, -194}, {-14, 73, -415}, {102, 15, -384}};
        double[] gen = {0.7, 1.3, 0.1, 1.1, 0.4, 677.7};
        List<CaveWay> ways = new java.util.ArrayList<>();
        for (int i = 0; i < at.length; i++)
            ways.add(cave(at[i][0], at[i][1], at[i][2], d(feet, at[i]), gen[i], Double.NaN, i == 5 ? CaveIndex.Size.LARGE : CaveIndex.Size.SMALL, null));
        ways.sort(java.util.Comparator.comparingDouble(CaveWay::dist));                 // AaRuntime: nearest first
        CaveWay all = ways.get(0).withMore(ways.subList(1, ways.size()));
        var over = new NeedChain.Surface(11, -77, 98, -236, 3, false);
        Chain c = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? all : null));
        assertTrue(c.ok(), c.why());
        List<Step.Cave> legs = c.steps().stream().filter(s -> s instanceof Step.Cave).map(s -> (Step.Cave) s).toList();
        assertFalse(legs.isEmpty(), c.why());
        assertEquals(List.of(-77, 94, -236), List.of(legs.get(0).x(), legs.get(0).y(), legs.get(0).z()), "the 7 m cave first: " + c.steps());
        assertEquals(1, legs.get(0).n(), "its share: round(0.7) - " + c.steps());
        assertTrue(c.why().contains("1 of 3 ore from the cave, the other 2 from: "), c.why());
        String said = c.why() + " | " + c.dropped();
        assertTrue(said.contains("known cave 56 m at -103 76 -278") && said.contains("its unseen part holds ~0 by the world-gen estimate"), "the ~0 cave is out, said: " + said);
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("known cave 251 m: ") && x.contains("dearer than")), "the large one priced, lost on price: " + c.dropped());
        assertTrue(c.why().contains("world-gen ~0.7 iron in its unseen part"), "the estimate shown (information): " + c.why());
    }

    /**
     * Scenario 1 at ET's 09:51 feet (the manager's ruling (b)): the same six caves now 123 / 232 / 232 / 241 / 351 / 368 m
     * off. The small ones' shares and their rest (232 + 241 + 351 m of walks) are never under the 351 m cave alone, so the
     * large one is taken for the whole need and the near small caves are said with their reason (dearer, or ~0). A near
     * small cave wins only when it covers the need, or when the next way is cheaper from there (scenario 1 at 09:44).
     */
    @Test
    void scenario1_etAt0951_theLargeCaveAlone_theSmallOnesSaid() {
        Object[][] at = {{-14, 73, -415, 123.0, 0.4}, {-103, 76, -278, 232.0, 0.1}, {-77, 94, -236, 232.0, 0.7}, {-93, 72, -244, 241.0, 1.3},
                {102, 15, -384, 351.0, 677.7}, {-213, 74, -194, 368.0, 1.1}};
        List<CaveWay> ways = new java.util.ArrayList<>();
        for (Object[] a : at)
            ways.add(cave((Integer) a[0], (Integer) a[1], (Integer) a[2], (Double) a[3], (Double) a[4], Double.NaN, (Integer) a[0] == 102 ? CaveIndex.Size.LARGE : CaveIndex.Size.SMALL, null));
        CaveWay all = ways.get(0).withMore(ways.subList(1, ways.size()));
        var over = new NeedChain.Surface(11, -77, 98, -236, 3, false);
        Chain c = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? all : null));
        assertTrue(c.ok(), c.why());
        List<Step.Cave> legs = c.steps().stream().filter(s -> s instanceof Step.Cave).map(s -> (Step.Cave) s).toList();
        assertEquals(1, legs.size(), "the large cave alone: " + c.steps());
        assertEquals(List.of(102, 15, -384, 3), List.of(legs.get(0).x(), legs.get(0).y(), legs.get(0).z(), legs.get(0).n()), c.steps().toString());
        String said = c.why() + " | " + c.dropped();
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("known cave 232 m (2): ") && x.contains("dearer than known cave 351 m")), "the 7 m cave of 09:44, said: " + c.dropped());
        assertTrue(said.contains("known cave 123 m at -14 73 -415") && said.contains("its unseen part holds ~0 by the world-gen estimate"), said);
    }

    /** D15 pass two: a passage of {@code length} layers, {@code fromFeet} off, {@code unseen} cells not seen (its end far). */
    static io.github.mojolowjo.entropybot.cave.Passages.Passage passage(int fromFeet, int length, int unseen) {
        int[] e = {0, 0, 0};
        return new io.github.mojolowjo.entropybot.cave.Passages.Passage(0, -1, e, e, length, 2, fromFeet, List.of(), unseen, 0, io.github.mojolowjo.entropybot.cave.Passages.EndKind.DEAD_END);
    }

    /**
     * Scenario 2, D15 pass two: a walked cave whose one open passage is far in (30 layers off, 40 long, 100 unseen cells:
     * 1 iron expected at cave.orePerUnseenCell 0.01) prices higher than a fresh cave at the same 51 m (a near passage of
     * 400 unseen cells: 4 expected); with both known the fresh one is taken. The walk inside is CaveWalk#findSeconds.
     */
    @Test
    void scenario2_aFarPassage_pricesHigherThanANearOne_atTheSameDistance() {
        var over = new NeedChain.Surface(48, -51, 65, -41, 3, false);
        CaveWay walked = cave(-43, 67, -39, 51, Double.NaN, 0.6, CaveIndex.Size.LARGE, List.of(passage(30, 40, 100)));
        CaveWay fresh = cave(40, 60, 20, 51, Double.NaN, 0.0, CaveIndex.Size.LARGE, List.of(passage(0, 10, 400)));
        Chain w = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(x -> x.contains("raw_iron") || x.contains("iron_ore") ? walked : null));
        Chain f = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(x -> x.contains("raw_iron") || x.contains("iron_ore") ? fresh : null));
        assertTrue(w.ok() && f.ok(), w.why() + " | " + f.why());
        // walked: (30 + 40) x 0.5 s = 35 s for 1 expected, the other 2 at 35 s each = 105 s; fresh: 10 x 0.5 = 5 s for 4, 3 of them 3.75 s
        assertEquals(f.seconds() + 105 - 3.75, w.seconds(), TOL, "the walk inside is the passages' price - " + w.why());
        assertTrue(w.why().contains("the passages: 3 ore: 1.0 expected along all 1 open passage(s)"), w.why());
        assertTrue(w.why().contains("explored 60%"), "e shown beside the price: " + w.why());
        Chain both = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(x -> x.contains("raw_iron") || x.contains("iron_ore") ? walked.withMore(List.of(fresh)) : null));
        Step.Cave k = (Step.Cave) both.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertEquals(40, k.x(), "the fresh cave: " + both.why());
        // not measured (the cells not loaded): the walk inside is 0, said
        CaveWay none = cave(40, 60, 20, 51, Double.NaN, 0.0, CaveIndex.Size.LARGE, null);
        Chain n = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(x -> x.contains("raw_iron") || x.contains("iron_ore") ? none : null));
        assertTrue(n.why().contains("its passages not measured yet"), n.why());
    }

    /** Scenario 3 (owner: "if a vein is close it should just mine that"): for n = 1 a seen vein 10 m off beats a cave 20 m off. */
    @Test
    void scenario3_forOneOre_aVeinAt10mBeatsACaveAt20m() {
        Spot vein = new Spot(10, 64, 0, 10, "minecraft:iron_ore", 3);
        CaveWay cw = cave(20, 21, -10, 20, Double.NaN, Double.NaN, CaveIndex.Size.LARGE, null);
        Chain c = NeedChain.resolve("raw_iron", 1, Z77CaveWayTest.world(cw, vein));
        assertTrue(c.ok() && !Z77CaveWayTest.caveMined(c), "the vein: " + c.why());
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("known cave 20 m")), "the cave priced and lost: " + c.dropped());
        Chain only = NeedChain.resolve("raw_iron", 1, Z77CaveWayTest.world(cw));
        assertEquals(FakePaths.walkS(20) + 1.15, only.seconds(), TOL, "the cave alone: its walk and the one break, no time per ore found - " + only.why());
    }

    /** One volume with two entrances: the walk inside is the same from each (the record's passages), so the nearer lip wins. */
    @Test
    void twoEntrances_theNearerLip() {
        int[] feet = {8, 62, 1};
        int[] entry = {18, 62, 1}, other = {12, 62, -30};
        PathPrice est = new PathPrice() {
            @Override public Answer price(int x, int y, int z, double dist) { return Answer.priced(new Walk(FakePaths.walkS(d(feet, new int[]{x, y, z})))); }
            @Override public double estimate(int x, int y, int z) { return FakePaths.walkS(d(feet, new int[]{x, y, z})); }
            @Override public double estimateFrom(int[] from, int x, int y, int z) { return FakePaths.walkS(d(from, new int[]{x, y, z})); }
        };
        CaveWay cw = new CaveWay(18, 62, 1, 10, "minecraft:iron_ore", "iron", Double.NaN, 0.5, CaveIndex.Size.LARGE, -1, List.of(), List.of(other, entry));
        Chain a = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(cw.withPassages(List.of(passage(0, 10, 400)))).withPath(est));
        assertTrue(a.steps().contains(new Step.ToCave(18, 62, 1)), "the nearer lip, though listed second - " + a.steps() + " " + a.why());
    }
}
