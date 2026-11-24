package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.List;

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

    /**
     * job-review-leftovers (item 3, run 3 w005: the feet 28 blocks down the bot's own open shaft at 20 28 -10; the shaft's
     * column is open to the sky, so its leaf-free height is the shaft floor and the feet read "on the surface"; a table was
     * asked 22 times for a surface cell 36 blocks up with nothing spare to pillar with): the cell asked from is judged by
     * the ground round it, not its own column only. On the surface: {@link #onSurface(int, int)} for the feet column AND
     * the feet within {@code station.shaftDepth} below {@code ringY}, the middle (median) leaf-free height of the columns
     * round the feet ({@link #ringSurface}). Not known ring: never claimed (rule 15), the under-ground request stands.
     */
    public static boolean onSurface(int feetY, int noLeavesY, int ringY) {
        if (!onSurface(feetY, noLeavesY)) return false;
        if (!io.github.mojolowjo.entropybot.world.SurfaceHeight.known(ringY)) return false;
        return feetY >= ringY - Tuning.i("station.shaftDepth");
    }

    /** game fact: a dug shaft is 1 or 2 columns wide, so the ring two columns out from the feet stands on the ground round it. */
    static final int RING = 2;

    /**
     * The median leaf-free height of the {@link #RING} ring of columns round {@code x z} ({@code heightAt}: a column's
     * leaf-free height, {@link io.github.mojolowjo.entropybot.world.SurfaceHeight#NOT_KNOWN} when not loaded); NOT_KNOWN
     * when fewer than half the ring is known (rule 15). Pure.
     */
    public static int ringSurface(java.util.function.IntBinaryOperator heightAt, int x, int z) {
        List<Integer> hs = new java.util.ArrayList<>();
        int all = 0;
        for (int dx = -RING; dx <= RING; dx++)
            for (int dz = -RING; dz <= RING; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != RING) continue;
                all++;
                int h = heightAt.applyAsInt(x + dx, z + dz);
                if (io.github.mojolowjo.entropybot.world.SurfaceHeight.known(h)) hs.add(h);
            }
        if (hs.size() * 2 < all) return io.github.mojolowjo.entropybot.world.SurfaceHeight.NOT_KNOWN;   // structural: half
        java.util.Collections.sort(hs);
        return hs.get(hs.size() / 2);                                              // structural: the median
    }

    /** The request for a station placed from {@code feet} with the column's leaf-free surface {@code noLeavesY} and the ring's {@code ringY}. */
    public static Ask ask(int[] feet, int noLeavesY, int ringY, int[] baseSpot) {
        int r = Tuning.i("steps.placeNearR");
        if (baseSpot != null && baseSpot.length >= 3 && feet != null) {      // structural: x y z
            double dx = baseSpot[0] - feet[0], dy = baseSpot[1] - feet[1], dz = baseSpot[2] - feet[2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= Tuning.get("station.baseReachM"))
                return new Ask(baseSpot.clone(), r, true, "near the base spot " + baseSpot[0] + " " + baseSpot[1] + " " + baseSpot[2] + " on the surface");
        }
        boolean up = onSurface(feet[1], noLeavesY, ringY);
        boolean shaft = !up && onSurface(feet[1], noLeavesY);
        return new Ask(feet.clone(), r, up, up ? "near me on the surface"
                : shaft ? "near me under ground (a cell with room at my height; my column is open to the sky but the ground round it is at " + ringY + ")"
                : "near me under ground (a cell with room at my height)");
    }
}
