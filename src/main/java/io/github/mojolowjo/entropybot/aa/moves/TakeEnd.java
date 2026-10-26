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
        if (got >= 1) return Verdict.DONE;
        if (pickupUntil < 0 && breaking) return Verdict.RUNNING;
        if (!stands) {
            if (pickupUntil < 0) return Verdict.PICKUP;
            if (now < pickupUntil) return Verdict.RUNNING;
        }
        return Verdict.SHORT;
    }

    /** The short end's words: what the take saw, so the fact names it. */
    public static String shortText(String step, boolean stands, String chopEnd, boolean pickedUpWaited) {
        return step + ": short by 1: " + (stands ? "the station still stands (the break did not happen: " + chopEnd + ")"
                : "the station was broken, its drop did not reach the bag" + (pickedUpWaited ? " within station.takePickupS" : "") + " (" + chopEnd + ")");
    }
}
