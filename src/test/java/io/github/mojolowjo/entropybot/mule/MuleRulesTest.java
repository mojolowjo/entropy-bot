package io.github.mojolowjo.entropybot.mule;

import io.github.mojolowjo.entropybot.storage.StorageRules;
import io.github.mojolowjo.entropybot.storage.StorageRules.Held;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MuleRulesTest {
    private static final StorageRules.Keeps NONE = StorageRules.Keeps.NONE;

    @Test
    void parsing() {
        assertEquals(MuleRules.Kind.HOLD, MuleRules.parse("hold", "this", "Owner").kind());
        assertEquals(MuleRules.Kind.ERROR, MuleRules.parse("hold", "", "Owner").kind());
        MuleRules.Args g = MuleRules.parse("give", "me cobblestone 32", "Owner");
        assertEquals(MuleRules.Kind.GIVE, g.kind());
        assertEquals("Owner", g.player());
        assertEquals("cobblestone", g.item());
        assertEquals(32, g.n());
        MuleRules.Args g2 = MuleRules.parse("give", "Steve bread", "Owner");
        assertEquals("Steve", g2.player());
        assertEquals(0, g2.n());
        assertEquals(MuleRules.Kind.ERROR, MuleRules.parse("give", "me", "Owner").kind());
        assertEquals(MuleRules.Kind.ERROR, MuleRules.parse("give", "me dirt -3", "Owner").kind());
        assertEquals(MuleRules.Kind.ERROR, MuleRules.parse("give", "me dirt 99999", "Owner").kind());
        assertEquals(List.of("oak_log", "cobblestone"), MuleRules.parse("carry", "oak_log, cobblestone oak_log", "O").items());
        assertEquals(MuleRules.Kind.CARRY_OFF, MuleRules.parse("carry", "off", "O").kind());
        assertEquals(MuleRules.Kind.CARRY_LIST, MuleRules.parse("carry", "", "O").kind());
        assertEquals(MuleRules.Kind.CARRY_LIST, MuleRules.parse("carry", "list", "O").kind());
        assertEquals(MuleRules.Kind.UNLOAD, MuleRules.parse("unload", "", "O").kind());
        assertEquals(MuleRules.Kind.ERROR, MuleRules.parse("unload", "now", "O").kind());
        MuleRules.Args f = MuleRules.parse("fetch", "torch 32", "O");
        assertEquals(MuleRules.Kind.FETCH, f.kind());
        assertEquals(32, f.n());
        assertEquals(1, MuleRules.parse("fetch", "torch", "O").n());
        assertEquals(MuleRules.Kind.ERROR, MuleRules.parse("fetch", "", "O").kind());
    }

    @Test
    void giveKeepRules() {
        List<Held> inv = List.of(new Held("minecraft:iron_pickaxe", 1, false, 0), new Held("minecraft:stone_pickaxe", 1, false, 1),
                new Held("minecraft:bread", 10, true, 2), new Held("minecraft:torch", 20, false, 3), new Held("minecraft:cobblestone", 100, false, 4),
                new Held("minecraft:iron_chestplate", 1, false, 5), new Held("minecraft:coal", 30, false, 6));
        assertEquals(0, MuleRules.giveable(inv, "minecraft:iron_pickaxe", NONE).n());
        assertEquals(0, MuleRules.giveable(inv, "minecraft:iron_chestplate", NONE).n());
        assertEquals(1, MuleRules.giveable(inv, "minecraft:stone_pickaxe", NONE).n() == 0 ? 1 : 0, "tools never go");
        assertEquals(2, MuleRules.giveable(inv, "minecraft:bread", NONE).n());          // 10 food, keep 8
        assertEquals(4, MuleRules.giveable(inv, "minecraft:torch", NONE).n());          // keep 16
        assertEquals(100, MuleRules.giveable(inv, "minecraft:cobblestone", NONE).n());  // deposit keeps 64, give doesn't
        StorageRules.Keeps sup = new StorageRules.Keeps(Map.of("minecraft:cobblestone", 40, "minecraft:torch", 18), Map.of(3, "torch"));
        assertEquals(60, MuleRules.giveable(inv, "minecraft:cobblestone", sup).n());
        assertEquals(2, MuleRules.giveable(inv, "minecraft:torch", sup).n());
        assertTrue(MuleRules.giveable(inv, "minecraft:torch", sup).why().contains("supplies"));
        assertEquals(0, MuleRules.giveable(inv, "minecraft:diamond", NONE).n());
        // other food counts towards the 8 kept
        List<Held> two = List.of(new Held("minecraft:bread", 5, true, 0), new Held("minecraft:apple", 6, true, 1));
        assertEquals(3, MuleRules.giveable(two, "minecraft:apple", NONE).n());
    }

    @Test
    void decideCutsToWhatItMayGive() {
        MuleRules.Giveable g = new MuleRules.Giveable(20, 36, "I keep 16 torches");
        assertEquals(20, MuleRules.decide(0, g, "minecraft:torch").n());
        assertEquals(5, MuleRules.decide(5, g, "minecraft:torch").n());
        MuleRules.GiveDecision cut = MuleRules.decide(32, g, "minecraft:torch");
        assertEquals(20, cut.n());
        assertTrue(cut.note().startsWith("only 20 of 32 torch"));
        assertTrue(MuleRules.decide(1, new MuleRules.Giveable(0, 1, "my tools, weapons and armor stay with me"), "minecraft:iron_sword").note().startsWith("error:"));
        assertEquals("ok: gave you 32 cobblestone", MuleRules.gaveText("Owner", "owner", 32, "minecraft:cobblestone", null));
        assertEquals("ok: gave Steve 4 bread", MuleRules.gaveText("Steve", "Owner", 4, "minecraft:bread", null));
    }

    @Test
    void throwPlanIsAStackAtATime() {
        assertEquals(List.of(64, 64, 2), MuleRules.throwPlan(130, 64));
        assertEquals(List.of(16, 4), MuleRules.throwPlan(20, 16));
        assertEquals(List.of(1, 1), MuleRules.throwPlan(2, 1));
        assertEquals(List.of(), MuleRules.throwPlan(0, 64));
        assertEquals(List.of(64), MuleRules.throwPlan(64, 99));
    }

    @Test
    void holdFilter() {
        Set<Integer> before = Set.of(1, 2);
        assertFalse(MuleRules.holdTakes(new MuleRules.Seen(1, "minecraft:dirt", null, 2), "Owner", before), "was there before");
        assertTrue(MuleRules.holdTakes(new MuleRules.Seen(3, "minecraft:dirt", null, 2), "Owner", before));
        assertFalse(MuleRules.holdTakes(new MuleRules.Seen(3, "minecraft:dirt", null, 4.5), "Owner", before), "too far");
        assertTrue(MuleRules.holdTakes(new MuleRules.Seen(1, "minecraft:dirt", "owner", 2), "Owner", before), "thrown by the sender");
        assertFalse(MuleRules.holdTakes(new MuleRules.Seen(3, "minecraft:dirt", "Steve", 2), "Owner", before), "thrown by someone else");
        assertEquals("ok: holding 33 items: 32 cobblestone, 1 bread",
                MuleRules.holdText(Map.of("minecraft:dirt", 5), new java.util.LinkedHashMap<>(Map.of("minecraft:dirt", 5, "minecraft:cobblestone", 32)) {{ put("minecraft:bread", 1); }}));
        assertTrue(MuleRules.holdText(Map.of(), Map.of()).startsWith("ok: nothing came"));
    }

    @Test
    void carryFilter() {
        List<String> w = List.of("oak_log", "cobblestone");
        assertTrue(MuleRules.carryTakes(new MuleRules.Seen(1, "minecraft:oak_log", null, 5), w));
        assertTrue(MuleRules.carryTakes(new MuleRules.Seen(1, "minecraft:cobblestone", null, 0.5), w));
        assertFalse(MuleRules.carryTakes(new MuleRules.Seen(1, "minecraft:oak_log", null, 6.5), w), "beyond 6 of the owner");
        assertFalse(MuleRules.carryTakes(new MuleRules.Seen(1, "minecraft:dirt", null, 2), w));
        assertTrue(MuleRules.carryTakes(new MuleRules.Seen(1, "minecraft:oak_log", null, 2), List.of("oak_logs")), "plural names");
    }

    @Test
    void fetchPlanStorageFirstThenGather() {
        assertEquals(List.of(), MuleRules.fetchPlan("minecraft:torch", 32, 40, 0));
        assertEquals(List.of("get minecraft:torch 32"), MuleRules.fetchPlan("minecraft:torch", 32, 0, 100));
        assertEquals(List.of("get minecraft:torch 22"), MuleRules.fetchPlan("minecraft:torch", 32, 10, 100));
        assertEquals(List.of("get minecraft:torch 16", "gather minecraft:torch 32"), MuleRules.fetchPlan("minecraft:torch", 32, 0, 16));
        assertEquals(List.of("gather minecraft:torch 32"), MuleRules.fetchPlan("minecraft:torch", 32, 0, 0));
        assertEquals("fetched 32 torch: took 16 from storage, gathered 16", MuleRules.fetchedText(32, "minecraft:torch", 16, 16, 0));
    }
}
