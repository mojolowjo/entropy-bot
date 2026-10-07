package io.github.mojolowjo.entropybot.surface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** chunks-0.23.5: the export's unload and wipe leave the companion's chunks alone. */
class SurfaceFilesTest {
    static final String OW = "minecraft:overworld";

    static String own(int cx) { return new SurfaceColumns().toJson(OW, cx, 0, 5); }

    static String comp(String dim, int cx) {
        return "{\"v\":2,\"dim\":\"" + dim + "\",\"cx\":" + cx + ",\"cz\":0,\"t\":9,\"src\":\"companion\",\"g\":[]}";
    }

    @Test
    void unloadDeletesOnlyTheBotsOwn(@TempDir Path d) throws Exception {
        Files.writeString(d.resolve("1.0.json"), own(1));
        Files.writeString(d.resolve("2.0.json"), comp(OW, 2));
        assertTrue(SurfaceFiles.deleteOwn(d.resolve("1.0.json"), OW));
        assertFalse(SurfaceFiles.deleteOwn(d.resolve("2.0.json"), OW));
        assertTrue(Files.exists(d.resolve("2.0.json")));
        assertFalse(SurfaceFiles.deleteOwn(d.resolve("9.9.json"), OW), "a missing file is fine");
    }

    @Test
    void wipeKeepsCompanionChunksOfThisDimOnly(@TempDir Path d) throws Exception {
        Files.writeString(d.resolve("1.0.json"), own(1));
        Files.writeString(d.resolve("1.0.json.tmp"), "half");
        Files.writeString(d.resolve("2.0.json"), comp(OW, 2));
        Files.writeString(d.resolve("3.0.json"), comp("minecraft:the_nether", 3));
        int[] r = SurfaceFiles.wipe(d, OW);
        assertArrayEquals(new int[] {3, 1}, r);
        assertTrue(Files.exists(d.resolve("2.0.json")));
        assertFalse(Files.exists(d.resolve("3.0.json")), "a companion file of another dim is wiped");
        assertArrayEquals(new int[] {0, 1}, SurfaceFiles.count(d));
        assertArrayEquals(new int[] {0, 0}, SurfaceFiles.count(d.resolve("none")));
    }

    @Test
    void theBotsOwnFileHasNoSrc() {
        assertFalse(SurfaceFiles.companion(own(1)));
        assertTrue(SurfaceFiles.companion(comp(OW, 1)));
        assertFalse(SurfaceFiles.keep(comp("minecraft:the_end", 1), OW));
    }
}
