package io.github.mojolowjo.entropybot.camp;

/**
 * 0.28.1 (trial fix G, docs/TRIAL_FIX_PLAN.md): how many blocks a {@code build floor|walls|shell|fill} needs, said at the
 * start, and the refusal when the bag has under a tenth of it. Pure (JUnit: {@code TrialFixTest}).
 *
 * <p>Shapes as Baritone's {@code sel} ops place them: floor = the bottom layer, fill = the whole box, walls = the four
 * sides, shell = all six faces. Blocks already standing are skipped by the build ({@code buildIgnoreExisting}), so the
 * count is the most it can need.
 *
 * <p>Loader notes: none (no game classes).
 */
public final class BuildCount {
    private BuildCount() {}

    /** The blocks the shape needs over the box (corners in any order); -1 for an unknown shape. */
    public static int need(String shape, int x1, int y1, int z1, int x2, int y2, int z2) {
        long dx = Math.abs(x2 - x1) + 1L, dy = Math.abs(y2 - y1) + 1L, dz = Math.abs(z2 - z1) + 1L;
        long n;
        switch (shape == null ? "" : shape) {
            case "floor", "roof" -> n = dx * dz;          // 0.28.2: roof = the top layer
            case "fill" -> n = dx * dy * dz;
            case "walls" -> n = dx * dy * dz - Math.max(0, dx - 2) * dy * Math.max(0, dz - 2);
            case "shell" -> n = dx * dy * dz - Math.max(0, dx - 2) * Math.max(0, dy - 2) * Math.max(0, dz - 2);
            default -> { return -1; }
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** The start note: "(needs 170 cobblestone, you have 40 - gather cobblestone 130 first)", or "(needs 50 cobblestone, you have 64)". */
    public static String note(int need, int have, String item) {
        if (have >= need) return "(needs " + need + " " + item + ", you have " + have + ")";
        return "(needs " + need + " " + item + ", you have " + have + " - gather " + item + " " + (need - have) + " first)";
    }

    /** Null when it may start, else the refusal: fewer than 10% of the blocks it needs. */
    public static String refusal(int need, int have, String item) {
        if (need <= 0 || have * 10L >= need) return null;
        return "error: that needs " + need + " " + item + " and I have " + have + " - next: gather " + item + " " + (need - have) + ", then build again";
    }
}
