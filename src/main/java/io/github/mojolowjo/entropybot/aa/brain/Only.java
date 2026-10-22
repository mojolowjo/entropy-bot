package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;

/**
 * simple-tools (0.39.w1): {@code aa only <need>|off}. While a need is named, every other need's and want's score is multiplied by
 * the Tuning entry {@code only.otherWeight} (default 0), so the owner can watch one chain at a time. Kept at full weight: the
 * named need, the danger desire, the owner's own goals and orders (OWNER, GATE, a score of 100 or more) and, outside the brain,
 * every reflex. Not saved: a restart turns it off. Pure (JUnit: OnlyTest).
 */
public final class Only {
    private Only() {}

    private static volatile String need;

    /** The need being watched ("tools"), null: off. */
    public static String need() { return need; }

    /** Sets (null or "off" clears) the watched need; returns the need now set. */
    public static String set(String n) {
        String t = n == null ? "" : n.trim().toLowerCase(java.util.Locale.ROOT);
        need = t.isEmpty() || t.equals("off") || t.equals("none") ? null : t;
        return need;
    }

    /** True when {@code id} is the watched need: its full id ("needs.tools"), its last word ("tools"), or the id after "needs.". */
    public static boolean is(String id) {
        String n = need;
        return n != null && (id.equals(n) || id.equals("needs." + n) || id.endsWith("." + n));
    }

    /** The factor on a desire's score now: 1 when off, for the watched need, danger, the owner's goals; else {@code only.otherWeight}. */
    public static double factor(String id, Desire.Kind kind) {
        if (need == null) return 1;
        if (is(id) || id.equals(AaDesires.DANGER_ID) || kind == Desire.Kind.OWNER || kind == Desire.Kind.GATE) return 1;
        return Tuning.get("only.otherWeight");
    }

    /** A score under the switch: positive scores below the order line are scaled, the rest stay. */
    public static double apply(String id, Desire.Kind kind, double score) {
        if (score <= 0 || score >= 100) return score;     // structural: the score scale tops at 100 (the owner's aa do)
        return score * factor(id, kind);
    }

    /** The line for {@code aa status}: "only: tools", or null when off. */
    public static String statusText() { return need == null ? null : "only: " + need; }
}
