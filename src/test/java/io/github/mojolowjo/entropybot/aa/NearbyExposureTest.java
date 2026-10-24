package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** S44 legit sources (0.39.z40): a block of the scan counts as seen only with an exposed face; a buried one is skipped and counted. */
class NearbyExposureTest {
    static final int[] ME = {0, 64, 0};
    static final Spot SIGHTED = new Spot(50, 64, 50, 70, "minecraft:iron_ore");
    static final String RAW = "minecraft:raw_iron_block", IRON = "minecraft:iron_ore";

    @BeforeEach
    void data() { TestData.planner().install(); }

    @AfterEach
    void clear() { TestData.clear(); }

    /** A fixture cache: the cells of the scan's blocks, the open cells (air, water), everything else solid; unloaded x >= 1000. */
    static final class World implements Nearby.Scan {
        final Map<String, String> blocks = new HashMap<>();
        final Set<String> open = new HashSet<>();
        World put(int x, int y, int z, String id) { blocks.put(x + " " + y + " " + z, id); return this; }
        World air(int x, int y, int z) { open.add(x + " " + y + " " + z); return this; }
        @Override public io.github.mojolowjo.entropybot.guard.Policy stationPolicy() { return TestStations.OPEN; }
        @Override public String stationDim() { return TestStations.OW; }
        @Override public List<int[]> logs(int radius) { return List.of(); }
        @Override public List<int[]> find(Set<String> ids, int radius) {
            return blocks.entrySet().stream().filter(e -> ids.contains(e.getValue()))
                    .map(e -> java.util.Arrays.stream(e.getKey().split(" ")).mapToInt(Integer::parseInt).toArray()).toList();
        }
        @Override public boolean isLog(String block) { return false; }
        @Override public boolean isOre(String block) { return false; }
        @Override public boolean fairOk(int x, int y, int z) { return true; }
        @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
        @Override public boolean allowed(int x, int y, int z) { return true; }
        @Override public String blockAt(int x, int y, int z) { return x >= 1000 ? null : blocks.getOrDefault(x + " " + y + " " + z, "minecraft:stone"); }
        @Override public Boolean open(int x, int y, int z) { return x >= 1000 ? null : open.contains(x + " " + y + " " + z); }
    }

    @Test
    void s44aRawIronBlockWithSixSolidNeighboursIsNotASource() {
        World w = new World().put(-24, -39, -85, RAW);
        Nearby.Source s = Nearby.source(w, ME, Set.of(RAW), () -> null);
        assertNull(s.spot(), "buried: only the strip and explore ways may make it known");
        assertEquals(Map.of(RAW, 1), s.buried());
        assertEquals(Boolean.FALSE, Nearby.exposed(w, -24, -39, -85));
        assertTrue(s.line().contains("1 raw_iron_block buried, not counted"), s.line());
    }

    @Test
    void s44theSameBlockWithOneAirNeighbourIsASource() {
        World w = new World().put(-24, -39, -85, RAW).air(-24, -40, -85);
        Nearby.Source s = Nearby.source(w, ME, Set.of(RAW), () -> null);
        assertNotNull(s.spot());
        assertEquals(-85, s.spot().z());
        assertEquals("baritone", s.from());
        assertTrue(s.line().endsWith("seen (exposed)"), s.line());
    }

    @Test
    void buriedOnesAreCountedPerBlockAndTheNearestExposedOneWins() {
        World w = new World().put(3, 60, 0, IRON).put(4, 60, 0, IRON).put(5, 60, 0, IRON).put(9, 60, 0, IRON).air(9, 61, 0);
        Nearby.Source s = Nearby.source(w, ME, Set.of(IRON), () -> null);
        assertEquals(9, s.spot().x());
        assertEquals(3, s.buried().get(IRON));
        assertTrue(s.line().contains("3 iron_ore buried, not counted"), s.line());
    }

    @Test
    void anOnlyBuriedScanFallsBackToMySightingsAndSaysSo() {
        World w = new World().put(3, 60, 0, IRON);
        Nearby.Source s = Nearby.source(w, ME, Set.of(IRON), () -> SIGHTED);
        assertSame(SIGHTED, s.spot());
        assertEquals("fallback", s.from());
        assertTrue(s.line().contains("from my sightings"), s.line());
        assertSame(SIGHTED, Nearby.nearest(w, ME, Set.of(IRON), () -> SIGHTED));
    }

    @Test
    void aSideInAnUnloadedChunkWithNoOpenFaceIsUnknownNotExposed() {
        World w = new World().put(999, 60, 0, IRON);
        assertNull(Nearby.exposed(w, 999, 60, 0), "rule 15: one side not loaded, no open face known");
        assertNull(Nearby.source(w, ME, Set.of(IRON), () -> null).spot());
    }
}
