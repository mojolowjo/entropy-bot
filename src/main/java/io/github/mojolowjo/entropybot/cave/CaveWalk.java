package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;

import java.util.List;
import java.util.Locale;

/**
 * 0.39.z78 J6b (D12): the caving step's decisions inside a KNOWN cave, on its record (the one store). In order:
 * <ol>
 * <li>the need met (the ore blocks mined) - ends, NEED_MET;</li>
 * <li>the step's minutes ({@code cave.exploreMinutes} at most, the cave way's cap) - ends, MINUTES;</li>
 * <li>a seen ore of the family left in reach of the eyes - mined by the vein (seen ore only, rule 17);</li>
 * <li>a seen ore of the family left farther in - walked to;</li>
 * <li>the nearest branch (a frontier group with more than BRANCH_MIN unseen cells behind it), ranked by straight distance
 * (rule 18 as amended: ranked rough, Baritone's walk exact) - its first cell walked to;</li>
 * <li>none: the cave is done (DONE), or its cells are not loaded (NOT_LOADED: the record cannot say where to go).</li>
 * </ol>
 * Baritone's no progress on a walk ends the step too (NO_PROGRESS; the adapter's hold, ExploreRules.TargetHold). Torches
 * are not in this slice (D12 5). Pure (JUnit: J6bCaveStepTest). Loader notes: none (StepHands is the adapter).
 */
public final class CaveWalk {
    private CaveWalk() {}

    public enum End { NEED_MET, MINUTES, DONE, NOT_LOADED, NO_PROGRESS }

    public sealed interface Next permits Mine, Goto, Stop {}

    /** Mine the seen ore cell (its vein). */
    public record Mine(int[] cell) implements Next {}

    /** Walk to the cell ({@code why}: "the seen iron at ...", "the branch at ... (N cells behind)"). */
    public record Goto(int[] cell, String why) implements Next {}

    /** The step ends. */
    public record Stop(End end, String why) implements Next {}

    /**
     * The next move. {@code mined}/{@code need}: ore blocks; {@code minutesIn}/{@code capMinutes}: the step's time inside;
     * {@code facts}: the record now; {@code frontier}: its branches; {@code left}: the family's seen ore not mined;
     * {@code feet}: x y z; {@code reach}: the eyes' reach for a vein.
     */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<CaveRecord.Group> frontier,
                            List<int[]> left, int[] feet, double reach) {
        if (need > 0 && mined >= need) return new Stop(End.NEED_MET, mined + " of " + need + " ore mined");
        if (capMinutes > 0 && minutesIn >= capMinutes) return new Stop(End.MINUTES, String.format(Locale.ROOT, "%.1f min inside, cave.exploreMinutes %.0f", minutesIn, capMinutes));
        int[] near = null;
        long nd = Long.MAX_VALUE;
        for (int[] o : left) { long d = d2(o, feet); if (d < nd) { nd = d; near = o; } }
        if (near != null && ExploreRules.withinReach(feet, near)) return new Mine(near);
        if (near != null) return new Goto(near, "the seen ore at " + near[0] + " " + near[1] + " " + near[2]);
        CaveRecord.Group best = null;
        long bd = Long.MAX_VALUE;
        for (CaveRecord.Group g : frontier) {
            if (g.behind() <= CaveRecord.BRANCH_MIN) continue;
            long d = d2(Sightings.cellOf(g.first()), feet);
            if (d < bd) { bd = d; best = g; }
        }
        if (best != null) {
            int[] c = Sightings.cellOf(best.first());
            return new Goto(c, "the branch at " + c[0] + " " + c[1] + " " + c[2] + " (" + best.behind() + " cells behind)");
        }
        if (facts != null && !facts.loaded()) return new Stop(End.NOT_LOADED, "the cave's cells are not loaded (its chunks: the record cannot say where to go)");
        return new Stop(End.DONE, "the cave is explored to its end (no branch of more than " + CaveRecord.BRANCH_MIN + " cells left)");
    }

    /**
     * D12's measure on the step's end line: "cave: the caving step at x y z ended - NEED_MET (3 of 3 ore mined): 2.4 min
     * inside, cells seen 40 -> 120 of 200 (explored 60%), ore seen 4 / mined 3, branches left 2".
     */
    public static String endLine(String where, End end, String why, double minutes, int seenBefore, CaveRecord.Facts after, int oreSeen, int mined) {
        int seenNow = after == null ? seenBefore : after.cellCount() - after.unseen();
        return String.format(Locale.ROOT, "cave: the caving step at %s ended - %s (%s): %.1f min inside, cells seen %d -> %d of %d (explored %d%%), ore seen %d / mined %d, branches left %s",
                where, end, why, minutes, seenBefore, seenNow, after == null ? 0 : after.cellCount(), after == null ? 0 : Math.round(after.explored() * 100), oreSeen, mined,   // structural: a percent
                after == null || after.groups() < 0 ? "not measured" : String.valueOf(after.groups()));
    }

    private static long d2(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
