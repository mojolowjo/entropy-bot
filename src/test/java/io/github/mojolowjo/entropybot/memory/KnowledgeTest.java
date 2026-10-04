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

    @Test
    void rsReadingsAndTheGridTravelAndSurviveAReload() {
        Knowledge k = fresh();
        assertNull(k.rsGrid());
        long v = k.version();
        k.noteRs("-24 53 181", JsonParser.parseString("{\"dim\":\"o\",\"items\":{\"minecraft:dirt\":7},\"seen\":200}").getAsJsonObject(), 2);
        assertTrue(k.version() > v);
        assertEquals("-24 53 181", k.rsGrid(), "a reading makes its grid the one rs take/put use");
        assertEquals(7, k.rs().get("-24 53 181").getAsJsonObject("items").get("minecraft:dirt").getAsInt());
        assertEquals("-24 53 181", k.toJson().get("rsGrid").getAsString());
        k.put("{\"rsGrid\":\"1 2 3\"}", 4);
        assertEquals("1 2 3", k.rsGrid(), "a put sets it");
        assertEquals("ok", k.flush());
        Knowledge again = fresh();
        assertEquals("1 2 3", again.rsGrid());
        assertEquals(1, again.rs().size());
    }
}
