package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.BuildInput;
import io.github.mojolowjo.entropybot.route.BuildQueue.Priority;
import io.github.mojolowjo.entropybot.route.CellMoves;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RouteStore;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;
import io.github.mojolowjo.entropybot.route.SectionRecord.Quality;
import io.github.mojolowjo.entropybot.surface.SurfaceFamily;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** chunks-0.23.5: the companion's chunks in the route map (the merge rule, the inbox, the surface moves, the idle queue). */
class SharedChunksTest {

    static String arr(int v) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 256; i++) b.append(i > 0 ? "," : "").append(v);
        return b.append(']').toString();
    }

    static String chunk(int cx, int cz, long t, int g, int f, String src) {
        return "{\"v\":2,\"dim\":\"minecraft:overworld\",\"cx\":" + cx + ",\"cz\":" + cz + ",\"t\":" + t
                + (src == null ? "" : ",\"src\":\"" + src + "\"")
                + ",\"g\":" + arr(g) + ",\"f\":" + arr(f) + ",\"c\":" + arr(-1) + ",\"l\":" + arr(0)
                + ",\"u\":" + arr(g - 3) + ",\"g2\":" + arr(-1) + ",\"f2\":" + arr(0) + ",\"u2\":" + arr(-1) + "}";
    }

    // ---- the merge rule ----

    @Test
    void qualityOrder() {
        boolean[] none = new boolean[9], all = new boolean[9], centre = new boolean[9];
        java.util.Arrays.fill(all, true);
        centre[4] = true;
        assertEquals(Quality.LIVE, RouteRules.quality(all, false, true));
        assertEquals(Quality.COARSE, RouteRules.quality(centre, false, true), "the bot's own blocks beat the companion");
        assertEquals(Quality.SURFACE, RouteRules.quality(none, true, true), "the owner's recent scan beats Baritone's old cache");
        assertEquals(Quality.COARSE, RouteRules.quality(none, true, false));
        assertNull(RouteRules.quality(none, false, false));
    }

    @Test
    void newerWins() {
        assertTrue(RouteRules.rebuild(null, 0, false, Quality.SURFACE, 5));
        assertTrue(RouteRules.rebuild(Quality.LIVE, 100, true, Quality.SURFACE, 5), "stale always");
        assertTrue(RouteRules.rebuild(Quality.SURFACE, 100, false, Quality.LIVE, 0), "live beats surface");
        assertTrue(RouteRules.rebuild(Quality.SURFACE, 100, false, Quality.COARSE, 0), "the bot's blocks beat surface");
        assertFalse(RouteRules.rebuild(Quality.LIVE, 100, false, Quality.COARSE, 0));
        assertFalse(RouteRules.rebuild(Quality.SURFACE, 100, false, Quality.SURFACE, 100), "same scan: kept");
        assertTrue(RouteRules.rebuild(Quality.SURFACE, 100, false, Quality.SURFACE, 101), "newer scan wins");
        assertFalse(RouteRules.rebuild(Quality.LIVE, 100, false, Quality.SURFACE, 50), "an older scan never replaces a live box");
        assertTrue(RouteRules.rebuild(Quality.LIVE, 100, false, Quality.SURFACE, 150), "a newer scan does (newer wins)");
        assertFalse(RouteRules.rebuild(Quality.LIVE, 100, false, null, 0));
    }

    // ---- the chunk file and the moves ----

    @Test
    void parseKeepsTheBand() {
        SurfaceChunk s = SurfaceChunk.parse(chunk(3, -2, 77, 64, SurfaceFamily.GRASS, "companion"));
        assertNotNull(s);
        assertTrue(s.companion());
        assertEquals(77, s.t());
        assertEquals(65, s.minFeet());
        assertTrue(s.covers(64));        // the box y 64..79
        assertFalse(s.covers(48));       // 48..63: under the ground block (64)
        assertTrue(s.covers(80));        // 80..95: one above the feet band (65)
        assertFalse(s.covers(96));
        assertFalse(SurfaceChunk.parse(chunk(0, 0, 1, 64, 1, null)).companion(), "no src: the bot's own");
        assertNull(SurfaceChunk.parse("{\"v\":1}"));
        assertNull(SurfaceChunk.parse("garbage"));
    }

    @Test
    void movesOnFlatGroundStepsAndWater() {
        Map<Long, SurfaceChunk> m = new HashMap<>();
        m.put(SurfaceInbox.key(0, 0), SurfaceChunk.parse(chunk(0, 0, 1, 64, SurfaceFamily.GRASS, "companion")));
        m.put(SurfaceInbox.key(1, 0), SurfaceChunk.parse(chunk(1, 0, 1, 65, SurfaceFamily.GRASS, "companion")));
        m.put(SurfaceInbox.key(0, 1), SurfaceChunk.parse(chunk(0, 1, 1, 62, SurfaceFamily.WATER, "companion")));
        m.put(SurfaceInbox.key(-1, 0), SurfaceChunk.parse(chunk(-1, 0, 1, 64, SurfaceFamily.LAVA, "companion")));
        SurfaceCellMoves mv = new SurfaceCellMoves((cx, cz) -> m.get(SurfaceInbox.key(cx, cz)));
        assertTrue(mv.standable(5, 65, 5));
        assertFalse(mv.standable(5, 66, 5));
        assertFalse(mv.standable(-3, 65, 5), "lava");
        assertFalse(mv.standable(5, 65, 40), "unknown chunk");
        List<double[]> got = new ArrayList<>();
        mv.forEachMove(5, 65, 5, (x, y, z, t) -> got.add(new double[] {x, y, z, t}));
        assertEquals(8, got.size(), "4 sides and 4 diagonals on flat ground");
        got.clear();
        mv.forEachMove(15, 65, 5, (x, y, z, t) -> got.add(new double[] {x, y, z, t}));
        assertTrue(got.stream().anyMatch(d -> d[0] == 16 && d[1] == 66 && d[3] == SurfaceCellMoves.SPRINT + SurfaceCellMoves.JUMP), "a step up into the next chunk");
        got.clear();
        mv.forEachMove(5, 65, 15, (x, y, z, t) -> got.add(new double[] {x, y, z, t}));
        assertTrue(got.stream().anyMatch(d -> d[2] == 16 && d[1] == 63 && d[3] == SurfaceCellMoves.WATER + 2 * SurfaceCellMoves.FALL_PER_BLOCK), "down into the water");
        got.clear();
        mv.forEachMove(0, 65, 5, (x, y, z, t) -> got.add(new double[] {x, y, z, t}));
        assertTrue(got.stream().noneMatch(d -> d[0] == -1), "never onto lava");
    }

    // ---- the inbox ----

    @Test
    void inboxTakesCompanionChunksNewerWins(@TempDir Path dir) throws Exception {
        SurfaceInbox in = new SurfaceInbox(dir);
        Files.writeString(dir.resolve("1.2.json"), chunk(1, 2, 100, 64, 1, "companion"));
        Files.writeString(dir.resolve("3.3.json"), chunk(3, 3, 100, 64, 1, null));       // the bot's own: skipped
        Files.writeString(dir.resolve("4.4.json"), "half a file");
        in.poll(1);
        assertEquals(1, in.size());
        assertNotNull(in.chunk(1, 2));
        assertNull(in.chunk(3, 3));
        assertEquals(1, in.bad.get());
        SurfaceInbox.Arrival a = in.nextArrival();
        assertEquals(new SurfaceInbox.Arrival(1, 2, 100), a);
        assertNull(in.nextArrival());
        in.poll(2);
        assertNull(in.nextArrival(), "unchanged file: no arrival");
        Path f = dir.resolve("1.2.json");
        Files.writeString(f, chunk(1, 2, 90, 64, 1, "companion"));
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        in.poll(3);
        assertNull(in.nextArrival(), "an older scan is ignored");
        Files.writeString(f, chunk(1, 2, 200, 64, 1, "companion"));
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 10000));
        in.poll(4);
        assertEquals(200, in.nextArrival().t());
        Files.writeString(f, chunk(1, 2, 300, 64, 1, null));                              // the bot loaded it
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 15000));
        in.poll(5);
        assertNull(in.chunk(1, 2), "the bot's own file replaces the companion's");
        Files.writeString(dir.resolve("7.7.json"), chunk(7, 7, 100, 64, 1, "companion"));
        in.poll(6);
        Files.delete(dir.resolve("7.7.json"));
        in.poll(7);
        assertNull(in.chunk(7, 7), "a deleted file is forgotten");
        assertTrue(in.line(8).startsWith("companion chunks 0 held"));
    }

    // ---- the idle queue: arrivals become SURFACE builds with the surface moves ----

    final RouteFakes.Store store = new RouteFakes.Store();
    final RouteFakes.Queue queue = new RouteFakes.Queue();
    final RouteCounters counters = new RouteCounters();
    final RouteLog log = RouteLog.of(s -> {});
    RoutePool pool;

    @AfterEach
    void stop() {
        if (pool != null) assertTrue(pool.stop(2000));
    }

    @Test
    void arrivalsQueueSurfaceBoxesAndBuildThemWithTheSurfaceMoves() throws Exception {
        CellMoves surface = new RouteFakes.Flat(64);
        List<BuildInput> inputs = new CopyOnWriteArrayList<>();
        RouteScheduler.BoxWork work = new RouteScheduler.BoxWork() {
            public boolean build(BuildInput in, RouteStore st, RouteCounters c, RouteLog l) {
                inputs.add(in);
                st.put(new SectionRecord(in.key(), in.quality(), in.builtAt(), 0, List.of(), new char[0]));
                return true;
            }

            public long walkHash(SectionKey key, CellMoves moves) { return 0; }

            public boolean rehash(RouteStore st, SectionKey key, long walkHash) { return false; }
        };
        SectionKey ground = new SectionKey(0, 2, 4, 2), sky = new SectionKey(0, 2, 10, 2);
        long[] scanT = {1000};
        RouteScheduler.BuildEnv env = new RouteScheduler.BuildEnv() {
            public String pauseReason() { return null; }
            public boolean idle() { return true; }
            public boolean lagging() { return false; }
            public AreaBoxes areas() { return RouteFakes.everywhere(); }
            public Quality terrain(SectionKey k) { return k.equals(ground) ? Quality.SURFACE : null; }
            public RouteScheduler.WorkerMoves newMoves() { return new RouteScheduler.WorkerMoves(new RouteFakes.Flat(64), true); }
            public long surfaceTime(SectionKey k) { return scanT[0]; }
            public CellMoves surfaceMoves() { return surface; }
        };
        pool = new RoutePool(1, "test-route", (w, t) -> counters.workerException(w, t, log));
        RouteScheduler s = new RouteScheduler(store, queue, counters, log, pool, work, () -> 0);
        assertEquals(1, s.surfaceArrived(env, 0, 2, 2, 1000), "only the box the ground band crosses");
        assertTrue(queue.lists.get(Priority.REST).contains(ground));
        assertFalse(queue.lists.get(Priority.REST).contains(sky));
        s.tick(env);
        RoutePoolTest.waitFree(pool, pool.size());
        assertEquals(1, inputs.size());
        assertEquals(Quality.SURFACE, inputs.get(0).quality());
        assertSame(surface, inputs.get(0).moves());
        assertFalse(inputs.get(0).allowBreak());
        assertEquals(0, s.surfaceArrived(env, 0, 2, 2, 1000), "the same scan again: nothing to do");
        long built = store.get(ground).builtAt();
        assertEquals(1, s.surfaceArrived(env, 0, 2, 2, built + 1), "a newer scan: queued again");
        assertTrue(s.line().contains("companion chunks 3"));
    }
}
