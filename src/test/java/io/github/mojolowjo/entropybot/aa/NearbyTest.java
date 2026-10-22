package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** simple-tools: the one "what is nearby" fact: Baritone's scan first, the sightings as the fallback. */
class NearbyTest {
    static final int[] ME = {0, 64, 0};
    static final Spot SIGHTED = new Spot(50, 64, 50, 70, null);

    @BeforeEach
    void data() {
        TestData d = TestData.planner();
        d.tiers.put("minecraft:stone", 1);
        d.tiers.put("minecraft:dirt", 0);
        d.install();
    }

    @AfterEach
    void clear() { TestData.clear(); }

    record Fake(List<int[]> logs, List<int[]> stones, Predicate<int[]> leaves, Predicate<int[]> allowed, Map<String, String> ids) implements Nearby.Scan {
        @Override public List<int[]> logs(int radius) { return logs; }
        @Override public List<int[]> stones(int radius) { return stones; }
        @Override public boolean leavesNear(int x, int y, int z, int reach) { return leaves.test(new int[]{x, y, z}); }
        @Override public boolean allowed(int x, int y, int z) { return allowed.test(new int[]{x, y, z}); }
        @Override public String blockAt(int x, int y, int z) { return ids.getOrDefault(x + " " + y + " " + z, "minecraft:stone"); }
    }

    @Test
    void theNearestLogWithLeavesBeatsANearerLogInABuildAndTheSightings() {
        var s = new Fake(List.of(new int[]{30, 64, 0}, new int[]{3, 64, 0}, new int[]{9, 64, 0}), List.of(), p -> p[0] != 3, p -> true, Map.of());
        Spot r = Nearby.log(s, ME, () -> SIGHTED);
        assertEquals(9, r.x(), "the log at 3 has no leaves by it (a stack in a build)");
        assertEquals(9.0, r.dist(), 1e-9);
    }

    @Test
    void aLogTheScanDoesNotFindFallsBackToTheSightings() {
        var s = new Fake(List.of(), List.of(), p -> true, p -> true, Map.of());
        assertSame(SIGHTED, Nearby.log(s, ME, () -> SIGHTED));
        assertNull(Nearby.log(s, ME, () -> null));
    }

    @Test
    void aLogNoLeavesBelongToIsNoTreeSoTheSightingsAnswer() {
        var s = new Fake(List.of(new int[]{4, 64, 0}), List.of(), p -> false, p -> true, Map.of());
        assertSame(SIGHTED, Nearby.log(s, ME, () -> SIGHTED));
    }

    @Test
    void theNearestDiggableStoneIsTakenAndItsTierBlockIsKept() {
        var s = new Fake(List.of(), List.of(new int[]{20, 60, 0}, new int[]{5, 60, 0}, new int[]{7, 60, 0}), p -> true, p -> p[0] != 5,
                Map.of("7 60 0", "minecraft:stone"));
        Spot r = Nearby.stone(s, ME, () -> null);
        assertEquals(7, r.x(), "the nearer cell at 5 is not mine to dig");
        assertEquals("minecraft:stone", r.block());
    }

    @Test
    void aBlockThatNeedsNoPickaxeTierIsNoStone() {
        var s = new Fake(List.of(), List.of(new int[]{4, 60, 0}), p -> true, p -> true, Map.of("4 60 0", "minecraft:dirt"));
        assertSame(SIGHTED, Nearby.stone(s, ME, () -> SIGHTED));
    }

    @Test
    void noBaritoneScanIsTheFallbackAlone() {
        assertSame(SIGHTED, Nearby.log(null, ME, () -> SIGHTED));
        assertNull(Nearby.stone(null, ME, null));
    }
}
