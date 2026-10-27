package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.StripAreas;
import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.brain.StripYield;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.OreSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 strip (J2 one-fix cycle, manager's ruling 2026-10-26 ~17:40): a strip's amounts are per family. Run CK (the
 * owner's PC) 16:50:31-17:00:31: "strip deepslate_iron_ore,iron_ore,coal_ore,deepslate_coal_ore at feet y 16 south for coal 1,
 * iron 3: timed out after 600 s (strip south feet y 16: 249 blocks, 5 clusters mined (iron 27) ...)" - iron met 9x early, the
 * 1 coal never found at y 16; runs CF 17:11:31 (112 cells, 0 clusters) and CJ 17:25:25 (102 cells, 0 clusters) the same for
 * iron. Now a met family leaves the strip, and the families left go on only while the strip is their cheapest way by the
 * planner's own prices (StripYield.check): this strip's time at the yield it measured (the band's prediction counted as one
 * ore in its cells) against each family's best other way, or a tunnel from the feet in another heading.
 * Hand numbers: CK's coal at y 16 ~224 cells an ore (the run's "coal 1 (1 ore x ~224 blocks = 224 cells)"), iron ~55 (0.0181 a
 * cell, CJ's yield line); a tunnel cell 2 stone breaks x 0.4 s (iron pickaxe) = 0.8 s.
 */
class Z77StripTest {

    @BeforeEach
    void fresh() {
        StripYield.clear();
        StripOrder.clearPredicted();
        StripAreas.clear();
    }

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
        StripYield.clear();
        StripOrder.clearPredicted();
        StripAreas.clear();
    }

    static final double CELL = 0.8, COAL = 224, IRON = 55.2;

    static Map<String, StripYield.Fact> facts(double coalOther, double ironOther) {
        return Map.of("coal", new StripYield.Fact(4.0, CELL, coalOther, "the fuel for 8 smelts: burn 1 charcoal <- smelt 1 oak_log", false),
                "iron", new StripYield.Fact(37.1, CELL, ironOther, "no other way priced", false));
    }

    static Map<String, Double> predicted() { return Map.of("coal", 1 / COAL, "iron", 1 / IRON); }

    /**
     * CK 16:50:31 -> 17:00:31: coal 1 + iron 3 at y 16, iron 27 found, 0 coal after 249 cells, logs in the bag (the coal's
     * other way: charcoal, 31.0 s). Going on for the coal: 1 x (249 + 224) / (0 + 1) = 473 cells x 0.8 = 378.4 s > 31.0 s:
     * the strip ends with the line, the heading held (a strip of two families: the playbook derives it again from the bag).
     */
    @Test
    void runCK_theMetStripEndsOnTheCoal_withTheLine() {
        StripYield.End e = StripYield.check(Map.of("coal", 1, "iron", 3), Map.of("iron", 27), 249, predicted(), facts(31.0, Double.NaN), "south", null);
        assertNotNull(e, "the coal is cheaper by charcoal");
        assertTrue(e.why().startsWith("met: iron 27; coal 0 of 1 after 249 cells (this strip's yield: coal 0 in 249 cells against the band's 1 in 224 -> 378.4 s more here; cheaper: coal: the fuel for 8 smelts: burn 1 charcoal <- smelt 1 oak_log 31.0 s)"), e.why());
        assertTrue(e.heading(), "two families: the heading is held");
        // while the iron is short and has no other way priced, the coal comes along: the strip runs on
        assertNull(StripYield.check(Map.of("coal", 1, "iron", 3), Map.of("iron", 2), 249, predicted(), facts(31.0, Double.NaN), "south", null));
        // a one-family coal strip (the playbook derived again from the bag): no sample at its first cells (z77 strip 2: the
        // band's own 224 cells an ore first); after them, dry: 1 x (224 + 224) x 0.8 = 358.4 s > 31.0 s - its ores held
        assertNull(StripYield.check(Map.of("coal", 1), Map.of(), 0, predicted(), facts(31.0, Double.NaN), "south", null), "0 cells: no sample");
        assertNull(StripYield.check(Map.of("coal", 1), Map.of(), 223, predicted(), facts(31.0, Double.NaN), "south", null), "223 cells: short of the band's 224");
        StripYield.End one = StripYield.check(Map.of("coal", 1), Map.of(), 224, predicted(), facts(31.0, Double.NaN), "south", null);
        assertNotNull(one);
        assertTrue(one.why().startsWith("met: none; coal 0 of 1 after 224 cells (this strip's yield: coal 0 in 224 cells against the band's 1 in 224 -> 358.4 s more here"), one.why());
        assertFalse(one.heading(), "the ores held: the coal's strip way is out for the order");
    }

    /**
     * Control: the coal still cheapest by the strip - a rich yield so far: 9 coal of 10 in 120 cells: (120 + 224) / 10 =
     * 34.4 cells an ore, 1 left x 34.4 x 0.8 = 27.52 s < 31.0 s: runs on. z77 strip 2: a yield above the band's is no
     * evidence against the strip - the same 9 in 200 (and in 300) cells runs on too; 0 coal in 300 cells: 1 x 524 x 0.8 =
     * 419.2 s > 31: ends.
     */
    @Test
    void control_aRichYield_theStripRunsOn() {
        assertNull(StripYield.check(Map.of("coal", 10, "iron", 3), Map.of("iron", 27, "coal", 9), 120, predicted(), facts(31.0, Double.NaN), "south", null));
        assertNull(StripYield.check(Map.of("coal", 10, "iron", 3), Map.of("iron", 27, "coal", 9), 200, predicted(), facts(31.0, Double.NaN), "south", null));
        assertNull(StripYield.check(Map.of("coal", 10, "iron", 3), Map.of("iron", 27, "coal", 9), 300, predicted(), facts(31.0, Double.NaN), "south", null));
        StripYield.End e = StripYield.check(Map.of("coal", 10, "iron", 3), Map.of("iron", 27), 300, predicted(), facts(31.0, Double.NaN), "south", null);
        assertNotNull(e);
        assertTrue(e.why().contains("coal 0 of 10 after 300 cells") && e.why().contains("-> 4192.0 s more here"), e.why());
    }

    /**
     * 0.39.z77 strip 2, run CS 19:32:56 (laptop, job-j2 07ce9d6b): "strip north at feet y 17 ended on its yield: strip: met:
     * none; iron 0 of 3 after 7 cells (this strip's yield: iron 0 in 7 cells against the band's 1 in 57 -> 648.4 s more here;
     * cheaper: a strip from here east (173 cells at the band and the areas seen along it) 588.2 s)". A cell 3.4 s (the run's
     * 588.2 / 173). 0 ore in 7 cells is the band's own prediction at 1 in 57: the strip runs on; so at 56 cells. 0 in 57 (the
     * band's cells an ore) and 0 in 120: the measured rate below the band's - 3 x (120 + 57) x 3.4 = 1805.4 s against the
     * east tunnel's 588.2 s: it ends with the "met: ..." line, the heading held.
     */
    @Test
    void runCS_zeroIn7RunsOn_zeroIn120Ends() {
        double cell = 3.4, band = 57;
        Map<String, Double> p = Map.of("iron", 1 / band);
        Map<String, StripYield.Fact> f = Map.of("iron", new StripYield.Fact(37.1, cell, Double.NaN, "no other way priced", false));
        StripYield.HeadingCells h = (hd, fam, left) -> hd.equals("east") ? 173 : 3 * 90;
        assertNull(StripYield.check(Map.of("iron", 3), Map.of(), 7, p, f, "north", h), "0 in 7 at 1 in 57: runs on");
        assertNull(StripYield.check(Map.of("iron", 3), Map.of(), 56, p, f, "north", h), "0 in 56: short of the band's 57");
        assertNotNull(StripYield.check(Map.of("iron", 3), Map.of(), 57, p, f, "north", h), "0 in 57: the sample is in");
        assertNull(StripYield.check(Map.of("iron", 3), Map.of("iron", 2), 100, p, f, "north", h), "2 in 100: above the band's rate, runs on");
        StripYield.End e = StripYield.check(Map.of("iron", 3), Map.of(), 120, p, f, "north", h);
        assertNotNull(e, "0 in 120: ends");
        assertEquals("met: none; iron 0 of 3 after 120 cells (this strip's yield: iron 0 in 120 cells against the band's 1 in 57 -> 1805.4 s more here;"
                + " cheaper: a strip from here east (173 cells at the band and the areas seen along it) 588.2 s)", e.why());
        assertTrue(e.heading(), "another heading: the heading is held");
    }

    /**
     * 0.39.z77 strip 3: a dry strip with no other way priced and no other heading known digs on; only the step watch
     * (steps.stepMaxS 600 s, Steps' "timed out after 600 s (<the strip's status>)") ends it - the verdict says so for that
     * status. Before the sample is in (10 cells at 1 in 55): no such words.
     */
    @Test
    void noOtherWay_theStripSaysItDigsOnToTheCap() {
        Map<String, Double> p = Map.of("iron", 1 / IRON);
        Map<String, StripYield.Fact> f = Map.of("iron", new StripYield.Fact(37.1, CELL, Double.NaN, "no other way priced", false));
        StripYield.Verdict v = StripYield.verdict(Map.of("iron", 3), Map.of(), 102, p, f, "east", null);
        assertNotNull(v);
        assertNull(v.end());
        assertEquals("no other way priced: digging on to the cap (steps.stepMaxS 600 s; this strip's yield: iron 0 in 102 cells against the band's 1 in 55)", v.on());
        assertNull(StripYield.check(Map.of("iron", 3), Map.of(), 102, p, f, "east", null));
        assertNull(StripYield.verdict(Map.of("iron", 3), Map.of(), 10, p, f, "east", null), "no sample yet");
    }

    /**
     * 0.39.z77 strip 2: another heading on the same band prior as this strip, never better - the band's own cells an ore x
     * the ore left as the floor (an estimate under it is raised); dry areas seen there (dearer) stand; ore seen there (a
     * measured yield) may price it under the band; not known (-1) stays not known.
     */
    @Test
    void anotherHeading_onTheSameBandPrior_neverBetter() {
        assertEquals(171, StripYield.sameBand(150, 3, 57, false), "raised to 3 x 57");
        assertEquals(400, StripYield.sameBand(400, 3, 57, false), "dry areas there: dearer, kept");
        assertEquals(120, StripYield.sameBand(120, 3, 57, true), "ore measured there: kept");
        assertEquals(-1, StripYield.sameBand(-1, 3, 57, false), "not known");
    }

    /** Rule 15: no prediction, no cell price or no other way priced and no heading offered - not known, the strip runs on. */
    @Test
    void notKnown_theStripRunsOn() {
        assertNull(StripYield.check(Map.of("coal", 1), Map.of(), 999, Map.of(), facts(1.0, 1.0), "south", null), "no prediction");
        assertNull(StripYield.check(Map.of("coal", 1), Map.of(), 999, predicted(), Map.of(), "south", null), "an owner's strip: no facts");
        assertNull(StripYield.check(Map.of("iron", 3), Map.of(), 999, predicted(), facts(Double.NaN, Double.NaN), "south", null), "no other way");
        assertNull(StripYield.check(Map.of(), Map.of(), 999, predicted(), facts(1.0, 1.0), "south", null), "no amount: until the bag is full");
        assertNull(StripYield.check(Map.of("iron", 3), Map.of("iron", 3), 9, predicted(), facts(1.0, 1.0), "south", null), "met: the end rule's");
    }

    /**
     * CJ 17:25:25 (and CF 17:11:31): iron 3 at y 16, 0 found after 102 cells, no other way priced. Going on: 3 x (102 + 55.2)
     * = 471.6 cells x 0.8 = 377.3 s; a tunnel from the feet in another heading (the strip way's own estimate, here 231 cells:
     * 184.8 s) is cheaper - the strip ends, its heading held. After 10 dry cells: 3 x 65.2 x 0.8 = 156.5 s against the same
     * heading's 184.8 s: runs on.
     */
    @Test
    void runCJ_aDryIronStrip_endsForAnotherHeading() {
        Map<String, Double> p = Map.of("iron", 1 / IRON);
        Map<String, StripYield.Fact> f = Map.of("iron", new StripYield.Fact(37.1, CELL, Double.NaN, "no other way priced", false));
        StripYield.HeadingCells h = (hd, fam, left) -> hd.equals("west") ? 3 * 77 : 3 * 90;
        StripYield.End e = StripYield.check(Map.of("iron", 3), Map.of(), 102, p, f, "east", h);
        assertNotNull(e);
        assertEquals("met: none; iron 0 of 3 after 102 cells (this strip's yield: iron 0 in 102 cells against the band's 1 in 55 -> 377.3 s more here;"
                + " cheaper: a strip from here west (231 cells at the band and the areas seen along it) 184.8 s)", e.why());
        assertTrue(e.heading(), "another heading: the heading is held");
        assertNull(StripYield.check(Map.of("iron", 3), Map.of(), 10, p, f, "east", h));
    }

    /**
     * The other heading by the strip way's own estimate (NeedChain.stripDig): the areas seen along it from the feet. A dry
     * tunnel east from 0 16 0 to 102 16 0 (4 faces a cell, no iron): from its end, back west runs through the dry areas (dear),
     * north and south start in the feet's area only (fresh beyond it).
     */
    @Test
    void theOtherHeadingIsTheStripWaysOwnEstimate() {
        ScenariosTest.world("wooden", "stone", "iron");
        for (int x = 0; x <= 102; x++) StripAreas.add(x, 16, 0, 4, Map.of());
        double slab = Z77SparesTest.iron16();
        int west = NeedChain.stripDig(slab, 3, new int[]{102, 0}, 16, "west", Z62RunSTest.IRON).cells();
        int north = NeedChain.stripDig(slab, 3, new int[]{102, 0}, 16, "north", Z62RunSTest.IRON).cells();
        int band = NeedChain.stripDig(slab, 3, null, 16, "north", Z62RunSTest.IRON).cells();
        assertTrue(west > north && north >= band, "west " + west + ", north " + north + ", band " + band);
    }

    /** A met family leaves the strip: its ores out of the fork's set; an ore with no amount (an owner's "until full") stays. */
    @Test
    void aMetFamilyLeavesTheStrip() {
        List<String> ores = List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:deepslate_iron_ore", "minecraft:iron_ore");
        java.util.function.Function<String, String> fam = o -> StripOrder.countKey(o, OreSpec::family);
        assertEquals(List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"), StripYield.liveOres(ores, Map.of("coal", 1, "iron", 3), Map.of("iron", 27), fam));
        assertEquals(ores, StripYield.liveOres(ores, Map.of("coal", 1, "iron", 3), Map.of("iron", 2), fam));
        assertEquals(List.of(), StripYield.liveOres(ores, Map.of("coal", 1, "iron", 3), Map.of("iron", 3, "coal", 1), fam));
        assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), StripYield.liveOres(ores, Map.of("coal", 1), Map.of("coal", 4), fam), "iron has no amount: it stays");
    }

    /** The heading fact: held for the order as a blocked heading is (every strip way at that Y takes its next heading). */
    @Test
    void theYieldEndHoldsTheHeading() {
        FactChange.stripYield(16, "south", "strip: met: iron 27; coal 0 of 1 after 249 cells (...)");
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + NeedChain.headingKey(16, "south"), f.key());
        assertEquals(NeedChain.headingKey(16, "south"), FactChange.ownSourceKey(f));
        assertEquals("strip south at feet y 16 ended on its yield: strip: met: iron 27; coal 0 of 1 after 249 cells (...)", f.text());
    }

    // ---- the planner's facts ----

    /**
     * The playbook's descent keeps each family's facts under its one strip's key: the band, a cell's seconds (2 x 0.6 s, the
     * stone pickaxe) and the family priced with no strip - the coal (the smelts' fuel) by the burn ways (logs in the bag: the
     * charcoal), the iron by its ways (nothing seen: an explore, no price). The CK scenario on these facts (Z77PlaybookTest's
     * fakes; 6 oak logs in the bag, oak logs burn 300 ticks): the descent for raw_iron 3 + coal 1; the coal's other way = 8
     * smelts' fuel = 1 charcoal <- smelt 1 oak_log, 10.0 s (the furnace the torches' charcoal placed, the burn in the bag).
     * Iron met (27 found), 30 cells, no coal: 1 x (30 + 16) / 1 = 46 cells x 1.2 = 55.2 s > 10.0 s: the strip ends.
     */
    @Test
    void runCK_thePlaybookDescentKeepsEachFamilysOtherWay_theCoalFromCharcoal() {
        Z77PlaybookTest.data().fuels.put(TestData.id("oak_log"), 300);
        Map<String, Integer> bag = new java.util.HashMap<>(Z77PlaybookTest.kitBag(0));
        bag.put("minecraft:oak_log", 6);
        World w = Z77PlaybookTest.world(bag, Map.of());
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok(), pc.why());
        Step.Strip st = (Step.Strip) pc.steps().stream().filter(s -> s instanceof Step.Strip).findFirst().orElseThrow();
        assertEquals(Map.of("coal", 1, "iron", 3), st.amounts(), st.text());
        Map<String, StripYield.Fact> f = StripYield.facts(st.key());
        assertEquals(1.2, f.get("coal").cellS(), 1e-9, f.toString());
        assertEquals(256.0, f.get("coal").perSlab(), 1e-9);
        assertEquals(10.0, f.get("coal").otherS(), 0.05, f.toString());
        assertEquals("the fuel for 8 smelts: burn 1 charcoal <- smelt 1 oak_log + burn 1 oak_log (in the bag, the cheapest burn by worth)", f.get("coal").other());
        assertTrue(Double.isNaN(f.get("iron").otherS()), "nothing seen for the iron: " + f);
        assertEquals("no source of raw_iron seen and no cave known (an explore: no price)", f.get("iron").other());
        StripYield.End e = StripYield.check(st.amounts(), Map.of("iron", 27), 30, StripOrder.predicted(st.key()), f, st.heading(), null);
        assertNotNull(e);
        assertTrue(e.why().startsWith("met: iron 27; coal 0 of 1 after 30 cells (this strip's yield: coal 0 in 30 cells against the band's 1 in 16 -> 55.2 s more here; cheaper: coal: the fuel for 8 smelts: burn 1 charcoal"), e.why());
        assertNull(StripYield.check(st.amounts(), Map.of("iron", 2), 30, StripOrder.predicted(st.key()), f, st.heading(), null), "the iron still short, no other way: on");
    }

    /**
     * The strip way keeps its rivals' best price; once a vein is seen (the strip way not offered beside a seen source), the
     * need's best way is kept for the strip from the feet - the tunnel the bot is in (CJ 17:25:25: "dig raw_iron seen 14 m"
     * after 102 dry cells). Run S's band (iron at y 16, the stone pickaxe: a cell 2 x 0.6 = 1.2 s); nothing else stands: no
     * other way priced. Then 3 raw iron seen 5 m off: "dig raw_iron seen 5 m, walk 1.5 s (path): 5.0 s" kept for the tunnel.
     */
    @Test
    void theStripWayKeepsItsRivalsBestPrice_aSeenVeinToo() {
        ScenariosTest.world("wooden", "stone", "iron");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        NeedChain.StripBand b = Z62RunSTest.band("east");
        Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(bag, b));
        assertTrue(c.ok(), c.why());
        Step.Strip st = Z62RunSTest.strip(c);
        StripYield.Fact f = StripYield.facts(st.key()).get("iron");
        assertNotNull(f, StripYield.facts(st.key()).toString());
        assertEquals(1.2, f.cellS(), 1e-9);
        assertEquals(b.perSlab(), f.perSlab(), 1e-9);
        assertTrue(Double.isNaN(f.otherS()) && f.other().equals("no other way priced"), f.toString());
        World seen = ScenariosTest.w(bag, true, true, Map.of("raw_iron", List.of(new Spot(-215, 16, 33, 5, "minecraft:iron_ore", 3))))
                .withStrip(word -> word.contains("raw_iron") || word.contains("iron_ore") ? b : null);
        Chain v = NeedChain.resolve("raw_iron", 3, seen);
        assertTrue(v.ok() && v.why().contains("dig raw_iron seen 5 m"), v.why());
        StripYield.Fact g = StripYield.facts(st.key()).get("iron");
        assertEquals(5.0, g.otherS(), 0.05, g.toString());
        assertEquals("raw_iron 5 m", g.other());
        assertFalse(g.otherStrip());
        assertEquals(1.2, g.cellS(), 1e-9, "the strip's own facts kept");
        // the dry tunnel against the vein: 3 x (dug + band) cells x 1.2 s >> 5.0 s - it ends, its ores held (one family, no
        // strip); z77 strip 2: once the band's own cells an ore are dug (before them: no sample, it runs on)
        int bandCells = (int) Math.ceil(1 / StripOrder.predicted(st.key()).get("iron") - 1e-9);
        assertNull(StripYield.check(st.amounts(), Map.of(), bandCells - 1, StripOrder.predicted(st.key()), StripYield.facts(st.key()), st.heading(), null), "short of the band's " + bandCells);
        StripYield.End e = StripYield.check(st.amounts(), Map.of(), Math.max(102, bandCells), StripOrder.predicted(st.key()), StripYield.facts(st.key()), st.heading(), null);
        assertNotNull(e);
        assertFalse(e.heading(), e.why());
        assertTrue(e.why().contains("cheaper: iron: raw_iron 5 m 5.0 s"), e.why());
    }
}
