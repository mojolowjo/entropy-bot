package io.github.mojolowjo.entropybot.recorder;

import io.github.mojolowjo.entropybot.recorder.Recorder.Change;
import io.github.mojolowjo.entropybot.recorder.Recorder.Slice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Review fixes: "exact" only while every change of a chunk was recorded; rate limits; quick reads; the parser. */
class ChunkWatchTest {
    static final String OW = "minecraft:overworld";
    static final long T0 = 1_759_600_000_000L, REBASE = 30 * 60_000L;

    @TempDir
    Path dir;

    final Object chunk = new Object();
    AtomicLong now = new AtomicLong(T0);
    RecStore store;
    ChunkWatch w;

    /** Chunk 0 0 tracked with a baseline written at T0 and watched at T0 + 1 s from the bot's chunk 0 0. */
    void watched() {
        store = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        w = new ChunkWatch(store);
        w.dim(OW);
        assertTrue(w.needsBaseline(0, 0, chunk, T0, REBASE));
        w.taking(0, 0, chunk, T0);
        store.writeBase(BlocksAtTest.base(0, 0, T0, new String[0][]));
        w.written(OW, 0, 0, T0);
        w.watch(0, 0, 4, k -> k == 0 ? chunk : null, T0 + 1000);
        assertArrayEquals(new long[]{T0, T0 + 1000}, store.coverage(OW, 0, 0));
        assertEquals("exact", slice(T0 + 500).note());
    }

    Slice slice(long at) { return store.blocksAt(OW, 0, 0, 0, 3, 31, 3, at); }

    @Test
    void aChunkThatLeavesTheRangeIsNoLongerExactAndGetsAFreshBaselineBack() {
        watched();
        // the bot walks off: chunk 0 0 is now range + 1 away; its changes would be dropped from here on
        w.watch(5, 0, 4, k -> chunk, T0 + 2000);
        assertNull(store.coverage(OW, 0, 0), "coverage ends, it is not 'seen' any more");
        assertFalse(w.tracked(0, 0));
        store.addChange(new Change(T0 + 3000, OW, 1, 5, 1, "minecraft:stone", "minecraft:air", false)); // say it was heard anyway
        now.set(T0 + 10_000);
        assertNotEquals("exact", slice(T0 + 5000).note());
        // walking back: the same chunk object, younger than the re-take time, still needs a fresh baseline
        assertTrue(w.needsBaseline(0, 0, chunk, T0 + 10_000, REBASE));
    }

    @Test
    void aMissedChangeEndsTheCoverage() {
        watched();
        w.missed(0, 0);
        assertNull(store.coverage(OW, 0, 0));
        assertTrue(w.needsBaseline(0, 0, chunk, T0 + 2000, REBASE));
        w.watch(0, 0, 4, k -> chunk, T0 + 3000);
        assertNull(store.coverage(OW, 0, 0), "watching again doesn't bring it back without a new baseline");
        assertNotEquals("exact", slice(T0 + 2500).note());
    }

    @Test
    void offOrARangeChangeStartsEveryChunkOver() {
        watched();
        w.reset();                                  // recording off, or the range changed
        assertNull(store.coverage(OW, 0, 0));
        assertEquals(0, w.size());
        w.watch(0, 0, 4, k -> chunk, T0 + 60_000);  // on again: the off time must not count as watched
        assertNull(store.coverage(OW, 0, 0));
        assertNotEquals("exact", slice(T0 + 30_000).note());
        assertTrue(w.needsBaseline(0, 0, chunk, T0 + 60_000, REBASE));
    }

    @Test
    void aBaselineWrittenAfterAMissDoesNotStartCoverage() {
        store = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        w = new ChunkWatch(store);
        w.dim(OW);
        w.taking(0, 0, chunk, T0);
        w.missed(0, 0);                             // a change dropped while the baseline was on its way to disk
        store.writeBase(BlocksAtTest.base(0, 0, T0, new String[0][]));
        w.written(OW, 0, 0, T0);
        w.watch(0, 0, 4, k -> chunk, T0 + 1000);
        assertNull(store.coverage(OW, 0, 0));
    }

    @Test
    void aReloadedChunkIsDroppedAndAReTakeKeepsTheStart() {
        watched();
        Object reloaded = new Object();
        w.watch(0, 0, 4, k -> reloaded, T0 + 2000);
        assertNull(store.coverage(OW, 0, 0), "another chunk object: unloaded in between");

        watched();
        long later = T0 + REBASE + 1;
        assertTrue(w.needsBaseline(0, 0, chunk, later, REBASE), "re-taken after 30 min");
        w.taking(0, 0, chunk, later);
        assertArrayEquals(new long[]{T0, T0 + 1000}, store.coverage(OW, 0, 0), "a re-take keeps the old coverage meanwhile");
        store.writeBase(BlocksAtTest.base(0, 0, later, new String[0][]));
        w.written(OW, 0, 0, later);
        w.watch(0, 0, 4, k -> chunk, later + 1000);
        assertArrayEquals(new long[]{T0, later + 1000}, store.coverage(OW, 0, 0));
        now.set(later + 1000);
        assertEquals("exact", slice(T0 + 500).note(), "rewound across a watched stretch");
    }

    @Test
    void aBaselineNewerThanTheCoverageIsNeverExact() {
        watched();
        store.writeBase(BlocksAtTest.base(0, 0, T0 + 50_000, new String[0][]));   // taken after the watch ended
        assertNotEquals("exact", slice(T0 + 500).note());
    }

    @Test
    void theLimiterStopsAFlappingBlockAndAFlood() {
        ChangeFilter.Limiter l = new ChangeFilter.Limiter(8, 10_000, 2000);
        int ok = 0;
        for (int i = 0; i < 50; i++) if (l.allow(42L, T0 + i * 10)) ok++;
        assertEquals(8, ok, "8 per block per 10 s");
        assertTrue(l.allow(42L, T0 + 10_001), "a new window");
        assertTrue(l.allow(43L, T0 + 500), "other blocks are fine");
        ChangeFilter.Limiter flood = new ChangeFilter.Limiter(8, 10_000, 2000);
        ok = 0;
        for (int i = 0; i < 5000; i++) if (flood.allow(i, T0)) ok++;
        assertEquals(2000, ok, "2000 a second in all");
        assertEquals(3000, flood.dropped());
        assertTrue(flood.allow(9999L, T0 + 1000), "the next second");
        assertTrue(ChangeFilter.inRange(0, 0, 4, -4, 4));
        assertFalse(ChangeFilter.inRange(0, 0, 5, 0, 4));
    }

    @Test
    void quickReadsSkipOldFilesAndSaySo() throws Exception {
        store = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        store.writeBase(BlocksAtTest.base(0, 0, T0, new String[0][]));
        // an older day file, big, and the change that matters in it
        StringBuilder big = new StringBuilder();
        big.append(RecStore.changeLine(new Change(T0 - 86_400_000L, OW, 1, 5, 1, "minecraft:stone", "minecraft:air", false))).append('\n');
        for (int i = 0; i < 2000; i++) big.append(RecStore.changeLine(new Change(T0 - 86_400_000L + i, OW, 900, 5, 900, "minecraft:stone", "minecraft:air", false))).append('\n');
        Files.writeString(dir.resolve("changes-" + store.day(T0 - 86_400_000L) + ".log"), big.toString());
        RecStore fresh = new RecStore(dir, () -> T0 + 1000, RecStore.DEFAULT_CAP);
        assertEquals(1, fresh.changes(OW, 1, 5, 1, 0, 0, 10).size(), "a background read goes through it");
        fresh.quickReadsWhen(() -> true);
        fresh.quickBytes = 1000;
        assertTrue(fresh.changes(OW, 1, 5, 1, 0, 0, 10).isEmpty(), "the game thread skips the big old file");
        Slice s = fresh.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 - 86_400_000L - 5);
        assertNotNull(s);
        assertTrue(s.note().endsWith(RecStore.TOO_OLD), s.note());
    }

    @Test
    void hugeNumbersAreErrorsNotExceptions() {
        for (String s : List.of("range 99999999999", "max for 99999999999m", "keep 99999999999", "trail 99999999999s",
                "trail 1.00000000001s", "snapshot 99999999999")) {
            assertEquals(RecorderCommand.Kind.ERROR, RecorderCommand.parse(s).kind(), s);
        }
    }

    @Test
    void incidentFilesAreWrittenWhole() throws Exception {
        store = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        assertNotNull(store.writeIncident(T0, "x", "reason: x\n"));
        try (Stream<Path> s = Files.list(dir.resolve("incidents"))) {
            assertEquals(List.of("inc-" + T0 + ".txt.gz"), s.map(p -> p.getFileName().toString()).toList(), "no .tmp left");
        }
    }
}
