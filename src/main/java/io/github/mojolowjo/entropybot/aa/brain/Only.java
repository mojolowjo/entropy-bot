package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;

/**
 * simple-tools (0.39.w1): {@code aa only <need>|off}. While a need is named, every other need's and want's score is multiplied by
 * the Tuning entry {@code only.otherWeight} (default 0), so the owner can watch one chain at a time. Kept at full weight: the
 * named need, the danger desire, the owner's own goals and orders (OWNER, GATE, a score of 100 or more) and, outside the brain,
 * every reflex.
 *
 * <p>orders-tier (0.39.z9, owner 2026-10-22 "AA only takes requests for tasks"): the switch's default value {@link #ORDERS}
 * silences every need, want, night desire and the idle set (its stock-ups and its wait) at 0, so a 0 never starts and with no
 * order the brain picks nothing. Danger, the owner's goals and orders, and the reflexes stay. {@link #NEEDS} is the full set
 * ({@code aa needs on}). Saved per world in AaSwitch; the static default here is the full set (pure tests). JUnit: OnlyTest.
 */
public final class Only {
    private Only() {}

    /** The switch values beside a need's name. */
    public static final String ORDERS = "orders", NEEDS = "needs";

    private static volatile String need;

    /** The need being watched ("torches"), {@link #ORDERS}, or null: the full set. */
    public static String need() { return need; }

    /** True in orders-only mode. */
    public static boolean orders() { return ORDERS.equals(need); }

    /** Sets (null, "off", "none" or "needs" clears to the full set; "orders" = orders only) the switch; returns the value now set. */
    public static String set(String n) {
        String t = n == null ? "" : n.trim().toLowerCase(java.util.Locale.ROOT);
        need = t.isEmpty() || t.equals("off") || t.equals("none") || t.equals(NEEDS) ? null : t;
        return need;
    }

    /** True when {@code id} is the watched need: its full id ("needs.torches"), its last word ("torches"), or the id after "needs.". */
    public static boolean is(String id) {
        String n = need;
        return n != null && !n.equals(ORDERS) && (id.equals(n) || id.equals("needs." + n) || id.endsWith("." + n));
    }

    /** The factor on a desire's score now: 1 when off, for the watched need, danger, the owner's goals; else {@code only.otherWeight}. */
    public static double factor(String id, Desire.Kind kind) {
        String n = need;
        if (n == null) return 1;
        if (kind == Desire.Kind.OWNER || kind == Desire.Kind.GATE) return 1;
        // 0.39.z11 (owner 2026-10-22 "turn off the danger orders for now"): in orders-only mode the danger desire is
        // silent too (it pillared for the night with no order running); the true reflexes (air, lava, fire, creeper,
        // eat, drink) are outside the brain and stay. In every other mode danger keeps its full score.
        // owner 2026-10-29: the report-back spot is the owner's fallback, in orders-only mode too: the idle desire keeps its score
        // (its best option's) and the report-back option keeps its own; the other idle options, the wait among them, are
        // silenced as before, so with no base place set the idle desire scores 0 and the bot stands as it did
        if (id.equals(AaDesires.REPORT_BACK_ID) || id.equals(AaDesires.IDLE_ID)) return 1;
        if (n.equals(ORDERS)) return 0;          // structural: orders only silences every desire but the owner's
        if (id.equals(AaDesires.DANGER_ID)) return 1;
        if (is(id)) return 1;
        if (id.equals(AaDesires.WAIT_ID)) return 1;      // chop C: idle stays a plain wait (the idle desire scores its best option)
        return Tuning.get("only.otherWeight");
    }

    /** A score under the switch: positive scores below the order line are scaled, the rest stay. */
    public static double apply(String id, Desire.Kind kind, double score) {
        if (score <= 0 || score >= 100) return score;     // structural: the score scale tops at 100 (the owner's aa do)
        return score * factor(id, kind);
    }

    /** The line for {@code aa status} beside the mode: "only: torches", or null when the full set or orders only. */
    public static String statusText() { return need == null || orders() ? null : "only: " + need; }

    /** The mode for the first line of {@code aa status}: "orders only", "needs on", or "only: torches". */
    public static String modeText() { return need == null ? "needs on" : orders() ? "orders only" : "only: " + need; }
}
