package io.github.mojolowjo.entropybot.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** C2 shared stock: totals over the bag, chest notes, RS readings and the owner's bag. */
class StockTest {
    static final long NOW = 10_000_000L;

    static JsonObject obj(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    static Stock sample(String ownerInv) {
        Map<String, JsonObject> chests = new TreeMap<>();
        chests.put("-28 54 189", obj("{\"dim\":\"minecraft:overworld\",\"items\":{\"minecraft:iron_ingot\":64,\"minecraft:bread\":10},\"seen\":" + (NOW - 720_000) + "}"));
        chests.put("1 2 3", obj("{\"dim\":\"minecraft:overworld\",\"items\":{\"minecraft:iron_ingot\":5},\"seen\":" + (NOW - 60_000) + "}"));
        chests.put("9 9 9", obj("{\"dim\":\"minecraft:overworld\",\"items\":{\"minecraft:iron_ingot\":999},\"seen\":1,\"trusted\":false}"));
        Map<String, JsonObject> places = new TreeMap<>();
        places.put("food", obj("{\"x\":-28,\"y\":54,\"z\":189,\"dim\":\"minecraft:overworld\"}"));
        Map<String, JsonObject> rs = new TreeMap<>();
        rs.put("-24 53 181", obj("{\"items\":{\"minecraft:iron_ingot\":300,\"minecraft:cobblestone\":2000},\"seen\":" + (NOW - 120_000) + "}"));
        return Stock.build(Map.of("minecraft:iron_ingot", 12), chests, places, rs, ownerInv, NOW);
    }

    static final String INV = "{\"name\":\"mojolowjo\",\"t\":" + (NOW - 3000) + ",\"inv\":[{\"id\":\"minecraft:iron_ingot\",\"count\":5}],\"health\":20}";

    @Test
    void haveOneItem() {
        String s = sample(INV).have("iron_ingot");
        assertEquals("iron_ingot: 386 - 12 on me, 64 in food chest (-28 54 189), 5 in chest 1 2 3, 300 in RS (2m ago), 5 on you (3s ago)", s);
    }

    @Test
    void untrustedLeftOutAndNoOwnerData() {
        Stock s = sample(null);
        assertFalse(s.ownerData());
        assertEquals(381, s.totals().get("iron_ingot"));
        assertTrue(s.have("").contains("you no companion data"), s.have(""));
        assertTrue(s.have("diamond").startsWith("we have no diamond"));
        assertEquals(-1, s.toJson().getAsJsonObject("sources").getAsJsonObject("owner").get("age").getAsLong());
    }

    @Test
    void topAndList() {
        Stock s = sample(INV);
        assertTrue(s.top().startsWith("we have 2396 items of 3 kinds: cobblestone 2000, iron_ingot 386, bread 10 - chests 60s ago (2), RS 2m ago, you 3s ago"), s.top());
        assertEquals("stock (ingot): iron_ingot 386 - chests 60s ago (2), RS 2m ago, you 3s ago", s.list("ingot"));
        assertTrue(s.list("zzz").startsWith("nothing matching zzz"));
        assertTrue(s.have("o").contains(" | "), s.have("o"));
    }

    @Test
    void jsonShape() {
        JsonObject o = sample(INV).toJson();
        assertEquals(NOW, o.get("t").getAsLong());
        JsonObject ch = o.getAsJsonObject("sources").getAsJsonObject("chests");
        assertEquals(2, ch.get("count").getAsInt());
        assertEquals(60_000, ch.get("age").getAsLong());
        JsonObject iron = ch.getAsJsonObject("items").getAsJsonObject("iron_ingot");
        assertEquals(69, iron.get("total").getAsInt());
        assertEquals("[\"-28 54 189\",\"food\",64]", iron.getAsJsonArray("at").get(0).toString());
        assertEquals(5, o.getAsJsonObject("sources").getAsJsonObject("owner").getAsJsonObject("items").get("iron_ingot").getAsInt());
        assertEquals(386, o.getAsJsonObject("totals").get("iron_ingot").getAsInt());
    }

    @Test
    void writerOnChangeAtMostEvery5s() {
        Stock.Writer w = new Stock.Writer(5000);
        assertNotNull(w.due(sample(INV), 0));
        assertNull(w.due(sample(INV), 1000), "too soon");
        assertNull(w.due(sample(INV), 9000), "unchanged");
        assertNotNull(w.due(sample(null), 9000), "changed");
        w.failed();
        assertNotNull(w.due(sample(null), 20000), "retry after a failed write");
    }

    @Test
    void brokenOwnerFileIsNoData() {
        assertFalse(sample("{not json").ownerData());
        assertFalse(sample("{\"name\":\"x\",\"t\":1}").ownerData());
    }
}
