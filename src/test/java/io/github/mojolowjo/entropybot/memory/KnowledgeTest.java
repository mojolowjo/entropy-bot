package io.github.mojolowjo.entropybot.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.io.BotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeTest {
    @TempDir
    Path dir;

    private Knowledge fresh() {
        Knowledge k = new Knowledge();
        k.load(new BotFiles(dir));
        return k;
    }

    @Test
    void putChangesBumpTheVersionAndDeletesWork() {
        Knowledge k = fresh();
        long v0 = k.version();
        long v1 = k.put("{\"places\":{\"base\":{\"x\":1,\"y\":2,\"z\":3,\"dim\":\"minecraft:overworld\"}}}", 10);
        assertTrue(v1 > v0);
        assertEquals(v1, k.put("{\"places\":{\"base\":{\"x\":1,\"y\":2,\"z\":3,\"dim\":\"minecraft:overworld\"}}}", 11), "the same value is no change");
        long v2 = k.put("{\"places\":{\"base\":null}}", 12);
        assertTrue(v2 > v1);
        assertTrue(k.places().isEmpty());
    }

    @Test
    void mergeKeepsTheNewerChestNoteAndTheBridgesPlaces() {
        Knowledge k = fresh();
        k.put("{\"chests\":{\"1 2 3\":{\"dim\":\"o\",\"items\":{\"a\":1},\"seen\":500}},\"places\":{\"food\":{\"x\":0,\"y\":0,\"z\":0}}}", 1);
        k.merge("{\"chests\":{\"1 2 3\":{\"dim\":\"o\",\"items\":{\"a\":9},\"seen\":400},\"4 5 6\":{\"dim\":\"o\",\"items\":{},\"seen\":1}}," +
                "\"places\":{\"food\":{\"x\":7,\"y\":7,\"z\":7}}}", 2);
        assertEquals(1, k.chests().get("1 2 3").getAsJsonObject("items").get("a").getAsInt(), "the mod's note was seen later");
        assertTrue(k.chests().containsKey("4 5 6"));
        assertEquals(7, k.places().get("food").get("x").getAsInt(), "the bridge's place wins");
        k.merge("{\"chests\":{\"1 2 3\":{\"dim\":\"o\",\"items\":{\"a\":5},\"seen\":900}}}", 3);
        assertEquals(5, k.chests().get("1 2 3").getAsJsonObject("items").get("a").getAsInt(), "a newer bridge note wins");
    }

    @Test
    void writesAfterADelayAndLoadsBack() throws Exception {
        Knowledge k = fresh();
        k.put("{\"places\":{\"mine\":{\"x\":-45,\"y\":16,\"z\":194,\"dir\":\"south\"}}}", 100);
        k.flushIfDue(120);
        assertFalse(Files.exists(dir.resolve("places.json")), "not before FLUSH_AFTER ticks");
        k.flushIfDue(100 + Knowledge.FLUSH_AFTER);
        assertTrue(Files.exists(dir.resolve("places.json")));
        Knowledge again = fresh();
        assertEquals("south", again.places().get("mine").get("dir").getAsString());
    }

    @Test
    void aBrokenFileFallsBackToItsBackupAndIsKeptAside() throws Exception {
        Files.writeString(dir.resolve("chests.json"), "{ this is not json");
        Files.writeString(dir.resolve("chests.bak.json"), "{\"1 2 3\":{\"dim\":\"o\",\"items\":{},\"seen\":5}}");
        Knowledge k = fresh();
        assertTrue(k.chests().containsKey("1 2 3"));
        assertNotNull(k.problem());
        JsonObject aside = JsonParser.parseString(Files.readString(dir.resolve("chests.broken.json"))).getAsJsonObject();
        assertEquals("{ this is not json", aside.get("text").getAsString());
    }

    @Test
    void theModsOwnNoteBumpsTheVersion() {
        Knowledge k = fresh();
        long v = k.version();
        JsonObject note = JsonParser.parseString("{\"dim\":\"o\",\"items\":{\"minecraft:bread\":3},\"seen\":9}").getAsJsonObject();
        k.noteChest("1 2 3", note, 5);
        assertTrue(k.version() > v);
        assertEquals(3, k.chests().get("1 2 3").getAsJsonObject("items").get("minecraft:bread").getAsInt());
    }
}
