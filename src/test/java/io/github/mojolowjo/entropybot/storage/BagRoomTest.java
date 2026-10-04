package io.github.mojolowjo.entropybot.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B7e (E1): the mod's own bag room ("rule when full") against the bridge's bagRoom, on one inventory. */
class BagRoomTest {
    static StorageRules.Held h(String id, int n, int slot) { return new StorageRules.Held(id, n, false, slot); }

    /** 36 slots: 9 filled, 27 empty. */
    static List<StorageRules.Held> bag() {
        StorageRules.Held[] s = new StorageRules.Held[36];
        s[0] = h("minecraft:stone_pickaxe", 1, 0);              // a tool: kept
        s[1] = h("minecraft:cobblestone", 64, 1);               // the 64 kept for pickaxes
        s[2] = h("minecraft:dirt", 50, 2);                      // junk: room
        s[3] = h("minecraft:raw_iron", 30, 3);                  // a valuable: kept for the base trip
        s[4] = new StorageRules.Held("minecraft:bread", 20, true, 4);   // food: kept
        s[5] = h("minecraft:coal", 10, 5);                      // coal is a valuable too
        s[6] = h("minecraft:torch", 40, 6);                     // kept
        s[7] = h("minecraft:gravel", 64, 7);                    // junk: room
        s[20] = h("minecraft:cobblestone", 64, 20);             // cobblestone beyond the 64: room
        return Arrays.asList(s);
    }

    /** The bridge's bagRoom (claude_bridge.js), transcribed: free slots, plus junk slots beyond each junk item's keep amount. */
    static int bridgeBagRoom(List<StorageRules.Held> slots, StorageRules.Keeps keeps) {
        List<StorageRules.Held> held = new ArrayList<>();
        for (StorageRules.Held x : slots) if (x != null) held.add(x);
        Map<String, Integer> junk = StorageRules.depositables(held, "", true, null, keeps), kept = new HashMap<>();
        int n = 0;
        for (StorageRules.Held s : slots) {
            if (s == null) { n++; continue; }
            if (!junk.containsKey(s.id())) continue;
            if (junk.get(s.id()) > 0 && kept.getOrDefault(s.id(), 0) < junk.get(s.id())) {
                kept.merge(s.id(), s.n(), Integer::sum);
                continue;
            }
            n++;
        }
        return n;
    }

    @Test
    void freeSlotsPlusJunk() {
        assertEquals(27 + 3, BagRoom.count(bag(), StorageRules.Keeps.NONE), "27 free, the dirt, the gravel, the second cobblestone stack");
        assertEquals(bridgeBagRoom(bag(), StorageRules.Keeps.NONE), BagRoom.count(bag(), StorageRules.Keeps.NONE), "the same count as the bridge");
    }

    @Test
    void suppliesAreNotRoom() {
        StorageRules.Keeps k = new StorageRules.Keeps(Map.of("minecraft:cobblestone", 100), Map.of());
        assertEquals(27 + 2, BagRoom.count(bag(), k), "100 cobblestone kept: both stacks stay");
        assertEquals(bridgeBagRoom(bag(), k), BagRoom.count(bag(), k));
    }

    @Test
    void exactlyTheKeepAmountIsNoRoom() {
        List<StorageRules.Held> s = new ArrayList<>(bag());
        s.set(20, null);
        assertEquals(28 + 2, BagRoom.count(s, null), "one stack of 64 cobblestone: kept, the freed slot is room");
    }

    @Test
    void fullBag() {
        List<StorageRules.Held> s = new ArrayList<>();
        for (int i = 0; i < 36; i++) s.add(h("minecraft:raw_copper", 64, i));
        assertEquals(0, BagRoom.count(s, StorageRules.Keeps.NONE), "all valuables: no room ('rule when full' fires at 4 or less)");
        s.set(10, h("minecraft:cobbled_deepslate", 64, 10));
        assertEquals(1, BagRoom.count(s, StorageRules.Keeps.NONE));
    }
}
