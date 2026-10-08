package io.github.mojolowjo.entropybot.camp;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mojolowjo.entropybot.camp.ShelterPlan.Stage;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 0.24.3: the shelter's shell, material order, own-block rule and stage machine. */
class ShelterPlanTest {
    static final int[] F = {10, 64, 20};

    @Test void shellAroundTheCell() {
        List<int[]> s = ShelterPlan.shell(F, c -> false);
        assertEquals(ShelterPlan.FULL, s.size());
        assertArrayEquals(new int[]{10, 64, 19}, s.get(0));                // north at the feet first
        for (int i = 0; i < 4; i++) assertEquals(64, s.get(i)[1]);
        for (int i = 4; i < 8; i++) assertEquals(65, s.get(i)[1]);
        assertArrayEquals(new int[]{10, 66, 19}, s.get(8));                 // the roof's support
        assertArrayEquals(new int[]{10, 66, 20}, s.get(9));                 // the roof, sealed last
        for (int[] c : s) assertFalse(c[0] == 10 && c[2] == 20 && c[1] < 66, "never the bot's own cell");
    }

    @Test void solidCellsAreLeftOut() {
        List<int[]> s = ShelterPlan.shell(F, c -> c[0] == 11);              // a wall to the east already
        assertEquals(ShelterPlan.FULL - 2, s.size());
    }

    @Test void materialOrder() {
        assertEquals("minecraft:spruce_planks", ShelterPlan.material(Map.of("minecraft:spruce_planks", 12, "minecraft:cobblestone", 64), 10));
        assertEquals("minecraft:cobblestone", ShelterPlan.material(Map.of("minecraft:spruce_planks", 4, "minecraft:cobblestone", 64, "minecraft:dirt", 64), 10));
        assertEquals("minecraft:dirt", ShelterPlan.material(Map.of("minecraft:dirt", 10), 10));
        assertNull(ShelterPlan.material(Map.of("minecraft:dirt", 3), 10));
        assertEquals("cut 8 logs then craft planks 32", ShelterPlan.prep(Map.of()));
        assertEquals("craft planks 16", ShelterPlan.prep(Map.of("minecraft:birch_log", 4)));
    }

    @Test void onlyOwnBlocksBreak() {
        Set<String> own = new HashSet<>();
        for (int[] c : ShelterPlan.shell(F, c -> false)) own.add(ShelterPlan.key(c));
        assertTrue(ShelterPlan.mayBreak(new int[]{10, 64, 19}, own));
        assertFalse(ShelterPlan.mayBreak(new int[]{10, 63, 20}, own));      // the floor was never mine
        int[][] ex = ShelterPlan.exit(F, own);
        assertArrayEquals(new int[]{10, 64, 19}, ex[0]);
        assertArrayEquals(new int[]{10, 65, 19}, ex[1]);
        assertArrayEquals(new int[]{10, 64, 18}, ex[2]);
        own.remove("10 64 19");                                              // north was someone's wall: go east
        assertArrayEquals(new int[]{11, 64, 20}, ShelterPlan.exit(F, own)[0]);
        assertNull(ShelterPlan.exit(F, Set.of()));
    }

    @Test void dayNightStages() {
        assertEquals(Stage.BUILD, ShelterPlan.next(Stage.BUILD, true, true, false, false, false, false, false));
        assertEquals(Stage.WAIT, ShelterPlan.next(Stage.BUILD, true, true, false, false, false, true, false));
        assertEquals(Stage.WAIT, ShelterPlan.next(Stage.WAIT, true, true, false, false, false, false, false));     // night: stay
        assertEquals(Stage.WAIT, ShelterPlan.next(Stage.WAIT, false, false, false, false, false, false, false));   // dusk not come yet
        assertEquals(Stage.OPEN, ShelterPlan.next(Stage.WAIT, false, true, false, false, false, false, false));    // day after the night
        assertEquals(Stage.WAIT, ShelterPlan.next(Stage.WAIT, false, true, true, false, false, false, false));     // timed: the timer counts
        assertEquals(Stage.OPEN, ShelterPlan.next(Stage.WAIT, true, true, true, true, false, false, false));
        assertEquals(Stage.OPEN, ShelterPlan.next(Stage.WAIT, true, true, false, false, true, false, false));      // leave
        assertEquals(Stage.OUT, ShelterPlan.next(Stage.OPEN, false, true, false, false, false, true, false));
        assertEquals(Stage.DONE, ShelterPlan.next(Stage.OUT, false, true, false, false, false, true, false));
        assertEquals(Stage.CLOSE, ShelterPlan.next(Stage.OUT, false, true, false, false, false, true, true));
        assertEquals(Stage.DONE, ShelterPlan.next(Stage.CLOSE, false, true, false, false, false, true, true));
    }

    @Test void args() {
        assertArrayEquals(new long[]{-1, 0}, ShelterPlan.args(""));
        assertArrayEquals(new long[]{6000, 1}, ShelterPlan.args("5m keep"));
        assertNull(ShelterPlan.args("forever"));
    }
}
