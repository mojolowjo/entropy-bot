package io.github.mojolowjo.entropybot.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class StorageRulesTest {
    static StorageRules.Held h(String id, int n) { return new StorageRules.Held(id, n, false); }

    @Test
    void depositKeepsToolsFoodAndTheKeepAmounts() {
        List<StorageRules.Held> inv = List.of(h("minecraft:iron_pickaxe", 1), h("minecraft:cobblestone", 64), h("minecraft:cobblestone", 36),
                new StorageRules.Held("minecraft:bread", 12, true), h("minecraft:raw_iron", 9), h("minecraft:coal", 10), h("minecraft:dirt", 30));
        Map<String, Integer> d = StorageRules.depositables(inv, "", false, null);
        assertEquals(new LinkedHashMap<>(Map.of("minecraft:cobblestone", 64, "minecraft:raw_iron", 0, "minecraft:dirt", 0)), new LinkedHashMap<>(d));
        assertEquals(List.of("minecraft:cobblestone", "minecraft:raw_iron", "minecraft:dirt"), List.copyOf(d.keySet()), "inventory order");
        // named items go all of them, kept kinds included
        assertEquals(Map.of("minecraft:coal", 0), StorageRules.depositables(inv, "coal", false, null));
        // only the valuables (the strip mine's trip to base)
        assertEquals(Map.of("minecraft:raw_iron", 0), StorageRules.depositables(inv, "", false, StorageRules.VALUABLE));
    }

    @Test
    void depositNamesWorkAcrossModNamespacesAndAsGroups() {
        List<StorageRules.Held> inv = List.of(h("leafscopperbackport:copper_pickaxe", 1), h("leafscopperbackport:copper_axe", 1),
                h("leafscopperbackport:copper_armor_chestplate", 1), h("minecraft:stone_pickaxe", 3), h("minecraft:torch", 39), h("minecraft:dirt", 5));
        assertEquals(Map.of("leafscopperbackport:copper_pickaxe", 0), StorageRules.depositables(inv, "copper_pickaxe", false, null));
        assertEquals(Map.of("leafscopperbackport:copper_pickaxe", 0, "leafscopperbackport:copper_axe", 0), StorageRules.depositables(inv, "copper tools", false, null));
        assertEquals(Map.of("leafscopperbackport:copper_armor_chestplate", 0), StorageRules.depositables(inv, "copper armor", false, null));
        assertEquals(Map.of("leafscopperbackport:copper_pickaxe", 0, "minecraft:dirt", 0), StorageRules.depositables(inv, "copper_pickaxe and dirt".replace(" and ", ", "), false, null));
        assertEquals(Map.of("minecraft:torch", 0), StorageRules.depositables(inv, "torches", false, null));
        assertEquals(3, StorageRules.depositables(inv, "tools", false, null).size(), "all tools: both copper ones and the stone pickaxes");
        assertTrue(StorageRules.depositables(inv, "gold tools", false, null).isEmpty());
    }

    @Test
    void depositPlanUsesTheChestWithTheMostAndTheJunkChestForTheRest() {
        StorageRules.Chest ores = new StorageRules.Chest("1 0 0", new int[]{1, 0, 0}, Map.of("minecraft:raw_iron", 40));
        StorageRules.Chest junk = new StorageRules.Chest("5 0 0", new int[]{5, 0, 0}, Map.of("minecraft:cobblestone", 900));
        Map<String, Integer> items = new LinkedHashMap<>();
        items.put("minecraft:raw_iron", 0);
        items.put("minecraft:dirt", 0);
        StorageRules.Plan p = StorageRules.depositPlan(items, List.of(junk, ores), new int[]{0, 0, 0}, "");
        assertNull(p.err());
        assertEquals("putting away raw_iron, dirt (2 chests)", p.label());
        assertEquals("1 0 0", p.stops().get(0).chest().key(), "nearest first");
        assertArrayEquals(new int[]{5, 0, 0}, p.stops().get(0).fallback(), "a full chest spills into the junk chest");
        assertNull(p.stops().get(1).fallback(), "the junk chest has no fallback");
        assertEquals("error: I don't know any chests near the base - next: scan base", StorageRules.depositPlan(items, List.of(), new int[3], "").err());
        assertEquals("error: nothing to deposit matching gold", StorageRules.depositPlan(Map.of(), List.of(junk), new int[3], "gold").err());
    }

    @Test
    void spotsAndScanArguments() {
        Map<String, JsonObject> places = new TreeMap<>();
        places.put("bulk", JsonParser.parseString("{\"x\":1,\"y\":2,\"z\":3,\"dim\":\"minecraft:overworld\"}").getAsJsonObject());
        places.put("hell", JsonParser.parseString("{\"x\":1,\"y\":2,\"z\":3,\"dim\":\"minecraft:the_nether\"}").getAsJsonObject());
        assertEquals("1 2 3", StorageRules.resolveSpot("bulk", places, "minecraft:overworld").fmt());
        assertEquals("bulk", StorageRules.resolveSpot("bulk", places, "minecraft:overworld").label());
        assertEquals("-4 60 7", StorageRules.resolveSpot("-4 60 7", places, "minecraft:overworld").label());
        assertEquals("error: I have no place called nope - next: places", StorageRules.resolveSpot("nope", places, "minecraft:overworld").err());
        assertEquals("error: hell is in minecraft:the_nether", StorageRules.resolveSpot("hell", places, "minecraft:overworld").err());
        assertEquals("usage: open x y z | open <place name>", StorageRules.resolveSpot("1 2", places, "minecraft:overworld").err());
        assertArrayEquals(new String[]{"", "12"}, StorageRules.scanArgs("12"));
        assertArrayEquals(new String[]{"base", "16"}, StorageRules.scanArgs("base"));
        assertArrayEquals(new String[]{"-26 53 187", "8"}, StorageRules.scanArgs("-26 53 187 8"));
        assertArrayEquals(new String[]{"", "24"}, StorageRules.scanArgs("99"));
    }

    @Test
    void whereListsTheBotTheNetworkAndTheChests() {
        long now = 1_000_000;
        Map<String, JsonObject> rs = Map.of("-24 53 181", JsonParser.parseString("{\"dim\":\"o\",\"items\":{\"minecraft:iron_ingot\":300},\"seen\":" + (now - 120_000) + "}").getAsJsonObject());
        Map<String, JsonObject> chests = Map.of("1 2 3", JsonParser.parseString("{\"dim\":\"o\",\"items\":{\"minecraft:iron_ingot\":5,\"minecraft:dirt\":1},\"seen\":" + (now - 10_000) + "}").getAsJsonObject());
        Map<String, JsonObject> places = Map.of("bulk", JsonParser.parseString("{\"x\":1,\"y\":2,\"z\":3,\"dim\":\"o\"}").getAsJsonObject());
        assertEquals("I carry 3 iron_ingot | 300 iron_ingot in the RS network at -24 53 181 (2m ago) | 5 iron_ingot in chest 1 2 3 (bulk, 10s ago)",
                StorageRules.where("iron", Map.of("minecraft:iron_ingot", 3), rs, chests, places, now));
        assertEquals("no gold in my inventory or any chest I've looked in", StorageRules.where("gold", Map.of(), rs, chests, places, now));
        assertEquals("usage: where <item>", StorageRules.where("", Map.of(), rs, chests, places, now));
    }

    @Test
    void rsSummaryAndStorageIds() {
        assertEquals("the RS network at 1 2 3 holds 12 items of 2 kinds: 10 dirt, 2 stone",
                StorageRules.rsSummary("1 2 3", new TreeMap<>(Map.of("minecraft:dirt", 10, "minecraft:stone", 2))));
        assertTrue(StorageRules.isStorageId("block.minecraft.barrel"));
        assertTrue(StorageRules.isStorageId("block.sophisticatedstorage.chest"));
        assertFalse(StorageRules.isStorageId("block.refinedstorage.grid"));
        assertFalse(StorageRules.isStorageId("block.minecraft.furnace"));
    }

    @Test
    void diskDriveReport() {
        // a disk's tooltip: its name first, then RS's usage lines (some with formatting codes), a hint without numbers
        assertEquals("Stored: 120/1,000, 12% full", StorageRules.diskInfo(List.of("1k Storage Disk", "§7Stored: 120/1,000", "Hold SHIFT for more", "12% full", "Id 3")));
        assertEquals("", StorageRules.diskInfo(List.of("1k Storage Disk")));
        assertEquals("", StorageRules.diskInfo(List.of()));
        java.util.ArrayList<String[]> disks = new java.util.ArrayList<>();
        disks.add(new String[]{"refinedstorage:1k_storage_disk", "Stored: 120/1,000"});
        disks.add(null);
        disks.add(new String[]{"refinedstorage:4k_storage_disk", ""});
        for (int i = 0; i < 5; i++) disks.add(null);
        assertEquals("the disk drive at -23 53 170 holds 1k_storage_disk (Stored: 120/1,000), 4k_storage_disk; 6 of 8 slots free",
                StorageRules.diskReport("-23 53 170", disks));
        java.util.ArrayList<String[]> none = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) none.add(null);
        assertEquals("the disk drive at 1 2 3 holds no disks (8 slots free)", StorageRules.diskReport("1 2 3", none));
    }
}
