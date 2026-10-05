package io.github.mojolowjo.entropybot.route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static io.github.mojolowjo.entropybot.route.FakeWorld.SOLID;
import static org.junit.jupiter.api.Assertions.*;

class RouteFilesTest {
    static final RouteFileHeader HEADER = RouteFileHeader.current("0.15.0", "1.11.3",
            RouteHashes.settings(Map.of("allowParkour", "false", "allowSprint", "true")),
            RouteHashes.areas(List.of(new int[]{0, -272, -64, 223, 431, -64, 319})));

    @TempDir
    Path dir;

    static List<SectionRecord> sample() {
        FakeWorld w = new FakeWorld(64);
        w.fill(15, 64, -5, 16, 79, 20, SOLID);
        w.set(15, 64, 5, FakeWorld.AIR).set(15, 65, 5, FakeWorld.AIR);
        SectionRecord a = BoxBuilderTest.build(w, new SectionKey(0, 0, 4, 0));
        SectionRecord b = BoxBuilderTest.build(w, new SectionKey(0, 1, 4, 0));
        SectionRecord solid = BoxBuilderTest.build(w, new SectionKey(0, 0, 3, 0));
        SectionRecord coarse = new SectionRecord(new SectionKey(0, 7, 4, 7), SectionRecord.Quality.COARSE, 99, 5,
                List.of(), new char[0]);
        return List.of(a, b, solid, coarse);
    }

    Path saved() throws IOException {
        Path f = RouteFiles.tilePath(dir, "overworld", 0, 0);
        RouteFiles.save(f, HEADER, sample(), Set.of(new SectionKey(0, 1, 4, 0)));
        return f;
    }

    @Test
    void roundTrip() throws IOException {
        Path f = saved();
        assertTrue(f.toString().replace('\\', '/').endsWith("overworld/t.0.0.bin"));
        RouteCounters c = new RouteCounters();
        RouteFiles.LoadResult r = RouteFiles.load(f, 0, HEADER, c, null);
        assertFalse(r.ignored(), r.note());
        assertFalse(r.settingsChanged());
        assertFalse(r.areasChanged());
        assertEquals(HEADER, r.header());
        List<SectionRecord> want = sample();
        assertEquals(want.size(), r.records().size());
        for (int i = 0; i < want.size(); i++) {
            SectionRecord a = want.get(i), b = r.records().get(i);
            assertEquals(a.key(), b.key());
            assertEquals(a.quality(), b.quality());
            assertEquals(a.builtAt(), b.builtAt());
            assertEquals(a.walkHash(), b.walkHash());
            assertArrayEquals(a.crossing(), b.crossing());
            assertEquals(a.doorCount(), b.doorCount());
            for (int d = 0; d < a.doorCount(); d++) {
                Door x = a.doors().get(d), y = b.doors().get(d);
                assertArrayEquals(x.mask(), y.mask());
                assertEquals(x.rep(), y.rep());
                assertEquals(Arrays.asList(x.face(), x.minX(), x.minY(), x.minZ(), x.maxX(), x.maxY(), x.maxZ(),
                        x.canLeave(), x.canEnter()), Arrays.asList(y.face(), y.minX(), y.minY(), y.minZ(), y.maxX(),
                        y.maxY(), y.maxZ(), y.canLeave(), y.canEnter()));
            }
        }
        assertEquals(Set.of(new SectionKey(0, 1, 4, 0)), r.stale());
        assertEquals(0, c.snapshot(0, 0, 0).filesIgnored());

        RouteStore store = RouteCore.memoryStore();
        RouteFiles.loadInto(store, f, 0, HEADER, c, null);
        assertEquals(4, store.size());
        assertTrue(store.isStale(new SectionKey(0, 1, 4, 0)));
        assertFalse(store.isStale(new SectionKey(0, 0, 4, 0)));
    }

    @Test
    void truncatedFilesAreIgnored() throws IOException {
        Path f = saved();
        byte[] all = Files.readAllBytes(f);
        RouteCounters c = new RouteCounters();
        // cut gzip
        Files.write(f, Arrays.copyOf(all, all.length / 2));
        assertTrue(RouteFiles.load(f, 0, HEADER, c, null).ignored());
        // gzip intact, content cut short
        byte[] raw;
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(all))) {
            raw = in.readAllBytes();
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
            gz.write(raw, 0, raw.length - 3);
        }
        Files.write(f, bo.toByteArray());
        assertTrue(RouteFiles.load(f, 0, HEADER, c, null).ignored());
        // garbage and empty
        Files.write(f, new byte[]{1, 2, 3});
        assertTrue(RouteFiles.load(f, 0, HEADER, c, null).ignored());
        Files.write(f, new byte[0]);
        assertTrue(RouteFiles.load(f, 0, HEADER, c, null).ignored());
        // missing
        assertTrue(RouteFiles.load(dir.resolve("nope.bin"), 0, HEADER, c, null).ignored());
        assertEquals(5, c.snapshot(0, 0, 0).filesIgnored());
        RouteStore store = RouteCore.memoryStore();
        RouteFiles.loadInto(store, f, 0, HEADER, c, null);
        assertEquals(0, store.size());
    }

    @Test
    void oldVersionAndOtherDimAreIgnored() throws IOException {
        Path f = RouteFiles.tilePath(dir, "overworld", 0, 0);
        RouteFiles.save(f, new RouteFileHeader(0, "0.14.0", "1.11.3", 1, 2), sample(), Set.of());
        RouteFiles.LoadResult r = RouteFiles.load(f, 0, HEADER, null, null);
        assertTrue(r.ignored());
        assertTrue(r.note().contains("format version 0"), r.note());
        saved();
        assertTrue(RouteFiles.load(f, 1, HEADER, null, null).ignored());
    }

    @Test
    void settingsChangeMakesEverythingStaleAndAreasChangeIsReported() throws IOException {
        Path f = saved();
        RouteFileHeader now = RouteFileHeader.current("0.15.1", "1.11.3",
                RouteHashes.settings(Map.of("allowParkour", "true", "allowSprint", "true")), 7);
        RouteStore store = RouteCore.memoryStore();
        RouteFiles.LoadResult r = RouteFiles.loadInto(store, f, 0, now, null, null);
        assertTrue(r.settingsChanged());
        assertTrue(r.areasChanged());
        assertTrue(r.note().contains("0.15.0"), r.note());
        assertEquals(4, store.staleKeys().size());
    }

    @Test
    void saveRefusesBoxesFromAnotherTile() {
        List<SectionRecord> mixed = List.of(sample().get(0), new SectionRecord(new SectionKey(0, 8, 4, 0),
                SectionRecord.Quality.LIVE, 0, 0, List.of(), new char[0]));
        assertThrows(IllegalArgumentException.class,
                () -> RouteFiles.save(dir.resolve("x.bin"), HEADER, mixed, Set.of()));
    }

    @Test
    void hashesAreStable() {
        assertEquals(RouteHashes.settings(Map.of("a", "1", "b", "2")), RouteHashes.settings(Map.of("b", "2", "a", "1")));
        assertNotEquals(RouteHashes.settings(Map.of("a", "1")), RouteHashes.settings(Map.of("a", "2")));
        assertNotEquals(RouteHashes.areas(List.of(new int[]{0, 1, 2})), RouteHashes.areas(List.of(new int[]{0, 1, 3})));
    }
}
