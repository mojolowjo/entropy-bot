package io.github.mojolowjo.entropybot.aa.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** loot-book-bg (0.39.z13): the loot book from loot-table files laid out like the jars, the cache, the override, the not-ready book. */
class LootFilesTest {

    /** test resources/lootjars: two namespaces, a block table (silk-touch and shears entries), a mob table with a nested reference, an older plural folder, a non-table file. */
    static Map<String, JsonElement> jarFiles() throws IOException, URISyntaxException {
        Path root = Path.of(LootFilesTest.class.getResource("/lootjars").toURI());
        Map<String, JsonElement> out = new HashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String id = LootFiles.entryId(root.relativize(p).toString().replace('\\', '/'));
                if (id != null) out.put(id, JsonParser.parseString(Files.readString(p)));
            }
        }
        return out;
    }

    static LootBook.Source find(List<LootBook.Source> l, LootBook.Kind k, String from, String item) {
        for (LootBook.Source s : l) if (s.kind() == k && s.from().equals(from) && s.item().equals(item)) return s;
        return null;
    }

    @Test
    void theTableIdsComeFromTheDataPackLayout() {
        assertEquals("minecraft:blocks/stone", LootFiles.tableId("minecraft", "loot_table/blocks/stone.json"));
        assertEquals("othermod:chests/vault", LootFiles.tableId("othermod", "loot_tables/chests/vault.json"));
        assertNull(LootFiles.tableId("minecraft", "recipe/stick.json"));
        assertEquals("othermod:blocks/ruby_ore", LootFiles.entryId("data/othermod/loot_table/blocks/ruby_ore.json"));
        assertNull(LootFiles.entryId("assets/othermod/loot_table/x.json"));
    }

    @Test
    void theJarLayoutGivesBlockMobAndChestSources() throws Exception {
        Map<String, JsonElement> files = jarFiles();
        assertEquals(5, files.size(), "the recipe file is no table: " + files.keySet());
        LootBook.Progress p = new LootBook.Progress("from the jars");
        p.total.set(files.size());
        LootFiles.Read r = LootFiles.build(files.keySet(), files::get, null, null, p);
        assertEquals(0, r.bad(), String.valueOf(r.firstBad()));
        assertEquals(4, r.tables(), "gameplay/ is read only through the cow's reference");
        assertEquals(5, p.done.get());
        List<LootBook.Source> l = r.sources();
        assertNotNull(find(l, LootBook.Kind.BLOCK, "minecraft:coal_ore", "minecraft:coal"), l.toString());
        assertNull(find(l, LootBook.Kind.BLOCK, "minecraft:coal_ore", "minecraft:coal_ore"), "the silk-touch entry (and the block itself) is not a drop");
        assertTrue(l.stream().noneMatch(s -> s.item().equals("minecraft:emerald")), "the shears entry is skipped: " + l);
        LootBook.Source beef = find(l, LootBook.Kind.MOB, "minecraft:cow", "minecraft:beef");
        assertNotNull(beef);
        assertEquals(2.0, beef.per(), 1e-9);
        assertNotNull(find(l, LootBook.Kind.MOB, "minecraft:cow", "minecraft:leather"), "the nested loot_table reference resolves by id in the same file set");
        assertEquals(2.0, find(l, LootBook.Kind.BLOCK, "othermod:ruby_ore", "othermod:ruby").per(), 1e-9);
        assertEquals(0.25, find(l, LootBook.Kind.CHEST, "othermod:chests/vault", "othermod:ruby").per(), 1e-9);
        assertTrue(l.stream().noneMatch(s -> s.from().contains("gameplay")));
    }

    @Test
    void theRegistryMapNamesTheOwnersWhenGiven() throws Exception {
        Map<String, JsonElement> files = jarFiles();
        // two blocks share the coal ore table (as deepslate ores do); the cow table is a mooshroom's too
        LootFiles.Read r = LootFiles.build(files.keySet(), files::get,
                Map.of("minecraft:blocks/coal_ore", List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore")),
                Map.of("minecraft:entities/cow", List.of("minecraft:cow", "minecraft:mooshroom")), null);
        assertNotNull(find(r.sources(), LootBook.Kind.BLOCK, "minecraft:deepslate_coal_ore", "minecraft:coal"));
        assertNotNull(find(r.sources(), LootBook.Kind.MOB, "minecraft:mooshroom", "minecraft:beef"));
        assertNull(find(r.sources(), LootBook.Kind.BLOCK, "othermod:ruby_ore", "othermod:ruby"), "a table no registry entry owns is no block's");
    }

    @Test
    void theCacheRoundTripsAndIsKeyedByTheModList() throws Exception {
        String h = LootFiles.hash(List.of("mod/othermod@1.2", "vanilla@1.21.1"), "1.21.1");
        assertEquals(h, LootFiles.hash(List.of("vanilla@1.21.1", "mod/othermod@1.2"), "1.21.1"), "the order of the list does not matter");
        assertNotEquals(h, LootFiles.hash(List.of("mod/othermod@1.3", "vanilla@1.21.1"), "1.21.1"), "a new mod version is a new key");
        assertNotEquals(h, LootFiles.hash(List.of("mod/othermod@1.2", "vanilla@1.21.1"), "1.21.2"), "a new game version is a new key");
        assertEquals("lootbook-" + h + ".json", LootFiles.cacheName(h));
        Map<String, JsonElement> files = jarFiles();
        LootFiles.Read r = LootFiles.build(files.keySet(), files::get, null, null, null);
        LootFiles.Read back = LootFiles.fromCache(LootFiles.toCache(h, r), h);
        assertEquals(r.sources(), back.sources());
        assertEquals(r.tables(), back.tables());
        assertThrows(IllegalArgumentException.class, () -> LootFiles.fromCache(LootFiles.toCache(h, r), "0000000000000000"), "another mod list's cache");
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> LootFiles.fromCache("{\"version\":1,\"hash\":\"" + h + "\",\"sources\":[{\"kind\":\"NOPE\"}]}", h));
        assertTrue(bad.getMessage().contains("source 0"), bad.getMessage());
        assertThrows(IllegalArgumentException.class, () -> LootFiles.fromCache("{not json", h));
    }

    @Test
    void theOverrideReplacesOneItemsSources() {
        LootBook book = new LootBook(List.of(
                new LootBook.Source(LootBook.Kind.MOB, "minecraft:cow", "minecraft:leather", 1, 1, false, null),
                new LootBook.Source(LootBook.Kind.MOB, "minecraft:cow", "minecraft:beef", 2, 1, false, null)), "jars: 2 tables, 0.1 s");
        var over = LootFiles.override("{\"leather\": [{\"kind\": \"mob\", \"from\": \"othermod:yak\", \"per\": 3}]}");
        LootBook b = book.withOverride(over, LootFiles.OVERRIDE_FILE);
        assertEquals(List.of(new LootBook.Source(LootBook.Kind.MOB, "othermod:yak", "minecraft:leather", 3, 1, false, null)), b.sources("minecraft:leather"));
        assertEquals(1, b.sources("minecraft:beef").size(), "the other item keeps its sources");
        assertTrue(b.origin.contains("1 items from lootbook-override.json"), b.origin);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LootFiles.override("{\"leather\": [{\"kind\": \"dragon\", \"from\": \"x\", \"per\": 1}]}"));
        assertTrue(e.getMessage().contains("leather entry 0"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> LootFiles.override("{\"leather\": [{\"kind\": \"mob\", \"from\": \"minecraft:cow\"}]}"), "per is required");
    }

    @Test
    void aBookBeingReadIsNotReadyAndSaysHowFar() {
        LootBook.Progress p = new LootBook.Progress("from the jars");
        p.total.set(10);
        p.done.set(3);
        LootBook b = LootBook.reading(p);
        assertFalse(b.ready());
        assertEquals("waiting for the loot book (3 of 10)", b.waiting());
        assertEquals("loot book: reading 3 of 10 tables (from the jars)", b.line());
        LootBook done = new LootBook(List.of(new LootBook.Source(LootBook.Kind.MOB, "minecraft:cow", "minecraft:beef", 2, 1, false, null)), "cache");
        assertTrue(done.ready());
        assertNull(done.waiting());
        assertEquals("loot book: 1 mob drops, 0 block drops, 0 chest items, 0 trades (cache)", done.line());
    }
}
