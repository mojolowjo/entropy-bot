package io.github.mojolowjo.entropybot.vocab;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class FishRulesTest {
    private static FishRules.State st(boolean rod, boolean hook, boolean bite, long sinceCast, long sinceReel,
                                      boolean ok, boolean far, int caught, FishRules.Parsed goal, long elapsed) {
        return new FishRules.State(rod, hook, bite, sinceCast, sinceReel, ok, far, caught, goal, elapsed);
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
    void rodRecipeIsTheGamesOwn() {
        Map<String, Integer> ok = Map.of("minecraft:stick", 3, "minecraft:string", 2), few = Map.of("minecraft:stick", 2, "minecraft:string", 2);
        assertTrue(FishRules.canCraftRod(ok));
        assertFalse(FishRules.canCraftRod(few));
        assertFalse(FishRules.canCraftRod(Map.of("minecraft:stick", 3, "minecraft:string", 1)));
        assertNull(FishRules.rodWhy(true, Map.of()));
        assertNull(FishRules.rodWhy(false, ok));
        assertTrue(FishRules.rodWhy(false, Map.of("stick", 1)).contains("3 stick"), FishRules.rodWhy(false, Map.of("stick", 1)));
    }

    @Test
    void theLoop() {
        FishRules.Parsed n = new FishRules.Parsed(2, 0), t = new FishRules.Parsed(0, 2);
        assertEquals(FishRules.Act.DONE_COUNT, FishRules.next(st(true, true, false, 5, 5, true, false, 2, n, 100)));
        assertEquals(FishRules.Act.DONE_TIME, FishRules.next(st(true, true, false, 5, 5, true, false, 0, t, 2400)));
        assertEquals(FishRules.Act.WAIT_BITE, FishRules.next(st(true, true, false, 5, 5, true, false, 0, t, 100)));
        assertEquals(FishRules.Act.TIMED_OUT, FishRules.next(st(true, true, false, 5, 5, true, false, 0, n, FishRules.countMax())));
        assertEquals(FishRules.Act.NO_ROD, FishRules.next(st(false, false, false, 0, 99, true, false, 0, n, 0)), "the craft is a step before this one");
        assertEquals(FishRules.Act.REEL_BITE, FishRules.next(st(true, true, true, 30, 99, true, false, 0, n, 100)));
        assertEquals(FishRules.Act.REEL_TIMEOUT, FishRules.next(st(true, true, false, FishRules.biteWait(), 99, true, false, 0, n, 100)));
        assertEquals(FishRules.Act.WAIT, FishRules.next(st(true, false, false, 0, 3, true, false, 0, n, 100)));
        assertEquals(FishRules.Act.CAST, FishRules.next(st(true, false, false, 0, 40, true, false, 0, n, 100)));
        assertEquals(FishRules.Act.GO_WATER, FishRules.next(st(true, false, false, 0, 40, false, true, 0, n, 100)));
        assertEquals(FishRules.Act.NO_WATER, FishRules.next(st(true, false, false, 0, 40, false, false, 0, n, 100)));
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
        assertTrue(Math.hypot(s[0], s[2] - 2) >= FishRules.minCast() && Math.hypot(s[0], s[2] - 2) <= FishRules.maxCast());
        assertNull(FishRules.pick(pond(5, 0, 2, 6, 63), 0, 2, null), "2 wide: no 3x3");
        int[] far = FishRules.pick(pond(14, 0, 4, 4, 63), 0, 2, null);
        assertNotNull(far);
        assertEquals(1, far[3], "out of casting distance: walk first");
        assertNull(FishRules.pick(pond(5, 0, 6, 6, 63), 0, 2, cell -> false));
    }

    @Test
    void countsTheGamesFishingFishOnly() {
        assertEquals(5, FishRules.fish(Map.of("minecraft:cod", 2, "minecraft:salmon", 2, "minecraft:tropical_fish", 1, "minecraft:pufferfish", 3)), "the fish of the fishing loot, less the harmful ones");
    }
}