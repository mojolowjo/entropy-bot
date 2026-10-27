package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 carry (runs CB 15:58:00-02 and CE 16:19:53-16:20:18, the owner's PC: the trip's carry list was on one re-price
 * and gone on the next - "priced with its explore 981 s" in CB, "priced 477 s" in CE - and the bot went down with no
 * furnace, table or fuel; CB 16:04:24-16:05:34: the trip re-priced at every Y the bot passed). One cause: the trip's derived
 * parts were recomputed from transient state - the carry list read off the run's cut (the explore's, a leg not priced yet),
 * the trip key off the feet's Y. Fakes: Z77PlaybookTest's (the fake paths, bands, loot, world-gen; feet 0 64 0 on land).
 * Pure (JUnit). Loader notes: none.
 */
class Z77CarryTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /** be_'s bag (Z77PlaybookTest): sticks, tables, furnaces and coal, no pickaxe - the surface leg makes the stone pickaxe. */
    static final Map<String, Integer> CB_BAG = Map.of("minecraft:stick", 64, "minecraft:crafting_table", 5, "minecraft:furnace", 5, "minecraft:coal", 9);

    static final String CB_CARRY = "2 descent to y 16: raw_iron 3, coal 1, carries furnace 2, coal 4 (fuel), crafting_table 2, stick 2 |";

    static long count(Chain c, Class<?> k) { return c.steps().stream().filter(k::isInstance).count(); }

    static BatchWay.Shaped shaped(Playbook pb, World w, int orders) {
        NeedChain.Legs l = NeedChain.resolvePlaybookLegs(pb, w);
        return BatchWay.shape("b1", orders, pb, l.chain(), l.starts(), l.whole());
    }

    // ---- scenario 1: a re-price of the same legs carries the same ----

    /**
     * CB 15:58:00 / 15:58:02: the same trip priced with a tree known (the stone pickaxe's wooden pickaxe from its logs: the
     * legs priced through) and re-priced with no wood known - the surface leg ends in an explore, taken as had after it; the
     * price is all the legs (be_: 450 + 716.25 = 1166.25 s), the run's steps end at the explore. The carry list is the legs'
     * (what the steps below the descent use): identical - furnace 2, coal 4 (27 smelts x 200 / 1600, up), table 2, stick 2.
     * Was: the explore's cut took the descent out of the chain the line read, so the re-price said no carry at all.
     */
    @Test
    void cb_theTripRepricedWithItsExplore_carriesTheSame() {
        Z77PlaybookTest.data();
        World tree = Z77PlaybookTest.world(CB_BAG, Map.of("oak_log", List.of(ScenariosTest.tree(20, 8))));
        World none = Z77PlaybookTest.world(CB_BAG, Map.of());
        Playbook pbA = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(tree, null)), pbB = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(none, null));
        assertEquals(pbA.why(), pbB.why(), "the same legs");
        BatchWay.Shaped a = shaped(pbA, tree, 6), b = shaped(pbB, none, 6);
        assertTrue(a.chain().ok() && !a.chain().explore(), a.chain().why());
        assertTrue(b.chain().ok() && b.chain().explore(), b.chain().why());
        assertTrue(a.change().contains(CB_CARRY), a.change());
        assertEquals(a.change(), b.change(), "the re-price with its explore carries the same");
        assertTrue(b.line().contains(CB_CARRY) && b.line().endsWith("; priced with its explore 1166 s"), b.line());
        // the run is as before: its steps end at the explore, nothing goes down before it (the find re-prices the trip)
        assertTrue(b.chain().steps().get(b.chain().steps().size() - 1) instanceof Step.Explore, b.chain().steps().toString());
        assertEquals(0, count(b.chain(), Step.ToStrip.class) + count(b.chain(), Step.Strip.class), b.chain().steps().toString());
        // the trip line is logged on a change only (BatchWay.sayAgain): the explore variant is no change
        assertTrue(BatchWay.sayAgain("b1", a.change()));
        assertFalse(BatchWay.sayAgain("b1", b.change()), "said once");
    }

    /** CE's shape: a crafting table, logs and planks known up there, their chunks not loaded (rule 15: not known, waited for). */
    static World ce(boolean loaded, Map<String, Integer> bag) {
        Spot table = new Spot(20, 64, 0, 20, "minecraft:crafting_table", 1), log = ScenariosTest.tree(20, 8), planks = new Spot(30, 64, 0, 30, "minecraft:oak_planks", 4);
        Map<String, List<Spot>> seen = loaded ? Map.of("crafting_table", List.of(table), "oak_log", List.of(log), "oak_planks", List.of(planks))
                : Map.of("crafting_table", List.of(table.notLoaded()), "oak_log", List.of(log.notLoaded()), "oak_planks", List.of(planks.notLoaded()));
        return ScenariosTest.w(bag, false, false, seen).withMobs(x -> List.of(), Z77PlaybookTest.book()).withPath(Z77PlaybookTest.PATHS).withStrip(Z77PlaybookTest::strip)
                .withWhere(new NeedChain.WhereFacts(Z77PlaybookTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0},
                        () -> new GenWhere.Base(Z77PlaybookTest.STONE, Integer.MIN_VALUE, null, "test")));
    }

    /** No table and no wood in the bag: the band leg's table (the iron pickaxe's) is the one met up there and carried down. */
    static final Map<String, Integer> CE_BAG = Map.of("minecraft:stone_pickaxe", 1, "minecraft:furnace", 5, "minecraft:coal", 9, "minecraft:stick", 64);

    /**
     * CE 16:20:05 ("priced 477 s", no carry list: the torches and the descent, the band legs cut): the band leg's table waits
     * on a fact not loaded - "priced when the bot gets there". What the descent carries is what the legs below it use, so
     * the run is cut before THAT descent: the torches run (0.3 s), nothing goes down; the line says which legs are not
     * priced yet. Was: cut before the band leg - the run went down with no table. The chunks loaded: the same legs priced,
     * the table carried down.
     */
    @Test
    void ce_aBandLegNotPricedYet_theDescentWaitsForItsCarryList() {
        Z77PlaybookTest.data();
        World w = ce(false, CE_BAG);
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        BatchWay.Shaped s = shaped(pb, w, 6);
        Chain pc = s.chain();
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        assertTrue(pc.why().contains(NeedChain.PRICED_THERE + "at band: iron_pickaxe: source crafting_table at 20 64 0: " + NeedChain.SOURCE_NOT_LOADED), pc.why());
        assertEquals(List.of(new Step.Craft("torch", 4)), pc.steps(), "nothing goes down before its legs below are priced");
        assertTrue(s.line().contains("1 surface: torch 4 | 2 descent to y 16: raw_iron 3, coal 1 (not priced yet) | 3 at the band: iron_ingot 3, iron_pickaxe 1 (not priced yet)")
                && !s.line().contains(", carries "), s.line());
        World up = ce(true, CE_BAG);
        BatchWay.Shaped p = shaped(Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(up, null)), up, 6);
        assertTrue(p.chain().ok() && count(p.chain(), Step.ToStrip.class) == 1, p.chain().why());
        assertTrue(p.line().contains("2 descent to y 16: raw_iron 3, coal 1, carries ") && p.line().contains("crafting_table") && !p.line().contains("(not priced yet)"), p.line());
    }

    /**
     * The same facts with the torches had: the descent is the trip's first leg, nothing runs before it - the trip stands
     * (rule 22) on the band leg not priced yet, said; asked again when the answer lands. Was: the descent ran alone.
     */
    @Test
    void ce_nothingBeforeTheDescent_theTripStands() {
        Z77PlaybookTest.data();
        Map<String, Integer> bag = new java.util.HashMap<>(CE_BAG);
        bag.put("minecraft:torch", 4);
        World w = ce(false, bag);
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT.subList(1, Z77PlaybookTest.KIT.size()), Playbook.facts(w, null));
        assertTrue(pb.ok() && pb.legs().get(0).kind() == Playbook.Kind.DESCENT, pb.why());
        BatchWay.Shaped s = shaped(pb, w, 5);
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), s.chain().steps(), s.line());
        assertFalse(s.settled(), "priced again when the answer lands");
        assertTrue(s.line().contains("stand: " + NeedChain.NOT_PRICED + "leg 2/4 (at band [iron_ingot 3, iron_pickaxe 1])" + NeedChain.WAITS_ON + "an answer before going down"), s.line());
    }

    // ---- scenario 2: the trip is re-priced on a fact, never on each Y ----

    /** The kit world whose feet are {@code feet} (the surface at y 64 over them: the depth 64 - y). */
    static World moving(int[] feet) {
        return ScenariosTest.w(Z77PlaybookTest.kitBag(9), false, false, Map.of()).withMobs(x -> List.of(), Z77PlaybookTest.book()).withPath(Z77PlaybookTest.PATHS)
                .withStrip(Z77PlaybookTest::strip)
                .withWhere(new NeedChain.WhereFacts(Z77PlaybookTest::where, () -> new NeedChain.Surface(64 - feet[1], 0, 64, 0, 64 - feet[1]), () -> new int[]{0, feet[1], 0},
                        () -> new GenWhere.Base(Z77PlaybookTest.STONE, Integer.MIN_VALUE, null, "test")));
    }

    /** The batch asks its trip at each Y from {@code from} down to {@code to} (every order, as the AA tick does); the bag stays. */
    static void descend(Blackboard bb, List<Goals.Goal> gs, int[] feet, int from, int to) {
        for (int y = from; y >= to; y--) {
            feet[1] = y;
            bb.in.pos = new int[]{0, y, 0};
            bb.in.underDepth = 64 - y;
            for (Goals.Goal g : gs) Goals.chain(bb, g);
        }
    }

    /**
     * CB 16:04:24-16:05:34 ("here at y -20", "-16", "-15", ...): the bot goes down 20 Y under ground (y 40 to 20), nothing
     * else changes - the trip is priced once, never per Y (was: once per failures.moved 16 Y cell, y 31 re-priced it). From
     * the surface to the band (y 64 to 16) the legs' place changes once - on the surface, then under ground (y 61, 3 blocks
     * of ground: aaLadder.surfaceDepth) - one re-price (was: four, one per 16 Y cell).
     */
    @Test
    void cb_descending20Y_noRepricePerY_theSurfaceToTheBand_oneReprice() {
        Z77PlaybookTest.data();
        int[] feet = {0, 40, 0};
        World w = moving(feet);
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(new FakePort(), new Clock.Fake(10_000_000), () -> w, gs);
        descend(bb, gs, feet, 40, 20);
        assertEquals(1, BatchWay.priced("b1"), "20 Y down under ground, nothing else changed: one price");
        BatchWay.clear();
        descend(bb, gs, feet, 64, 16);
        assertEquals(2, BatchWay.priced("b1"), "the surface, then under ground: one re-price");
        // a fact the trip depends on still re-prices it: the bag
        World w2 = moving(feet).withBag(Z77PlaybookTest.kitBag(10));
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<World>) () -> w2);
        for (Goals.Goal g : gs) Goals.chain(bb, g);
        assertEquals(3, BatchWay.priced("b1"), "the bag changed: priced again");
    }
}
