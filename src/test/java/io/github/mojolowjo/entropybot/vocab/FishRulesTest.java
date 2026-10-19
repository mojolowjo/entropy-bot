package io.github.mojolowjo.entropybot.vocab;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FishRulesTest {
    private static FishRules.State st(boolean rod, boolean craft, boolean tried, boolean hook, boolean bite, long sinceCast, long sinceReel,
                                      boolean ok, boolean far, int caught, FishRules.Parsed goal, long elapsed) {
        return new FishRules.State(rod, craft, tried, hook, bite, sinceCast, sinceReel, ok, far, caught, goal, elapsed);
    }

    @Test
    void parsesCountAndMinutes() {
        assertEquals(new FishRules.Parsed(4, 0), FishRules.parse(""));
        assertEquals(new FishRules.Parsed(6, 0), FishRules.parse("6"));
        assertEquals(new FishRules.Parsed(0, 3), FishRules.parse("3m"));
        assertEquals(new FishRules.Parsed(0, 3), FishRules.parse("3 minutes"));
        assertEquals(new FishRules.Parsed(0, 12), FishRules.parse("12 min"));
        assertTrue(FishRules.parse("2m").timed());
        assertNull(FishRules.parse("0"));
        assertNull(FishRules.parse("99m"));
        assertNull(FishRules.parse("cod"));
        assertNull(FishRules.parse("3 fish"));
    }

    @Test
    void biteReadsTheTrueFlag() {
        assertFalse(FishRules.bite(null));
        assertFalse(FishRules.bite(List.of()));
        assertFalse(FishRules.bite(List.of(5, "x")));
        assertTrue(FishRules.bite(List.of(0, true)));
    }

    @Test
    void rodRecipe() {
        assertTrue(FishRules.canCraftRod(3, 2));
        assertFalse(FishRules.canCraftRod(2, 2));
        assertFalse(FishRules.canCraftRod(3, 1));
        assertNull(FishRules.rodWhy(true, 0, 0));
        assertNull(FishRules.rodWhy(false, 3, 2));
        assertTrue(FishRules.rodWhy(false, 1, 0).contains("3 sticks + 2 string"));
    }

    @Test
    void theLoop() {
        FishRules.Parsed n = new FishRules.Parsed(2, 0), t = new FishRules.Parsed(0, 2);
        assertEquals(FishRules.Act.DONE_COUNT, FishRules.next(st(true, false, false, true, false, 5, 5, true, false, 2, n, 100)));
        assertEquals(FishRules.Act.DONE_TIME, FishRules.next(st(true, false, false, true, false, 5, 5, true, false, 0, t, 2400)));
        assertEquals(FishRules.Act.WAIT_BITE, FishRules.next(st(true, false, false, true, false, 5, 5, true, false, 0, t, 100)));
        assertEquals(FishRules.Act.TIMED_OUT, FishRules.next(st(true, false, false, true, false, 5, 5, true, false, 0, n, FishRules.COUNT_MAX)));
        assertEquals(FishRules.Act.CRAFT_ROD, FishRules.next(st(false, true, false, false, false, 0, 99, true, false, 0, n, 0)));
        assertEquals(FishRules.Act.NO_ROD, FishRules.next(st(false, true, true, false, false, 0, 99, true, false, 0, n, 0)), "tried once, no loop");
        assertEquals(FishRules.Act.NO_ROD, FishRules.next(st(false, false, false, false, false, 0, 99, true, false, 0, n, 0)));
        assertEquals(FishRules.Act.REEL_BITE, FishRules.next(st(true, false, false, true, true, 30, 99, true, false, 0, n, 100)));
        assertEquals(FishRules.Act.REEL_TIMEOUT, FishRules.next(st(true, false, false, true, false, FishRules.BITE_WAIT, 99, true, false, 0, n, 100)));
        assertEquals(FishRules.Act.WAIT, FishRules.next(st(true, false, false, false, false, 0, 3, true, false, 0, n, 100)));
        assertEquals(FishRules.Act.CAST, FishRules.next(st(true, false, false, false, false, 0, 40, true, false, 0, n, 100)));
        assertEquals(FishRules.Act.GO_WATER, FishRules.next(st(true, false, false, false, false, 0, 40, false, true, 0, n, 100)));
        assertEquals(FishRules.Act.NO_WATER, FishRules.next(st(true, false, false, false, false, 0, 40, false, false, 0, n, 100)));
    }

    private static List<int[]> pond(int x0, int z0, int w, int d, int y) {
        List<int[]> l = new ArrayList<>();
        for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) l.add(new int[]{x, y, z});
        return l;
    }

    @Test
    void spotNeedsA3x3PatchInCastRange() {
        int[] s = FishRules.pick(pond(5, 0, 6, 6, 63), 0, 2, null);       // water x 5..10, z 0..5; me at 0,2
        assertNotNull(s);
        assertEquals(0, s[3]);
        assertTrue(Math.hypot(s[0], s[2] - 2) >= FishRules.MIN_CAST && Math.hypot(s[0], s[2] - 2) <= FishRules.MAX_CAST);
        assertNull(FishRules.pick(pond(5, 0, 2, 6, 63), 0, 2, null), "2 wide: no 3x3");
        int[] far = FishRules.pick(pond(14, 0, 4, 4, 63), 0, 2, null);
        assertNotNull(far);
        assertEquals(1, far[3], "out of casting distance: walk first");
        assertNull(FishRules.pick(pond(5, 0, 6, 6, 63), 0, 2, cell -> false));
        int[] stand = FishRules.standPoint(far, 0, 2);
        assertTrue(Math.hypot(stand[0] - far[0], stand[2] - far[2]) <= 5.5);
    }

    @Test
    void countsOnlyFoodFish() {
        assertEquals(5, FishRules.fish(Map.of("minecraft:cod", 2, "minecraft:salmon", 2, "minecraft:tropical_fish", 1, "minecraft:pufferfish", 3)));
    }
}