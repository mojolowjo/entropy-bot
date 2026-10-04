package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.storage.StorageRules;
import io.github.mojolowjo.entropybot.storage.StorageRules.Held;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B7e F: {@code dig ... junk drop}: which items a full bag throws away, and which drops the clear still picks up. */
class JunkDropTest {
    static final String CD = "minecraft:cobbled_deepslate";

    /** A tunnel bag: junk, the kept cobblestone, ores, valuables, tools, food, torches. */
    static Held h(String id, int n) {
        return new Held(id, n, false);
    }

    static List<Held> bag() {
        return List.of(h(CD, 64), h(CD, 64), h(CD, 64), h(CD, 40),
                h("minecraft:cobblestone", 64), h("minecraft:cobblestone", 30),
                h("minecraft:tuff", 50), h("minecraft:dirt", 12), h("minecraft:gravel", 9), h("minecraft:diorite", 5),
                h("minecraft:raw_iron", 20), h("minecraft:coal", 30), h("minecraft:diamond", 3),
                h("minecraft:iron_ore", 2), h("minecraft:stone_pickaxe", 1), h("minecraft:iron_pickaxe", 1),
                new Held("minecraft:bread", 16, true), h("minecraft:torch", 40), h("minecraft:flint", 7),
                h("minecraft:clay_ball", 4));
    }

    static Map<String, Integer> counts(List<Held> held) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Held h : held) m.merge(h.id(), h.n(), Integer::sum);
        return m;
    }

    @Test
    void throwsOnlyPlainJunkBlocksAndKeepsWhatDepositKeeps() {
        List<Held> held = bag();
        Map<String, Integer> dep = StorageRules.depositables(held, "", false, null, StorageRules.Keeps.NONE);
        Map<String, Integer> t = JunkDrop.plan(dep, counts(held), null, 0);
        assertEquals(232, t.get(CD), "all of it without a floor");
        assertEquals(30, t.get("minecraft:cobblestone"), "64 cobblestone stay for pickaxes (deposit's rule)");
        assertEquals(50, t.get("minecraft:tuff"));
        assertEquals(12, t.get("minecraft:dirt"));
        assertEquals(9, t.get("minecraft:gravel"));
        assertEquals(5, t.get("minecraft:diorite"));
        for (String keep : List.of("minecraft:raw_iron", "minecraft:coal", "minecraft:diamond", "minecraft:iron_ore", "minecraft:stone_pickaxe",
                "minecraft:iron_pickaxe", "minecraft:bread", "minecraft:torch", "minecraft:flint", "minecraft:clay_ball")) {
            assertFalse(t.containsKey(keep), keep + " is never thrown");
        }
        assertEquals("threw away 232 cobbled_deepslate, 30 cobblestone, 50 tuff, 12 dirt, 9 gravel, 5 diorite", JunkDrop.summary(t));
    }

    @Test
    void aFloorDigKeepsSixtyFourOfItsBlock() {
        List<Held> held = bag();
        Map<String, Integer> dep = StorageRules.depositables(held, "", false, null, StorageRules.Keeps.NONE);
        Map<String, Integer> inv = counts(held);
        String block = FloorFill.chooseBlock(null, inv);
        assertEquals(CD, block);
        Map<String, Integer> t = JunkDrop.plan(dep, inv, block, JunkDrop.FLOOR_KEEP);
        assertEquals(232 - 64, t.get(CD));
        // a named cobblestone floor: 64 for the floor and 64 pickaxe material together stay (cobblestone kept first)
        Map<String, Integer> t2 = JunkDrop.plan(dep, inv, "cobblestone", JunkDrop.FLOOR_KEEP);
        assertNull(t2.get("minecraft:cobblestone"));
        assertEquals(232 - 34, t2.get(CD));
        // a supplies entry keeps more
        Map<String, Integer> dep3 = StorageRules.depositables(held, "", false, null, new StorageRules.Keeps(Map.of("minecraft:tuff", 48), Map.of()));
        assertEquals(2, JunkDrop.plan(dep3, inv, null, 0).get("minecraft:tuff"));
        assertTrue(JunkDrop.plan(Map.of(), inv, null, 0).isEmpty(), "nothing a deposit would take: nothing thrown");
    }

    @Test
    void theClearSkipsJunkDropsButFetchesItsFloorBlockWhileLow() {
        ClearJob plain = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 0, 0, 1, 1, 1)), null, null);
        ClearJob junk = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 0, 0, 1, 1, 1)).junkDrop(true), null, null);
        ClearJob floor = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 0, 0, 1, 1, 1)).junkDrop(true).floor(true, null), null, null);
        Map<String, Integer> few = Map.of(CD, 80), many = Map.of(CD, 200);
        assertTrue(JunkDrop.chase(CD, plain, few), "without junk drop every drop is picked up");
        assertFalse(JunkDrop.chase(CD, junk, few));
        assertTrue(JunkDrop.chase("minecraft:raw_iron", junk, few), "never valuables");
        assertTrue(JunkDrop.chase(CD, floor, few), "the floor's block while it has few");
        assertFalse(JunkDrop.chase(CD, floor, many));
        assertFalse(JunkDrop.chase("minecraft:tuff", floor, few), "not another junk block");
        assertTrue(JunkDrop.chase("minecraft:tuff", floor, Map.of()), "none at all yet: any block of the list");
        // review 6: never what it just threw (near the throw spot, for 2 minutes)
        JunkDrop.noteThrow(floor, 260.5, -46, 854.5, 1000);
        assertFalse(JunkDrop.chase(CD, floor, few, 258, -46, 854, 1100), "its own throw");
        assertTrue(JunkDrop.chase(CD, floor, few, 270, -46, 854, 1100), "farther than 6 blocks");
        assertTrue(JunkDrop.chase(CD, floor, few, 258, -46, 854, 1000 + JunkDrop.THROWN_TICKS + 1), "2 minutes later");
        assertTrue(JunkDrop.chase("minecraft:raw_iron", floor, few, 258, -46, 854, 1100), "never a valuable");
    }

    @Test
    void theLastSixtyFourStonePickaxeMaterialStay() {
        // only cobbled deepslate and nothing kept by deposit: 64 of it stay for stone pickaxes
        List<Held> held = List.of(h(CD, 64), h(CD, 36));
        Map<String, Integer> dep = StorageRules.depositables(held, "", false, null, StorageRules.Keeps.NONE);
        assertEquals(36, JunkDrop.plan(dep, counts(held), null, 0).get(CD));
        // blackstone counts toward the 64 (and is never thrown)
        List<Held> held2 = List.of(h(CD, 100), h("minecraft:blackstone", 40));
        Map<String, Integer> dep2 = StorageRules.depositables(held2, "", false, null, StorageRules.Keeps.NONE);
        assertEquals(76, JunkDrop.plan(dep2, counts(held2), null, 0).get(CD));
        assertNull(JunkDrop.plan(dep2, counts(held2), null, 0).get("minecraft:blackstone"));
    }

    @Test
    void throwsTowardWhereTheClearBegan() {
        ClearBox box = ClearBox.of(247, -46, 853, 310, -44, 855);
        double[] start = {243.5, 854.5};
        assertEquals(90f, JunkDrop.throwYaw(start, 260.5, 854.5, box), 0.01f, "back along the tunnel (-x)");
        // at the start: away from the box's middle (also -x here)
        assertEquals(90f, JunkDrop.throwYaw(start, 244.0, 854.5, box), 0.01f);
        assertEquals(-90f, JunkDrop.throwYaw(new double[]{320, 854.5}, 300.5, 854.5, box), 0.01f, "+x");
    }
}
