package io.github.mojolowjo.entropybot.map;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.mojolowjo.entropybot.map.MapMath.*;
import static org.junit.jupiter.api.Assertions.*;

class MapMathTest {
    static final int GRASS = 0x7FB238, WATER = 0x4040FF; // MapColor.GRASS.col, MapColor.WATER.col

    // vanilla MapColor.calculateRGBColor, copied: 0xAABBGGRR for NativeImage
    static int vanillaAbgr(int col, int modifier) {
        int r = (col >> 16 & 0xFF) * modifier / 255, g = (col >> 8 & 0xFF) * modifier / 255, b = (col & 0xFF) * modifier / 255;
        return 0xFF000000 | b << 16 | g << 8 | r;
    }

    @Test
    void coloursAreVanillasInJavaAwtOrder() {
        assertEquals(0xFF6D9930, argb(GRASS, NORMAL), "grass at 220/255");
        assertEquals(0xFF7FB238, argb(GRASS, HIGH), "HIGH is the colour itself");
        for (int b = 0; b < 4; b++) {
            assertEquals(abgrToArgb(vanillaAbgr(GRASS, MODIFIER[b])), argb(GRASS, b));
            assertEquals(abgrToArgb(vanillaAbgr(WATER, MODIFIER[b])), argb(WATER, b));
        }
        assertEquals(0xFF332211, abgrToArgb(0xFF112233));
    }

    @Test
    void landIsBrighterUphillAndDarkerDownhillFromTheNorth() {
        // flat ground: NORMAL on both checkerboard squares
        assertEquals(NORMAL, land(64, 64, 0, 0));
        assertEquals(NORMAL, land(64, 64, 1, 0));
        // two up from the north neighbour: HIGH; two down: LOW
        assertEquals(HIGH, land(66, 64, 0, 0));
        assertEquals(LOW, land(62, 64, 0, 0));
        // one step: 0.8 -/+ the 0.2 dither lands just past 0.6 in doubles, as in vanilla, so a
        // single step already shows on both checkerboard squares
        assertEquals(HIGH, land(65, 64, 0, 0));
        assertEquals(HIGH, land(65, 64, 1, 0));
        assertEquals(LOW, land(63, 64, 0, 0));
        assertEquals(LOW, land(63, 64, 1, 0));
    }

    @Test
    void deeperWaterIsDarker() {
        assertEquals(HIGH, water(1, 0, 0));
        assertEquals(HIGH, water(1, 1, 0));
        assertEquals(NORMAL, water(5, 0, 0));
        assertEquals(LOW, water(10, 0, 0));
        assertEquals(LOW, water(40, 0, 0), "capped at the max depth");
        assertEquals(LOW, water(8, 1, 0));
    }

    @Test
    void regionsAndPixelsIncludingNegativeCoordinates() {
        assertEquals(0, region(0));
        assertEquals(0, region(511));
        assertEquals(1, region(512));
        assertEquals(-1, region(-1));
        assertEquals(-1, region(-512));
        assertEquals(-2, region(-513));
        assertEquals(0, index(0, 0));
        assertEquals(511, index(511, 0));
        assertEquals(512, index(0, 1));
        assertEquals(511 * 512 + 511, index(-1, -1), "the south-east corner of region -1,-1");
        assertEquals(index(5, 7), index(5 - 1024, 7 + 512), "the same spot in another region");
    }

    @Test
    void namesOfFilesAndFolders() {
        assertEquals("minecraft_overworld", dimFolder("minecraft:overworld"));
        assertEquals("mymod_caves_deep", dimFolder("mymod:caves/deep"));
        assertEquals("minecraft:overworld", guessDim("minecraft_overworld"));
        assertEquals("-1.0.png", fileName(-1, 0));
        assertArrayEquals(new int[] { -1, 0 }, parseFileName("-1.0.png"));
        assertArrayEquals(new int[] { 3, -12 }, parseFileName("3.-12.png"));
        assertNull(parseFileName("0.0.png.tmp"));
        assertNull(parseFileName("0.0.broken.png"));
        assertNull(parseFileName("a.0.png"));
        assertNull(parseFileName("../0.0.png"));
    }

    @Test
    void indexJson() {
        Map<String, List<long[]>> m = new LinkedHashMap<>();
        m.put("minecraft:overworld", List.of(new long[] { 0, 0, 5 }, new long[] { -1, 2, 6 }));
        m.put("minecraft:the_end", List.of());
        assertEquals("{\"minecraft:overworld\":[[0,0,5],[-1,2,6]],\"minecraft:the_end\":[]}", MapMath.indexJson(m));
        assertEquals("{}", MapMath.indexJson(Map.of()));
    }
}
