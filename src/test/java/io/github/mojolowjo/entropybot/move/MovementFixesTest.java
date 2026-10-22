package io.github.mojolowjo.entropybot.move;

import io.github.mojolowjo.entropybot.restore.BuildSpotter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.4 movement fixes from the obstacle matrix (runs 10, 11, 8). */
class MovementFixesTest {
    // ---- run 10: sealed at the start ----

    // ---- run 11: resume vs re-plan after a fight ----

    // ---- run 8: a build beside the walk ----

    static List<int[]> hut(int x0, int y0, int z0) {
        List<int[]> b = new ArrayList<>();
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) for (int y = 0; y < 3; y++)
            if (x == 0 || z == 0 || x == 8 || z == 8) b.add(new int[]{x0 + x, y0 + y, z0 + z});
        return b;
    }

    @Test
    void scatteredBlocksAreNoBuild() {
        List<int[]> few = List.of(new int[]{0, 64, 0}, new int[]{20, 64, 0}, new int[]{40, 64, 0});
        assertNull(BuildSpotter.spot(few));
    }
}
