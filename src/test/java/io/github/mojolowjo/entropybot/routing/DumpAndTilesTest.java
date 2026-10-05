package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.CellMoves;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteFileHeader;
import io.github.mojolowjo.entropybot.route.RouteFiles;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RouteStore;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DumpAndTilesTest {

    @Test
    void dumpRoundTripGivesTheSameMoves() throws IOException {
        SectionKey k = new SectionKey(0, -2, 4, 11);
        CellMoves flat = new RouteFakes.Flat(70);
        StringBuilder sb = new StringBuilder();
        DumpFixture.Counts c = DumpFixture.write(sb, k, SectionRecord.Quality.LIVE, 0xabcdef0123456789L, "test box", flat);
        assertEquals(20 * 20, c.cells(), "the box plus a 2-block margin, one layer");
        assertEquals(4 * 400, c.moves());
        DumpFixture.Fixture f = DumpFixture.read(new StringReader(sb.toString()));
        assertEquals(k, f.key());
        assertEquals("LIVE", f.quality());
        assertEquals(0xabcdef0123456789L, f.settingsHash());
        assertEquals("test box", f.note());
        assertEquals(400, f.moves().cellCount());
        int x0 = k.minX(), z0 = k.minZ();
        for (int x = x0 - 2; x <= x0 + 17; x++)
            for (int z = z0 - 2; z <= z0 + 17; z++) {
                assertTrue(f.moves().standable(x, 70, z));
                assertFalse(f.moves().standable(x, 71, z));
                List<String> a = new ArrayList<>(), b = new ArrayList<>();
                flat.forEachMove(x, 70, z, (tx, ty, tz, t) -> a.add(tx + " " + ty + " " + tz + " " + t));
                f.moves().forEachMove(x, 70, z, (tx, ty, tz, t) -> b.add(tx + " " + ty + " " + tz + " " + t));
                assertEquals(a, b, "exact ticks after the round trip");
            }
        assertFalse(f.moves().standable(x0 - 3, 70, z0), "outside the dump");
    }

    @Test
    void aBadDumpSaysWhere() {
        IOException e = assertThrows(IOException.class, () -> DumpFixture.read(new StringReader(DumpFixture.MAGIC + "\nbox 0 0 0 0\nm 1 2 3 4\n")));
        assertTrue(e.getMessage().contains("line 3"));
        assertThrows(IOException.class, () -> DumpFixture.read(new StringReader("hello\n")));
        assertEquals("box.-2.4.11.20261004-120000.txt", DumpFixture.fileName(new SectionKey(0, -2, 4, 11), "20261004-120000"));
    }

    static final class FakeIo implements RouteTiles.TileIo {
        final List<String> saves = new ArrayList<>();
        int failures;
        final List<Path> loads = new ArrayList<>();

        @Override
        public void save(Path file, RouteFileHeader header, Collection<SectionRecord> records, Set<SectionKey> stale) throws IOException {
            if (failures > 0) {
                failures--;
                throw new IOException("disk full");
            }
            saves.add(file.getFileName() + " " + records.size() + " boxes " + stale.size() + " stale");
        }

        @Override
        public RouteFiles.LoadResult loadInto(RouteStore store, Path file, int dim, RouteFileHeader expected,
                                              RouteCounters counters, RouteLog log) {
            loads.add(file);
            boolean bad = file.getFileName().toString().contains("-9");
            return new RouteFiles.LoadResult(expected, bad ? List.of() : List.of(RouteFakes.record(new SectionKey(0, 0, 0, 0), SectionRecord.Quality.LIVE)),
                    Set.of(), bad, false, true, bad ? "truncated" : "ok");
        }
    }

    @Test
    void savesEachDirtyTileWithItsBoxesAndRetriesFailures(@TempDir Path dir) {
        RouteFakes.Store store = new RouteFakes.Store();
        store.put(RouteFakes.record(new SectionKey(0, 0, 4, 0), SectionRecord.Quality.LIVE));
        store.put(RouteFakes.record(new SectionKey(0, 7, 5, 7), SectionRecord.Quality.LIVE));   // same tile 0,0
        store.put(RouteFakes.record(new SectionKey(0, 8, 4, 0), SectionRecord.Quality.COARSE));  // tile 1,0
        store.markStale(new SectionKey(0, 7, 5, 7));
        FakeIo io = new FakeIo();
        RouteCounters c = new RouteCounters();
        List<String> lines = new ArrayList<>();
        RouteTiles t = new RouteTiles(dir, d -> d == 0 ? "overworld" : null, io, c, RouteLog.of(lines::add));
        RouteFileHeader h = RouteFileHeader.current("0.15.0", "1.11.3", 1, 2);
        io.failures = 1;
        int n = t.saveDirty(store, h);
        assertEquals(1, n, "one tile written, one failed");
        assertEquals(1, c.snapshot(0, 0, 0).workerExceptions());
        assertTrue(lines.get(0).contains("disk full"));
        assertTrue(Files.isDirectory(dir.resolve("overworld")));
        assertEquals(1, t.saveDirty(store, h), "the failed tile is retried with nothing else dirty");
        assertTrue(io.saves.contains("t.0.0.bin 2 boxes 1 stale"), io.saves.toString());
        assertTrue(io.saves.contains("t.1.0.bin 1 boxes 0 stale"), io.saves.toString());
        assertEquals(0, t.saveDirty(store, h), "nothing dirty");
        assertTrue(t.line().contains("save failures 1"));
    }

    @Test
    void loadsEveryTileFileAndCountsBadOnes(@TempDir Path dir) throws IOException {
        Path ow = Files.createDirectories(dir.resolve("overworld"));
        Files.writeString(ow.resolve("t.0.0.bin"), "x");
        Files.writeString(ow.resolve("t.-9.0.bin"), "x");
        Files.writeString(ow.resolve("notes.txt"), "x");
        FakeIo io = new FakeIo();
        RouteTiles t = new RouteTiles(dir, d -> d == 0 ? "overworld" : null, io, new RouteCounters(), RouteLog.of(s -> {}));
        RouteTiles.LoadSummary s = t.loadAll(new RouteFakes.Store(), 0, RouteFileHeader.current("a", "b", 1, 2));
        assertEquals(2, s.files());
        assertEquals(1, s.boxes());
        assertEquals(1, s.ignored());
        assertEquals(1, s.areasChanged());
        assertTrue(s.line().contains("1 ignored"));
        assertEquals(0, t.loadAll(new RouteFakes.Store(), 1, null).files(), "no folder for other dims");
        assertEquals(0, new RouteTiles(dir.resolve("none"), d -> "overworld", io, new RouteCounters(), RouteLog.of(x -> {}))
                .loadAll(new RouteFakes.Store(), 0, null).files());
    }
}
