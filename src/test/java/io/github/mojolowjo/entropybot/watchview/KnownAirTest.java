package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class KnownAirTest {
    static final String OW = "minecraft:overworld";

    @Test
    void cellKeysMatchMinecraftsLayout() {
        int[][] pts = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 319, -29_999_999}, {-30_000_000, -2048, 12345}, {5, 2047, -7}};
        for (int[] p : pts) {
            long k = CellKey.of(p[0], p[1], p[2]);
            assertEquals(p[0], CellKey.x(k));
            assertEquals(p[1], CellKey.y(k));
            assertEquals(p[2], CellKey.z(k));
        }
        // BlockPos.asLong(1, 2, 3) = 1 << 38 | 3 << 12 | 2
        assertEquals((1L << 38) | (3L << 12) | 2L, CellKey.of(1, 2, 3));
        assertEquals(CellKey.of(2, 2, 2), CellKey.offset(CellKey.of(1, 2, 3), 1, 0, -1));
    }

    @Test
    void addContainsAndVersion() {
        KnownAir a = new KnownAir();
        long v0 = a.version();
        assertTrue(a.add(OW, 1, 2, 3));
        assertTrue(a.version() > v0);
        long v1 = a.version();
        assertFalse(a.add(OW, 1, 2, 3), "known already");
        assertEquals(v1, a.version(), "a cell seen again does not change the set");
        assertTrue(a.contains(OW, 1, 2, 3));
        assertFalse(a.contains("minecraft:the_nether", 1, 2, 3), "per dimension");
        assertFalse(a.contains(OW, 1, 3, 3));
        assertEquals(1, a.size());
        assertFalse(a.add(null, 0, 0, 0));
    }

    @Test
    void aRegionPastItsCapDropsItsOldestCell() {
        KnownAir a = new KnownAir(3, 100);
        a.add(OW, 0, 0, 0);
        a.add(OW, 1, 0, 0);
        a.add(OW, 2, 0, 0);
        a.add(OW, 0, 0, 0);                                 // seen again: counts as new
        a.add(OW, 3, 0, 0);                                 // over 3: the oldest (1 0 0) goes
        assertFalse(a.contains(OW, 1, 0, 0));
        assertTrue(a.contains(OW, 0, 0, 0));
        assertTrue(a.contains(OW, 3, 0, 0));
        assertEquals(3, a.size());
        assertEquals(1, a.evicted());
        a.add(OW, 1000, 0, 0);                              // another region: its own cap
        assertEquals(4, a.size());
    }

    @Test
    void theTotalCapDropsTheOldestAnywhere() {
        KnownAir a = new KnownAir(100, 3);
        a.add(OW, 0, 0, 0);
        a.add(OW, 1000, 0, 0);
        a.add("minecraft:the_nether", 0, 0, 0);
        a.add(OW, 2000, 0, 0);
        assertFalse(a.contains(OW, 0, 0, 0), "the oldest of all");
        assertEquals(3, a.size());
        a.add(OW, 3000, 0, 0);
        assertFalse(a.contains(OW, 1000, 0, 0));
        assertTrue(a.contains("minecraft:the_nether", 0, 0, 0));
    }

    @Test
    void nearIsASphere() {
        KnownAir a = new KnownAir();
        a.add(OW, 0, 0, 0);
        a.add(OW, 5, 0, 0);
        a.add(OW, 4, 4, 0);                                 // 5.66 away
        a.add(OW, 300, 0, 0);                               // another region
        a.add(OW, -3, 0, 0);                                // region -1
        assertEquals(3, a.near(OW, 0, 0, 0, 5).length, "across a region border");
        assertEquals(1, a.near(OW, 300, 0, 0, 5).length);
        assertEquals(1, a.near(OW, -6, 0, 0, 3).length);
        assertEquals(0, a.near("minecraft:the_end", 0, 0, 0, 500).length);
    }

    @Test
    void writeAndReadKeepTheCellsAndTheirOrder() throws IOException {
        KnownAir a = new KnownAir(3, 100);
        a.add(OW, 0, 0, 0);
        a.add(OW, 1, -60, 0);
        a.add("minecraft:the_nether", 7, 70, -7);
        a.add(OW, 0, 0, 0);                                 // now the newest
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        a.write(out);
        KnownAir b = new KnownAir(3, 100);
        assertEquals(3, b.read(new ByteArrayInputStream(out.toByteArray())));
        assertTrue(b.contains(OW, 1, -60, 0));
        assertTrue(b.contains("minecraft:the_nether", 7, 70, -7));
        b.add(OW, 2, 0, 0);
        b.add(OW, 3, 0, 0);                                 // the cap of 3: 1 -60 0 (the oldest) goes, not 0 0 0
        assertFalse(b.contains(OW, 1, -60, 0));
        assertTrue(b.contains(OW, 0, 0, 0));
    }

    @Test
    void aBrokenFileThrowsAndLeavesTheSetAlone() {
        KnownAir a = new KnownAir();
        a.add(OW, 1, 1, 1);
        assertThrows(IOException.class, () -> a.read(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6, 7, 8})));
        assertThrows(IOException.class, () -> a.read(new ByteArrayInputStream(new byte[]{0x45, 0x42, 0x4B, 0x41, 0, 0, 0, 1, 0})));
        assertTrue(a.contains(OW, 1, 1, 1));
    }
}
