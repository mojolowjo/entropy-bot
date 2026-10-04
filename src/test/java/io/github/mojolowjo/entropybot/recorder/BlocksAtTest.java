package io.github.mojolowjo.entropybot.recorder;

import io.github.mojolowjo.entropybot.recorder.Recorder.Change;
import io.github.mojolowjo.entropybot.recorder.Recorder.Slice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** blocksAt: "the box as it was at time T" from a baseline plus the changes heard, forward and rewound. */
class BlocksAtTest {
    static final String OW = "minecraft:overworld", STONE = "minecraft:stone", AIR = "minecraft:air";
    static final long T0 = 1_759_600_000_000L;

    @TempDir
    Path dir;

    /** Chunk cx cz, minY 0, two sections: y 0..15 stone, 16..31 air, with some cells set. */
    static ChunkBase base(int cx, int cz, long at, String[][] sets) {
        String[][] pal = new String[2][];
        short[][] idx = new short[2][];
        String[] s0 = new String[4096], s1 = new String[4096];
        Arrays.fill(s0, STONE);
        Arrays.fill(s1, AIR);
        for (String[] set : sets) {
            int x = Integer.parseInt(set[0]) & 15, y = Integer.parseInt(set[1]), z = Integer.parseInt(set[2]) & 15;
            (y < 16 ? s0 : s1)[((y & 15) * 16 + z) * 16 + x] = set[3];
        }
        ChunkBase.section(s0, pal, idx, 0);
        ChunkBase.section(s1, pal, idx, 1);
        return new ChunkBase(OW, cx, cz, 0, at, pal, idx);
    }

    static String at(Slice s, int x, int y, int z) {
        int dx = s.x2() - s.x1() + 1, dz = s.z2() - s.z1() + 1;
        return s.ids()[((y - s.y1()) * dz + (z - s.z1())) * dx + (x - s.x1())];
    }

    RecStore store(AtomicLong now) {
        RecStore s = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        s.writeBase(base(0, 0, T0, new String[0][]));
        s.covered(OW, 0, 0, T0, T0 + 100_000);
        s.addChange(new Change(T0 + 1000, OW, 1, 5, 1, STONE, AIR, true));
        s.addChange(new Change(T0 + 2000, OW, 1, 5, 1, AIR, "minecraft:cobblestone", false));
        s.addChange(new Change(T0 + 3000, OW, 2, 20, 2, AIR, "minecraft:wall_torch[facing=north]", true));
        return s;
    }

    @Test
    void forwardFromTheBaseline() {
        AtomicLong now = new AtomicLong(T0 + 50_000);
        RecStore s = store(now);
        Slice a = s.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 + 1500);
        assertEquals("exact", a.note());
        assertEquals(AIR, at(a, 1, 5, 1), "the bot dug it");
        assertEquals(STONE, at(a, 2, 5, 2));
        assertEquals(AIR, at(a, 2, 20, 2));
        Slice b = s.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 + 2500);
        assertEquals("minecraft:cobblestone", at(b, 1, 5, 1));
        Slice c = s.blocksAt(OW, 3, 31, 3, 0, 0, 0, T0 + 5000);
        assertEquals("minecraft:wall_torch", at(c, 2, 20, 2), "states are dropped in a slice");
        Slice before = s.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 + 500);
        assertEquals(STONE, at(before, 1, 5, 1));
    }

    @Test
    void afterTheChunkWasLeftTheNoteSaysSo() {
        AtomicLong now = new AtomicLong(T0 + 500_000);
        RecStore s = store(now);
        Slice late = s.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 + 400_000);
        assertNotEquals("exact", late.note());
        assertTrue(late.note().startsWith("from the baseline of"), late.note());
        assertTrue(late.note().contains("while the bot was away is unknown"), late.note());
        assertEquals("minecraft:cobblestone", at(late, 1, 5, 1), "still the best guess");
    }

    @Test
    void rewoundFromANewerBaseline() {
        AtomicLong now = new AtomicLong(T0 + 50_000);
        RecStore s = store(now);
        s.writeBase(base(0, 0, T0 + 10_000, new String[][]{{"1", "5", "1", "minecraft:cobblestone"}, {"2", "20", "2", "minecraft:wall_torch"}}));
        Slice a = s.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 + 1500);
        assertEquals("exact", a.note(), "the chunk was watched since T0");
        assertEquals(AIR, at(a, 1, 5, 1), "the cobblestone undone");
        assertEquals(AIR, at(a, 2, 20, 2), "the torch undone");
        Slice b = s.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 + 500);
        assertEquals(STONE, at(b, 1, 5, 1), "and the dig undone too");
        s.forgetCoverage(OW, 0, 0);
        Slice c = s.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 + 500);
        assertTrue(c.note().contains("rewound"), c.note());
    }

    @Test
    void unknownCellsBigBoxesAndNothingKnown() {
        AtomicLong now = new AtomicLong(T0 + 50_000);
        RecStore s = store(now);
        assertNull(s.blocksAt(OW, 0, 0, 0, 15, 31, 15, T0), "8192 cells is over 4096");
        assertNull(s.blocksAt(OW, 100, 0, 100, 101, 1, 101, T0), "no baseline, no changes");
        assertNull(s.blocksAt("minecraft:the_nether", 0, 0, 0, 1, 1, 1, T0));
        Slice half = s.blocksAt(OW, 14, 5, 0, 17, 5, 0, T0 + 1500);
        assertEquals(STONE, at(half, 15, 5, 0));
        assertNull(at(half, 16, 5, 0), "chunk 1 has no baseline");
        assertTrue(half.note().endsWith("2 cells unknown"), half.note());
        // a chunk without a baseline still shows what a heard change set there
        s.addChange(new Change(T0 + 4000, OW, 16, 5, 0, STONE, "minecraft:dirt", false));
        Slice later = s.blocksAt(OW, 14, 5, 0, 17, 5, 0, T0 + 5000);
        assertEquals("minecraft:dirt", at(later, 16, 5, 0));
        assertTrue(later.note().endsWith("1 cells unknown"), later.note());
    }

    @Test
    void baselinesSurviveTheDiskAndOtherStores() {
        AtomicLong now = new AtomicLong(T0 + 50_000);
        store(now).flush();
        RecStore again = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        again.flush();
        Slice a = again.blocksAt(OW, 0, 0, 0, 3, 31, 3, T0 + 2500);
        assertEquals("minecraft:cobblestone", at(a, 1, 5, 1), "baseline from disk, changes from the day file");
        assertNotEquals("exact", a.note(), "a new session never watched the chunk");
    }

    @Test
    void chunkBaseRoundTrip() throws Exception {
        ChunkBase b = base(-3, 7, T0, new String[][]{{"-48", "3", "112", "minecraft:diamond_ore"}, {"-33", "17", "127", "mod:thing"}});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.write(out);
        ChunkBase r = ChunkBase.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(OW, r.dim);
        assertEquals(-3, r.cx);
        assertEquals(7, r.cz);
        assertEquals(T0, r.takenMs);
        assertEquals("minecraft:diamond_ore", r.id(-48, 3, 112));
        assertEquals("mod:thing", r.id(-33, 17, 127));
        assertEquals(STONE, r.id(-40, 0, 120));
        assertEquals(AIR, r.id(-40, 31, 120));
        assertNull(r.id(-40, 32, 120), "above the chunk's sections");
        assertNull(r.id(0, 5, 120), "another chunk");

        // a section with more than 256 ids uses two bytes per cell
        String[] many = new String[4096];
        for (int i = 0; i < 4096; i++) many[i] = "mod:b" + (i % 300);
        String[][] pal = new String[1][];
        short[][] idx = new short[1][];
        ChunkBase.section(many, pal, idx, 0);
        ChunkBase big = new ChunkBase(OW, 0, 0, -64, T0, pal, idx);
        out.reset();
        big.write(out);
        ChunkBase back = ChunkBase.read(new ByteArrayInputStream(out.toByteArray()));
        for (int i = 0; i < 4096; i += 97) {
            int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) - 64;
            assertEquals("mod:b" + (i % 300), back.id(x, y, z));
        }
    }
}
