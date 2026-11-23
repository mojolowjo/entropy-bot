package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-planner-stick, review round 3 (rule 15): a ravine across a chunk border was indexed by load order - the chunk read
 * first got "not known" for the neighbour's wall, kept its floor as open ground, and was never read again. The chunk
 * waits on the neighbour ({@link RimWaits}) and its rim is read again when the neighbour arrives; both load orders end
 * on the same ground. The harness below is ChunkPass.indexed / caveRuns / rimAgain without the game's chunks.
 */
class RimWaitsTest {

    /** A north-south ravine, floor at world x -1..2 (y 40), flat ground at 64: chunk 0 holds x 0..2, chunk -1 holds x -1. */
    private static int ground(int wx) { return wx >= -1 && wx <= 2 ? 40 : 64; }

    private static boolean air(int x, int y, int z) { return y >= ground(x); }

    /** The pass in small: the loaded chunks, the index, the waits. */
    private static final class Pass {
        final Set<Long> loaded = new HashSet<>();
        final CaveIndex idx = new CaveIndex();
        final RimWaits waits = new RimWaits();

        CaveIndex.ChunkRuns indexed(int cx, int cz) {
            int[] g = new int[256];
            for (int c = 0; c < 256; c++) g[c] = ground((cx << 4) + (c & 15));
            Set<Long> missing = new HashSet<>();
            int[] r = CaveIndex.rimmed(g, 10, 8, (lx, lz) -> {
                int wx = (cx << 4) + lx, wz = (cz << 4) + lz;
                long nk = CaveIndex.chunkKey(wx >> 4, wz >> 4);
                if (!loaded.contains(nk)) { missing.add(nk); return CaveIndex.NO_GROUND; }
                return ground(wx);
            });
            waits.waiting(CaveIndex.chunkKey(cx, cz), missing);
            return CaveIndex.runs(cx, cz, r, -64, RimWaitsTest::air);
        }

        /** A chunk loads and is read (ChunkPass.caveRuns), then the due rim re-reads run (ChunkPass.rimAgain); the chunks replaced. */
        List<Long> load(int cx, int cz) {
            loaded.add(CaveIndex.chunkKey(cx, cz));
            waits.arrived(CaveIndex.chunkKey(cx, cz));
            if (!idx.has(cx, cz)) idx.put(indexed(cx, cz));
            List<Long> replaced = new ArrayList<>();
            for (long k : waits.take(8)) {
                int x = (int) (k >> 32), z = (int) k;
                int[] was = idx.surfaceOf(x, z);
                if (was == null) continue;
                CaveIndex.ChunkRuns fresh = indexed(x, z);
                waits.reread++;
                if (java.util.Arrays.equals(was, fresh.surface())) continue;
                idx.replace(fresh);
                waits.changed++;
                replaced.add(k);
            }
            return replaced;
        }
    }

    @Test
    void aChunkReadBeforeItsNeighbour_isReadAgainWhenTheNeighbourArrives_bothOrdersEndTheSame() {
        Pass east = new Pass();                               // chunk 0 first: its west wall is across the border, not known
        assertEquals(List.of(), east.load(0, 0));
        assertEquals(40, east.idx.groundAt(1, 5), "the neighbour not loaded: the floor is open ground for now (not known, never a wall made up)");
        assertTrue(east.idx.volumeAt(1, 50, 5) < 0);
        assertEquals(1, east.waits.waitingChunks(), "chunk 0 waits on its neighbours");
        assertEquals(List.of(CaveIndex.chunkKey(0, 0)), east.load(-1, 0), "the west neighbour arrives: chunk 0 is read again and its ground changes");
        assertEquals(64, east.idx.groundAt(1, 5), "the floor takes the rim");
        assertEquals(64, east.idx.groundAt(-1, 5), "the neighbour's own floor column: its east wall is in chunk 0, loaded");
        assertEquals(Boolean.TRUE, east.idx.airAt(1, 50, 5), "the ravine's air is cave air");
        assertTrue(east.idx.volumeAt(1, 50, 5) >= 0 && east.idx.volumeAt(1, 50, 5) == east.idx.volumeAt(-1, 50, 5), "one volume across the border");

        Pass west = new Pass();                               // the other order
        west.load(-1, 0);
        assertEquals(40, west.idx.groundAt(-1, 5), "chunk -1 first: its east wall is not known");
        assertEquals(List.of(CaveIndex.chunkKey(-1, 0)), west.load(0, 0));
        for (int x = -16; x < 16; x++)
            assertEquals(east.idx.groundAt(x, 5), west.idx.groundAt(x, 5), "the same ground at x " + x + " whichever chunk was read first");
        assertEquals(1, east.waits.changed);
        assertEquals(1, west.waits.changed);
    }

    @Test
    void theReReadIsBounded_onePerNeighbourThatArrives_noneForAnUnloadedChunk() {
        Pass p = new Pass();
        p.load(0, 0);
        p.load(-1, 0);
        int reread = p.waits.reread;
        assertEquals(List.of(), p.load(-1, 0), "the same neighbour read again (a resend): nothing waits on it any more");
        assertEquals(reread, p.waits.reread);
        // a neighbour with plain ground: the chunk is read again once, nothing changes, the index is not touched
        int before = p.waits.changed;
        assertEquals(List.of(), p.load(1, 0));
        assertEquals(before, p.waits.changed, "plain ground across that border: no change");
        assertTrue(p.waits.reread > reread, "but it was read");

        RimWaits w = new RimWaits();
        long c = CaveIndex.chunkKey(4, 4), n = CaveIndex.chunkKey(5, 4);
        w.waiting(c, List.of(n));
        w.gone(c);
        w.arrived(n);
        assertEquals(List.of(), w.take(8), "a chunk that unloaded waits on nothing: its next load is a first read");
        w.waiting(c, List.of(n));
        w.arrived(n);
        w.arrived(n);
        assertEquals(List.of(c), w.take(8));
        assertEquals(List.of(), w.take(8), "due once");
        w.waiting(c, List.of());
        assertEquals(0, w.waitingChunks(), "every neighbour loaded: no wait");
        assertTrue(w.status().contains("0 chunk(s) wait"), w.status());
    }
}
