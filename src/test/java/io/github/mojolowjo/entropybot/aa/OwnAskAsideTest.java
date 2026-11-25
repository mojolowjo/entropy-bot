package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-review-leftovers review (item 4, worker path): with the planner worker installed the order's own chain is resolved
 * from the OwnAsk alone (BatchWay.own, port.own) on the world before the aside. The ask carries the aside, so the worker
 * prices the bag without the order's own held -d amount, as the tick's own supplier does.
 */
class OwnAskAsideTest {
    @Test
    void theAskSetsTheHeldAmountAsideOnAnyWorld() {
        Map<String, Integer> bag = new LinkedHashMap<>(J6bEpTest.pond(62).bag());
        bag.put("minecraft:cobblestone", 20);
        NeedChain.World w = J6bEpTest.pond(62).withBag(bag);
        Map<String, Integer> without = new LinkedHashMap<>(bag);
        without.put("minecraft:cobblestone", 0);
        var ask = new BatchWay.OwnAsk("minecraft:cobblestone", List.of("minecraft:cobblestone"), 12, false, Map.of("minecraft:cobblestone", 20));
        NeedChain.Chain viaAsk = ask.resolve(w);
        NeedChain.Chain expected = NeedChain.resolve("minecraft:cobblestone", 12, w.withBag(without));
        assertEquals(expected.why(), viaAsk.why());
        assertEquals(String.valueOf(expected.steps()), String.valueOf(viaAsk.steps()));
        NeedChain.Chain plain = new BatchWay.OwnAsk("minecraft:cobblestone", List.of("minecraft:cobblestone"), 12, false).resolve(w);
        assertNotEquals(String.valueOf(plain.steps()) + plain.why(), String.valueOf(viaAsk.steps()) + viaAsk.why(),
                "without the aside the held 20 would meet the 12 from the bag");
    }
}
