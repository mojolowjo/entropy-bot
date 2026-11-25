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
    /**
     * job-own-aside-worker (worlds run 4, w060: "craft oak_planks 4 - missing 1 oak_log" with oak_log 2 kept, repaired with
     * the same craft twice; w065 the same with 2): a repair's gap prices without the held -d amounts (Goals.heldOff over
     * ownKeptHeld), so it never crafts planks from the logs the runner's craft bag refuses.
     */
    @Test
    void aRepairGapNeverCraftsFromAHeldAmount() {
        try {
            TestData.planner().install();
            var w = new io.github.mojolowjo.entropybot.aa.NeedChain.World(new LinkedHashMap<>(Map.of("minecraft:oak_log", 2, "minecraft:dirt", 3)), false, x -> null);
            var plain = io.github.mojolowjo.entropybot.aa.NeedChain.resolve("minecraft:oak_planks", 4, w);
            assertTrue(plain.ok() && !plain.explore(), "with the logs free the craft stands from the bag: " + plain.why());
            var off = Goals.heldOff(w, Map.of("oak_log", 2));
            assertEquals(0, off.bag().get("minecraft:oak_log"), "the 2 held logs are off the gap's world");
            assertEquals(3, off.bag().get("minecraft:dirt"), "the rest of the bag as it is");
            var gap = io.github.mojolowjo.entropybot.aa.NeedChain.resolve("minecraft:oak_planks", 4, off);
            assertTrue(!gap.ok() || gap.explore(), "no craft from the held logs (no tree known: an explore or no way): " + gap.why() + " " + gap.steps());
            assertSame(w, Goals.heldOff(w, Map.of()), "nothing held: the world as it is");
        } finally {
            TestData.clear();
        }
    }

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

    @Test
    void aGroupMemberWithNoneHeldStillKeepsTheOthersAsideOffTheWorld() {
        // review sent back again (item 1): oak 3 held of its own -d amount, birch none; the aside was cleared and the chain
        // priced on the full bag, so an inner get could use the oak the runner's keptFromUse holds
        Map<String, Integer> bag = Map.of("minecraft:oak_log", 3, "minecraft:dirt", 5);
        List<String> ids = List.of("minecraft:oak_log", "minecraft:birch_log");
        var ask = Goals.ownAsk(bag, Map.of("oak_log", 3), ids, "logs", true, 4);
        assertEquals(4, ask.n(), "the fewest held of any member is 0, so the ask stays whole");
        var w = new io.github.mojolowjo.entropybot.aa.NeedChain.World(bag, false, x -> null);
        var on = ask.pricedOn(w);
        assertEquals(0, on.bag().get("minecraft:oak_log"), "the held oak is off the world the chain prices on");
        assertEquals(5, on.bag().get("minecraft:dirt"));
        var plain = Goals.ownAsk(Map.of("minecraft:cobblestone", 20), Map.of("cobblestone", 8), List.of("minecraft:cobblestone"), "cobblestone", false, 32);
        assertEquals(24, plain.n());
        // review sent back (2): 32 cobblestone -d with 32 held is met, never 32 more on the world with the 32 taken off
        var full = Goals.ownAsk(Map.of("minecraft:cobblestone", 32), Map.of("cobblestone", 32), List.of("minecraft:cobblestone"), "cobblestone", false, 32);
        assertEquals(0, full.n());
        var fw = new io.github.mojolowjo.entropybot.aa.NeedChain.World(Map.of("minecraft:cobblestone", 32), false, x -> null);
        var fc = full.resolve(fw);
        assertTrue(fc.ok() && fc.steps().isEmpty(), "nothing to gather: " + fc.why() + " " + fc.steps());
        assertEquals(12, plain.pricedOn(new io.github.mojolowjo.entropybot.aa.NeedChain.World(Map.of("minecraft:cobblestone", 20), false, x -> null)).bag().get("minecraft:cobblestone"));
    }
}
