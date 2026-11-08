package io.github.mojolowjo.entropybot.aa.moves;

import java.util.List;

/**
 * job-dig2 (run JAU 05:38:39-05:38:44: the dig leg asked a new priced path from its fresh feet, the 46-level search could not
 * land inside the fork's 2000 ms ask, the leg ended twice "not known" and the park rule parked the order): what the dig-down
 * leg does when its price is not known.
 * <ul>
 * <li>The ask is NOT KNOWN (timed out, no estimate) or still computing past {@code band.askWaitS}, or the priced walk ended
 * without an exhausted search: the leg hands Baritone the dig goal itself (GoalBlock at the band Y under the feet, its own
 * search with planAhead partial paths, the TRAVEL lease) - it never ends there. The band price (BandPrice's seconds) stands
 * as the leg's price until a real path lands (rule 15: not known is never unreachable, never a failure).</li>
 * <li>A landed dig price of a column within {@code band.columnReuseM} of the feet (at the band Y) is reused: the leg digs
 * that column, no new ask.</li>
 * </ul>
 * Pure (JUnit: Dig2Test). Loader notes: none.
 */
public final class BandDirect {
    private BandDirect() {}

    /** Hand the dig to Baritone's own search now: the act is not known, or a wait ran past {@code askWaitS}. */
    public static boolean handOver(BandLeg.Act act, long waitedMs, double askWaitS) {
        if (act == BandLeg.Act.NOT_KNOWN) return true;
        return act == BandLeg.Act.WAIT && waitedMs >= (long) (askWaitS * 1000);     // structural: s to ms
    }

    /** The leg's line when it digs on Baritone's own path ({@code why}: why the price is not known). */
    public static String words(int y, String why) {
        return "dig down to y " + y + ": the price is not known yet (" + why + "); digging on Baritone's own path, the band price stands";
    }

    /** The reuse line. */
    public static String reuseWords(int[] col, double m) {
        return "reusing the priced dig column " + col[0] + " " + col[1] + " " + col[2] + " (" + Math.round(m) + " m from the feet, band.columnReuseM): no new ask";
    }

    /**
     * The nearest landed dig column ({x, y, z}, its y the band Y {@code y}) within {@code maxM} blocks (horizontal) of
     * {@code feet}; null: none.
     */
    public static int[] reuse(List<int[]> landed, int[] feet, int y, double maxM) {
        if (landed == null || feet == null || feet.length < 3 || maxM <= 0) return null;     // structural: x y z
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] c : landed) {
            if (c == null || c.length < 3 || c[1] != y) continue;     // structural: x y z
            double d = horiz(c, feet);
            if (d <= maxM && d < bestD) { best = c; bestD = d; }
        }
        return best == null ? null : best.clone();
    }

    /**
     * job-dig3 (run JAV 06:00:54): the dry-start rule for every start of the dig leg, the reused column included: the start
     * dry (BandDry.dry) and, when a column is reused ({@code colFluid} non-null), that column's first cells dry too.
     */
    public static boolean startDry(boolean standable, boolean feetInWater, List<Boolean> startFluid, List<Boolean> colFluid) {
        if (!BandDry.dry(standable, feetInWater, startFluid)) return false;
        return colFluid == null || BandDry.dry(true, false, colFluid);
    }

    public static double horiz(int[] a, int[] b) {
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }
}
