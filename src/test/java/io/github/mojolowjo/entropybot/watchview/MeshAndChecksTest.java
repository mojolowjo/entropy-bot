package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.commands.SelfCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Camera v2: the rebuild rule, the face geometry and the check findings. */
class MeshAndChecksTest {
    @Test
    void rebuildOnlyOnChange() {
        assertTrue(MeshRule.due(false, 0, 0, 0, 0, 0), "never built");
        assertFalse(MeshRule.due(true, 5, 5, 1000, 900, 0), "nothing changed");
        assertFalse(MeshRule.due(true, 6, 5, 1000, 900, 0), "changed, but within the minimum gap");
        assertTrue(MeshRule.due(true, 6, 5, 1400, 900, 0), "the set changed");
        assertTrue(MeshRule.due(true, 5, 5, 1400, 900, 13 * 13), "the bot moved far");
        assertFalse(MeshRule.due(true, 5, 5, 1400, 900, 11 * 11));
        assertTrue(MeshRule.due(true, 5, 5, 900 + MeshRule.REFRESH_MS, 900, 0), "the slow refresh");
        assertEquals(32, MeshRule.radius(1));
        assertEquals(80, MeshRule.radius(5));
        assertEquals(512, MeshRule.radius(32), "0.16.1: the render distance, up to 32 chunks");
        assertEquals(512, MeshRule.radius(64));
    }

    @Test
    void facesLieOnTheBoundaryTowardsTheAir() {
        double e = 0.004;
        for (int side = 0; side < 6; side++) {
            float[][] c = FaceGeometry.corners(10, 20, 30, side, e, 8, 18, 28);
            int axis = side < 2 ? 1 : side < 4 ? 2 : 0;
            double base = axis == 0 ? 2 : axis == 1 ? 2 : 2;                 // the block, relative to the origin
            double plane = (side % 2 == 0) ? base - e : base + 1 + e;       // even sides: the low face; odd: the high one
            for (float[] v : c) {
                assertEquals(plane, v[axis], 1e-5, "side " + side);
                for (int other = 0; other < 3; other++) if (other != axis) assertTrue(v[other] == 2f || v[other] == 3f);
                assertTrue((v[3] == 0f || v[3] == 1f) && (v[4] == 0f || v[4] == 1f));
            }
            // the four corners are distinct and cover the full square of uv
            assertEquals(4, java.util.Arrays.stream(c).map(v -> v[3] + "," + v[4]).distinct().count());
        }
        assertThrows(IllegalArgumentException.class, () -> FaceGeometry.corners(0, 0, 0, 6, 0, 0, 0, 0));
    }

    @Test
    void checkFindings() {
        assertTrue(WatchChecks.findings(true, true, false, -1, 0, -1, null).isEmpty());
        List<SelfCheck.Finding> f = WatchChecks.findings(false, false, true, 5000, 9000, 20, "draw: boom");
        assertEquals(List.of("watchhook", "camerapos", "tunneldraw", "tunnelerror"), f.stream().map(SelfCheck.Finding::key).toList());
        assertTrue(WatchChecks.findings(true, true, true, 3000, 1000, 20, null).isEmpty(), "just switched on: no alarm yet");
        assertTrue(WatchChecks.findings(true, true, true, 50, 9000, 20, null).isEmpty(), "drawing");
        assertTrue(WatchChecks.findings(true, true, true, 3000, 9000, 2500, null).isEmpty(), "a stalled game (no frames at all) is no draw failure");
        assertFalse(WatchChecks.notDrawing(true, 9000, -1, -1), "no frame ever: nothing to blame on the view");
        assertTrue(WatchChecks.notDrawing(true, 9000, -1, 10), "frames, but never drawn");
    }

    @Test
    void veilFindings() {
        assertTrue(WatchChecks.veilFindings(0, true, true, 9000, 500).isEmpty());
        assertEquals(List.of("tunnelhide"), WatchChecks.veilFindings(2, true, false, 0, 0).stream().map(SelfCheck.Finding::key).toList());
        assertEquals(List.of("tunnelskip"), WatchChecks.veilFindings(0, false, false, 0, 0).stream().map(SelfCheck.Finding::key).toList(),
                "skip hook not applied: a finding even with the view off");
        assertEquals(List.of("tunnelskip"), WatchChecks.veilFindings(0, true, true, 5000, 0).stream().map(SelfCheck.Finding::key).toList(),
                "applied, on for 5 s, nothing skipped: not firing");
        assertTrue(WatchChecks.veilFindings(0, true, true, 1000, 0).isEmpty(), "just switched on");
        assertTrue(WatchChecks.skipReport(true, true, 300, 9000).startsWith("skipped"));
        assertTrue(WatchChecks.skipReport(true, true, 0, 9000).contains("NOT firing"));
        assertTrue(WatchChecks.skipReport(false, true, -1, 0).contains("NOT applied"));
    }
}
