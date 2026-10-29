package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 noway (run BU 13:34:23, owner's seed: "order iron_pickaxe stopped: no way to get it: playbook: at band: iron_helmet
 * ends in an explore (no known source for #logs)" - the HELMET's band leg ended the PICKAXE's order; the pickaxe was never
 * made). A trip leg's no-way ends only the orders whose own items need that leg; the other orders of the batch derive their
 * trip again without them and run on. Fakes: Z77PlaybookTest's (the kit, the iron band at y 16).
 */
class Z77NoWayTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /** The kit's bag with ONE table: the pickaxe's band leg places it; the armour's band leg (after the strip on) has none. */
    /**
     * One table, one furnace. Owner 2026-10-29 (the carried table): the one table is taken after leg 3's craft and placed again
     * at leg 5, so the table is no longer what leg 5 lacks - the one FURNACE is: placed at leg 3, leg 5's smelts need their own,
     * and in a tuff band (Z77PlaybookTest.TUFF) no furnace material is to be had down there.
     */
    static Map<String, Integer> oneTable() {
        return Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 64, "minecraft:crafting_table", 1, "minecraft:furnace", 1, "minecraft:coal", 9);
    }

    /** Each leg serves the orders whose own items need it: the torches the surface, the pickaxe legs 2-3, the armour legs 4-5. */
    @Test
    void eachLegServesTheOrdersWhoseItemsNeedIt() {
        Z77PlaybookTest.data();
        World w = Z77PlaybookTest.world(Z77PlaybookTest.kitBag(9), Map.of());
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        List<Set<Integer>> serves = pb.legs().stream().map(Playbook.Leg::serves).toList();
        assertEquals(List.of(Set.of(0), Set.of(1), Set.of(1), Set.of(2, 3, 4, 5), Set.of(2, 3, 4, 5)), serves, pb.why());
        // fresh world: the surface leg makes the stone pickaxe, the gate every band order digs with
        Playbook fresh = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(Z77PlaybookTest.world(Map.of(), Map.of()), null));
        assertEquals(Set.of(0, 1, 2, 3, 4, 5), fresh.legs().get(0).serves(), fresh.why());
    }

    /**
     * Run BU's shape: the armour's band leg (5/5) has no way. The pickaxe's order runs its trip without the armour (surface,
     * descent, at the band: iron_pickaxe); the four armour orders end with the leg's reason; the torches run on with it.
     */
    @Test
    void aBandLegsNoWayEndsOnlyTheOrdersThatNeedIt() {
        Z77PlaybookTest.data();
        World w = Z77PlaybookTest.world(oneTable(), Map.of(), Z77PlaybookTest.TUFF);
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        assertFalse(legs.chain().ok(), legs.chain().why());
        assertEquals(4, legs.failedLeg(), "the armour's band leg: " + legs.chain().why());
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(port, clock, () -> w, gs);
        Goals.Goal pick = gs.get(1);
        Chain ch = Goals.chain(bb, pick);
        assertTrue(ch.ok(), ch.why());
        assertTrue(ch.why().startsWith(NeedChain.PLAYBOOK), ch.why());
        assertTrue(ch.steps().stream().anyMatch(s -> s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Craft c && c.item().contains("iron_pickaxe")), ch.steps().toString());
        assertNull(pick.parked);
        assertNull(gs.get(0).parked, "the torches need no armour leg");
        for (Goals.Goal a : gs.subList(2, 6)) {
            assertNotNull(a.parked, a.r.key() + " ends");
            assertTrue(a.parked.startsWith(BatchWay.NO_WAY + legs.chain().why()), a.parked);
        }
        assertTrue(port.log.contains("trip b1: leg 5/5 (" + pb.legs().get(4).text() + ") has no way: it ends the orders iron_helmet, iron_chestplate, iron_leggings,"
                + " iron_boots (their items need it); the other orders' trip is derived again without them"), port.log.toString());
        assertTrue(port.log.stream().anyMatch(l -> l.startsWith("iron_pickaxe: trip b1 (2 orders): 3 legs - 1 surface: torch 4 | 2 descent to y 16")), port.log.toString());
        assertTrue(port.told.stream().noneMatch(l -> l.startsWith("order iron_pickaxe stopped")), port.told.toString());
        assertTrue(port.told.stream().anyMatch(l -> l.startsWith("order iron_helmet stopped: " + BatchWay.NO_WAY + "playbook: at band: ")), port.told.toString());
    }

    /** An order that needs the failed leg asks first: its chain is the no-way (it ends as before); the others needing it end too, the rest not. */
    @Test
    void anOrderThatNeedsTheLegGetsItsNoWay_theRestRunOn() {
        Z77PlaybookTest.data();
        World w = Z77PlaybookTest.world(oneTable(), Map.of(), Z77PlaybookTest.TUFF);
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(new FakePort(), new Clock.Fake(10_000_000), () -> w, gs);
        Goals.Goal helmet = gs.get(2);
        Chain ch = Goals.chain(bb, helmet);
        assertFalse(ch.ok(), "the helmet's own no-way: " + ch.why());
        assertNull(helmet.parked, "parked by its own job, as an order's no-way always was");
        for (Goals.Goal a : gs.subList(3, 6)) assertNotNull(a.parked, a.r.key());
        assertNull(gs.get(0).parked);
        assertNull(gs.get(1).parked);
        helmet.parked = "test: its job parked it";
        assertTrue(Goals.chain(bb, gs.get(1)).ok(), "the pickaxe's trip without the armour stands");
    }

    /** A leg every open order needs: the trip's no-way is every order's, as before (nobody else is parked). */
    @Test
    void aLegEveryOrderNeedsEndsThemAsBefore() {
        Z77PlaybookTest.data();
        World w = Z77PlaybookTest.world(Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 64, "minecraft:furnace", 5, "minecraft:coal", 9), Map.of());
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(new FakePort(), new Clock.Fake(10_000_000), () -> w, gs);
        gs.get(0).parked = "test";
        gs.get(1).parked = "test";                 // only the armour open: the failed leg serves them all
        Chain ch = Goals.chain(bb, gs.get(2));
        assertFalse(ch.ok(), ch.why());
        for (Goals.Goal a : gs.subList(3, 6)) assertNull(a.parked, a.r.key() + ": each ends by its own job");
    }
}
