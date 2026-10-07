package io.github.mojolowjo.entropybot.restore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * P1 (owner's answer 1): someone else's build near a dig. Given the built blocks (the guard's protected kinds) a job's
 * scan found within {@link #RADIUS} of its work spot and outside every protect box, the biggest cluster of at least
 * {@link #MIN_BLOCKS} (blocks within 3 of each other) becomes a suggested protect box: its bounds grown by
 * {@link #GROW}. Never protects anything itself. Loader-neutral.
 */
public final class BuildSpotter {
    private BuildSpotter() {}

    public static final int RADIUS = 8, MIN_BLOCKS = 6, GROW = 2, LINK = 3;

    /** box = x1 y1 z1 x2 y2 z2. */
    public record Hint(int[] center, int[] box, int count, String name) {
        public String key() { return key(center); }

        public String whisper() {
            return "looks like a build at " + center[0] + " " + center[1] + " " + center[2] + " - protect it? (" + command() + ")";
        }

        /** 0.23.4: the walk's hint (a walk that passes a build no safe or main area covers). */
        public String walkWhisper() {
            return "looks like a build at " + center[0] + " " + center[1] + " " + center[2] + " - area here 8 " + name + " safe?";
        }

        public String command() {
            return "area " + box[0] + " " + box[2] + " " + box[3] + " " + box[5] + " " + name + " safe " + box[1] + " " + box[4];
        }

        public static String key(int[] c) {
            return Math.floorDiv(c[0], 16) + " " + Math.floorDiv(c[1], 16) + " " + Math.floorDiv(c[2], 16);
        }
    }

    public static Hint spot(List<int[]> built) {
        if (built == null || built.size() < MIN_BLOCKS) return null;
        boolean[] seen = new boolean[built.size()];
        List<int[]> best = null;
        for (int i = 0; i < built.size(); i++) {
            if (seen[i]) continue;
            List<int[]> group = new ArrayList<>();
            Deque<Integer> q = new ArrayDeque<>();
            q.add(i);
            seen[i] = true;
            while (!q.isEmpty()) {
                int a = q.poll();
                group.add(built.get(a));
                for (int b = 0; b < built.size(); b++) {
                    if (seen[b] || !near(built.get(a), built.get(b))) continue;
                    seen[b] = true;
                    q.add(b);
                }
            }
            if (best == null || group.size() > best.size()) best = group;
        }
        if (best.size() < MIN_BLOCKS) return null;
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] p : best) {
            for (int k = 0; k < 3; k++) {
                box[k] = Math.min(box[k], p[k]);
                box[k + 3] = Math.max(box[k + 3], p[k]);
            }
        }
        for (int k = 0; k < 3; k++) {
            box[k] -= GROW;
            box[k + 3] += GROW;
        }
        int[] c = {(box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2};
        String name = ("build_" + c[0] + "_" + c[2]).replace("-", "m");
        if (name.length() > 24) name = name.substring(0, 24);
        return new Hint(c, box, best.size(), name);
    }

    /** 0.23.4: a walk's build hint at most once a minute. */
    public static final long WALK_HINT_MS = 60_000;

    public static boolean walkHintDue(long lastAt, long now) {
        return lastAt <= 0 || now - lastAt >= WALK_HINT_MS;
    }

    /**
     * 0.23.4: may a walk whisper about this cluster? Never when its centre lies in a safe or main area (typeAtCenter
     * "safe"/"main"), never in the near-me zone around the owner, never twice for one spot (mentioned).
     */
    public static boolean walkHintAllowed(String typeAtCenter, boolean inNearZone, boolean mentioned) {
        if (mentioned || inNearZone) return false;
        return !"safe".equals(typeAtCenter) && !"main".equals(typeAtCenter);
    }

    private static boolean near(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) <= LINK && Math.abs(a[1] - b[1]) <= LINK && Math.abs(a[2] - b[2]) <= LINK;
    }

    /** The block ids a build hint never counts: what the bot puts down itself (torches, its mine's table and chests). */
    public static boolean ownKind(String blockId) {
        String s = RestoreRules.shortId(blockId);
        return s.endsWith("torch") || s.equals("crafting_table") || s.equals("chest") || s.equals("cobblestone");
    }
}
