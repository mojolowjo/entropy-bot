package io.github.mojolowjo.entropybot.restore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * aa-litter: the blocks the bot PLACED on the way (the scaffold of a walk, a pillar, a bridge) in the restore ledger.
 * {@code built: litter} = standing scaffold the step's end takes down ({@code restore placed [r]}); {@code built: kept
 * <kind> at x y z ...} = a structure the bot decided to keep (reuse paid for it): the bot's own, never ripped out later
 * ({@code restore placed} skips it, {@link RestorePlan} never re-places it). Pure (JUnit: PlacedPlanTest).
 */
public final class PlacedPlan {
    private PlacedPlan() {}

    public static final String LITTER = "built: litter", KEPT = "built: kept ";
    /** The walk-back limit of "restore placed" without a radius. */
    public static final int DEFAULT_RADIUS = 24;

    public static boolean litter(Ledger.Entry e) { return e != null && LITTER.equals(e.why); }

    public static boolean kept(Ledger.Entry e) { return e != null && e.why != null && e.why.startsWith(KEPT); }

    /** The ledger note of a kept structure: "built: kept bridge at 10 64 -3 (reuse 120 s vs cleanup 40 s)". */
    public static String keptWhy(String kind, int x, int y, int z, String reason) {
        return KEPT + (kind == null || kind.isBlank() ? "structure" : kind.trim()) + " at " + x + " " + y + " " + z
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")");
    }

    /**
     * The litter entries to take down: confirmed, in this dimension, within {@code maxDist} of {@code center} (the others
     * are Left "too far"), the nearest first (what is in reach comes down first; a column is taken from its foot). Kept entries are never
     * in it.
     */
    public static RestorePlan.Plan plan(List<Ledger.Entry> entries, int[] center, String dim, int maxDist) {
        List<Ledger.Entry> order = new ArrayList<>();
        for (Ledger.Entry e : entries) if (litter(e) && !e.pending) order.add(e);
        order.sort(Comparator.comparingLong((Ledger.Entry e) -> d2(e, center)).thenComparingInt(e -> e.y));
        List<RestorePlan.Action> actions = new ArrayList<>();
        List<RestorePlan.Left> left = new ArrayList<>();
        long max2 = (long) maxDist * maxDist;
        for (Ledger.Entry e : order) {
            if (dim != null && !dim.equals(e.dim)) left.add(new RestorePlan.Left(e, "too far"));
            else if (d2(e, center) > max2) left.add(new RestorePlan.Left(e, "too far"));
            else actions.add(new RestorePlan.Action(e, null));
        }
        return new RestorePlan.Plan(actions, left);
    }

    private static long d2(Ledger.Entry e, int[] c) {
        long dx = e.x - c[0], dy = e.y - c[1], dz = e.z - c[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** "took down 3 blocks" / "took down 3 blocks, 2 left: couldn't reach (restore placed)"; null when there was nothing. */
    public static String summary(int removed, List<RestorePlan.Left> left) {
        if (removed == 0 && (left == null || left.isEmpty())) return null;
        StringBuilder sb = new StringBuilder();
        if (removed > 0) sb.append("took down ").append(removed).append(removed == 1 ? " block" : " blocks");
        if (left != null && !left.isEmpty()) {
            Map<String, Integer> by = new LinkedHashMap<>();
            for (RestorePlan.Left l : left) by.merge(l.why(), 1, Integer::sum);
            if (sb.length() > 0) sb.append(", ");
            sb.append(left.size()).append(" left: ").append(String.join(", ", by.keySet())).append(" (restore placed)");
        }
        return sb.toString();
    }

    /** True when the end line is a failure for the chain: something is left standing. */
    public static boolean failed(int removed, List<RestorePlan.Left> left) {
        return left != null && !left.isEmpty();
    }

    /** {@code aa litter}: what is standing to clean and what was kept, with the reason. */
    public static String text(List<Ledger.Entry> all) {
        List<Ledger.Entry> lit = new ArrayList<>(), keep = new ArrayList<>();
        for (Ledger.Entry e : all) {
            if (litter(e)) lit.add(e);
            else if (kept(e)) keep.add(e);
        }
        if (lit.isEmpty() && keep.isEmpty()) return "aa litter: nothing placed and left standing";
        StringBuilder sb = new StringBuilder("aa litter: ").append(lit.size()).append(" to take down, ").append(keep.size()).append(" kept");
        if (!lit.isEmpty()) {
            Map<String, Integer> by = new LinkedHashMap<>();
            for (Ledger.Entry e : lit) by.merge(RestoreRules.shortId(e.block), 1, Integer::sum);
            List<String> parts = new ArrayList<>();
            by.forEach((k, v) -> parts.add(v + " " + k));
            Ledger.Entry top = lit.get(lit.size() - 1);
            sb.append("\n  standing: ").append(String.join(", ", parts)).append(" (last at ").append(top.x).append(' ').append(top.y).append(' ').append(top.z).append("; restore placed takes them down)");
        }
        if (!keep.isEmpty()) {
            Map<String, Integer> by = new LinkedHashMap<>();
            for (Ledger.Entry e : keep) {
                String k = e.why.substring(KEPT.length());
                by.merge(k, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> en : by.entrySet()) sb.append("\n  kept: ").append(en.getKey()).append(" - ").append(en.getValue()).append(en.getValue() == 1 ? " block" : " blocks");
        }
        return sb.toString();
    }
}

