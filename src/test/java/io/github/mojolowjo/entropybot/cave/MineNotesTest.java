package io.github.mojolowjo.entropybot.cave;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.clear.OreBook;
import io.github.mojolowjo.entropybot.io.BotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** B7d D3: the listed ores and explored chunks (files, the move from memory.json, caps), explore's target and report, "ores", the cave records. */
class MineNotesTest {
    static final String OW = "minecraft:overworld";

    @Test
    void movesTheBridgeNotesOverOnceThenKeepsItsOwn(@TempDir Path dir) {
        BotFiles mine = new BotFiles(dir.resolve("entropybot")), bridge = new BotFiles(dir.resolve("bridge"));
        bridge.writeJson("memory.json", "{\"ores\":{\"1 2 3\":{\"id\":\"iron_ore\",\"dim\":\"minecraft:overworld\",\"seen\":5}},"
                + "\"explored\":{\"minecraft:overworld 0 0\":1,\"minecraft:overworld 0 1\":1}}");
        MineNotes n = new MineNotes();
        String line = n.load(mine, bridge);
        assertEquals("ores.json: 1 ores from memory.json; explored.json: 2 chunks from memory.json", line);
        assertTrue(n.explored("minecraft:overworld 0 1"));
        n.flushIfDue(1000);
        assertNotNull(mine.readJson(MineNotes.ORES));
        assertNotNull(mine.readJson(MineNotes.EXPLORED));
        // the mod's files win from now on (the bridge's copy is left as it was)
        n.note(4, 5, 6, "coal_ore", OW, 9);
        n.markExplored(List.of("minecraft:overworld 5 5"));
        n.flush();
        MineNotes again = new MineNotes();
        assertEquals("ores.json: 2 ores; explored.json: 3 chunks", again.load(mine, bridge));
        assertEquals("coal_ore", again.ores().get("4 5 6").get("id").getAsString());
        assertTrue(JsonParser.parseString(bridge.readJson("memory.json")).getAsJsonObject().getAsJsonObject("ores").size() == 1);
    }

    @Test
    void brokenFilesAreSetAside(@TempDir Path dir) {
        BotFiles mine = new BotFiles(dir);
        mine.writeJson(MineNotes.ORES, "[1, 2]");
        MineNotes n = new MineNotes();
        String line = n.load(mine, null);
        assertTrue(line.startsWith("ores.json: broken ("), line);
        assertTrue(line.endsWith("explored.json: none yet"), line);
        assertNotNull(mine.readJson("ores.broken.json"));
        assertEquals(0, n.oreCount());
    }

    @Test
    void theBookAndTheCaps(@TempDir Path dir) {
        MineNotes n = new MineNotes();
        n.load(new BotFiles(dir), null);
        OreBook book = n.book(OW);
        assertTrue(book.note(1, 2, 3, "iron_ore"));
        assertFalse(book.note(1, 2, 3, "iron_ore"), "listed already");
        assertEquals(OW, n.ores().get("1 2 3").get("dim").getAsString());
        assertTrue(book.forget("1 2 3"));
        assertFalse(book.forget("1 2 3"));
        // 1001 ores: the oldest seen goes
        for (int i = 0; i <= MineNotes.MAX_ORES; i++) n.note(i, 0, 0, "coal_ore", OW, 1000 + i);
        assertEquals(MineNotes.MAX_ORES, n.oreCount());
        assertFalse(n.ores().containsKey("0 0 0"));
        assertTrue(n.ores().containsKey("1000 0 0"));
        assertTrue(n.takePruneNote().startsWith("dropped the oldest 1 listed ores"));
        // 5001 chunks: the first-noted go, down to 4500
        List<String> keys = new ArrayList<>();
        for (int i = 0; i <= MineNotes.MAX_EXPLORED; i++) keys.add(OW + " " + i + " 0");
        n.markExplored(keys);
        assertEquals(MineNotes.EXPLORED_TO, n.exploredCount());
        assertFalse(n.explored(OW + " 0 0"));
        assertTrue(n.explored(OW + " 5000 0"));
        n.clearOres();
        assertEquals(0, n.oreCount());
    }

    @Test
    void oresListNearestFirstAndForgetsMinedOnes() {
        Map<String, JsonObject> ores = new java.util.LinkedHashMap<>();
        ores.put("10 40 0", ore("iron_ore", OW));
        ores.put("2 40 0", ore("iron_ore", OW));
        ores.put("5 40 0", ore("oritech:nickel_ore", OW));
        ores.put("0 40 1", ore("coal_ore", OW));            // mined since
        ores.put("1 40 0", ore("iron_ore", "minecraft:the_nether"));
        OresList.Answer a = OresList.list(ores, OW, 0, 40, 0, "", p -> p[0] == 0 && p[2] == 1);
        assertEquals("3 ores left for you: 2 iron_ore, 1 oritech:nickel_ore | nearest: iron_ore at 2 40 0, oritech:nickel_ore at 5 40 0, iron_ore at 10 40 0", a.text());
        assertEquals(List.of("0 40 1"), a.gone());
        assertEquals("1 ores left for you: 1 oritech:nickel_ore | nearest: oritech:nickel_ore at 5 40 0", OresList.list(ores, OW, 0, 40, 0, "nickel", p -> false).text());
        assertEquals("no gold listed", OresList.list(ores, OW, 0, 40, 0, "gold", p -> false).text());
        assertEquals("no ores listed - I list the ones I leave while clearing or strip mining", OresList.list(Map.of(), OW, 0, 40, 0, "", p -> false).text());
    }

    static JsonObject ore(String id, String dim) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("dim", dim);
        o.addProperty("seen", 1);
        return o;
    }

    /** The world explore's target search sees: a set of explored chunks, an area, refused spots. */
    static final class Land implements ExploreRules.Land {
        final Set<String> explored = new HashSet<>();
        int x1 = -1000, x2 = 1000, z1 = -1000, z2 = 1000;
        Set<String> refusedAt = new HashSet<>();
        @Override public boolean explored(String key) { return explored.contains(key); }
        @Override public boolean inside(int x, int z) { return x >= x1 && x <= x2 && z >= z1 && z <= z2; }
        @Override public boolean refused(int x, int y, int z) { return refusedAt.contains(x + " " + z); }
        @Override public void markRefused(String key) { explored.add(key); }
    }

    @Test
    void exploreTargets() {
        assertEquals(5, ExploreRules.minutes(""));
        assertEquals(10, ExploreRules.minutes("10"));
        assertEquals(30, ExploreRules.minutes("90"));
        assertEquals(5, ExploreRules.minutes("0"));
        assertEquals(7, ExploreRules.minutes("7m"));
        Land land = new Land();
        List<String> around = ExploreRules.around(OW, 0, 0);
        assertEquals(25, around.size(), "the chunks within 2 of the bot");
        land.explored.addAll(around);
        ExploreRules.Target t = ExploreRules.target(OW, 0, 64, 0, land, null);
        assertNotNull(t);
        assertTrue(Math.max(Math.abs(t.x()), Math.abs(t.z())) >= 32, "the nearest unexplored chunk: ring 3");
        assertEquals(8, Math.floorMod(t.x(), 16), "a chunk centre");
        // the area ends at x 20: nothing east of it; a refused centre is marked and skipped
        land.x2 = 20;
        land.x1 = -20;
        land.z1 = -20;
        land.z2 = 60;
        land.refusedAt.add("-8 56");
        t = ExploreRules.target(OW, 0, 64, 0, land, null);
        assertNotNull(t);
        assertEquals("8 56", t.x() + " " + t.z(), "inside the area, past the refused centre");
        assertTrue(land.explored.contains(OW + " -1 3"), "the refused chunk is never tried again");
        // a job's own skip set
        Set<String> skip = new HashSet<>(List.of(ExploreRules.key(OW, t.x() >> 4, t.z() >> 4)));
        ExploreRules.Target t2 = ExploreRules.target(OW, 0, 64, 0, land, skip);
        assertNotEquals(t, t2);
        assertFalse(land.explored.contains(ExploreRules.key(OW, t.x() >> 4, t.z() >> 4)), "skip is not written to the notes");
        // nothing left
        land.x1 = 0;
        land.x2 = 15;
        land.z1 = 0;
        land.z2 = 15;
        assertNull(ExploreRules.target(OW, 0, 64, 0, land, null));
        assertTrue(ExploreRules.arrived(30, 30, new ExploreRules.Target(40, 40)));
        assertFalse(ExploreRules.arrived(30, 30, new ExploreRules.Target(50, 40)));
    }

    @Test
    void exploreReport() {
        assertEquals("explored 12 chunks in 5 min (the time is up); nothing new to note", ExploreRules.note(12, 6000, "the time is up", List.of()));
        List<ExploreRules.Poi> fresh = new ArrayList<>();
        for (int i = 1; i <= 8; i++) fresh.add(new ExploreRules.Poi(i, i % 2 == 0 ? "dungeon" : "geode", i, 30, -i));
        assertEquals("explored 3 chunks in 2 min (nothing left to explore in reach); new: geode at 1 30 -1, dungeon at 2 30 -2, geode at 3 30 -3, dungeon at 4 30 -4, "
                + "geode at 5 30 -5, dungeon at 6 30 -6 (+2 more, \"poi\")", ExploreRules.note(3, 2400, "nothing left to explore in reach", fresh));
        assertEquals("error: I explore only inside my areas, and there are none - area <name> <r>", ExploreRules.NO_AREAS + "area <name> <r>");
    }

    /** The cave records "mine cave" leans on: a new cave here, carrying on in a known one, closing one with nothing in it. */
    @Test
    void caveMemoryForTheMoves(@TempDir Path dir) {
        Caves caves = new Caves();
        caves.load(new BotFiles(dir));
        Caves.Cave a = caves.pick("", OW, 0, 40, 0, 1, 1);
        assertEquals("cave_1", a.name);
        assertTrue(a.visited.isEmpty(), "new: this job owns it");
        caves.visit(a, 3, 40, 0, 2, 2);
        assertEquals(3, a.furthest);
        assertSame(a, caves.pick("", OW, 10, 40, 0, 3, 3), "an unfinished cave within 64: carries on there");
        // no cave here: a cave this job made is closed for good, so the next pick makes a new one
        caves.finish(a, false, 4, 4);
        Caves.Cave b = caves.pick("", OW, 10, 40, 0, 5, 5);
        assertEquals("cave_2", b.name);
        assertNull(caves.pick("cave_9", OW, 0, 0, 0, 6, 6), "an unknown name");
        assertSame(a, caves.pick("cave_1", OW, 500, 40, 0, 7, 7), "a finished cave by name");
        // the known caves as the move sees them
        List<CaveRules.Known> known = new ArrayList<>();
        caves.toJson(false).getAsJsonObject("caves").entrySet().forEach(e -> {
            JsonObject v = e.getValue().getAsJsonObject(), en = v.getAsJsonObject("entrance");
            known.add(new CaveRules.Known(e.getKey(), v.get("dim").getAsString(), en.get("x").getAsInt(), en.get("y").getAsInt(), en.get("z").getAsInt(), v.get("frontierLeft").getAsBoolean()));
        });
        assertEquals("cave_2", CaveRules.nearestKnown(known, OW, 0, 40, 0, Set.of(), k -> false).name(), "the closed one is never picked");
        assertNull(CaveRules.nearestKnown(known, OW, 0, 40, 0, Set.of("cave_2"), k -> false));
    }
}
