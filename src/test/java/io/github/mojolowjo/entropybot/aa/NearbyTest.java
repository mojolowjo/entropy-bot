package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** simple-tools: the one "what is nearby" fact: Baritone's scan first, the sightings as the fallback. */
class NearbyTest {
    static final int[] ME = {0, 64, 0};
    static final Spot SIGHTED = new Spot(50, 64, 50, 70, null);

    @BeforeEach
    void data() {
        TestData d = TestData.planner();
        d.tiers.put("minecraft:stone", 1);
        d.tiers.put("minecraft:dirt", 0);
        d.install();
    }

    @AfterEach
    void clear() { TestData.clear(); }

    record Fake(List<int[]> logs, List<int[]> stones, Predicate<int[]> leaves, Predicate<int[]> allowed, Map<String, String> ids) implements Nearby.Scan {
        @Override public io.github.mojolowjo.entropybot.guard.Policy stationPolicy() { return io.github.mojolowjo.entropybot.aa.TestStations.OPEN; }  // stated: the owner's area over the world
        @Override public String stationDim() { return io.github.mojolowjo.entropybot.aa.TestStations.OW; }
        @Override public List<int[]> logs(int radius) { return logs; }
        @Override public List<int[]> find(java.util.Set<String> blocks, int radius) { return stones; }
        @Override public boolean isLog(String block) { return block.endsWith("_log"); }
        @Override public boolean fairOk(int x, int y, int z) { return true; }
        @Override public boolean leavesNear(int x, int y, int z, int reach) { return leaves.test(new int[]{x, y, z}); }
        @Override public boolean allowed(int x, int y, int z) { return allowed.test(new int[]{x, y, z}); }
        @Override public String blockAt(int x, int y, int z) { return ids.getOrDefault(x + " " + y + " " + z, "minecraft:stone"); }
    }

    @Test
    void theNearestLogWithLeavesBeatsANearerLogInABuildAndTheSightings() {
        var s = new Fake(List.of(new int[]{30, 64, 0}, new int[]{3, 64, 0}, new int[]{9, 64, 0}), List.of(), p -> p[0] != 3, p -> true, Map.of());
        Spot r = Nearby.log(s, ME, () -> SIGHTED);
        assertEquals(9, r.x(), "the log at 3 has no leaves by it (a stack in a build)");
        assertEquals(9.0, r.dist(), 1e-9);
    }

    @Test
    void aLogTheScanDoesNotFindFallsBackToTheSightings() {
        var s = new Fake(List.of(), List.of(), p -> true, p -> true, Map.of());
        assertSame(SIGHTED, Nearby.log(s, ME, () -> SIGHTED));
        assertNull(Nearby.log(s, ME, () -> null));
    }

    @Test
    void aLogNoLeavesBelongToIsNoTreeSoTheSightingsAnswer() {
        var s = new Fake(List.of(new int[]{4, 64, 0}), List.of(), p -> false, p -> true, Map.of());
        assertSame(SIGHTED, Nearby.log(s, ME, () -> SIGHTED));
    }

    @Test
    void theNearestBlockOfTheSetIMayDigIsTakenAndItsBlockIsKept() {
        var s = new Fake(List.of(), List.of(new int[]{20, 60, 0}, new int[]{5, 60, 0}, new int[]{7, 60, 0}), p -> true, p -> p[0] != 5,
                Map.of("7 60 0", "minecraft:stone"));
        Spot r = Nearby.nearest(s, ME, java.util.Set.of("minecraft:stone"), () -> null);
        assertEquals(7, r.x(), "the nearer cell at 5 is not mine to dig");
        assertEquals("minecraft:stone", r.block());
    }

    @Test
    void aCellWhoseBlockIsNotInTheSetIsSkipped() {
        var s = new Fake(List.of(), List.of(new int[]{4, 60, 0}), p -> true, p -> true, Map.of("4 60 0", "minecraft:dirt"));
        assertSame(SIGHTED, Nearby.nearest(s, ME, java.util.Set.of("minecraft:stone"), () -> SIGHTED));
    }

    /** station-memory: my placements at any distance (loaded or not) and the scan's stations, one list, nearest first. */
    @Test
    void theStationMemoryIsMyPlacementsAndTheScanNearestFirst() {
        java.util.Set<String> table = java.util.Set.of("minecraft:crafting_table");
        var f = new Fake(List.of(), List.of(new int[]{30, 64, 0}, new int[]{5, 64, 0}), p -> true, p -> true,
                Map.of("30 64 0", "minecraft:crafting_table", "5 64 0", "minecraft:crafting_table", "20 64 0", "minecraft:crafting_table"));
        var s = new Nearby.Scan() {
        @Override public io.github.mojolowjo.entropybot.guard.Policy stationPolicy() { return io.github.mojolowjo.entropybot.aa.TestStations.OPEN; }  // stated: the owner's area over the world
        @Override public String stationDim() { return io.github.mojolowjo.entropybot.aa.TestStations.OW; }
            @Override public List<int[]> logs(int radius) { return f.logs(radius); }
            @Override public List<int[]> find(java.util.Set<String> blocks, int radius) { return f.find(blocks, radius); }
            @Override public boolean isLog(String block) { return f.isLog(block); }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return true; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return x == 400 ? null : f.blockAt(x, y, z); }   // 400: not loaded
            @Override public List<Nearby.Mine> own() {
                return List.of(new Nearby.Mine(20, 64, 0, "minecraft:crafting_table"), new Nearby.Mine(400, 64, 0, "minecraft:crafting_table"),
                        new Nearby.Mine(5, 64, 0, "minecraft:crafting_table"), new Nearby.Mine(2, 64, 0, "minecraft:furnace"));
            }
        };
        List<Spot> l = Nearby.stations(s, ME, table);
        assertEquals(List.of(5, 20, 30, 400), l.stream().map(Spot::x).toList(), "the furnace is not a table; the cell both know is listed once");
        assertEquals(400.0, l.get(3).dist(), 1e-9, "a placement of mine 400 m away is still known");
        assertEquals(1, Nearby.unverified(s, table));
    }

    /** station-stale (0.39.z18): a table of mine in a loaded chunk that holds air now is forgotten at lookup; an unloaded one stays. */
    @Test
    void aGoneTableInALoadedChunkIsForgottenAtLookup() {
        java.util.Set<String> table = java.util.Set.of("minecraft:crafting_table");
        List<String> forgot = new java.util.ArrayList<>();
        Map<String, String> world = Map.of("-4 -60 14", "minecraft:air", "6 64 0", "minecraft:crafting_table");
        var s = new Nearby.Scan() {
        @Override public io.github.mojolowjo.entropybot.guard.Policy stationPolicy() { return io.github.mojolowjo.entropybot.aa.TestStations.OPEN; }  // stated: the owner's area over the world
        @Override public String stationDim() { return io.github.mojolowjo.entropybot.aa.TestStations.OW; }
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(java.util.Set<String> blocks, int radius) { return List.of(); }
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return world.get(x + " " + y + " " + z); }
            @Override public List<Nearby.Mine> own() {
                return List.of(new Nearby.Mine(-4, -60, 14, "minecraft:crafting_table"), new Nearby.Mine(6, 64, 0, "minecraft:crafting_table"),
                        new Nearby.Mine(900, 64, 0, "minecraft:crafting_table"));
            }
            @Override public String forgetOwn(Nearby.Mine m, String now) { String l = Nearby.forgotLine(m, now); forgot.add(l); return l; }
        };
        List<Spot> l = Nearby.stations(s, ME, table);
        assertEquals(List.of(6, 900), l.stream().map(Spot::x).toList());
        assertEquals(List.of("stations: forgot my crafting_table at -4 -60 14: the block there is minecraft:air now"), forgot);
        assertEquals(1, Nearby.unverified(s, table), "the one 900 m off is kept, unverified");
    }

    @Test
    void noBaritoneScanIsTheFallbackAlone() {
        assertSame(SIGHTED, Nearby.log(null, ME, () -> SIGHTED));
        assertNull(Nearby.nearest(null, ME, java.util.Set.of("minecraft:stone"), null));
    }
}
