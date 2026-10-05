package io.github.mojolowjo.entropybot.route;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Queue priorities, staleness, counters, the log limiter, and the loader-neutral rule. */
class RouteBookkeepingTest {
    static SectionKey k(int x) {
        return new SectionKey(0, x, 4, 0);
    }

    @Test
    void nowQueueBeforeIdleQueue() {
        BuildQueue q = RouteCore.queue();
        assertTrue(q.offer(k(1), BuildQueue.Priority.REST));
        assertTrue(q.offer(k(2), BuildQueue.Priority.STALE));
        assertTrue(q.offer(k(3), BuildQueue.Priority.PLACES));
        assertTrue(q.offer(k(4), BuildQueue.Priority.NOW));
        assertEquals(1, q.nowSize());
        assertEquals(3, q.idleSize());
        assertEquals(k(4), q.poll(false));
        assertNull(q.poll(false), "idle keys wait while the bot is busy");
        assertEquals(k(3), q.poll(true));
        assertEquals(k(2), q.poll(true));
        assertEquals(k(1), q.poll(true));
        assertNull(q.poll(true));
    }

    @Test
    void offeringAgainRaisesNeverLowers() {
        BuildQueue q = RouteCore.queue();
        q.offer(k(1), BuildQueue.Priority.REST);
        q.offer(k(2), BuildQueue.Priority.REST);
        assertFalse(q.offer(k(1), BuildQueue.Priority.REST));
        assertTrue(q.offer(k(2), BuildQueue.Priority.NOW));
        assertFalse(q.offer(k(2), BuildQueue.Priority.STALE));
        assertEquals(1, q.nowSize());
        assertEquals(1, q.idleSize());
        assertEquals(k(2), q.poll(false));
        assertEquals(k(1), q.poll(true));
        assertNull(q.poll(true), "the raised key's old entry is skipped");
        q.offer(k(5), BuildQueue.Priority.STALE);
        q.offer(k(6), BuildQueue.Priority.NOW);
        q.clearIdle();
        assertEquals(0, q.idleSize());
        assertEquals(k(6), q.poll(true));
        assertNull(q.poll(true));
        assertTrue(q.offer(k(5), BuildQueue.Priority.STALE), "cleared keys can come back");
    }

    @Test
    void blockChangesMarkTheBoxAndNeighboursNearTheFace() {
        assertEquals(List.of(k(0)), RouteCore.boxesForBlockChange(0, 8, 72, 8));
        assertEquals(List.of(k(0), k(1)), RouteCore.boxesForBlockChange(0, 15, 72, 8));
        List<SectionKey> corner = RouteCore.boxesForBlockChange(0, 0, 64, 0);
        assertEquals(4, corner.size());
        assertTrue(corner.contains(new SectionKey(0, -1, 4, 0)));
        assertTrue(corner.contains(new SectionKey(0, 0, 3, 0)));
        assertTrue(corner.contains(new SectionKey(0, 0, 4, -1)));
        assertEquals(new SectionKey(0, -1, 4, -1), SectionKey.of(0, -1, 64, -16));
    }

    @Test
    void rehashMarksStaleOnlyWhenTheWalkGridChanged() {
        FakeWorld w = new FakeWorld(64);
        RouteStore store = RouteCore.memoryStore();
        SectionKey box = k(0);
        assertTrue(RouteCore.rehash(store, box, 1), "unknown box: build it");
        BoxBuilder.buildInto(new BuildInput(box, w, SectionRecord.Quality.LIVE, false, 1), store, null, null);
        long h = RouteCore.walkHash(box, w);
        assertEquals(store.get(box).walkHash(), h);
        assertFalse(RouteCore.rehash(store, box, h));
        assertFalse(store.isStale(box));
        w.set(5, 64, 5, FakeWorld.SOLID);
        long h2 = RouteCore.walkHash(box, w);
        assertNotEquals(h, h2);
        assertTrue(RouteCore.rehash(store, box, h2));
        assertTrue(store.isStale(box));
        assertEquals(List.of(box), new ArrayList<>(store.staleKeys()));
        BoxBuilder.buildInto(new BuildInput(box, w, SectionRecord.Quality.LIVE, false, 2), store, null, null);
        assertFalse(store.isStale(box), "a rebuild clears the mark");
    }

    @Test
    void storeTracksDirtyTiles() {
        RouteStore store = RouteCore.memoryStore();
        store.put(new SectionRecord(k(0), SectionRecord.Quality.LIVE, 0, 0, List.of(), new char[0]));
        store.put(new SectionRecord(k(9), SectionRecord.Quality.LIVE, 0, 0, List.of(), new char[0]));
        store.markStale(k(5)); // unknown: ignored
        assertFalse(store.isStale(k(5)));
        List<int[]> tiles = new ArrayList<>(store.takeDirtyTiles());
        assertEquals(2, tiles.size());
        assertTrue(store.takeDirtyTiles().isEmpty());
        store.markAllStale();
        assertEquals(2, store.staleKeys().size());
        assertEquals(2, store.takeDirtyTiles().size());
    }

    @Test
    void boxesAlongAStretch() {
        List<SectionKey> l = RouteCore.boxesAlong(0, new Cell(8, 64, 8), new Cell(40, 64, 8));
        assertEquals(List.of(k(0), k(1), k(2)), l);
    }

    @Test
    void countersAndStatusLine() {
        RouteCounters c = new RouteCounters();
        c.snapshot(0, 0, 0);
        for (int i = 0; i < 10; i++) c.boxBuilt();
        c.planningTimeout();
        c.fallbackNoPath();
        c.fallbackStuck();
        c.planMade();
        RouteStats s = c.snapshot(2000, 3, 7);
        assertEquals(5.0, s.boxesPerSecond(), 1e-9);
        assertEquals(10, s.boxesBuilt());
        assertEquals(3, s.nowQueue());
        assertEquals(7, s.idleQueue());
        assertTrue(s.line().contains("queues now 3 idle 7"), s.line());
        assertTrue(s.line().contains("fallbacks nopath 1 stuck 1"), s.line());
    }

    @Test
    void logShowsTheFirstFewInFullThenRateLimits() {
        List<String> lines = new ArrayList<>();
        AtomicLong now = new AtomicLong(0);
        RouteLog log = new RouteLog(lines::add, 2, 60_000, now::get);
        for (int i = 0; i < 10; i++) log.error("worker", new IllegalStateException("e" + i));
        assertEquals(3, lines.size(), "2 in full, then one limited line");
        assertTrue(lines.get(0).contains("IllegalStateException") && lines.get(0).contains("\n"));
        now.set(61_000);
        log.error("worker", new IllegalStateException("late"));
        assertEquals(4, lines.size());
        assertTrue(lines.get(3).contains("7 more held back"), lines.get(3));
        RouteLog.of(s -> { throw new RuntimeException("sink broken"); }).error("x", new Exception());
    }

    @Test
    void routePackageIsLoaderNeutral() throws IOException {
        Path dir = Path.of("src/main/java/io/github/mojolowjo/entropybot/route");
        assertTrue(Files.isDirectory(dir), "run from the project folder: " + dir.toAbsolutePath());
        Set<String> banned = Set.of("net.minecraft", "net.neoforged", "baritone", "com.mojang", "net.fabricmc",
                "org.spongepowered", "io.github.mojolowjo.entropybot.");
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                files++;
                for (String line : Files.readAllLines(p)) {
                    String t = line.trim();
                    if (!t.startsWith("import ")) continue;
                    String what = t.substring(7).replace("static ", "").trim();
                    for (String b : banned)
                        if (what.startsWith(b) && !what.startsWith("io.github.mojolowjo.entropybot.route."))
                            bad.add(p.getFileName() + ": " + t);
                }
            }
        }
        assertTrue(files >= 20, "found " + files + " files");
        assertTrue(bad.isEmpty(), "loader or mod imports in the route core: " + bad);
    }
}
