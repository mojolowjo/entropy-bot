package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.guard.AreaType;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** V1a (0.22.0): areas.json v1 -> v2 on fake files shaped like the live bot's (backup of 2026-10-06). */
class AreaMigrationTest {
    static final String AREAS_V1 = "{\"areas\":[{\"name\":\"map\",\"dim\":\"minecraft:overworld\",\"x1\":-272,\"z1\":-64,\"x2\":223,\"z2\":431},"
            + "{\"name\":\"outside\",\"dim\":\"minecraft:overworld\",\"x1\":-526,\"z1\":-315,\"x2\":474,\"z2\":685},"
            + "{\"name\":\"tunnel\",\"dim\":\"minecraft:overworld\",\"x1\":-110,\"z1\":852,\"x2\":8591,\"z2\":856,\"y1\":-50,\"y2\":-44}],"
            + "\"protect\":[{\"name\":\"stairs_x20\",\"dim\":\"minecraft:overworld\",\"x1\":-21,\"z1\":172,\"x2\":-19,\"z2\":201,\"y1\":35,\"y2\":64},"
            + "{\"name\":\"base\",\"dim\":\"minecraft:overworld\",\"x1\":-40,\"z1\":170,\"x2\":-10,\"z2\":200,\"y1\":-64,\"y2\":80},"
            + "{\"dim\":\"minecraft:overworld\",\"x1\":-87,\"z1\":190,\"x2\":-34,\"z2\":192,\"y1\":-27,\"y2\":26}],"
            + "\"strict\":true,\"near\":{\"on\":true,\"r\":24}}";
    static final String BRAIN = "{\"routines\":{\"cavestrip\":\"goto cave2 then stripmine\",\"fence\":\"area farm 30 then guard mode strict\"},"
            + "\"rules\":[{\"kind\":\"every\",\"arg\":\"30m\",\"text\":\"protect old here 5 then deposit\"}],"
            + "\"zone\":{\"dim\":\"minecraft:overworld\",\"x2\":-2,\"y2\":58,\"z2\":140,\"x1\":-26,\"y1\":53,\"z1\":164},"
            + "\"suppliesOwner\":{\"torch\":true},\"supplies\":{\"torch\":32}}";

    static Map<String, JsonObject> places() {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        m.put("base", JsonParser.parseString("{\"x\":-27,\"y\":53,\"z\":187,\"dim\":\"minecraft:overworld\"}").getAsJsonObject());
        return m;
    }

    @Test
    void migratesEveryBoxOnceWithABackup() {
        JsonObject a = JsonParser.parseString(AREAS_V1).getAsJsonObject(), b = JsonParser.parseString(BRAIN).getAsJsonObject();
        Map<String, String> written = new LinkedHashMap<>();
        AreaMigration.Result r = AreaMigration.withBackup(a, places(), b, (n, j) -> { written.put(n, j); return "ok: " + j.length() + " bytes"; });
        assertTrue(r.ran());
        assertEquals(AREAS_V1.replace(" ", ""), written.get("areas.bak-0.22.0.json").replace(" ", ""), "the backup is the old file");
        assertTrue(written.containsKey("commands.bak-0.22.0.json"));
        assertEquals("areas: moved to types: 2 neutral, 1 main, 1 destroy, 3 safe", r.summary());
        // map holds the base and is the smaller one: main; outside and tunnel neutral
        assertEquals("main", PolicyCommands.typeOf(a.getAsJsonArray("areas").get(0).getAsJsonObject()).word());
        assertEquals(AreaType.NEUTRAL, PolicyCommands.typeOf(a.getAsJsonArray("areas").get(1).getAsJsonObject()));
        // every protect box is a safe area with its y range; the unnamed one is protect_1; "base" kept (no area had it)
        assertEquals(3, a.getAsJsonArray("protect").size(), "count before = after");
        JsonObject s0 = a.getAsJsonArray("protect").get(1).getAsJsonObject();
        assertEquals("base", s0.get("name").getAsString());
        assertEquals("safe", s0.get("type").getAsString());
        assertEquals(-64, s0.get("y1").getAsInt());
        assertEquals("protect_1", a.getAsJsonArray("protect").get(2).getAsJsonObject().get("name").getAsString());
        // the build zone -> a destroy area "zone", gone from commands.json
        JsonObject z = a.getAsJsonArray("areas").get(3).getAsJsonObject();
        assertEquals("zone", z.get("name").getAsString());
        assertEquals("destroy", z.get("type").getAsString());
        assertEquals(53, z.get("y1").getAsInt());
        assertFalse(b.has("zone"));
        // routines and rules rewritten; the rest untouched (near, strict, suppliesOwner)
        assertEquals("area here 30 farm then fence mode strict", b.getAsJsonObject("routines").get("fence").getAsString());
        assertEquals("goto cave2 then stripmine", b.getAsJsonObject("routines").get("cavestrip").getAsString());
        assertEquals("area here 5 old safe 8 16 then deposit", b.getAsJsonArray("rules").get(0).getAsJsonObject().get("text").getAsString());
        assertTrue(b.has("suppliesOwner"));
        assertEquals(24, a.getAsJsonObject("near").get("r").getAsInt());
        assertTrue(a.get("strict").getAsBoolean());
        assertEquals(2, a.get("version").getAsInt());
        assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("protect box (no name) -> safe area protect_1")), r.lines().toString());
        assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("routine fence:")));
        // the guard reads it: 4 areas (3 + zone), 3 safe
        Policy p = Policy.parse(a.toString());
        assertEquals(4, p.areas.size());
        assertEquals(3, p.protect.size());
        // idempotent: a second run changes nothing and writes no backup
        String once = a.toString();
        written.clear();
        AreaMigration.Result r2 = AreaMigration.withBackup(a, places(), b, (n, j) -> { written.put(n, j); return "ok"; });
        assertFalse(r2.ran());
        assertTrue(written.isEmpty());
        assertEquals(once, a.toString());
    }

    @Test
    void aFailedBackupRefuses() {
        JsonObject a = JsonParser.parseString(AREAS_V1).getAsJsonObject();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AreaMigration.withBackup(a, places(), new JsonObject(), (n, j) -> "error: disk full"));
        assertTrue(e.getMessage().contains("areas.bak-0.22.0.json"));
        assertFalse(a.has("version"), "nothing changed");
        assertFalse(a.getAsJsonArray("protect").get(0).getAsJsonObject().has("type"));
    }

    @Test
    void nameClashesAndAnAreaCalledBase() {
        JsonObject a = JsonParser.parseString("{\"areas\":[{\"name\":\"base\",\"x1\":0,\"z1\":0,\"x2\":5,\"z2\":5},{\"name\":\"big\",\"x1\":-50,\"z1\":-50,\"x2\":50,\"z2\":50}],"
                + "\"protect\":[{\"name\":\"base\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1}]}").getAsJsonObject();
        AreaMigration.Result r = AreaMigration.migrate(a, Map.of(), null);
        assertEquals("main", a.getAsJsonArray("areas").get(0).getAsJsonObject().get("type").getAsString(), "an area named base is main");
        assertFalse(a.getAsJsonArray("areas").get(1).getAsJsonObject().has("type"));
        assertEquals("base_safe", a.getAsJsonArray("protect").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("areas: moved to types: 1 neutral, 1 main, 0 destroy, 1 safe", r.summary());
    }

    @Test
    void anEmptyFileJustGetsTheVersion() {
        JsonObject a = new JsonObject();
        AreaMigration.Result r = AreaMigration.migrate(a, null, null);
        assertTrue(r.ran());
        assertEquals(2, a.get("version").getAsInt());
        assertFalse(AreaMigration.needed(a));
    }
}
