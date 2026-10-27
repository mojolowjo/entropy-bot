package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.GameTimes;

/**
 * 0.39.z76 send-back 3 (rule 22, owner 2026-10-26: "the bot moves only as a priced step of the current order. Waiting is
 * standing"; run AU 01:57:53-02:02:53: the deleted "explore while paths compute" walked for 5 min while the walks it waited
 * on were searched again from every new cell): the standing step's clock ({@link Step.AwaitPaths}). It stands - nothing is
 * started, Baritone idle - up to its {@code waitS} in GAME ticks (a paused game does not burn it); each landing prices the
 * order again where it stands (Steps). Past the wait it is over, with the words the log and the re-price carry: the walks
 * still not known are then taken by Baritone's estimate ("ranked, not priced", rule 18 as amended). Replaces the deleted
 * FirstAnswerWait (it decided when to start exploring). Pure (JUnit: Z76StandTest). Loader notes: none (the hands read
 * Level#getGameTime).
 */
public final class StandWait {
    private long start = -1;
    private boolean said;

    /** The stand begins now (game ticks). */
    public void begin(long now) {
        start = now;
        said = false;
    }

    public boolean standing() { return start >= 0; }

    public void end() { start = -1; }

    /** Game ticks stood so far (0 before {@link #begin}). */
    public long stood(long now) { return start < 0 ? 0 : Math.max(0, now - start); }

    /** The wait is over: {@code waitS} seconds of game time stood. */
    public boolean over(long now, int waitS) { return start >= 0 && stood(now) >= (long) waitS * GameTimes.TPS; }

    /** The status words while standing ("standing 4.0 s of 30 while 2 walks compute; Baritone idle, no move"). */
    public String words(long now, int waitS, int computing) {
        return "standing " + secs(stood(now)) + " s of " + waitS + " while " + (computing < 0 ? "the walks" : computing + " walk" + (computing == 1 ? "" : "s"))
                + " compute from here (Baritone idle, no move; rule 22)";
    }

    /** The line said once when the wait is over: true the first time only. */
    public boolean sayOver() {
        if (said) return false;
        said = true;
        return true;
    }

    /**
     * 0.39.z76 send-back 3 review: the standing step's start as the hands run it - the car idled ({@code idleCar}: Baritone
     * cancelled, nothing started) and the clock begun; the start line is said through {@code say}. Null: it runs.
     */
    public String start(Step.AwaitPaths a, long now, int firstPending, Runnable idleCar, java.util.function.Consumer<String> say) {
        idleCar.run();
        begin(now);
        say.accept(a.text() + ": standing, Baritone idle, while the walks compute from here (rule 22; " + firstPending + " first answers pending in the fork)");
        return null;
    }

    /**
     * The standing step's status as the hands run it: running while it stands; past its waitS said once and ended FAILED with
     * the price fact ({@link FactChange#repriceAfterStand}), so the order is priced again where the bot stands.
     */
    public Car.Status status(Step.AwaitPaths a, long now, int computing, java.util.function.Consumer<String> say) {
        if (!over(now, a.waitS())) return Car.Status.running(a.text() + ": " + words(now, a.waitS(), computing));
        String why = overWords(a.waitS());
        if (sayOver()) say.accept(a.text() + ": " + why);
        end();
        FactChange.repriceAfterStand(why);
        return Car.Status.failed(why);
    }

    /** The end line: why the order is priced again now. */
    public static String overWords(int waitS) {
        return "stood " + waitS + " s (pathCost.firstAnswerWaitS) with the walks' paths not known: the order is priced again,"
                + " the walks still not known taken by Baritone's estimate (ranked, not priced; rule 18 as amended)";
    }

    /**
     * 0.39.z77 stand (run CS 19:33:43.8: the walk's answer landed, the stand stood 24 s more): the end line when the answer the
     * stand waits for landed before its wait was over ({@code landed}: what landed; {@code price}: the order's price then, null:
     * none said) - the stand ends now and the order is priced again. Pure.
     */
    public static String landedWords(String landed, String price) {
        return "the walks' answer landed (" + landed + ")" + (price == null || price.isBlank() ? "" : "; the order's price now: " + price)
                + ": the stand ends now, the order is priced again";
    }

    private static String secs(long ticks) { return String.format(java.util.Locale.ROOT, "%.1f", ticks / (double) GameTimes.TPS); }
}
