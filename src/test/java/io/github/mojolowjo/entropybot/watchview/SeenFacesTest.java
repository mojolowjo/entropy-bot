package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SeenFacesTest {
    static final String OW = "minecraft:overworld";

    @Test
    void facesAddUpPerBlockWithTheBrightestLight() {
        SeenFaces s = new SeenFaces();
        long v0 = s.version();
        assertTrue(s.add(OW, 1, 2, 3, 1, 4));
        assertTrue(s.version() > v0);
        long v1 = s.version();
        assertFalse(s.add(OW, 1, 2, 3, 1, 2), "seen again, darker: no change");
        assertEquals(v1, s.version(), "the mesh is not rebuilt for it");
        assertTrue(s.add(OW, 1, 2, 3, 1, 9), "brighter: changed");
        assertTrue(s.add(OW, 1, 2, 3, 4, 0), "another side");
        SeenFaces.Entry e = s.get(OW, 1, 2, 3);
        assertEquals((1 << 1) | (1 << 4), e.mask());
        assertEquals(9, e.light());
        assertEquals(1, s.size());
        assertEquals(2, s.faces());
        assertFalse(s.add(OW, 1, 2, 3, 6, 0), "no such side");
        assertFalse(s.add(null, 1, 2, 3, 1, 0));
        assertNull(s.get("minecraft:the_nether", 1, 2, 3));
    }

    @Test
    void capsDropTheOldestFirst() {
        SeenFaces s = new SeenFaces(3, 5);
        s.add(OW, 0, 0, 0, 1, 15);
        s.add(OW, 1, 0, 0, 1, 15);
        s.add(OW, 2, 0, 0, 1, 15);
        s.add(OW, 0, 0, 0, 2, 15);          // seen again: newest now
        s.add(OW, 3, 0, 0, 1, 15);          // region full: (1,0,0) goes
        assertNull(s.get(OW, 1, 0, 0));
        assertNotNull(s.get(OW, 0, 0, 0));
        assertEquals(3, s.size());
        assertEquals(4, s.faces(), "faces of the dropped block are gone too");
        assertEquals(1, s.evicted());
        // the total cap across regions (another region at x 300)
        s.add(OW, 300, 0, 0, 1, 15);
        s.add(OW, 301, 0, 0, 1, 15);
        s.add(OW, 302, 0, 0, 1, 15);         // 6 > 5: the oldest anywhere, (2,0,0), goes
        assertEquals(5, s.size());
        assertNull(s.get(OW, 2, 0, 0));
        assertEquals(2, s.evicted());
    }

    @Test
    void defaultCapsAreTheAgreedOnes() {
        assertEquals(60_000, SeenFaces.MAX_PER_REGION);
        assertEquals(200_000, SeenFaces.MAX_TOTAL);
    }

    @Test
    void nearIsASphere() {
        SeenFaces s = new SeenFaces();
        s.add(OW, 0, 0, 0, 1, 15);
        s.add(OW, 10, 0, 0, 1, 15);
        s.add(OW, 300, 0, 0, 1, 15);
        assertEquals(1, s.near(OW, 0, 0, 0, 5).size());
        assertEquals(2, s.near(OW, 0, 0, 0, 10).size());
        assertEquals(1, s.near(OW, 299, 0, 0, 5).size(), "across a region border");
        assertTrue(s.near("minecraft:the_end", 0, 0, 0, 5).isEmpty());
    }

    @Test
    void fileRoundTripKeepsMasksLightAndAge() throws IOException {
        SeenFaces s = new SeenFaces(3, 100);
        s.add(OW, 0, 0, 0, 1, 3);
        s.add(OW, 1, 0, 0, 1, 7);
        s.add(OW, 0, 0, 0, 5, 3);           // (0,0,0) seen again: (1,0,0) is the oldest now
        s.add(OW, -5, 70, 9, 2, 15);        // another region (x -5)
        s.add("minecraft:the_nether", 1, 1, 1, 0, 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.write(out);
        assertEquals(4 + 4 + 4 + 2 * (2 + 4) + "minecraft:overworld".length() + "minecraft:the_nether".length() + 4 * 10, out.size(), "10 bytes a block");
        SeenFaces r = new SeenFaces(3, 100);
        assertEquals(4, r.read(new ByteArrayInputStream(out.toByteArray())));
        assertEquals(new SeenFaces.Entry(CellKey.of(0, 0, 0), (1 << 1) | (1 << 5), 3), r.get(OW, 0, 0, 0));
        assertEquals(7, r.get(OW, 1, 0, 0).light());
        assertEquals(15, r.get(OW, -5, 70, 9).light());
        assertEquals(5, r.faces());
        // the age order survives: after loading, the oldest of region 0 is still (1,0,0)
        r.add(OW, 2, 0, 0, 1, 1);
        r.add(OW, 3, 0, 0, 1, 1);           // 4 > 3 in region 0
        assertNull(r.get(OW, 1, 0, 0));
        assertNotNull(r.get(OW, 0, 0, 0));
    }

    @Test
    void aBrokenFileThrows() throws IOException {
        assertThrows(IOException.class, () -> new SeenFaces().read(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6, 7, 8})));
        SeenFaces s = new SeenFaces();
        s.add(OW, 0, 0, 0, 1, 3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.write(out);
        byte[] b = out.toByteArray();
        assertThrows(IOException.class, () -> new SeenFaces().read(new ByteArrayInputStream(Arrays.copyOf(b, b.length - 3))), "cut short");
        byte[] bad = b.clone();
        bad[bad.length - 1] = 0;                                  // mask 0
        bad[bad.length - 2] = 0;
        assertThrows(IOException.class, () -> new SeenFaces().read(new ByteArrayInputStream(bad)), "a block with no face");
    }
}
