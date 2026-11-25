package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-review-leftovers (item 4), run 3 w064 replayed for an order with no trip: cobblestone 32 -d, 20 in the bag. The
 * runner (keptFromUse) holds the 20 from every craft; the order's own chain sets the same 20 aside and asks only 12.
 */
class OwnAsideTest {
    @Test
    void anOrdersOwnHeldAmountIsSetAsideAndOnlyTheRestIsAsked() {
        Map<String, Integer> bag = new LinkedHashMap<>(Map.of("minecraft:cobblestone", 20, "minecraft:oak_planks", 4));
        Map<String, Integer> held = new HashMap<>(Map.of("cobblestone", 20));
        int n = Goals.ownAside(bag, held, List.of("minecraft:cobblestone"), "cobblestone");
        assertEquals(20, n);
        assertEquals(0, bag.get("minecraft:cobblestone"), "no inner get (a furnace) uses the held 20");
        assertEquals(4, bag.get("minecraft:oak_planks"), "the rest of the bag as it is");
        assertEquals(12, 32 - n, "the order asks only the rest");
    }

    @Test
    void aGroupOrderSetsEachMembersHeldPartAside() {
        Map<String, Integer> bag = new LinkedHashMap<>(Map.of("minecraft:oak_log", 6, "minecraft:birch_log", 3));
        Map<String, Integer> held = new HashMap<>(Map.of("oak_log", 5, "birch_log", 3));
        int n = Goals.ownAside(bag, held, List.of("minecraft:oak_log", "minecraft:birch_log"), "logs");
        assertEquals(8, n);
        assertEquals(1, bag.get("minecraft:oak_log"));
        assertEquals(0, bag.get("minecraft:birch_log"));
    }

    @Test
    void aGroupOrdersAskDropsByTheFewestHeldOfAnyMember() {
        // reviewer's case: group n=4, 1 of each held; the summed aside asked 2 of each where each member needs 3
        Map<String, Integer> bag = new LinkedHashMap<>(Map.of("minecraft:oak_log", 1, "minecraft:birch_log", 1));
        Map<String, Integer> before = new LinkedHashMap<>(bag);
        Map<String, Integer> held = new HashMap<>(Map.of("oak_log", 1, "birch_log", 1));
        List<String> ids = List.of("minecraft:oak_log", "minecraft:birch_log");
        assertEquals(2, Goals.ownAside(bag, held, ids, "logs"));
        Map<String, Integer> taken = new HashMap<>();
        for (var e : before.entrySet()) taken.put(e.getKey(), e.getValue() - bag.get(e.getKey()));
        assertEquals(3, 4 - Goals.askAside(taken, ids, "logs", true), "each member asks its own rest");
        assertEquals(2, Goals.askAside(Map.of("minecraft:oak_log", 2, "minecraft:birch_log", 5), ids, "logs", true));
        assertEquals(0, Goals.askAside(Map.of("minecraft:oak_log", 2), ids, "logs", true), "a member with none held");
        assertEquals(20, Goals.askAside(Map.of("minecraft:cobblestone", 20), List.of("minecraft:cobblestone"), "cobblestone", false));
    }
}
