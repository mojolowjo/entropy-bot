package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** S45 lava distance (0.39.z40): a cell near lava costs more (a price, not a ban); a break that opens lava is refused. */
class LavaNearTest {
    /** Baritone's ActionCosts.WALK_ONE_BLOCK_COST (20 / 4.317 ticks): the scale the penalty sits on. */
    static final double WALK = 20 / 4.317;

    @BeforeEach
    void data() { TestData.planner().install(); }

    @AfterEach
    void clear() { TestData.clear(); }

    static LavaNear.Lava at(int[]... cells) {
        Set<String> s = new java.util.HashSet<>();
        for (int[] c : cells) s.add(c[0] + " " + c[1] + " " + c[2]);
        return (x, y, z) -> s.contains(x + " " + y + " " + z);
    }

    static List<int[]> line(int z, int from, int to) {
        List<int[]> out = new ArrayList<>();
        for (int x = from; x <= to; x++) out.add(new int[]{x, 64, z});
        return out;
    }

    @Test
    void theDefaultsAreTwoBlocksAndTenTicks() {
        assertEquals(2, LavaNear.reach());
        assertEquals(10.0, LavaNear.penalty(), 1e-9);
    }

    @Test
    void aCellWithinReachOfLavaCostsThePenaltyOneFurtherNothing() {
        var lava = at(new int[]{0, 64, 3});
        assertEquals(10, LavaNear.ticks(lava, 0, 64, 1, 2, 10), 1e-9);
        assertEquals(0, LavaNear.ticks(lava, 0, 64, 0, 2, 10), 1e-9, "3 blocks away: out of reach");
        assertEquals(0, LavaNear.ticks(lava, 0, 64, 1, 2, 0), 1e-9, "penalty 0 = off");
        assertEquals(10, LavaNear.ticks(at(new int[]{0, 62, 0}), 0, 64, 0, 2, 10), 1e-9, "lava 2 below the feet is near too");
    }

    @Test
    void s45twoEqualPathsTheOnePassingOneBlockFromLavaLoses() {
        var lava = at(new int[]{5, 64, 1});                         // one lava block beside the near path (z = 0)
        List<int[]> near = line(0, 0, 10), far = line(4, 0, 10);    // equal length: 10 moves each
        double base = 10 * WALK;
        double nearCost = base + LavaNear.pathTicks(lava, near, LavaNear.reach(), LavaNear.penalty());
        double farCost = base + LavaNear.pathTicks(lava, far, LavaNear.reach(), LavaNear.penalty());
        assertTrue(farCost < nearCost, "far " + farCost + " vs near " + nearCost);
        assertEquals(50, nearCost - base, 1e-9, "5 cells in reach x 10 ticks");
        // and the penalty buys a detour of a few blocks: 50 ticks is more than 4 extra blocks of walk
        assertTrue(nearCost - base > 4 * WALK);
    }

    @Test
    void aBreakWithLavaAtAnyFaceOpensLavaOneFurtherDoesNot() {
        assertTrue(LavaNear.opensLava(at(new int[]{0, 63, 0}), 0, 64, 0), "lava below: stock Baritone does not check down");
        assertTrue(LavaNear.opensLava(at(new int[]{1, 64, 0}), 0, 64, 0));
        assertTrue(LavaNear.opensLava(at(new int[]{0, 65, 0}), 0, 64, 0));
        assertFalse(LavaNear.opensLava(at(new int[]{1, 65, 0}), 0, 64, 0), "a diagonal is no face");
        assertFalse(LavaNear.opensLava(null, 0, 64, 0));
    }

    @Test
    void theNoteIsRoughSecondsOnlyWhenPaid() {
        assertEquals(" (+3.0 s lava detour)", LavaNear.note(60, 20));
        assertEquals("", LavaNear.note(0, 20));
    }
}
