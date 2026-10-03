package io.github.mojolowjo.entropybot.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TileFilesTest {
    @TempDir
    Path dir;

    @Test
    void aTileGoesToDiskAndComesBackTheSame() throws Exception {
        int[] px = new int[MapMath.REGION * MapMath.REGION];
        px[MapMath.index(-1, -1)] = MapMath.argb(0x7FB238, MapMath.NORMAL);
        px[MapMath.index(-512, -512)] = MapMath.argb(0x4040FF, MapMath.LOW);
        Path f = dir.resolve("minecraft_overworld").resolve(MapMath.fileName(-1, -1));
        assertNull(TileFiles.readPng(f), "no file yet");
        TileFiles.writePng(f, px);
        assertTrue(Files.exists(f));
        assertFalse(Files.exists(f.resolveSibling("-1.-1.png.tmp")), "no temp file left behind");
        assertArrayEquals(px, TileFiles.readPng(f), "transparent pixels stay transparent, colours exact");
        px[0] = 0xFF123456;
        TileFiles.writePng(f, px);
        assertEquals(0xFF123456, TileFiles.readPng(f)[0], "written over");
    }

    @Test
    void aBrokenTileIsSetAside() throws Exception {
        Path f = dir.resolve("minecraft_overworld").resolve("0.0.png");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "not a png");
        assertNull(TileFiles.readPng(f));
        assertFalse(Files.exists(f));
        assertTrue(Files.exists(f.resolveSibling("0.0.broken.png")));
    }

    @Test
    void theIndexListsTheTilesOnDisk() throws Exception {
        int[] px = new int[MapMath.REGION * MapMath.REGION];
        TileFiles.writePng(dir.resolve("minecraft_overworld").resolve("0.0.png"), px);
        TileFiles.writePng(dir.resolve("minecraft_overworld").resolve("-1.0.png"), px);
        TileFiles.writePng(dir.resolve("mymod_deep_dark").resolve("2.3.png"), px);
        Files.writeString(dir.resolve("minecraft_overworld").resolve("0.0.png.tmp"), "half");
        assertEquals(3, TileFiles.writeIndex(dir, List.of("minecraft:overworld")));
        String idx = Files.readString(dir.resolve(TileFiles.INDEX));
        assertTrue(idx.matches("\\{\"minecraft:overworld\":\\[\\[-1,0,\\d+],\\[0,0,\\d+]],\"mymod:deep_dark\":\\[\\[2,3,\\d+]]}"), idx);
        // a dimension id with '_' in its namespace is kept from the old index, not guessed again
        Files.move(dir.resolve("mymod_deep_dark"), dir.resolve("my_mod_dark"));
        Files.writeString(dir.resolve(TileFiles.INDEX), "{\"my_mod:dark\":[]}");
        TileFiles.writeIndex(dir, List.of());
        assertTrue(Files.readString(dir.resolve(TileFiles.INDEX)).contains("\"my_mod:dark\":[[2,3,"));
    }
}
