package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DigWhyTest {
    private static final Map<String, String> NONE = Map.of();

    @Test
    void brokeSomethingSaysNothing() {
        assertNull(DigWhy.reason(null, 3, 5, NONE, NONE, List.of(), NONE));
    }

    @Test
    void prefixWithNextIsKept() {
        assertNull(DigWhy.reason("stopped: snow needs a shovel - next: craft wooden_shovel 1", 0, 5, NONE, NONE, List.of(), NONE));
    }

    @Test
    void noPickaxeForStone() {
        String r = DigWhy.reason(null, 0, 4, Map.of("1 2 3", "needs a better tool (stone)"), NONE, List.of(), NONE);
        assertEquals("needs a pickaxe for stone - next: craft wooden_pickaxe 1", r);
        assertTrue(DigWhy.reason(null, 0, 4, Map.of("1 2 3", "needs a better tool (iron_ore)"), NONE, List.of(), NONE).contains("wooden_pickaxe"));
        assertTrue(DigWhy.reason(null, 0, 4, Map.of("1 2 3", "needs a better tool (diamond_ore)"), NONE, List.of(), NONE).contains("better pickaxe"));
        assertEquals("needs a pickaxe - next: craft wooden_pickaxe 1", DigWhy.reason("stopped: out of pickaxes (stone needs one)", 0, 4, NONE, NONE, List.of(), NONE));
    }

    @Test
    void nowhereToStand() {
        assertEquals("nowhere to stand within reach of 4 5 6 - next: goto 4 5 6 (stand next to it), then dig again",
                DigWhy.reason(null, 0, 2, NONE, NONE, List.of("4 5 6"), NONE));
    }

    @Test
    void fenceAndGuard() {
        assertTrue(DigWhy.reason(null, 0, 2, NONE, Map.of("1 1 1", "the guard refused the lease"), List.of(), NONE).startsWith("the fence refused 1 1 1 (outside my areas)"));
        assertTrue(DigWhy.reason("stopped: the guard refused: outside", 0, 2, NONE, NONE, List.of(), NONE).startsWith("next: area list"));
    }

    @Test
    void protectedBlock() {
        assertEquals("7 8 9 is protected (a built block: oak_planks) - next: dig 7 8 9 7 8 9 force (if it is yours)",
                DigWhy.reason(null, 0, 0, NONE, NONE, List.of(), Map.of("7 8 9", "oak_planks")));
    }

    @Test
    void gaveUpAndOrder() {
        Map<String, String> skip = new LinkedHashMap<>();
        skip.put("0 0 0", "takes too long to break");
        assertTrue(DigWhy.reason(null, 0, 1, skip, NONE, List.of(), NONE).startsWith("gave up on 0 0 0 after 10 s"));
        // no pickaxe comes before nowhere to stand
        skip.put("1 1 1", "needs a better tool (cobblestone)");
        assertTrue(DigWhy.reason(null, 0, 1, skip, NONE, List.of("2 2 2"), NONE).startsWith("needs a pickaxe"));
        assertTrue(DigWhy.reason(null, 0, 1, NONE, Map.of("3 3 3", "no progress"), List.of(), NONE).contains("[no progress]"));
    }

    @Test
    void emptyBox() {
        assertEquals("nothing to dig there (air, or done already)", DigWhy.reason(null, 0, 0, NONE, NONE, List.of(), NONE));
    }
    @Test
    void shovelBlocks() {
        assertTrue(DigWhy.reason(null, 0, 4, Map.of("1 2 3", "needs a better tool (dirt)"), NONE, List.of(), NONE).contains("wooden_shovel"));
        assertTrue(DigWhy.reason(null, 0, 4, Map.of("1 2 3", "needs a better tool (snow_block)"), NONE, List.of(), NONE).contains("wooden_shovel"));
        assertTrue(DigWhy.reason(null, 0, 4, Map.of("1 2 3", "needs a better tool (gravel)"), NONE, List.of(), NONE).contains("needs a shovel for gravel"));
    }

    @Test
    void axeBlocks() {
        assertTrue(DigWhy.reason(null, 0, 4, Map.of("1 2 3", "needs a better tool (oak_log)"), NONE, List.of(), NONE).contains("wooden_axe"));
        assertTrue(DigWhy.reason(null, 0, 4, Map.of("1 2 3", "needs a better tool (spruce_planks)"), NONE, List.of(), NONE).contains("needs an axe for spruce_planks"));
    }
}
