package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** watch tunnel dollhouse: every quad's front (CCW winding) points into the air, so back-face culling keeps the right side. */
class DollhouseTest {
    @Test
    void everyFaceWindsTowardsTheAir() {
        for (int side = 0; side < 6; side++) {
            float[][] c = FaceGeometry.corners(3, -4, 7, side, TunnelMesh.INSET, 0, 0, 0);
            assertArrayEquals(Shell.OFF[side], FaceGeometry.frontNormal(c), "side " + side);
        }
    }

    @Test
    void cullingMatchesTheCameraSide() {
        Random r = new Random(5);
        for (int i = 0; i < 2000; i++) {
            int side = r.nextInt(6);
            int bx = r.nextInt(20) - 10, by = r.nextInt(20) - 10, bz = r.nextInt(20) - 10;
            double cx = r.nextDouble() * 40 - 20, cy = r.nextDouble() * 40 - 20, cz = r.nextDouble() * 40 - 20;
            float[][] c = FaceGeometry.corners(bx, by, bz, side, 0, 0, 0, 0);
            int[] n = FaceGeometry.frontNormal(c);
            double dot = (cx - c[0][0]) * n[0] + (cy - c[0][1]) * n[1] + (cz - c[0][2]) * n[2];
            if (Math.abs(dot) < 1e-6) continue;
            assertEquals(dot > 0, FaceGeometry.facesCamera(bx, by, bz, side, cx, cy, cz), "GL keeps the front: side " + side);
        }
    }

    @Test
    void fromAboveAndBesideOnlyFloorAndFarWallShow() {
        // a tunnel along x at y 0..1, z 0; the camera 12 up and 7 to the south (+z)
        double cx = 5.5, cy = 13.6, cz = 7.5;
        assertTrue(FaceGeometry.facesCamera(5, -1, 0, 1, cx, cy, cz), "floor (top face of the block below)");
        assertFalse(FaceGeometry.facesCamera(5, 2, 0, 0, cx, cy, cz), "ceiling (bottom face of the block above)");
        assertFalse(FaceGeometry.facesCamera(5, 0, 1, 2, cx, cy, cz), "near wall (north face of the block to the south)");
        assertTrue(FaceGeometry.facesCamera(5, 0, -1, 3, cx, cy, cz), "far wall (south face of the block to the north)");
    }
}
