package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.Playbook.Target;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 J2 (D5 J2; rule 1 clarified: a playbook is one priced option): the batch's playbook - derived from recipes, tags,
 * world-gen and tiers - priced beside the ad-hoc chains (each order its own chain, in turn on the bag the last left). Hand
 * numbers (SCENARIOS.md style, within 0.1 s) on these fakes: the walk is Baritone's fake path - a cell below y 60 is the dig
 * down from the feet at y 64, (64 - y) stone breaks x 0.6 s (hardness 1.5, stone pickaxe speed 4: ceil(1.5 x 30 / 4) = 12
 * ticks), anything else the straight metres / 4.317; iron and coal ore 1.15 s (ceil(3 x 30 / 4) = 23 ticks); the iron and coal
 * bands 0..31 with 256 ore a chunk slab at y 16, so 256 x 16 / 256 = 16 blocks per ore, a tunnel cell 2 breaks; smelt 10 s,
 * craft 0.3, place 0.15; with the iron pickaxe (speed 6) stone 0.4 s (8 ticks), ore 0.75 s (15 ticks). No pickaxe wears out (the bag's\n * uses unlimited, a made one's durability not in the test's data: no spares); only coal burns (the test's fuel table).
 */
class Z77PlaybookTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    static final String IRON_ORE = "minecraft:iron_ore", COAL_ORE = "minecraft:coal_ore", STONE = "minecraft:stone";
    static final List<Target> KIT = List.of(new Target("minecraft:torch", 4), new Target("minecraft:iron_pickaxe", 1), new Target("minecraft:iron_helmet", 1),
            new Target("minecraft:iron_chestplate", 1), new Target("minecraft:iron_leggings", 1), new Target("minecraft:iron_boots", 1));

    /** The planner data; only coal and charcoal burn (the bag's sticks and tables are no fuel the furnace job picks). */
    static TestData data() {
        TestData d = ScenariosTest.world("wooden", "stone", "iron", "diamond");
        d.fuels.keySet().removeIf(k -> !k.equals(TestData.id("coal")) && !k.equals(TestData.id("charcoal")) && !k.equals(TestData.id("coal_block")));
        Object[][] picks = {{"wooden", 2.0, 0}, {"stone", 4.0, 1}, {"iron", 6.0, 2}, {"diamond", 8.0, 3}};
        for (Object[] p : picks) d.tool(p[0] + "_pickaxe", ItemWorth.Role.PICKAXE, (Double) p[1], (Integer) p[2], 0);
        return d;
    }

    static StripBand band(String ore) { return new StripBand(List.of(ore), 0, 31, 16, 256, 0, 0, 64, "east", STONE); }

    /** The fake strip facts: the iron words strip iron ore, the coal words coal ore (a joined word by any of its parts). */
    static StripBand strip(String word) {
        for (String p : word.split("\\|")) {
            if (p.equals("minecraft:raw_iron") || p.equals(IRON_ORE)) return band(IRON_ORE);
            if (p.equals("minecraft:coal") || p.equals("#minecraft:coals")) return band(COAL_ORE);
        }
        return null;
    }

    /** The fake world-gen facts: logs on the surface, iron and coal in the band, the rest not placed by a feature. */
    static GenWhere.Fact where(String word) {
        if (word.contains("log")) return new GenWhere.Fact(GenWhere.Kind.SURFACE, 0, 0, "minecraft:oak_log", "minecraft:trees_plains", null);
        for (String p : word.split("\\|"))
            if (p.equals("minecraft:raw_iron") || p.equals(IRON_ORE) || p.equals("minecraft:coal") || p.equals("#minecraft:coals") || p.equals(COAL_ORE))
                return new GenWhere.Fact(GenWhere.Kind.BAND, 0, 31, IRON_ORE, "minecraft:ore_test", null);
        return GenWhere.unknown("no world-gen feature places it (test)");
    }

    static final PathPrice PATHS = (x, y, z, dist) -> PathPrice.Answer.priced(new PathPrice.Walk(y < 60 ? (64 - y) * 0.6 : dist / Unit.WALK_BPS));

    static LootBook book() {
        return new LootBook(List.of(src(IRON_ORE, "minecraft:raw_iron"), src(COAL_ORE, "minecraft:coal"), src(STONE, "minecraft:cobblestone"),
                src("minecraft:oak_log", "minecraft:oak_log")), "test");
    }

    static LootBook.Source src(String block, String item) { return new LootBook.Source(LootBook.Kind.BLOCK, block, item, 1, 1, false, null); }

    static World world(Map<String, Integer> bag, Map<String, List<Spot>> seen) {
        return ScenariosTest.w(bag, false, false, seen).withMobs(x -> List.of(), book()).withPath(PATHS).withStrip(Z77PlaybookTest::strip)
                .withWhere(new NeedChain.WhereFacts(Z77PlaybookTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0},
                        () -> new GenWhere.Base(STONE, Integer.MIN_VALUE, null, "test")));
    }

    static Map<String, Integer> kitBag(int coal) {
        return Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 64, "minecraft:crafting_table", 5, "minecraft:furnace", 5, "minecraft:coal", coal);
    }

    static long count(Chain c, Class<?> k) { return c.steps().stream().filter(k::isInstance).count(); }

    // ---- 1. fresh world, nothing known ----

    /**
     * Feet 0 64 0 on land, nothing known: the playbook derives the route (the stone pickaxe from the base block's cobblestone,
     * the torches from charcoal - all surface; one descent to y 16 for the iron pickaxe's 3 raw iron and 1 coal (3 smelts x
     * 200 / 1600, up), the smelt and the pickaxe there, then the strip on at the same level with it for the armour's 24 and
     * 3 coal, one smelt for them). Its explore has a surface part (E1). Taken as had after it, the stone pickaxe leaves a
     * second unknown: SHAPE CHANGE (playbook first, pricing only inside legs) - the trip runs to its explore and the legs after
     * it are priced when the bot gets there (its find re-prices the trip); no comparison, no ad-hoc meanwhile.
     */
    @Test
    void s1_freshWorld_theTripRunsToItsExplore_theLegsAfterArePricedThere() {
        data();
        World w = world(Map.of(), Map.of());
        Playbook pb = Playbook.derive(KIT, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        assertEquals("playbook: surface [torch 4, stone_pickaxe 1] > descent y 16 [raw_iron 3, coal 1] > at band [iron_ingot 3, iron_pickaxe 1] > strip on y 16 "
                + "[raw_iron 24, coal 3] > at band [iron_ingot 24, iron_helmet 1, iron_chestplate 1, iron_leggings 1, iron_boots 1]", pb.why());
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok() && pc.explore(), pc.why());
        assertTrue(pc.why().contains("priced when the bot gets there (" + NeedChain.NOT_PRICED + "a second need") && pc.why().contains("after the surface explore"), pc.why());
        assertTrue(pc.steps().get(pc.steps().size() - 1) instanceof Step.Explore, "the run ends at the explore: " + pc.steps());
        Step.Explore ex = (Step.Explore) pc.steps().stream().filter(s -> s instanceof Step.Explore).findFirst().orElseThrow();
        // 0.39.z77 round fix 3: the torches come first - their explore is "logs or coal" (charcoal from logs, or coal seen on the
        // way): a surface part turns the land-first water term on (E1, per part) and no leg goes down before it
        assertTrue(java.util.Arrays.stream(NeedChain.word(ex.until()).split("\\|")).anyMatch(p -> where(p).surface()), "a surface part: " + ex.until());
        assertTrue(pc.steps().stream().noneMatch(s -> s instanceof Step.ToBand || s instanceof Step.ToStrip), "no leg down before the surface explore: " + pc.steps());
        assertTrue(pc.steps().get(0) instanceof Step.Explore || pc.steps().stream().noneMatch(s -> s instanceof Step.Craft c && c.item().contains("pickaxe")),
                "the torches' explore before any pickaxe: " + pc.steps());
        BatchWay.Shaped s = BatchWay.shape("b1", 6, pb, pc, Map.of());
        assertSame(pc, s.chain(), "the trip runs, no other planner");
        assertTrue(s.line().startsWith("trip b1 (6 orders): 5 legs - 1 surface: torch 4, stone_pickaxe 1 | 2 descent to y 16: raw_iron 3, coal 1"), s.line());
    }

    // ---- 2. the kit with its tools in the bag: the playbook wins by the descents and the stations ----

    /**
     * The bag: a stone pickaxe, 64 sticks, 5 tables, 5 furnaces, 9 coal. By hand -
     * PLAYBOOK (the player's order: the iron pickaxe's ore first, then the rest with it): torches 0.3 (the bag's coal); the
     * descent 48 x 0.6 = 28.8; the strip 3 iron x 16 = 48 cells x 2 x 0.6 = 57.6 + 3 x 1.15 = 3.45 (61.05; its 1 coal in the
     * bag); the furnace placed 0.15 + 3 smelts 30; the table placed 0.15 + the pickaxe 0.3; the strip on at y 16 with the iron
     * pickaxe, 24 x 16 = 384 cells x 2 x 0.4 = 307.2 + 24 x 0.75 = 18 (325.2, no walk); a furnace placed again 0.15 (0.39.z77
     * legs: the strip on leaves the bot at its tunnel's end, 384 cells from the first two stations) + 24 smelts 240; a table
     * placed again 0.15 + four crafts 1.2: 687.45 s.
     * AD-HOC in turn (each later order digs with the iron pickaxe the second one made): torches 0.3; pickaxe 28.8 + 61.05 +
     * furnace 0.15 + 30 + table 0.15 + 0.3 = 120.45; helmet 28.8 + 80 cells x 0.8 = 64 + 5 x 0.75 + 0.15 + 50 + 0.45 = 147.15;
     * chestplate 28.8 + 102.4 + 6 + 0.15 + 80 + 0.45 = 217.8; leggings 28.8 + 89.6 + 5.25 + 0.15 + 70 + 0.45 = 194.25; boots
     * 28.8 + 51.2 + 3 + 0.15 + 40 + 0.45 = 123.6: 803.55 s. The difference 116.4 = 4 descents 115.2 + 4 furnaces and 4
     * tables placed 1.2. The playbook runs.
     */
    @Test
    void s1_kitPricedAgain_thePlaybookWinsByTheDescents() {
        data();
        World w = world(kitBag(9), Map.of());
        Playbook pb = Playbook.derive(KIT, Playbook.facts(w, null));
        assertEquals("playbook: surface [torch 4] > descent y 16 [raw_iron 3, coal 1] > at band [iron_ingot 3, iron_pickaxe 1] > strip on y 16 [raw_iron 24, coal 3] "
                + "> at band [iron_ingot 24, iron_helmet 1, iron_chestplate 1, iron_leggings 1, iron_boots 1]", pb.why(), "the bag's stone pickaxe: no gate");
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        Chain pc = legs.chain();
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        assertEquals(687.45, pc.seconds(), 0.1, pc.why());
        assertEquals(1, count(pc, Step.ToStrip.class), "one descent: " + pc.steps());
        List<Step.Strip> strips = pc.steps().stream().filter(s -> s instanceof Step.Strip).map(s -> (Step.Strip) s).toList();
        assertEquals(2, strips.size(), pc.steps().toString());
        assertEquals(List.of(IRON_ORE), strips.get(0).ores(), "the bag's 9 coal cover the fuel: no coal in the strips");
        assertEquals(List.of(3, 24), strips.stream().map(s -> s.amounts().values().stream().mapToInt(Integer::intValue).sum()).toList());
        assertEquals(List.of(new Step.Smelt("iron_ingot", 3, true), new Step.Smelt("iron_ingot", 24, true)), pc.steps().stream().filter(s -> s instanceof Step.Smelt).toList(),
                "one smelt for the pickaxe, one for the armour");
        // SHAPE CHANGE: the watcher's words - the whole trip with what the descent carries down, then each leg as it starts
        // (the ad-hoc row's 803.55 s by hand above is no longer priced: no comparison)
        BatchWay.Shaped sh = BatchWay.shape("b1", 6, pb, pc, legs.starts());
        assertSame(pc, sh.chain());
        assertTrue(sh.line().startsWith("trip b1 (6 orders): 5 legs - 1 surface: torch 4 | 2 descent to y 16: raw_iron 3, coal 1, carries "), sh.line());
        assertTrue(sh.line().contains(", carries ") && sh.line().contains("coal 4 (fuel)") && sh.line().contains("stick 2") && sh.line().contains("furnace 2") && sh.line().contains("crafting_table 2"),
                "the smelts' fuel (27 x 200 / 1600, up), the pickaxe's sticks and two of each station (z77 legs: one again at the strip on's end) go down: " + sh.line());
        assertTrue(sh.line().endsWith(" | 5 at the band: iron_ingot 24, iron_helmet 1, iron_chestplate 1, iron_leggings 1, iron_boots 1; priced 687 s"), sh.line());
        List<String> said = new ArrayList<>();
        for (Step s : pc.steps()) if (legs.starts().containsKey(s)) said.add(legs.starts().get(s));
        assertEquals(LEG_LINES, said);
    }

    static final List<String> LEG_LINES = List.of("playbook leg 1/5: on the surface: torch 4", "playbook leg 2/5: descent to y 16 for raw_iron 3, coal 1 (one strip)",
            "playbook leg 3/5: at the band: iron_ingot 3, iron_pickaxe 1", "playbook leg 4/5: strip on at y 16 for raw_iron 24, coal 3 (with the new pickaxe)",
            "playbook leg 5/5: at the band: iron_ingot 24, iron_helmet 1, iron_chestplate 1, iron_leggings 1, iron_boots 1");

    /**
     * Run BE 08:08:15 ("playbook 450.0 s (ends in an explore) vs ad-hoc 900.0 s (ends in an explore): ad-hoc"; manager: the
     * price is the SUM of the legs, price against price). No tree known: the surface gate (the stone pickaxe, its wooden
     * pickaxe's logs) ends in an explore - priced as the explore, 450 s, and taken as had after it; the rest as in the kit
     * scenario, but the descent from where the explore leaves the bot is the break formula (rule 18, price review 1: 48 levels x 2
     * breaks x 0.6 = 57.6, never the feet's path): torches 0.3 + descent 57.6 + strip 61.05 + furnace and 3 smelts 30.15 +
     * table and pickaxe 0.45 + the strip on 325.2 + a furnace and a table placed again 0.3 (z77 legs) + 24 smelts and four
     * crafts 241.2 = 716.25: 1166.25 s. The steps end at the
     * explore (its find re-prices the rest). The ad-hoc row: torches 0.3, then each iron order's pickaxe ends in the same
     * explore with nothing priced before it: 5 x 450 = 2250.3 s - the playbook wins.
     */
    @Test
    void be_aPlaybookWhoseFirstLegExploresIsTheSumOfItsLegs() {
        data();
        World w = world(Map.of("minecraft:stick", 64, "minecraft:crafting_table", 5, "minecraft:furnace", 5, "minecraft:coal", 9), Map.of());
        Playbook pb = Playbook.derive(KIT, Playbook.facts(w, null));
        assertTrue(pb.why().contains("surface [torch 4, stone_pickaxe 1]"), pb.why());
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok() && pc.explore(), pc.why());
        assertEquals(NeedChain.exploreS() + 716.25, pc.seconds(), 0.1, pc.why());
        assertTrue(pc.steps().get(pc.steps().size() - 1) instanceof Step.Explore, pc.steps().toString());
        assertTrue(pc.steps().stream().noneMatch(s -> s instanceof Step.Strip), "the run's steps end at the explore: " + pc.steps());
        assertTrue(BatchWay.shape("b1", 6, pb, pc, Map.of()).line().endsWith("; priced with its explore 1166 s"), "the trip's sum, said");
    }

    /**
     * SHAPE CHANGE (rule 15, pricing inside legs): the descent's path computing - the first leg (the torches, 0.3 s) runs now;
     * the descent is priced when the bot gets there: the run's steps end before it, said. The first leg itself waiting on a
     * path: the trip stands (rule 22), never another planner.
     */
    @Test
    void be_aLaterLegNotPricedYet_theFirstLegRuns_theTripIsPricedAtTheBoundary() {
        data();
        World w = world(kitBag(9), Map.of()).withPath((x, y, z, d) -> y < 60 ? PathPrice.Answer.computing("searching") : PATHS.price(x, y, z, d));
        Playbook pb = Playbook.derive(KIT, Playbook.facts(w, null));
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        assertEquals(List.of(new Step.Craft("torch", 4)), pc.steps());
        assertTrue(pc.why().contains("priced when the bot gets there (descent to y 16: " + PathPrice.NOT_KNOWN), pc.why());
        // nothing before the waiting leg: the trip stands
        World none = world(Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 64, "minecraft:crafting_table", 5, "minecraft:furnace", 5, "minecraft:coal", 9, "minecraft:torch", 4), Map.of())
                .withPath((x, y, z, d) -> y < 60 ? PathPrice.Answer.computing("searching") : PATHS.price(x, y, z, d));
        Playbook pb2 = Playbook.derive(KIT.subList(1, KIT.size()), Playbook.facts(none, null));
        Chain pc2 = NeedChain.resolvePlaybook(pb2, none);
        assertFalse(pc2.ok(), pc2.why());
        BatchWay.Shaped s = BatchWay.shape("b1", 5, pb2, pc2, Map.of());
        assertFalse(s.settled(), "asked again when the answer lands");
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), s.chain().steps(), s.line());
        assertTrue(s.line().contains("stand while the first leg's walks compute"), s.line());
    }

    /** Freeze review 1: two orders that each take 1 coal, the bag holds 1 - it counts for one of them only: the playbook gathers the other. */
    @Test
    void review1_theBagCountsOnceForTheBatch() {
        TestData d = data();
        d.recipe("test:lamp", 1, "coal", "stick").recipe("test:lantern", 1, "coal", "stick");
        World w = world(Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 64, "minecraft:coal", 1), Map.of());
        Playbook pb = Playbook.derive(List.of(new Target("test:lamp", 1), new Target("test:lantern", 1)), Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        assertTrue(pb.why().contains("descent y 16 [coal 1]"), "the second order's coal is gathered: " + pb.why());
    }

    /** Freeze review 2: a kept tree is not reused where one of its leaves is on the path (a loop it would hide): asked again, the loop is cut. */
    @Test
    void review2_aKeptTreeIsNotReusedOverItsOwnLeaf() {
        data();
        Playbook.Ex x = new Playbook.Ex(Playbook.facts(world(kitBag(9), Map.of()), null));
        Playbook.Tree fake = new Playbook.Tree(null, Map.of("minecraft:raw_iron", 3), List.of());
        x.memo.put("minecraft:iron_pickaxe|1", fake);
        Playbook.Tree off = Playbook.expand(x, "minecraft:iron_pickaxe", 1, new ArrayList<>());
        assertSame(fake, off, "off the path: the kept tree");
        Playbook.Tree on = Playbook.expand(x, "minecraft:iron_pickaxe", 1, new ArrayList<>(List.of("minecraft:raw_iron")));
        assertNotSame(fake, on, "its leaf on the path: asked again");
        assertFalse(on.ok(), "raw_iron needs itself through the pickaxe's ingots: " + on);
    }

    // ---- 3. the fuel stripped too: one strip of both families, split into legs past steps.stepMaxS (R2) ----

    /**
     * The bag holds 1 coal (the torches take it): each strip takes the iron AND the coal at once (the fork's StripProcess
     * takes any ore set; the end rule waits for every amount). The pickaxe's part: 28.8 + iron 61.05 + coal 1 x 16 cells x 2
     * x 0.6 = 19.2 + 1.15 = 20.35, then 0.15 + 30 + 0.15 + 0.3; the rest with the iron pickaxe: iron 325.2 + coal 3 x 16 =
     * 48 cells x 2 x 0.4 = 38.4 + 3 x 0.75 = 2.25 (40.65), then a furnace and a table placed again 0.3 (z77 legs: at the
     * tunnel's end) + 240 + 1.2. Total 0.3 + 110.2 + 30.6 + 365.85 + 241.5 = 748.45 s. With steps.stepMaxS 300 the second strip's dig 365.85 is past it: 2 legs of that strip (iron 12 + 12, coal 2
     * + 1), the price the same. The ad-hoc row cannot get the fuel down there (a strip leg is the chain's one leg to another
     * level): it ends in an explore, the priced playbook runs.
     */
    @Test
    void s1_theFuelIsStrippedInTheSameStrip_inLegs() {
        data();
        Tuning.set("steps.stepMaxS", 300);
        World w = world(kitBag(1), Map.of());
        Playbook pb = Playbook.derive(KIT, Playbook.facts(w, null));
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        assertEquals(748.45, pc.seconds(), 0.1, pc.why());
        List<Step.Strip> strips = pc.steps().stream().filter(s -> s instanceof Step.Strip).map(s -> (Step.Strip) s).toList();
        assertEquals(3, strips.size(), "the pickaxe's strip, then two legs of the rest: " + pc.steps());
        for (Step.Strip s : strips) assertEquals(List.of(IRON_ORE, COAL_ORE), s.ores(), "both families in one strip");
        assertEquals(strips.get(1).key(), strips.get(2).key(), "legs of the same strip");
        List<int[]> ic = new ArrayList<>();
        for (Step.Strip s : strips) {
            int iron = 0, coal = 0;
            for (var e : s.amounts().entrySet()) { if (e.getKey().contains("coal")) coal += e.getValue(); else iron += e.getValue(); }
            ic.add(new int[]{iron, coal});
        }
        assertArrayEquals(new int[]{3, 1}, ic.get(0));
        assertArrayEquals(new int[]{12, 2}, ic.get(1));
        assertArrayEquals(new int[]{12, 1}, ic.get(2));
        assertEquals(1, count(pc, Step.ToStrip.class), "one descent");
    }

    /** R2 for any strip: a strip priced within steps.stepMaxS is one leg, past it the legs share every amount. Pure. */
    @Test
    void stripLegsShareTheAmounts() {
        Step.Strip whole = new Step.Strip(List.of(IRON_ORE), Map.of("iron", 5), 16, "east", 0);
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.Goals.stripLoop(whole), NeedChain.stripLegs(whole, 599));
        List<Step> legs = NeedChain.stripLegs(whole, 1250);         // 3 legs: 2 + 2 + 1
        List<Integer> parts = legs.stream().filter(s -> s instanceof Step.Strip).map(s -> ((Step.Strip) s).amounts().get("iron")).toList();
        assertEquals(List.of(2, 2, 1), parts);
        assertEquals(9, legs.size(), "each leg with its deposit and walk back");
    }

    // ---- 4. the source 10 m away: no descent at all ----

    /**
     * 27 iron ore and 4 coal ore on a cave wall 10 m away: the known sources cover the counts, so the iron is a surface leaf -
     * the playbook has no DESCENT leg; it still prices one table and one furnace for the batch where the ad-hoc row places
     * five of each, so it is not dearer.
     */
    @Test
    void s2_theSourceTenMetresAway_noDescentLeg() {
        data();
        World w = world(kitBag(9), Map.of("raw_iron", List.of(new Spot(10, 64, 0, 10, IRON_ORE, 27)), "coal", List.of(new Spot(10, 64, 2, 10, COAL_ORE, 4))));
        Playbook pb = Playbook.derive(KIT, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        assertTrue(pb.legs().stream().noneMatch(l -> l.kind() == Playbook.Kind.DESCENT), pb.why());
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        assertEquals(0, count(pc, Step.ToStrip.class), pc.steps().toString());
        assertEquals(1, pc.steps().stream().filter(s -> s instanceof Step.PlaceNear p && p.item().contains("furnace")).count(), "one furnace for the batch: " + pc.steps());
    }

    // ---- 5. a modded pack: two gates, two descents, from the same code ----

    /**
     * A fake pack: "test:a_ingot" smelts from "test:a_raw", whose ore asks tier 2; the only tier-2 pickaxe is made of
     * "test:b_ingot" from "test:b_raw", whose ore a wooden pickaxe (tier 1, all surface) digs. The legs: surface (the wooden
     * pickaxe) > descent (b, its Y) > at band (b_ingot, the tier-2 pickaxe) > descent (a, its Y) > at band (a_ingot).
     */
    @Test
    void s5_aModdedPackGivesTwoGatesAndTwoDescents() {
        TestData d = new TestData();
        d.tool("wooden_pickaxe", ItemWorth.Role.PICKAXE, 2, 0, 59).tool("test:b_pickaxe", ItemWorth.Role.PICKAXE, 4, 1, 100);
        d.tag("wooden_pickaxe", "minecraft:pickaxes").tag("test:b_pickaxe", "minecraft:pickaxes").tag("oak_log", "minecraft:logs");
        d.recipe("oak_planks", 4, "oak_log").recipe("stick", 4, "oak_planks", "oak_planks").recipe("wooden_pickaxe", 1, "oak_planks", "oak_planks", "oak_planks", "stick", "stick")
                .recipe("test:b_pickaxe", 1, "test:b_ingot", "test:b_ingot", "test:b_ingot", "stick", "stick").smelt("test:b_ingot", "test:b_raw").smelt("test:a_ingot", "test:a_raw");
        d.tiers.put("test:a_ore", 2);
        d.tiers.put("test:b_ore", 1);
        d.install();
        Map<String, List<String>> ores = Map.of("test:a_raw", List.of("test:a_ore"), "test:b_raw", List.of("test:b_ore"));
        Map<String, Integer> ys = Map.of("test:a_raw", -20, "test:b_raw", 10);
        Playbook.Facts f = new Playbook.Facts(
                word -> word.contains("log") ? new GenWhere.Fact(GenWhere.Kind.SURFACE, 0, 0, "minecraft:oak_log", "trees", null)
                        : word.endsWith("_raw") ? new GenWhere.Fact(GenWhere.Kind.BAND, -64, 40, word, "ore", null) : GenWhere.unknown("test"),
                (word, n) -> false, word -> false, ores::get, ys::get,
                (word, y) -> ys.containsKey(word) && ys.get(word).equals(y) ? 64.0 : 0.0, Map.of(), 0);
        Playbook pb = Playbook.derive(List.of(new Target("test:a_ingot", 3)), f);
        assertTrue(pb.ok(), pb.why());
        assertEquals("playbook: surface [wooden_pickaxe 1] > descent y 10 [test:b_raw 3] > at band [test:b_ingot 3, test:b_pickaxe 1] > descent y -20 [test:a_raw 3] "
                + "> at band [test:a_ingot 3]", pb.why());
        assertEquals(List.of(Playbook.Kind.SURFACE, Playbook.Kind.DESCENT, Playbook.Kind.AT_BAND, Playbook.Kind.DESCENT, Playbook.Kind.AT_BAND),
                pb.legs().stream().map(Playbook.Leg::kind).toList());
        assertEquals(java.util.Set.of("test:b_pickaxe"), pb.legs().get(2).gates(), "the tier-2 pickaxe is a gate made at the band");
        assertEquals(2, pb.legs().get(2).tier());
    }

    /** Rule 15: no item or tier data - the playbook is "not loaded", said, never "none". */
    @Test
    void notLoadedIsSaid() {
        Playbook pb = Playbook.derive(KIT, new Playbook.Facts(w -> null, (w, n) -> false, w -> false, w -> null, w -> null, (w, y) -> Double.NaN, Map.of(), 0));
        assertFalse(pb.ok());
        assertTrue(pb.fail().startsWith(Playbook.NOT_LOADED), pb.fail());
    }

    // ---- 6. the carry: what the band needs is had before the descent ----

    /**
     * The bag: a stone pickaxe, 9 coal, 8 cobblestone - no table, no furnace, no sticks; a tree 20 m away. The tree is cut
     * before the descent (the sticks and the table come from its logs); down there nothing is walked to - the table and the
     * furnace are placed near the bot from the bag (z75's take-along, beforeDown).
     */
    @Test
    void s6_whatTheBandNeedsIsCarriedDown() {
        data();
        World w = world(Map.of("minecraft:stone_pickaxe", 1, "minecraft:coal", 9, "minecraft:cobblestone", 8),
                Map.of("oak_log", List.of(ScenariosTest.tree(20, 6))));
        Playbook pb = Playbook.derive(KIT, Playbook.facts(w, null));
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        int down = -1, chop = -1;
        for (int i = 0; i < pc.steps().size(); i++) {
            if (pc.steps().get(i) instanceof Step.ToStrip && down < 0) down = i;
            if (pc.steps().get(i) instanceof Step.ChopTree && chop < 0) chop = i;
        }
        assertTrue(chop >= 0 && down > chop, "the tree is cut before the descent: " + pc.steps());
        for (int i = down + 1; i < pc.steps().size(); i++) {
            Step s = pc.steps().get(i);
            assertFalse(s instanceof Step.Walk || s instanceof Step.ToStation || s instanceof Step.ChopTree || s instanceof Step.Collect, "no walk down there: " + s.text() + " in " + pc.steps());
        }
        assertTrue(pc.steps().subList(down, pc.steps().size()).stream().anyMatch(s -> s instanceof Step.PlaceNear p && p.item().contains("furnace")), pc.steps().toString());
    }

    // ---- the order's side: a batch's chain, the chat words, a failed playbook step ----

    static Thing.Result item(String path) { return new Thing.Result(path, path, false, List.of("minecraft:" + path), List.of(path), 0, null, null, null); }

    /** Goals.chain for an order of a batch: the playbook, logged once with both prices and said to the owner; each leg said as it starts. */
    @Test
    void anOrderOfTheBatchRunsThePlaybookAndSaysItsLegs() {
        data();
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        World w = world(kitBag(9), Map.of());
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<World>) () -> w);
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{0, 64, 0};
        List<Goals.Goal> gs = new ArrayList<>();
        for (Target t : KIT) {
            Goals.Goal g = Goals.add(bb, item(t.item().substring("minecraft:".length())), t.n(), 0, clock.now());
            g.batch = "b1";
            gs.add(g);
        }
        Goals.Goal torch = gs.get(0);
        Chain ch = Goals.chain(bb, torch);
        assertTrue(ch.why().startsWith(NeedChain.PLAYBOOK), ch.why());
        assertEquals(687.45, ch.seconds(), 0.1);
        assertEquals(1, port.log.stream().filter(l -> l.startsWith("torch: trip b1 (6 orders): 5 legs - ")).count(), port.log.toString());
        Goals.chain(bb, torch);                                  // the same bag and place: the kept trip, no second line
        assertEquals(1, port.log.stream().filter(l -> l.contains("trip b1 (")).count(), port.log.toString());
        // the order takes it: the whole trip said before the bot moves, then each leg's first step says the leg
        var rt = Goals.routine(bb, torch);
        assertEquals(1, port.told.stream().filter(l -> l.startsWith("trip b1 (6 orders): 5 legs - 1 surface: torch 4 | 2 descent to y 16")).count(), port.told.toString());
        Steps run = new Steps(new DoneHands());
        run.start("goal.torch", rt.steps(), 0, Goals.replanner(bb, torch));
        for (int i = 0; i < 200 && run.running(); i++) run.tick(i + 1);
        assertEquals(LEG_LINES, port.told.stream().filter(l -> l.startsWith("playbook leg")).toList());
        // SHAPE CHANGE: a failed step of the trip is repaired inside its leg - never the J2 hold and an ad-hoc chain
        // (the strip ended short with no source held: the gap for what it served - raw_iron 3 for the smelt - from the bag as
        // it is, the trip's later steps kept, never priced against a whole re-plan)
        int at = -1;
        for (int i = 0; i < rt.steps().size(); i++) if (rt.steps().get(i) instanceof Step.Strip) { at = i; break; }
        Step strip = rt.steps().get(at);
        List<Step> rest = rt.steps().subList(at, rt.steps().size());
        List<Map<String, Integer>> restUses = rt.uses().size() == rt.steps().size() ? rt.uses().subList(at, rt.steps().size()) : null;
        var r = Goals.replan(bb, torch, strip, new FactChange.Fact("short strip", "strip: 1 of 3 iron"), "strip: 1 of 3 iron",
                new Steps.Remaining(rest, restUses, Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 64, "minecraft:crafting_table", 5, "minecraft:furnace", 5, "minecraft:coal", 9, "minecraft:torch", 4)));
        assertNotNull(r.steps(), r.why());
        // here the gap (raw_iron 3 from the surface) ends in an explore, so no repair stands: the trip is priced again from the
        // bag - the same derivation, never the order's own chain (Z73RepairTest: a repair that stands is kept in a trip)
        assertTrue(port.log.stream().anyMatch(l -> l.contains("strip: 1 of 3 iron - re-planned: ") && l.contains("chain: playbook <- ")), port.log.subList(Math.max(0, port.log.size() - 3), port.log.size()).toString());
        assertTrue(torch.playbookRun, "the trip goes on");
        assertTrue(Goals.droppedSources(torch).keySet().stream().noneMatch(k -> k.startsWith("playbook ")), Goals.droppedSources(torch).toString());
        assertTrue(Goals.chain(bb, torch).why().startsWith(NeedChain.PLAYBOOK), "still the trip");
        // an order alone: its own trip too
        Goals.Goal alone = Goals.add(bb, item("iron_helmet"), 1, 0, clock.now());
        assertTrue(Goals.chain(bb, alone).why().startsWith(NeedChain.PLAYBOOK), "one order is one trip");
    }

    static Blackboard batchBoard(FakePort port, Clock.Fake clock, java.util.function.Supplier<World> w, List<Goals.Goal> gs) {
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        bb.fact(NeedChain.WORLD_FACT, w);
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{0, 64, 0};
        for (Target t : KIT) {
            Goals.Goal g = Goals.add(bb, item(t.item().substring("minecraft:".length())), t.n(), 0, clock.now());
            g.batch = "b1";
            gs.add(g);
        }
        return bb;
    }

    /**
     * Answers (1) (manager: the batch's run carries on): the iron pickaxe's order runs the playbook; its "got" comes at leg 3
     * (the pickaxe crafted). That is a fact inside the run, not its end: the order carries on (its score kept, its desire not
     * removed - Goals.carriesOn), a landed price re-resolves nothing, and the same run says legs 4/5 and 5/5. No second
     * comparison is priced.
     */
    @Test
    void answers1_theBatchRunCarriesOnPastAnOrdersGot() {
        data();
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        World w = world(kitBag(9), Map.of());
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = batchBoard(port, clock, () -> w, gs);
        Goals.Goal pick = gs.get(1);
        var rt = Goals.routine(bb, pick);
        assertTrue(pick.playbookRun, "the pickaxe's order runs the batch's playbook");
        Steps run = new Steps(new DoneHands());
        run.start("goal.iron_pickaxe", rt.steps(), 0, Goals.replanner(bb, pick));
        int crafted = -1;
        for (int i = 0; i < rt.steps().size(); i++) if (rt.steps().get(i) instanceof Step.Craft k && k.item().equals("iron_pickaxe")) crafted = i;
        int t = 0;
        while (run.running() && port.told.stream().noneMatch(l -> l.startsWith("playbook leg 4/5")) && t < 500) run.tick(++t);
        bb.in.bag = Map.of("iron_pickaxe", 1, "torch", 4);           // the pickaxe crafted: its order is met
        assertTrue(crafted >= 0);
        List<Goals.Goal> met = Goals.done(bb);
        assertTrue(met.contains(pick), met.toString());
        assertTrue(Goals.carriesOn(pick, pick.id()), "its run carries on");
        assertTrue(Goals.score(bb, pick) > 0, "no interrupt by the batch's next order while its run goes on");
        var kept = Goals.replan(bb, pick, rt.steps().get(0), FactChange.priced("walk to 0 16 0: 28.8 s, complete"), "landed");
        assertNull(kept.steps(), "a landed price re-resolves nothing: " + kept.why());
        while (run.running() && t < 1000) run.tick(++t);
        assertEquals(LEG_LINES, port.told.stream().filter(l -> l.startsWith("playbook leg")).toList(), "the same run, its numbers kept");
        assertEquals(1, port.told.stream().filter(l -> l.startsWith("trip b1 (")).count(), "one trip said: " + port.told);
    }

    /**
     * Review 1 (R3): while a descent's path computes (the strip cells COMPUTING, paths pending) the verdict is not settled -
     * still kept until the next answer lands: the order's chain asked again and its blocked() test every tick price no
     * second comparison (no walk asked again); a landed answer makes the next ask compare again.
     */
    @Test
    void review1_anUnsettledVerdictIsKeptUntilAnAnswerLands() {
        data();
        int[] asks = {0};
        long[] landed = {0};
        PathPrice pp = new PathPrice() {
            @Override public Answer price(int x, int y, int z, double dist) {
                asks[0]++;
                return y < 60 ? Answer.computing("searching") : PATHS.price(x, y, z, dist);
            }
            @Override public boolean pending() { return true; }
            @Override public long landed() { return landed[0]; }
        };
        // the torches in the bag: the trip's first leg is the descent, whose walk computes - the trip stands, kept until an answer lands
        World w = world(Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 64, "minecraft:crafting_table", 5, "minecraft:furnace", 5, "minecraft:coal", 9, "minecraft:torch", 4), Map.of()).withPath(pp);
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = batchBoard(new FakePort(), new Clock.Fake(10_000_000), () -> w, gs);
        Goals.chain(bb, gs.get(0));
        int first = asks[0];
        assertTrue(first > 0, "the first trip asks the strips' walks");
        // shape review 2 (manager: per BATCH, the 42-per-tick lesson): every order of the batch, every tick, asks the one trip
        for (int i = 0; i < 20; i++) for (Goals.Goal g : gs) { Goals.blocked(bb, g); Goals.chain(bb, g); }
        assertEquals(first, asks[0], "20 ticks x 6 orders with paths pending: the trip kept, no walk asked again");
        assertEquals(1, BatchWay.priced("b1"), "one trip priced for the whole batch");
        landed[0]++;
        for (Goals.Goal g : gs) { Goals.blocked(bb, g); Goals.chain(bb, g); }
        assertTrue(asks[0] > first, "an answer landed: priced again");
        assertEquals(2, BatchWay.priced("b1"), "one re-price per landed path for the whole batch, not one per order");
        // shape review 2: an order alone whose trip is one leg (its own chain) is kept the same way - not resolved on every ask
        Goals.Goal alone = Goals.add(bb, item("stick"), 4, 0, 10_000_000);
        for (int i = 0; i < 10; i++) Goals.chain(bb, alone);
        assertEquals(1, BatchWay.priced("own " + alone.id()), "the own chain resolved once while its key holds");
    }

    /**
     * 0.39.z77 freeze (run BD 07:53:24: the client froze in the expansion - every fuel's wooden tree through every tag member,
     * the world read at each node). A vanilla-sized recipe set: the iron nugget / block cycles, nuggets smelted from every
     * iron tool and armour piece, the raw-iron and coal blocks, every wood's planks / slabs / stairs / fences / boats / doors
     * as fuel. The kit's derivation stands within the node bound and a second, each world fact read once a word.
     */
    @Test
    void freeze_theRealRecipeSetExpandsWithinItsBound() {
        TestData d = data();
        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped"};
        for (String w : woods) {
            d.tag(w + "_log", "minecraft:logs", "minecraft:logs_that_burn").tag(w + "_planks", "minecraft:planks");
            d.recipe(w + "_planks", 4, w + "_log");
            d.recipe(w + "_slab", 6, w + "_planks", w + "_planks", w + "_planks").recipe(w + "_stairs", 4, java.util.Collections.nCopies(6, w + "_planks").toArray(new String[0]));
            d.recipe(w + "_fence", 3, w + "_planks", "stick", w + "_planks", w + "_planks", "stick", w + "_planks").recipe(w + "_boat", 1, java.util.Collections.nCopies(5, w + "_planks").toArray(new String[0]));
            d.recipe(w + "_door", 3, java.util.Collections.nCopies(6, w + "_planks").toArray(new String[0])).recipe(w + "_trapdoor", 2, java.util.Collections.nCopies(6, w + "_planks").toArray(new String[0]));
            for (String k : List.of("_log", "_planks", "_slab", "_stairs", "_fence", "_boat", "_door", "_trapdoor")) d.fuel(w + k, 300);
        }
        d.recipe("stick", 4, "#minecraft:planks", "#minecraft:planks").recipe("crafting_table", 1, "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks");
        d.recipe("bowl", 4, "#minecraft:planks", "#minecraft:planks", "#minecraft:planks").fuel("bowl", 100).fuel("stick", 100).fuel("crafting_table", 300).fuel("wooden_pickaxe", 200);
        d.recipe("chest", 1, java.util.Collections.nCopies(8, "#minecraft:planks").toArray(new String[0])).fuel("chest", 300).recipe("barrel", 1, "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks").fuel("barrel", 300);
        d.recipe("iron_nugget", 9, "iron_ingot").recipe("iron_ingot", 1, java.util.Collections.nCopies(9, "iron_nugget").toArray(new String[0]));
        d.recipe("iron_block", 1, java.util.Collections.nCopies(9, "iron_ingot").toArray(new String[0])).recipe("iron_ingot", 9, "iron_block");
        d.recipe("raw_iron_block", 1, java.util.Collections.nCopies(9, "raw_iron").toArray(new String[0])).recipe("raw_iron", 9, "raw_iron_block");
        d.recipe("coal_block", 1, java.util.Collections.nCopies(9, "coal").toArray(new String[0])).recipe("coal", 9, "coal_block");
        for (String k : List.of("pickaxe", "axe", "shovel", "hoe", "sword", "helmet", "chestplate", "leggings", "boots")) d.smelt("iron_nugget", "iron_" + k);
        d.smelt("iron_ingot", "iron_ore").smelt("iron_ingot", "deepslate_iron_ore").smelt("iron_ingot", "raw_iron_block");
        Map<String, Integer> asked = new java.util.HashMap<>();
        World w = world(kitBag(9), Map.of());
        Playbook.Facts base = Playbook.facts(w, null);
        Playbook.Facts f = new Playbook.Facts(word -> { asked.merge(word, 1, Integer::sum); return base.where().apply(word); }, base.surfaceKnown(), base.dropOnly(),
                base.oresOf(), base.stripY(), base.perSlab(), base.bag(), base.bagTier());
        long t0 = System.nanoTime();
        Playbook pb = Playbook.derive(KIT, f);
        double ms = (System.nanoTime() - t0) / 1e6;
        assertTrue(pb.ok(), pb.why());
        assertTrue(pb.why().contains("descent y 16 [raw_iron 3, coal 1]") && pb.why().contains("strip on y 16"), pb.why());
        assertTrue(Playbook.lastNodes > 0 && Playbook.lastNodes < NeedChain.maxTrials(), "nodes " + Playbook.lastNodes);
        assertTrue(ms < 1000, "a second at most: " + ms + " ms, " + Playbook.lastNodes + " nodes");
        assertTrue(asked.values().stream().allMatch(n -> n == 1), "each world fact read once a word: " + asked);
    }

    /**
     * The same set with every wood ingredient a tag (#planks, each wood's own logs tag) and no world-gen fact for the logs (no
     * surface leaf anywhere: every member of every tag fails, the worst case for a search without memo): the derivation
     * ends within the bound with its reason, never a hang.
     */
    @Test
    void freeze_noSurfaceLeafAnywhere_endsWithItsReason() {
        TestData d = data();
        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped"};
        for (String w : woods) {
            for (String l : List.of("_log", "_wood", "stripped_" + w + "_log", "stripped_" + w + "_wood"))
                d.tag(l.startsWith("stripped") ? l : w + l, "minecraft:" + w + "_logs", "minecraft:logs");
            d.tag(w + "_planks", "minecraft:planks").recipe(w + "_planks", 4, "#minecraft:" + w + "_logs");
            for (String k : List.of("_slab", "_stairs", "_fence", "_boat", "_door")) { d.recipe(w + k, 2, "#minecraft:planks", "stick", "#minecraft:planks"); d.fuel(w + k, 300); }
        }
        d.recipe("stick", 4, "#minecraft:planks", "#minecraft:planks").fuel("stick", 100);
        d.recipe("iron_nugget", 9, "iron_ingot").recipe("iron_ingot", 1, java.util.Collections.nCopies(9, "iron_nugget").toArray(new String[0]));
        for (String k : List.of("pickaxe", "axe", "shovel", "hoe", "sword", "helmet", "chestplate", "leggings", "boots")) d.smelt("iron_nugget", "iron_" + k);
        World w = world(Map.of("minecraft:stone_pickaxe", 1, "minecraft:coal", 9), Map.of());
        Playbook.Facts base = Playbook.facts(w, null);
        Map<String, Integer> asked = new java.util.HashMap<>();
        Playbook.Facts f = new Playbook.Facts(word -> { asked.merge(word, 1, Integer::sum); return word.contains("log") ? GenWhere.unknown("placed both ways (test)") : base.where().apply(word); }, base.surfaceKnown(),
                base.dropOnly(), base.oresOf(), base.stripY(), base.perSlab(), base.bag(), base.bagTier());
        long t0 = System.nanoTime();
        Playbook pb = Playbook.derive(KIT, f);
        double ms = (System.nanoTime() - t0) / 1e6;
        assertTrue(ms < 1000, "a second at most: " + ms + " ms, " + Playbook.lastNodes + " nodes");
        assertFalse(pb.ok(), "no surface leaf for the sticks: " + pb.why());
        assertTrue(asked.values().stream().allMatch(n -> n == 1), "each world fact read once a word (the live read scans the biomes): " + asked);
        assertTrue(pb.fail().startsWith("torch: "), "the memo kept it within the bound, the reason is the sticks: " + pb.fail());
        assertFalse(pb.fail().contains("needChain.maxTrials"), pb.fail());
    }

    /** R3: the load probe's aatick line says the playbook's comparisons and their ms (only when one ran in the tick). */
    @Test
    void theLoadProbeSaysThePlaybookMs() {
        long[] now = {0};
        var laps = new io.github.mojolowjo.entropybot.aa.core.TickLaps(() -> now[0]);
        laps.begin(true);
        laps.resolved(3_000_000);
        laps.playbook(5_000_000);
        laps.playbook(2_500_000);
        now[0] = 30_000_000;
        String l = laps.line(7, 20);
        assertTrue(l.endsWith("| resolves 1 = 3.0 ms | playbook 2 = 7.5 ms"), l);
        laps.begin(true);
        now[0] = 60_000_000;
        assertFalse(laps.line(8, 20).contains("playbook"), "no comparison this tick: not said");
    }

    /** The bound is said, never a hang: a budget of 10 nodes fails the playbook with the reason. */
    @Test
    void freeze_pastTheNodeBoundThePlaybookSaysSo() {
        data();
        Tuning.set("needChain.maxTrials", 10);
        Playbook pb = Playbook.derive(KIT, Playbook.facts(world(kitBag(9), Map.of()), null));
        assertFalse(pb.ok());
        assertTrue(pb.fail().contains("passed needChain.maxTrials 10 nodes"), pb.fail());
    }

    /** Hands whose every step is done at once. */
    static final class DoneHands implements Steps.Hands {
        @Override public String start(Step s) { return null; }
        @Override public Car.Status status(Step s) { return Car.Status.done("ok"); }
        @Override public void cancel(String why) {}
    }

}
