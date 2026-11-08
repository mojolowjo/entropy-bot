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
        reason = null;
        segmentWay = null;
        sawComputing = false;
    }

    /** job-start: the stand's reason last said (null: none yet this stand). */
    private String reason;

    /**
     * job-start (rule 7, one line per change of reason, never per tick): the stand's reason now - how many walks it waits on
     * ({@code computing} &lt; 0: not known) - when it differs from the one last said this stand, else null. The first call of a
     * stand only records it (the start line said it). Pure.
     */
    public String reasonChanged(int computing) {
        // job-stand2 (run JAS: 371 lines, the fork's pending count went 1, 0, 1, 2 ... 69 and back, one line per count): the
        // reason is the KIND of what the stand waits on (walks computing / not counted / none), the count only in the line
        String kind = computing < 0 ? "walks not counted" : computing == 0 ? "no walk computing" : "walks still computing";
        if (kind.equals(reason)) return null;
        boolean first = reason == null;
        reason = kind;
        if (first) return null;
        return "still standing, waiting on " + (computing <= 0 ? kind : computing + " walk" + (computing == 1 ? "" : "s") + " still computing");
    }

    /** job-stand2 item 2: a walk was seen computing this stand (a count of 0 before that is the asks not registered yet). */
    private boolean sawComputing;

    /**
     * job-stand2 item 2 (rule 22: stand only while something computes): the stand is over when it saw walks computing and now
     * none is - each landed, timed out (held by its estimate, pathCost.notKnownHoldS) or is walked as a first segment (the
     * fork counts a walked segment's price no more as a first answer pending). Pure.
     */
    public boolean setDone(int computing) {
        if (computing > 0) sawComputing = true;
        return sawComputing && computing == 0 && segmentWay == null;
    }

    /** job-stand2 item 2: the end line when nothing it waited on still computes. */
    public static String setDoneWords() {
        return "every walk the stand waited on landed, timed out (held by Baritone's estimate, pathCost.notKnownHoldS) or is walked:"
                + " the stand ends now, the order is priced again (rule 22: stand only while something computes)";
    }

    public boolean standing() { return start >= 0; }

    public void end() { start = -1; segmentWay = null; }

    // ---- job-segmod (runs JAN, JAO: 27 / 46 "still standing" lines): the stand walks its cheapest way's first segment ----

    /** The way whose first segment the stand walks now (null: it stands still). */
    private String segmentWay;

    /** job-segmod: the gate {@code pathCost.firstSegmentWalk} (1 on, 0 the stand stands as before). */
    public static boolean segmentWalkOn() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("pathCost.firstSegmentWalk") >= 0.5; }

    /** job-segmod: {@code pathCost.firstSegmentMinCells}. */
    public static int segmentMinCells() { return (int) Math.ceil(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("pathCost.firstSegmentMinCells")); }

    /**
     * job-segmod (rule 22: a first segment IS a partial price, Baritone's own best-so-far path; standing is for a price with no
     * first segment yet): the stand walks the segment when the gate is on, the price search still runs and the segment has
     * at least {@code minCells} cells. Pure.
     */
    public static boolean walksSegment(boolean on, boolean running, int cells, int minCells) {
        return on && running && cells >= minCells;
    }

    /** The line said when the stand starts walking the segment. */
    public static String segmentWords(int cells, String way) {
        return "walking the first segment (" + cells + " cells) of " + way + " while its price computes";
    }

    /** The stand walks {@code way}'s first segment from now. */
    public void walking(String way) { segmentWay = way; }

    /** The way whose first segment is walked (null: standing still). */
    public String walkingWay() { return segmentWay; }

    /**
     * job-segmod item 2: the walked way's price landed "no path" ({@code reason}: the fork's) - the walk ends (the caller
     * cancels Baritone), the stand stands still again and the way is held as any no-path way (the chain's price). The line,
     * or null when no segment is walked. Pure.
     */
    public String abandon(String reason) {
        if (segmentWay == null) return null;
        String l = "the first-segment walk of " + segmentWay + " ends: its price landed no path (" + reason + "); standing again, the way held as no path";
        segmentWay = null;
        return l;
    }

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
        if (!over(now, a.waitS()) && setDone(computing)) {
            String why = setDoneWords();
            say.accept(a.text() + ": " + why + " (" + secs(stood(now)) + " s stood)");
            end();
            FactChange.repriceAfterStand(why);
            return Car.Status.failed(why);
        }
        if (!over(now, a.waitS())) {
            String r = reasonChanged(computing);
            if (r != null) say.accept(a.text() + ": " + r + " (" + secs(stood(now)) + " s stood)");
            if (segmentWay != null) return Car.Status.running(a.text() + ": walking the first segment of " + segmentWay + " while its price computes ("
                    + secs(stood(now)) + " s of " + a.waitS() + "; rule 22: a partial price)");
            return Car.Status.running(a.text() + ": " + words(now, a.waitS(), computing));
        }
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

    /**
     * 2026-11-03 (runs JU, JV, JW, JAB: the order "stopped at step 1 (stand ...)" 1-60 s in, with the walk's price "not known
     * yet (computing)"): a re-plan answer with no steps whose reason is a walk still computing is NOT the answer the stand waits
     * for - the stand stands on (rule 15: not known is no verdict). The walks' count was 0 at that instant (the requests had
     * not been registered yet), so the planner gave no stand of its own. Pure.
     */
    public static boolean stillComputing(String why) {
        return why != null && why.contains(io.github.mojolowjo.entropybot.aa.PathPrice.NOT_KNOWN + " yet (computing");
    }

    private static String secs(long ticks) { return String.format(java.util.Locale.ROOT, "%.1f", ticks / (double) GameTimes.TPS); }
}
