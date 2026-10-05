package io.github.mojolowjo.entropybot.restore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1: the order and the items of a restore. Newest first (a tunnel closes from its far end back), only entries within
 * {@code maxDist} of the spot the restore starts from, each with the first of its items the bag still holds (counted
 * down as they are given out). What can't be done is a {@link Left} with the reason the end line names. Loader-neutral.
 */
public final class RestorePlan {
    private RestorePlan() {}

    /** The walk-back limit after a job (further entries wait for "restore now"); restore now's default radius. */
    public static final int AFTER_JOB = 24, NOW_DEFAULT = 32;

    public record Action(Ledger.Entry entry, String item) {}

    public record Left(Ledger.Entry entry, String why) {}

    public record Plan(List<Action> actions, List<Left> left) {}

    public static Plan plan(List<Ledger.Entry> entries, int[] center, String dim, int maxDist, Map<String, Integer> inventory) {
        Map<String, Integer> inv = new LinkedHashMap<>(inventory == null ? Map.of() : inventory);
        List<Ledger.Entry> order = new ArrayList<>(entries);
        order.sort(Comparator.comparingLong((Ledger.Entry e) -> e.seq).reversed());
        List<Action> actions = new ArrayList<>();
        List<Left> left = new ArrayList<>();
        long max2 = (long) maxDist * maxDist;
        for (Ledger.Entry e : order) {
            if (e.pending) continue;
            if (!e.restorable()) {
                left.add(new Left(e, e.why == null ? "not terrain" : e.why));
                continue;
            }
            if (dim != null && !dim.equals(e.dim)) {
                left.add(new Left(e, "too far"));
                continue;
            }
            long dx = e.x - center[0], dy = e.y - center[1], dz = e.z - center[2];
            if (dx * dx + dy * dy + dz * dz > max2) {
                left.add(new Left(e, "too far"));
                continue;
            }
            String item = null;
            for (String c : e.items) {
                if (inv.getOrDefault(c, 0) > 0) {
                    item = c;
                    break;
                }
            }
            if (item == null) {
                left.add(new Left(e, "no " + RestoreRules.shortId(e.items.get(e.items.size() - 1))));
                continue;
            }
            inv.merge(item, -1, Integer::sum);
            actions.add(new Action(e, item));
        }
        return new Plan(actions, left);
    }

    /**
     * The end line's part: "put back 3 blocks", "put back 3 blocks, 1 left: no cobblestone (restore now when you have
     * it)", "2 left: too far (restore now)". Null when there was nothing to put back.
     */
    public static String summary(int placed, List<Left> left) {
        if (placed == 0 && (left == null || left.isEmpty())) return null;
        StringBuilder sb = new StringBuilder();
        if (placed > 0) sb.append("put back ").append(placed).append(placed == 1 ? " block" : " blocks");
        if (left != null && !left.isEmpty()) {
            Map<String, Integer> by = new LinkedHashMap<>();
            for (Left l : left) by.merge(l.why(), 1, Integer::sum);
            List<String> parts = new ArrayList<>();
            by.forEach((k, v) -> parts.add(k));
            if (sb.length() > 0) sb.append(", ");
            sb.append(left.size()).append(" left: ").append(String.join(", ", parts));
            boolean fixable = false;
            for (String k : by.keySet()) if (k.startsWith("no ") || k.equals("too far") || k.equals("occupied") || k.startsWith("couldn't")) fixable = true;
            if (fixable) sb.append(by.keySet().stream().anyMatch(k -> k.startsWith("no ")) ? " (restore now when you have it)" : " (restore now)");
        }
        return sb.toString();
    }
}
