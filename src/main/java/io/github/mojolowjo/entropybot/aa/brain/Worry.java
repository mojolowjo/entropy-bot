package io.github.mojolowjo.entropybot.aa.brain;

/**
 * needs-ruling 0.39.z32 (owner 2026-10-23, NEEDS_RULING 1): while an order runs ({@code aa needs on}), the needs, wants,
 * night desires and the idle set are silent (score 0, "not wanted: an order runs ..."), except the eating need and the
 * danger desire at or over their worry line (Tuning {@code needs.worryFood}, {@code needs.worryDanger}): those keep their
 * score and preempt the order's step (Brain.challenger); when their task ends the brain picks the order again and its
 * chain is priced from the bag of now (Goals.steps). With no order every desire scores as before. {@code aa needs off}
 * (orders only, {@link Only#ORDERS}) is unchanged: Only silences them all first. Pure. JUnit: WorryTest, ScenariosTest S32/S33.
 */
public final class Worry {
    private Worry() {}

    /** The worry line of a desire (Tuning), or NaN: it has none (it never preempts an order). */
    public static double line(Desire d) {
        return d.preemptsOrder ? Tuning.get(d.worryKey) : Double.NaN;
    }

    /** The desire is at or over its worry line. */
    public static boolean over(Desire d, double score) {
        double l = line(d);
        return !Double.isNaN(l) && score >= l;
    }

    /** True when an order gates the others: the full set is on (no 'aa only', not orders only) and an order is wanted. */
    public static boolean gates(boolean orderWanted) { return orderWanted && Only.need() == null; }

    /**
     * The gate on one desire while an order is wanted: null = it keeps its score (the owner's own, a gate, a score of 0 or
     * less or of 100 = the owner's aa do, or a worry over its line); else the readable reason it is silent.
     */
    public static String silences(Desire d, double score) {
        Desire.Kind kind = d.kind;
        String id = d.id;
        if (kind == Desire.Kind.OWNER || kind == Desire.Kind.GATE || score <= 0 || score >= 100) return null;  // structural: the score scale tops at 100 (the owner's aa do)
        if (over(d, score)) return null;
        double l = line(d);
        return "an order runs (" + (Double.isNaN(l) ? "only food and danger preempt it" : "worry " + id + " " + Math.round(score) + " < " + Math.round(l)) + ")";
    }

    /** The aa why line of a worry over its line: "worry needs.food 65 >= 60". */
    public static String text(Desire d, double score) {
        return "worry " + d.id + " " + Math.round(score) + " >= " + Math.round(line(d));
    }
}
