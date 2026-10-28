package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z78 J5 slice 1 (to-do/j5-knowledge-audit.md D2; run DU 16:29:01.344: "stopped 1 blocks away", run DT 16:19:41.301:
 * feet 6 16 7, the furnace at 6 18 7): a station is in reach from the eye when the block's box is within the player's
 * block interaction range - vanilla's own test (Player#canInteractWithBlock: the box's squared distance to the eye under the
 * range squared). The walk to a station ends done when it is in reach, before or after the walk: a walk not needed is no
 * fact about the station (nothing held). Pure (JUnit: J5StationHoldsTest). Loader notes: the eye and the range come from
 * vanilla Player#getEyePosition / Player#blockInteractionRange.
 */
public final class StationReach {
    private StationReach() {}

    /** The block at {@code cell} is within {@code range} of {@code eye} {x, y, z} (its box's nearest point). */
    public static boolean inReach(double[] eye, int[] cell, double range) {
        if (eye == null || cell == null || eye.length < 3 || cell.length < 3 || !(range > 0)) return false;     // structural: x y z
        double d2 = 0;
        for (int i = 0; i < 3; i++) {                                                                              // structural: x y z
            double lo = cell[i], hi = cell[i] + 1.0, v = eye[i];
            double d = v < lo ? lo - v : v > hi ? v - hi : 0;
            d2 += d * d;
        }
        return d2 < range * range;
    }
}
