package io.github.mojolowjo.entropybot.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldStateTest {
    @TempDir
    Path root;

    @Test
    void keys() {
        assertEquals("run1", WorldState.key(false, "run1", null));
        assertEquals("New_World_2_", WorldState.key(false, "New World (2)", null));
        assertEquals("main.example.net_25566", WorldState.key(true, null, "main.example.net:25566"));
        assertEquals("example.org_25565", WorldState.key(true, null, "Example.org"));
        assertNull(WorldState.key(true, null, ""));
        assertNull(WorldState.key(false, "..", null));
    }

    @Test
    void worldNames() {
        for (String n : new String[]{"areas.json", "areas.bak.json", "areas.bak-0.22.0.json", "places.json", "chests.broken.json", "rs.json",
                "pois.json", "caves.json", "ores.json", "explored.json", "restore.json", "knownair.bin", "seenfaces.bin",
                "sense/overworld/1.2.json", "map", "routes/trips.csv", "route/penalties.json"})
            assertTrue(WorldState.isWorldName(n), n);
        for (String n : new String[]{"fast.json", "fast-server.json", "pm.json", "commands.json", "owner.json", "brain.json", "blind.json",
                "stock.json", "watch.json", "recorder/x.json", "brain/decisions.jsonl", "surface/overworld", "world.json", "actions.json"})
            assertFalse(WorldState.isWorldName(n), n);
    }

    @Test
    void botFilesRouteWorldFiles() {
        BotFiles f = new BotFiles(root);
        f.setWorld(root.resolve("worlds/a"));
        assertTrue(f.writeJson("areas.json", "{\"a\":1}").startsWith("ok"));
        assertTrue(f.writeJson("pm.json", "{}").startsWith("ok"));
        assertTrue(Files.exists(root.resolve("worlds/a/areas.json")));
        assertTrue(Files.exists(root.resolve("pm.json")));
        assertFalse(Files.exists(root.resolve("areas.json")));
        f.setWorld(root.resolve("worlds/b"));
        assertNull(f.readJson("areas.json"));                    // world b doesn't see world a's areas
        assertEquals("{}", f.readJson("pm.json"));               // global files shared
        assertEquals(root.resolve("worlds/b").toAbsolutePath().normalize(), f.worldRoot());
        assertThrows(IllegalArgumentException.class, () -> f.setWorld(root.getParent().resolve("elsewhere")));
    }

    @Test
    void multiplayerMigratesOnceAndKeepsGlobals() throws Exception {
        Files.writeString(root.resolve("areas.json"), "{\"main\":1}");
        Files.writeString(root.resolve("places.json"), "{}");
        Files.writeString(root.resolve("pm.json"), "{}");
        Files.createDirectories(root.resolve("sense/overworld"));
        Files.writeString(root.resolve("sense/overworld/0.0.json"), "{}");
        List<String> l = WorldState.enter(root, "srv_1", true, "srv_1");
        assertTrue(l.get(l.size() - 1).contains("3 copied"), l.toString());
        assertEquals("{\"main\":1}", Files.readString(root.resolve("worlds/srv_1/areas.json")));
        assertTrue(Files.exists(root.resolve("worlds/srv_1/sense/overworld/0.0.json")));
        assertFalse(Files.exists(root.resolve("worlds/srv_1/pm.json")));
        assertTrue(Files.exists(root.resolve("areas.json")));       // the root copy stays as a backup
        assertTrue(Files.exists(root.resolve("worlds/" + WorldState.MIGRATED)));
        // a second server: no migration
        WorldState.enter(root, "srv_2", true, "srv_1");
        assertFalse(Files.exists(root.resolve("worlds/srv_2/areas.json")));
    }

    @Test
    void singleplayerStartsEmpty() throws Exception {
        Files.writeString(root.resolve("areas.json"), "{\"main\":1}");
        List<String> l = WorldState.enter(root, "arena", false);
        assertTrue(String.join(";", l).contains("starts empty"));
        assertFalse(Files.exists(root.resolve("worlds/arena/areas.json")));
        assertFalse(Files.exists(root.resolve("worlds/" + WorldState.MIGRATED)));
        // the multiplayer server joined later still gets the root's files
        WorldState.enter(root, "srv", true, "srv");
        assertTrue(Files.exists(root.resolve("worlds/srv/areas.json")));
    }

    @Test
    void lanWorldNeverTakesTheMigration() throws Exception {
        Files.writeString(root.resolve("areas.json"), "{\"main\":1}");
        assertNull(WorldState.mainKey(root));
        WorldState.enter(root, "main.example.net_25565", true);      // no main-server.txt: nothing migrates
        assertFalse(Files.exists(root.resolve("worlds/main.example.net_25565/areas.json")));
        Files.writeString(root.resolve(WorldState.MAIN_FILE), "Main.Example.net:25565\n");
        assertEquals("main.example.net_25565", WorldState.mainKey(root));
        WorldState.enter(root, "192.168.1.5_51234", true);           // a LAN world first
        assertFalse(Files.exists(root.resolve("worlds/192.168.1.5_51234/areas.json")));
        assertFalse(Files.exists(root.resolve("worlds/" + WorldState.MIGRATED)));
        WorldState.enter(root, "main.example.net_25565", true);      // the main server still gets it
        assertTrue(Files.exists(root.resolve("worlds/main.example.net_25565/areas.json")));
    }

    @Test
    void badKeyRefused() {
        assertThrows(java.io.IOException.class, () -> WorldState.enter(root, "../x", false));
    }
}
