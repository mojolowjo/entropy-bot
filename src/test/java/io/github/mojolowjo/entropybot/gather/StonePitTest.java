package io.github.mojolowjo.entropybot.gather;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StonePitTest {
    /** Ground at y 63 and below: dirt down to 60, stone from 59 down. */
    private static StonePit.Cell layered(int x, int y, int z) {
        if (y >= 64) return StonePit.Cell.AIR;
        return y >= 60 ? StonePit.Cell.SOFT : StonePit.Cell.STONE;
    }

    @Test
    void staircaseLongEnoughForTheStone() {
        int[] feet = {0, 64, 0};
        StonePit.Plan p = StonePit.plan(StonePitTest::layered, feet, new int[]{1, 0}, 64);
        assertNull(p.err());
        assertTrue(p.stone() >= 64, "stone " + p.stone());
        // columns 1..4 are all dirt (feet-1 .. feet-i = 63..60); stone from column 5: 3 per extra level
        // 3*(1+2+...+k) >= 64 -> k = 7 -> 11 columns
        assertEquals(11, p.columns());
        // column 1 is one deep at x 2, 3 wide across z
        assertArrayEquals(new int[]{2, 63, -1}, p.cells().get(0));
        // every cell is in front of the bot, none under its feet
        for (int[] c : p.cells()) assertTrue(c[0] >= 2 && c[1] <= 63 && c[1] >= 53);
    }

    @Test
    void stopsAtWater() {
        StonePit.Plan p = StonePit.plan((x, y, z) -> x == 4 && y == 62 ? StonePit.Cell.LIQUID : layered(x, y, z), new int[]{0, 64, 0}, new int[]{1, 0}, 64);
        assertNotNull(p.err());
        assertTrue(p.err().contains("water or lava at 4 62"), p.err());
        assertTrue(p.err().contains("next: goto a hillside"));
    }

    @Test
    void noStoneSaysSo() {
        StonePit.Plan p = StonePit.plan((x, y, z) -> y >= 64 ? StonePit.Cell.AIR : StonePit.Cell.SOFT, new int[]{0, 64, 0}, new int[]{0, -1}, 10);
        assertTrue(p.err().contains("no stone within 24 blocks"));
    }

    @Test
    void partialStoneStillDigs() {
        StonePit.Plan p = StonePit.plan((x, y, z) -> x >= 8 && y < 64 ? StonePit.Cell.HARD : layered(x, y, z), new int[]{0, 64, 0}, new int[]{1, 0}, 64);
        assertNull(p.err());
        assertEquals(6, p.columns());
        assertEquals(9, p.stone());
        assertTrue(StonePit.note(p, new int[]{1, 0}, 64).contains("east"));
    }

    @Test
    void gatherHasACobblestoneSource() {
        GatherSources.Source s = GatherSources.resolve("cobblestone", Map.of());
        assertEquals(GatherSources.Kind.STONE, s.kind());
        assertEquals(List.of("dig stone 64"), GatherSources.commands(s, 64, false, false));
        assertEquals(GatherSources.Kind.STONE, GatherSources.resolve("cobbled_deepslate", Map.of()).kind());
        assertNull(GatherSources.resolve("stone", Map.of()));             // smelted from cobblestone
        assertEquals("dug", GatherSources.doneWord("dig stone 64"));
    }
}
