package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * D14 (stations placed once, on the surface or at a base spot): what the place step asks the fork's
 * {@code IBuilderProcess.placeStation} for - the centre, the reach and whether the cell must be on the surface. The fork
 * picks the cell (rule 2: a cell choice is a fork Goal/cost, never a mod-side scan); this class only names the request.
 * <ul>
 * <li>Centre: the base spot when the feet are within {@code station.baseReachM} of it (its walk stays the place step's
 *     own short leg, so the place price holds), else the feet.</li>
 * <li>Surface: the feet stand on the leaf-free surface (MOTION_BLOCKING_NO_LEAVES' meaning, {@code world.SurfaceHeight});
 *     a bot under a tree is on the surface (runs JAC, JAD, JAF misjudged this with WORLD_SURFACE). Under ground the cell
 *     stays within a block of the feet's height, in a cave chamber with room.</li>
 * </ul>
 * Pure (JUnit: D14StationsTest). Loader notes: none here; the live surface is {@code world.SurfaceHeightLive.noLeaves}.
 */
public final class StationPlace {
    private StationPlace() {}

    /** The base spot {x, y, z} the sense round set last (AaRuntime, the same value as Inputs.basePos); null: none set. */
    public static volatile int[] base;

    /** The request: centre, reach, surface; {@code why} the words for the log. */
    public record Ask(int[] center, int radius, boolean surface, String why) {}

    /**
     * On the surface: the feet at or above the leaf-free surface of their column, less one step ({@code noLeavesY}: the first
     * cell over the top non-leaf blocking block; {@link io.github.mojolowjo.entropybot.world.SurfaceHeight#NOT_KNOWN}: not
     * known - never claimed, rule 15).
     */
    public static boolean onSurface(int feetY, int noLeavesY) {
        if (!io.github.mojolowjo.entropybot.world.SurfaceHeight.known(noLeavesY)) return false;
        return feetY >= noLeavesY - 1;                                        // structural: a 1-block step
    }

    /** The request for a station placed from {@code feet} with the column's leaf-free surface {@code noLeavesY}. */
    public static Ask ask(int[] feet, int noLeavesY, int[] baseSpot) {
        int r = Tuning.i("steps.placeNearR");
        if (baseSpot != null && baseSpot.length >= 3 && feet != null) {      // structural: x y z
            double dx = baseSpot[0] - feet[0], dy = baseSpot[1] - feet[1], dz = baseSpot[2] - feet[2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= Tuning.get("station.baseReachM"))
                return new Ask(baseSpot.clone(), r, true, "near the base spot " + baseSpot[0] + " " + baseSpot[1] + " " + baseSpot[2] + " on the surface");
        }
        boolean up = onSurface(feet[1], noLeavesY);
        return new Ask(feet.clone(), r, up, up ? "near me on the surface" : "near me under ground (a cell with room at my height)");
    }
}
