package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * D14 (stations placed once, on the surface or at a base spot): what the place step asks the fork's
 * {@code IBuilderProcess.placeStation} for - the centre, the reach and whether the cell must be on the surface. The fork
 * picks the cell (rule 2: a cell choice is a fork Goal/cost, never a mod-side scan); this class only names the request.
 * <ul>
 * <li>Centre: the base spot when the feet are within {@code station.baseReachM} of it (its walk stays the place step's
 *     own short leg, so the place price holds), else the feet.</li>
 * <li>Surface: the feet are not under ground by the planner's own test ({@link NeedChain.Surface#under} on the one
 *     reading, {@link NeedChain.Surface#atFeet}: the rock over the head and the hole round the feet over the ground
 *     under any tree). Merge review 2026-11-25 findings 11 and 16 (rule 23 (2)): the place step's own leaf-free column and
 *     ring test (job-review-leftovers, run 3 w005) is gone, so the plan and the place step never disagree in one cell (a
 *     1-3 deep shaft open to the sky, a grove of trunks round the feet). Under ground the cell stays within a block of
 *     the feet's height, in a cave chamber with room.</li>
 * </ul>
 * Pure (JUnit: D14StationsTest). Loader notes: none here; the live fact is {@code commands.Jobs.feetSurface}.
 */
public final class StationPlace {
    private StationPlace() {}

    /** The base spot {x, y, z} the sense round set last (AaRuntime, the same value as Inputs.basePos); null: none set. */
    public static volatile int[] base;

    /** The request: centre, reach, surface; {@code why} the words for the log. */
    public record Ask(int[] center, int radius, boolean surface, String why) {}

    /**
     * The request for a station placed from {@code feet} whose surface fact is {@code at} (Jobs.feetSurface; null: the
     * surface over the column is not known - never claimed, rule 15: the under-ground request stands).
     */
    public static Ask ask(int[] feet, NeedChain.Surface at, int[] baseSpot) {
        int r = Tuning.i("steps.placeNearR");
        if (baseSpot != null && baseSpot.length >= 3 && feet != null) {      // structural: x y z
            double dx = baseSpot[0] - feet[0], dy = baseSpot[1] - feet[1], dz = baseSpot[2] - feet[2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= Tuning.get("station.baseReachM"))
                return new Ask(baseSpot.clone(), r, true, "near the base spot " + baseSpot[0] + " " + baseSpot[1] + " " + baseSpot[2] + " on the surface");
        }
        boolean up = at != null && !at.under();
        boolean shaft = at != null && !up && at.hole();
        return new Ask(feet.clone(), r, up, up ? "near me on the surface"
                : at == null ? "near me under ground (a cell with room at my height; the surface over me is not known)"
                : shaft ? "near me under ground (a cell with room at my height; my column is open to the sky but I am " + at.depth() + " below the ground round it)"
                : "near me under ground (a cell with room at my height)");
    }
}
