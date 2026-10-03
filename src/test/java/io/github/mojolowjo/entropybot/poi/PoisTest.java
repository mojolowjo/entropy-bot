package io.github.mojolowjo.entropybot.poi;

import io.github.mojolowjo.entropybot.io.BotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PoisTest {
    static final String OW = "minecraft:overworld";

    @TempDir
    Path dir;

    @Test
    void aFindNearAKnownPointOfTheSameKindIsTheSamePoint() {
        Pois p = new Pois();
        p.load(new BotFiles(dir));
        Pois.Poi a = p.saw("spawner", -32, 49, 157, OW, 1000, 1, null);
        assertNotNull(a);
        assertNull(p.saw("spawner", -30, 50, 160, OW, 2000, 2, null), "the same dungeon");
        assertNotNull(p.saw("geode", -30, 50, 160, OW, 2000, 3, null), "another kind is another point");
        assertNotNull(p.saw("spawner", 100, 49, 157, OW, 2000, 4, null), "far away is another point");
        assertNotNull(p.saw("spawner", -32, 49, 157, "minecraft:the_nether", 2000, 5, null), "another dimension too");
        assertEquals(4, p.size());
    }

    @Test
    void idsKeepGoingUpAndForgetWorks() {
        Pois p = new Pois();
        p.load(new BotFiles(dir));
        Pois.Poi a = p.saw("village", 0, 64, 0, OW, 1, 1, null);
        Pois.Poi b = p.saw("geode", 500, 0, 0, OW, 1, 1, null);
        assertEquals(a.id() + 1, b.id());
        assertTrue(p.forget(a.id(), 2));
        assertFalse(p.forget(a.id(), 3));
        Pois.Poi c = p.saw("village", 0, 64, 0, OW, 1, 4, null);
        assertTrue(c.id() > b.id(), "ids are never reused");
    }

    @Test
    void savedAndLoadedBack() throws Exception {
        Pois p = new Pois();
        p.load(new BotFiles(dir));
        p.saw("trial chamber", 10, 1, 176, OW, 5, 10, null);
        p.flushIfDue(10 + Pois.FLUSH_AFTER);
        assertTrue(Files.exists(dir.resolve(Pois.FILE)));
        Pois q = new Pois();
        assertTrue(q.load(new BotFiles(dir)).contains("1 points"));
        assertNull(q.saw("trial chamber", 12, 3, 180, OW, 6, 11, null), "still known after a load");
    }

    @Test
    void aBrokenFileIsSetAside() throws Exception {
        Files.writeString(dir.resolve(Pois.FILE), "nope");
        Pois p = new Pois();
        assertTrue(p.load(new BotFiles(dir)).contains("broken"));
        assertTrue(Files.exists(dir.resolve("pois.broken.json")));
        assertEquals(0, p.size());
    }
}
