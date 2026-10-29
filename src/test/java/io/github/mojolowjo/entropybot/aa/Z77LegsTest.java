package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 legs (manager's send-back 2; run S1 BK 10:57:13-14): the trip's steps are flattened LEG BY LEG - a craft in its
 * own leg - and a band leg's planks (its table, sticks, fuel) are carried down from the surface, never a source under
 * ground. BK's cause: a need met "before going down" (NeedChain.beforeDown) was priced on the whole simulated bag, the
 * ingots the strip and the smelt down there make included - "iron_helmet 1 <- before going down: craft: 5 iron_ingot" beat
 * the craft down there (the known table up there 4 m vs one placed down there) and its step went in before the descent,
 * so the dry run said "step 7 (craft iron_helmet 1) needs 5 of iron_ingot; the bag will hold 0". Fakes: Z77PlaybookTest's
 * (the fake paths, bands, loot, world-gen); here a crafting table KNOWN up there (as BK's -110 71 -96), none in the bag, a
 * tree 20 m with 8 logs.
 * Pure (JUnit). Loader notes: none.
 */
class Z77LegsTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    static final Spot TREE = ScenariosTest.tree(20, 8);

    /** BK's shape: a stone pickaxe, 5 furnaces and 9 coal in the bag, no table and no wood; the table known up there; a tree 20 m. */
    static World bk() {
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:furnace", 5, "minecraft:coal", 9);
        return ScenariosTest.w(bag, true, false, Map.of("oak_log", List.of(TREE))).withMobs(x -> List.of(), Z77PlaybookTest.book())
                .withPath(Z77PlaybookTest.PATHS).withStrip(Z77PlaybookTest::strip)
                .withWhere(new NeedChain.WhereFacts(Z77PlaybookTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0},
                        () -> new io.github.mojolowjo.entropybot.aa.brain.GenWhere.Base(Z77PlaybookTest.STONE, Integer.MIN_VALUE, null, "test")));
    }

    /** The bot at 0 16 0, 48 blocks of ground over it (the surface at y 64); the strip facts from the feet (strip at y 16 there). */
    static World atY16(Map<String, Integer> bag) {
        java.util.function.Function<String, NeedChain.StripBand> strip16 = word -> {
            NeedChain.StripBand b = Z77PlaybookTest.strip(word);
            return b == null ? null : new NeedChain.StripBand(b.ores(), b.minY(), b.maxY(), 16, b.perSlab(), 0, 0, 16, b.heading(), b.host(), b.headings(), b.freshDist());
        };
        return ScenariosTest.w(bag, false, false, Map.of()).withMobs(x -> List.of(), Z77PlaybookTest.book())
                .withPath(Z77PlaybookTest.PATHS).withStrip(strip16)
                .withWhere(new NeedChain.WhereFacts(Z77PlaybookTest::where, () -> new NeedChain.Surface(48, 0, 64, 0, 48), () -> new int[]{0, 16, 0},
                        () -> new io.github.mojolowjo.entropybot.aa.brain.GenWhere.Base(Z77PlaybookTest.STONE, Integer.MIN_VALUE, null, "test")));
    }

    static final List<Playbook.Target> THREE = List.of(new Playbook.Target("minecraft:iron_helmet", 1), new Playbook.Target("minecraft:iron_boots", 1),
            new Playbook.Target("minecraft:torch", 4));

    /**
     * Legs 2 (manager 2026-10-26; run BJ 10:39:15: a trip re-priced at y -59 said "surface: iron_chestplate ..."): a 3-order
     * trip (helmet, boots, torches) priced again from y 16 under 48 blocks of ground, the bag with sticks and coal (the
     * torches' leaves had here). No surface leg: "here at y 16 [torch 4]", the strip at the feet ("strip here y 16", no
     * walk down), the smelt and the armour at the band; the trip line says so.
     */
    @Test
    void legs2_aTripPricedFromY16_hasNoSurfaceLeg() {
        Z77PlaybookTest.data();
        World w = atY16(Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 64, "minecraft:coal", 9, "minecraft:furnace", 5, "minecraft:crafting_table", 5));
        Playbook pb = Playbook.derive(THREE, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        assertTrue(pb.legs().stream().noneMatch(l -> l.kind() == Playbook.Kind.SURFACE), "no surface leg from under ground: " + pb.why());
        assertEquals(List.of(Playbook.Kind.HERE, Playbook.Kind.DESCENT, Playbook.Kind.AT_BAND), pb.legs().stream().map(Playbook.Leg::kind).toList(), pb.why());
        assertTrue(pb.why().startsWith("playbook: here y 16 [torch 4] > strip here y 16 [raw_iron 9"), pb.why());
        assertTrue(pb.legs().get(1).here(), pb.why());
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        Chain pc = legs.chain();
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        assertTrue(pc.steps().stream().noneMatch(s -> s instanceof Step.ToStrip || s instanceof Step.ToSurface), "the strip at the feet, no walk: " + pc.steps());
        String line = BatchWay.shape("b1", 3, pb, pc, legs.starts()).line();
        assertTrue(line.contains("1 here at y 16: torch 4 | 2 strip here at y 16: raw_iron 9") && !line.contains("surface"), line);
        assertTrue(legs.starts().values().stream().anyMatch("playbook leg 1/3: here at y 16: torch 4"::equals), legs.starts().values().toString());
    }

    /**
     * Legs 2: the same trip with no wood in the bag and none known - the torches' sticks come from logs, which only grow on
     * the surface: ONE surface leg, the climb for them, after the band legs (the armour needs nothing from up there).
     */
    @Test
    void legs2_aSurfaceOnlyNeed_isTheOneClimbLeg() {
        Z77PlaybookTest.data();
        World w = atY16(Map.of("minecraft:stone_pickaxe", 1, "minecraft:coal", 9, "minecraft:furnace", 5, "minecraft:crafting_table", 5));
        Playbook pb = Playbook.derive(THREE, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        List<Playbook.Leg> up = pb.legs().stream().filter(l -> l.kind() == Playbook.Kind.SURFACE).toList();
        assertEquals(1, up.size(), pb.why());
        assertTrue(up.get(0).climb() && up.get(0).whyY().startsWith(Playbook.CLIMB_FOR), pb.why());
        assertEquals(List.of(new Playbook.Target("minecraft:torch", 4)), up.get(0).gets(), pb.why());
        assertSame(up.get(0), pb.legs().get(pb.legs().size() - 1), "after the band legs: " + pb.why());
        assertTrue(pb.legs().stream().noneMatch(l -> l.kind() == Playbook.Kind.HERE), pb.why());
    }

    static int index(Chain c, Step s) {
        for (int i = 0; i < c.steps().size(); i++) if (c.steps().get(i).equals(s)) return i;
        return -1;
    }

    static int first(Chain c, Class<?> k) {
        for (int i = 0; i < c.steps().size(); i++) if (k.isInstance(c.steps().get(i))) return i;
        return -1;
    }

    /**
     * The whole trip walked on a copy of the bag (DryRun's rule per step: its plain uses, its tag slots by their members, the
     * fuel the plan named), every step judged - also past the strips, which give their amounts (the fake loot: one raw iron or
     * one coal an ore; the DryRun in the game stops at a strip, its yield not known). Null: no step runs short; else the step.
     */
    static String dryWalk(Chain pc, Map<String, Integer> bag0) {
        Map<String, Integer> b = new LinkedHashMap<>(bag0);
        assertEquals(pc.steps().size(), pc.uses().size(), "uses parallel to the steps");
        for (int i = 0; i < pc.steps().size(); i++) {
            Step s = pc.steps().get(i);
            if (s instanceof Step.Strip st) {
                for (var e : st.amounts().entrySet()) b.merge(e.getKey().contains("iron") ? "minecraft:raw_iron" : "minecraft:coal", e.getValue(), Integer::sum);
                continue;
            }
            StepNeeds n = StepNeeds.of(s).withUses(pc.uses().get(i));
            DryRun.Shortage sh = DryRun.check(List.of(s), 0, List.of(n), b);
            if (sh != null) return "step " + (i + 1) + " (" + s.text() + "): " + sh.text() + " | steps " + pc.steps();
            for (var e : n.consumes().entrySet()) b.merge(e.getKey(), -e.getValue(), Integer::sum);
            for (var e : n.tagUses().entrySet()) {
                int left = e.getValue();
                for (var k : new ArrayList<>(b.keySet())) {
                    if (left <= 0) break;
                    if (b.get(k) <= 0 || !NeedChain.gives(e.getKey(), k)) continue;
                    int t = Math.min(left, b.get(k));
                    b.merge(k, -t, Integer::sum);
                    left -= t;
                }
            }
            if (n.fuelTicks() > 0) b.merge("minecraft:coal", -((n.fuelTicks() + 1599) / 1600), Integer::sum);    // the test's fuel: coal, 1600 ticks
            // the chop takes the whole tree whatever its number (S17; the planner's bag gets the tree's logs, the step's number
            // is a floor): the fake tree's 8 oak logs (a chop names no log item when the spot's block is the log)
            Map<String, Integer> made = s instanceof Step.ChopTree t && t.log() == null ? Map.of(TREE.block(), Math.max(t.logs(), TREE.count())) : DryRun.produces(s);
            if (made != null) for (var e : made.entrySet()) b.merge(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey(), e.getValue(), Integer::sum);
        }
        return null;
    }

    /**
     * Send-back 2 item 1, run BK: the derived 6-order trip on BK's shape. Every iron craft sits in its own leg, after the
     * smelt that makes its ingots: the iron pickaxe after the first smelt (leg 3), the four armour pieces after the second
     * (leg 5), both after the descent; up there before it only what the bag holds then (the torches, the table's wood). The
     * whole trip, walked on its own bag past the strips, runs short at no step. The leg starts come in leg order.
     */
    @Test
    void bk_theArmourCraftsComeAfterTheSmelt_theTripRunsShortNowhere() {
        Z77PlaybookTest.data();
        World w = bk();
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        Chain pc = legs.chain();
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        int down = first(pc, Step.ToStrip.class);
        assertTrue(down > 0, "one descent: " + pc.steps());
        List<Integer> smelts = new ArrayList<>();
        for (int i = 0; i < pc.steps().size(); i++) if (pc.steps().get(i) instanceof Step.Smelt) smelts.add(i);
        assertEquals(2, smelts.size(), pc.steps().toString());
        int pick = index(pc, new Step.Craft("iron_pickaxe", 1));
        assertTrue(pick > smelts.get(0) && pick < smelts.get(1), "the iron pickaxe in leg 3, after its smelt: " + pc.steps());
        for (String a : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots")) {
            int k = index(pc, new Step.Craft(a, 1));
            assertTrue(k > smelts.get(1), a + " in leg 5, after the armour's smelt: " + pc.steps());
        }
        for (int i = 0; i < down; i++) assertFalse(pc.steps().get(i) instanceof Step.Craft c && c.item().startsWith("iron_"),
                "no iron craft before the descent: " + pc.steps());
        assertTrue(pc.why().contains("before going down") || pc.dropped().stream().anyMatch(d -> d.contains("before going down")), pc.why());
        // up there the strip's raw iron is set aside: the helmet before going down has no iron (an explore, never picked over a price)
        assertTrue(pc.dropped().stream().anyMatch(d -> d.startsWith("iron_helmet before going down: no known source for raw_iron")),
                "the helmet up there is dropped with the reason: " + pc.dropped());
        assertNull(dryWalk(pc, w.bag()), "the trip's dry run: " + pc.why());
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < pc.steps().size(); i++) if (legs.starts().containsKey(pc.steps().get(i))) at.add(i);
        assertEquals(5, at.size(), legs.starts().values().toString());
        for (int i = 0; i < at.size(); i++) assertTrue(legs.starts().get(pc.steps().get(at.get(i))).startsWith("playbook leg " + (i + 1) + "/5: "), "the legs in order: " + legs.starts().values());
    }

    /**
     * Send-back 2 item 2 (runs BK 10:57:13 "collect oak_planks 3", BJ 10:39:15 "collect oak_planks 4 - unreachable" under
     * ground): the band legs' planks - the tables placed down there (one at leg 3, one at the strip on's tunnel end for leg
     * 5), the iron pickaxe's sticks - are made from logs cut up there and carried down: the trip line names the carry ("carries
     * ... oak_log N"), and after the descent no step gathers wood (no collect, no tree). By hand: two tables made down there
     * (4 planks each): 8 planks, 2 logs at 4 planks a log; the iron pickaxe's 2 sticks made up there (the tie keeps the
     * first way) and carried as sticks.
     */
    @Test
    void item2_theBandLegsPlanksAreCarriedDownAsLogs() {
        Z77PlaybookTest.data();
        World w = bk();
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        Chain pc = legs.chain();
        assertTrue(pc.ok() && !pc.explore(), pc.why());
        int down = first(pc, Step.ToStrip.class);
        for (int i = down; i < pc.steps().size(); i++) {
            Step s = pc.steps().get(i);
            assertFalse(s instanceof Step.ChopTree || s instanceof Step.Collect, "no wood gathered under ground: step " + (i + 1) + " " + s.text() + " in " + pc.steps());
        }
        assertEquals(2, pc.steps().subList(down, pc.steps().size()).stream().filter(s -> s instanceof Step.PlaceNear p && p.item().contains("crafting_table")).count(),
                "a table at leg 3 and one at the strip on's end: " + pc.steps());
        BatchWay.Shaped sh = BatchWay.shape("b1", 6, pb, pc, legs.starts());
        // owner 2026-10-29 (the carried table): the table taken after leg 3's craft is placed again at leg 5 - one log goes down (the sticks'), not two
        assertTrue(sh.line().contains(", carries ") && sh.line().contains("oak_log 1") && sh.line().contains("stick 2"), "the logs go down: " + sh.line());
        assertNull(dryWalk(pc, w.bag()), "the trip's dry run: " + pc.why());
    }
}
