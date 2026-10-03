package io.github.mojolowjo.entropybot.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FoodRunTest {
    static final String OW = "minecraft:overworld";
    static final Predicate<String> FOOD = id -> id.equals("minecraft:bread") || id.equals("minecraft:cooked_beef");

    static JsonObject j(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    static Map<String, JsonObject> chests() {
        Map<String, JsonObject> c = new TreeMap<>();
        c.put("10 64 0", j("{\"dim\":\"minecraft:overworld\",\"items\":{\"minecraft:bread\":20}}"));
        c.put("3 64 0", j("{\"dim\":\"minecraft:overworld\",\"items\":{\"minecraft:cooked_beef\":5,\"minecraft:dirt\":64}}"));
        c.put("1 64 0", j("{\"dim\":\"minecraft:overworld\",\"items\":{\"minecraft:dirt\":64}}"));
        c.put("2 64 0", j("{\"dim\":\"minecraft:overworld\",\"items\":{\"minecraft:bread\":64},\"trusted\":false}"));
        c.put("5 64 0", j("{\"dim\":\"minecraft:the_nether\",\"items\":{\"minecraft:bread\":64}}"));
        c.put("900 64 0", j("{\"dim\":\"minecraft:overworld\",\"items\":{\"minecraft:bread\":64}}"));
        return c;
    }

    @Test
    void theFoodChestComesFirstThenTheNearestTrustedChestsWithFood() {
        Map<String, JsonObject> places = Map.of("food", j("{\"x\":-5,\"y\":64,\"z\":0,\"dim\":\"minecraft:overworld\"}"));
        List<FoodRun.Target> t = FoodRun.targets(places, chests(), FOOD, OW, 0.5, 64, 0.5);
        assertEquals(2, t.size());
        assertEquals("-5 64 0", t.get(0).key());
        assertEquals("the food chest", t.get(0).why());
        assertEquals("3 64 0", t.get(1).key());
    }

    @Test
    void untrustedOtherDimensionsFarAndFoodlessChestsAreSkipped() {
        List<FoodRun.Target> t = FoodRun.targets(Map.of(), chests(), FOOD, OW, 0.5, 64, 0.5);
        assertEquals(List.of("3 64 0", "10 64 0"), t.stream().map(FoodRun.Target::key).toList());
    }

    @Test
    void nothingKnownIsAnEmptyList() {
        assertTrue(FoodRun.targets(Map.of(), Map.of(), FOOD, OW, 0, 64, 0).isEmpty());
    }
}
