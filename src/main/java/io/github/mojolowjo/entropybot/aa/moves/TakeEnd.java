package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z75 take (run AI 20:06:10: "take crafting_table at -34 70 -55: cluster 1 block: picked up 0 of 1" - a plain stop, the
 * table still standing): when a station take is over, decided on the observed state only - the station in the bag ends it
 * DONE, never "I broke it". While the fork's component break runs: running. Once it ended and the station block is gone but
 * not in the bag, its drop is Baritone's to pick up (the stock follow process's pickup), and the step waits for the bag
 * up to {@code station.takePickupS}; then, or with the block still standing, the take ends short with its typed fact
 * (FactChange.take: the order re-plans without that take). Pure (JUnit: Z75UndergroundFuelTest). Loader notes: none.
 */
public final class TakeEnd {
    private TakeEnd() {}

    public enum Verdict { DONE, RUNNING, PICKUP, SHORT }

    /**
     * @param got          the station items the bag gained since the step started
     * @param breaking     the fork's break of the station still runs
     * @param stands       the station block still stands at its cell
     * @param now          ticks now; {@code pickupUntil}: the tick the pickup wait ends (-1: no pickup started)
     */
    public static Verdict judge(int got, boolean breaking, boolean stands, long now, long pickupUntil) {
        return judge(got, breaking, stands, now, pickupUntil, Long.MAX_VALUE);
    }

    /** z75 review 2: the fork's break gets a second to take control (its process inactive on the first ticks is no end). */
    static final long TAKE_CONTROL_TICKS = 20; // structural: one second (20 game ticks) for a started process to show active

    /** As above; {@code sinceStart}: ticks since the take started (a break not active yet on the first second: running). */
    public static Verdict judge(int got, boolean breaking, boolean stands, long now, long pickupUntil, long sinceStart) {
        if (got >= 1) return Verdict.DONE;
        if (pickupUntil < 0 && breaking) return Verdict.RUNNING;
        if (pickupUntil < 0 && stands && sinceStart < TAKE_CONTROL_TICKS) return Verdict.RUNNING;
        if (!stands) {
            if (pickupUntil < 0) return Verdict.PICKUP;
            if (now < pickupUntil) return Verdict.RUNNING;
        }
        return Verdict.SHORT;
    }

    /** The short end's words: what the take saw, so the fact names it. */
    public static String shortText(String step, boolean stands, String chopEnd, boolean pickedUpWaited) {
        return shortText(step, stands, chopEnd, pickedUpWaited, null);
    }

    /**
     * Take 2 (run AJ 20:23:41 / 20:24:55): with {@code facts} - what the take saw that the fork's in-reach break needs (on the
     * ground at the start, the eyes' distance to the block, a liquid or falling block beside or above it) and the chop's own
     * last words - so the next run says which precondition failed (rule 7). Null facts: the words as before.
     */
    public static String shortText(String step, boolean stands, String chopEnd, boolean pickedUpWaited, String facts) {
        return step + ": short by 1: " + (stands ? "the station still stands (the break did not happen: " + chopEnd + ")"
                : "the station was broken, its drop did not reach the bag" + (pickedUpWaited ? " within station.takePickupS" : "") + " (" + chopEnd + ")")
                + (facts == null || facts.isEmpty() ? "" : " [" + facts + "]");
    }
}
