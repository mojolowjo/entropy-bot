package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Package C: state.json's "settings" block for the dashboard's settings page. */
class SettingsBlockTest {

    private static JsonObject j(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    private static final SettingsBlock.Live IDLE = new SettingsBlock.Live(false, null, true, true, null, null, null);

    @Test
    void emptyFilesGiveTheDefaults() {
        JsonObject s = SettingsBlock.build(new JsonObject(), new JsonObject(), IDLE);
        assertEquals(List.of("guard", "areas", "protect", "supplies", "torches", "autominer", "deathPolicy", "parked", "reconnect",
                "defend", "routines", "chain", "hotbar", "toolOres", "recorder"), List.copyOf(s.keySet()), "the shape, in order");
        assertFalse(s.getAsJsonObject("guard").get("strict").getAsBoolean());
        assertEquals("log", s.getAsJsonObject("guard").get("mode").getAsString(), "no live mode: from strict");
        assertEquals(0, s.getAsJsonArray("areas").size());
        assertEquals(0, s.getAsJsonArray("protect").size());
        assertEquals(0, s.getAsJsonObject("supplies").size());
        assertEquals(0, s.get("torches").getAsInt());
        JsonObject am = s.getAsJsonObject("autominer");
        assertFalse(am.get("on").getAsBoolean());
        assertEquals(0, am.get("pausedUntil").getAsLong());
        assertTrue(am.get("orePrefer").isJsonNull());
        assertTrue(s.get("deathPolicy").getAsBoolean(), "the death policy is on unless switched off");
        assertTrue(s.get("parked").isJsonNull());
        assertTrue(s.get("reconnect").getAsBoolean());
        assertTrue(s.get("defend").getAsBoolean());
        assertEquals(0, s.getAsJsonArray("routines").size());
        assertTrue(s.get("chain").isJsonNull());
        assertEquals(0, s.getAsJsonObject("hotbar").size(), "no \"hotbar\" key = an empty layout");
        assertEquals("iron", s.get("toolOres").getAsString(), "no \"toolOres\" key = iron for ores");
    }

    @Test
    void nullFilesAreEmptyFiles() {
        JsonObject s = SettingsBlock.build(null, null, IDLE);
        assertEquals(SettingsBlock.build(new JsonObject(), new JsonObject(), IDLE), s);
    }

    @Test
    void filledFilesAndLiveValues() {
        JsonObject brain = j("""
                {"supplies": {"minecraft:torch": 64, "minecraft:bread": 32},
                 "autominer": {"on": true, "since": 5, "pausedUntil": 1234, "log": [{"what": "deposit"}]},
                 "deathPolicy": false, "parked": {"at": 9, "why": "5 deaths in an hour"},
                 "reconnect": false,
                 "routines": {"night": "deposit then eat then base", "unload": "deposit"},
                 "hotbar": {"1": "pickaxe", "2": "sword", "4": "minecraft:torch", "10": "food", "3": ""},
                 "toolOres": "cheapest"}""");
        JsonObject areas = j("""
                {"areas": [{"name": "map", "dim": "minecraft:overworld", "x1": -272, "z1": -64, "x2": 223, "z2": 431}, 7],
                 "protect": [{"name": "base", "dim": "minecraft:overworld", "x1": -40, "y1": 40, "z1": 170, "x2": -10, "y2": 80, "z2": 200}],
                 "strict": true, "corner1": {"x": 1}}""");
        SettingsBlock.Live live = new SettingsBlock.Live(true, "strict", false, false, "iron,diamond", "night", "night: step 1/3 (deposit), next: eat");
        JsonObject s = SettingsBlock.build(brain, areas, live);
        assertEquals("strict", s.getAsJsonObject("guard").get("mode").getAsString());
        assertTrue(s.getAsJsonObject("guard").get("strict").getAsBoolean());
        assertEquals(1, s.getAsJsonArray("areas").size(), "a non-object entry is skipped");
        assertEquals(-272, s.getAsJsonArray("areas").get(0).getAsJsonObject().get("x1").getAsInt());
        assertEquals(80, s.getAsJsonArray("protect").get(0).getAsJsonObject().get("y2").getAsInt());
        assertEquals(64, s.getAsJsonObject("supplies").get("minecraft:torch").getAsInt());
        assertEquals(64, s.get("torches").getAsInt(), "torches = the torch supply");
        JsonObject am = s.getAsJsonObject("autominer");
        assertTrue(am.get("on").getAsBoolean());
        assertEquals(1234, am.get("pausedUntil").getAsLong());
        assertEquals("iron,diamond", am.get("orePrefer").getAsString());
        assertFalse(am.has("log"), "the decision log stays out (\"why\" shows it)");
        assertFalse(s.get("deathPolicy").getAsBoolean());
        assertEquals("5 deaths in an hour", s.getAsJsonObject("parked").get("why").getAsString());
        assertFalse(s.get("reconnect").getAsBoolean(), "reconnect is the live value");
        assertFalse(s.get("defend").getAsBoolean());
        assertEquals(2, s.getAsJsonArray("routines").size());
        JsonObject night = s.getAsJsonArray("routines").get(0).getAsJsonObject(), unload = s.getAsJsonArray("routines").get(1).getAsJsonObject();
        assertEquals("night", night.get("name").getAsString());
        assertEquals("deposit then eat then base", night.get("text").getAsString());
        assertTrue(night.get("running").getAsBoolean());
        assertFalse(unload.get("running").getAsBoolean());
        assertEquals("night: step 1/3 (deposit), next: eat", s.get("chain").getAsString());
        assertEquals(j("{\"1\":\"pickaxe\",\"2\":\"sword\",\"4\":\"minecraft:torch\"}"), s.getAsJsonObject("hotbar"), "slots 1-9 only, blanks dropped");
        assertEquals("cheapest", s.get("toolOres").getAsString());
    }

    @Test
    void oddValuesFallBack() {
        JsonObject brain = j("{\"supplies\": {\"torch\": \"lots\"}, \"autominer\": {\"on\": \"maybe\"}, \"deathPolicy\": null,"
                + " \"hotbar\": [1, 2], \"toolOres\": \"gold\", \"routines\": []}");
        JsonObject s = SettingsBlock.build(brain, j("{\"areas\": {}}"), new SettingsBlock.Live(false, "log", true, true, "", "chain", "chain: step 1/2"));
        assertEquals(0, s.getAsJsonObject("supplies").get("torch").getAsInt());
        assertFalse(s.getAsJsonObject("autominer").get("on").getAsBoolean());
        assertTrue(s.getAsJsonObject("autominer").get("orePrefer").isJsonNull(), "an empty preference is none");
        assertTrue(s.get("deathPolicy").getAsBoolean());
        assertEquals(0, s.getAsJsonObject("hotbar").size());
        assertEquals("iron", s.get("toolOres").getAsString(), "only \"cheapest\" switches it");
        assertEquals(0, s.getAsJsonArray("routines").size());
        assertEquals(0, s.getAsJsonArray("areas").size());
        assertEquals("chain: step 1/2", s.get("chain").getAsString(), "a plain chain shows, no routine runs");
    }
}
