package io.github.mojolowjo.entropybot.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BotFilesTest {
    @TempDir
    Path dir;

    @Test
    void writesAtomicallyAndReadsBack() throws Exception {
        BotFiles f = new BotFiles(dir);
        assertNull(f.readJson("places.json"));
        assertEquals("ok: 8 bytes", f.writeJson("places.json", "{\"a\": 1}"));
        assertEquals("{\"a\": 1}", f.readJson("places.json"));
        assertEquals("ok: 2 bytes", f.writeJson("runs/run_a.json", "[]"));
        assertEquals("[]", f.readJson("runs/run_a.json"));
        assertTrue(Files.exists(dir.resolve("runs").resolve("run_a.json")));
        assertFalse(Files.exists(dir.resolve("places.json.tmp")), "no temp file left behind");
        assertEquals("ok: 7 bytes", f.writeJson("places.json", "{\"b\":2}"));
        assertEquals("{\"b\":2}", f.readJson("places.json"));
    }

    @Test
    void refusesBadNamesAndBadJson() {
        BotFiles f = new BotFiles(dir);
        assertTrue(f.writeJson("../x.json", "{}").startsWith("error"));
        assertTrue(f.writeJson("x.txt", "{}").startsWith("error"));
        assertTrue(f.writeJson("C:/x.json", "{}").startsWith("error"));
        assertTrue(f.writeJson("a//b.json", "{}").startsWith("error"));
        assertTrue(f.writeJson("ok.json", "{nope").startsWith("error: not valid JSON"));
        assertTrue(f.writeJson("ok.json", null).startsWith("error"));
        assertTrue(f.readJson("../x.json").startsWith("error"));
        assertNull(BotFiles.validate("memory/chests-2.json"));
    }
}
