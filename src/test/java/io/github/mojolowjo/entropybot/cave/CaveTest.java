package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.io.BotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CaveTest {
    /** Solid stone everywhere except the cells carved out; light 0 unless lit. */
    static final class FakeCave implements CaveSearch.World {
        final Map<Long, String> blocks = new HashMap<>();
        final Set<Long> lit = new HashSet<>();

        static long k(int x, int y, int z) { return CaveSearch.key(x, y, z); }

        void air(int x1, int y1, int z1, int x2, int y2, int z2) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) blocks.put(k(x, y, z), "minecraft:air");
        }

        void set(int x, int y, int z, String id) { blocks.put(k(x, y, z), id); }

        String id(int x, int y, int z) { return blocks.getOrDefault(k(x, y, z), "minecraft:stone"); }

        @Override public boolean loaded(int x, int y, int z) { return Math.abs(x) < 200 && Math.abs(z) < 200; }
        @Override public boolean solid(int x, int y, int z) { String b = id(x, y, z); return !b.equals("minecraft:air") && !b.contains("water") && !b.contains("lava"); }
        @Override public boolean open(int x, int y, int z) { return id(x, y, z).equals("minecraft:air"); }
        @Override public boolean liquid(int x, int y, int z) { String b = id(x, y, z); return b.contains("water") || b.contains("lava"); }
        @Override public boolean lava(int x, int y, int z) { return id(x, y, z).contains("lava"); }
        @Override public int blockLight(int x, int y, int z) { return lit.contains(k(x, y, z)) ? 14 : 0; }
        @Override public int skyLight(int x, int y, int z) { return 0; }
        @Override public String block(int x, int y, int z) { return id(x, y, z); }
    }

    @Test
    void findsTheNearestDarkUnvisitedCellAndTheOresAlongTheWay() {
        FakeCave w = new FakeCave();
        w.air(0, 10, 0, 30, 11, 0);                      // a tunnel 2 high, along x
        w.set(5, 12, 0, "minecraft:iron_ore");           // in the ceiling, next to the head cell
        w.set(20, 10, 1, "minecraft:coal_ore");          // in the wall, not on the list
        for (int x = 0; x <= 8; x++) w.lit.add(FakeCave.k(x, 10, 0));   // a torch lights the first stretch
        var r = CaveSearch.search(w, 0, 10, 0, new HashSet<>(), id -> id.equals("minecraft:iron_ore"), 0, 10, 0, 160);
        assertNotNull(r.frontier());
        assertEquals(9, r.frontier().x(), "the first dark cell at least 6 steps away");
        assertEquals(1, r.ores().size());
        assertEquals("minecraft:iron_ore", r.ores().get(0).id());
        assertEquals(31, r.reached());
    }

    @Test
    void visitedCellsAreNoFrontierAndClimbsAndDropsWork() {
        FakeCave w = new FakeCave();
        w.air(0, 10, 0, 6, 12, 0);
        w.air(7, 11, 0, 12, 12, 0);                      // a step up at x 7
        w.air(13, 8, 0, 20, 12, 0);                      // then a drop of 3
        Set<Long> visited = new HashSet<>();
        for (int x = 0; x <= 12; x++) visited.add(CaveSearch.coarse(x, 11, 0));
        for (int x = 0; x <= 12; x++) visited.add(CaveSearch.coarse(x, 10, 0));
        var r = CaveSearch.search(w, 0, 10, 0, visited, id -> false, 0, 10, 0, 160);
        assertNotNull(r.frontier());
        assertTrue(r.frontier().x() >= 13, "beyond what was visited: " + r.frontier());
        assertEquals(8, r.frontier().y(), "down the drop");
    }

    @Test
    void nothingDarkLeftIsNoFrontierAndLavaIsAvoided() {
        FakeCave w = new FakeCave();
        w.air(0, 10, 0, 12, 11, 0);
        w.set(10, 9, 1, "minecraft:lava");
        for (int x = 0; x <= 7; x++) w.lit.add(FakeCave.k(x, 10, 0));
        var r = CaveSearch.search(w, 0, 10, 0, new HashSet<>(), id -> false, 0, 10, 0, 160);
        assertNull(r.frontier(), "the dark end is next to lava");
    }

    @Test
    void theEntranceLimitStopsTheSearch() {
        FakeCave w = new FakeCave();
        w.air(0, 10, 0, 100, 11, 0);
        var r = CaveSearch.search(w, 0, 10, 0, new HashSet<>(), id -> false, 0, 10, 0, 20);
        assertTrue(r.reached() <= 21, "only 20 blocks from the entrance: " + r.reached());
    }

    @TempDir
    Path dir;

    @Test
    void cavesArePickedResumedSavedAndRenamed() {
        Caves c = new Caves();
        c.load(new BotFiles(dir));
        Caves.Cave a = c.pick(null, "o", 0, 10, 0, 1, 1);
        assertEquals("cave_1", a.name);
        assertSame(a, c.pick(null, "o", 10, 10, 10, 2, 2), "near an unfinished cave: that one again");
        c.visit(a, 30, 10, 0, 3, 3);
        assertEquals(30, a.furthest);
        c.finish(a, false, 4, 4);
        Caves.Cave b = c.pick(null, "o", 5, 10, 5, 5, 5);
        assertEquals("cave_2", b.name, "a finished cave is not picked again");
        assertNull(c.pick("cave_9", "o", 0, 0, 0, 6, 6));
        assertTrue(c.rename("cave_2", "big_cave", 7).startsWith("ok"));
        c.flushIfDue(7 + Caves.FLUSH_AFTER);
        Caves again = new Caves();
        assertTrue(again.load(new BotFiles(dir)).contains("2 caves"));
        assertFalse(again.get("cave_1").frontierLeft);
        assertFalse(again.get("cave_1").visited.isEmpty());
        assertNotNull(again.get("big_cave"));
    }
}
