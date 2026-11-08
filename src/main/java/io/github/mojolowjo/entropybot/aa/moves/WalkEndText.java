package io.github.mojolowjo.entropybot.aa.moves;

/**
 * job-surface (run JAL 01:44:06: "the walk up ended short at -56 66 -12: Baritone's walk into the cell ended (stop unknown)";
 * rule 7: every end has a reason): the words of a goal walk's end. At the goal: "ended at its goal". Short: "ended short at
 * x y z: " and the first known of the fork's path end (graph exhausted, a budget, a named stop) and Baritone's last chat
 * line since the walk started (as the step monitor reads it, commit 138f2e00). Neither known: that is said, never "unknown"
 * alone. Pure (JUnit: SurfaceJobTest). Loader notes: none here; the fork's {@code lastCalc()} and BaritoneHook's lines are
 * read by the game side.
 */
public final class WalkEndText {
    private WalkEndText() {}

    /** The fork's last path calculation, as read: kept (false: none kept or an older fork), exhausted, budget, stop name. */
    public record Calc(boolean kept, boolean exhausted, boolean budget, String stop) {
        public static final Calc NONE = new Calc(false, false, false, null);
    }

    /**
     * @param at        the feet at the end
     * @param atGoal    the feet stand on the goal cell
     * @param linesAt   Baritone's line count when the walk started; {@code linesNow} now; {@code lastLine} its last line
     */
    public static String of(String at, boolean atGoal, Calc calc, long linesAt, long linesNow, String lastLine) {
        if (atGoal) return "ended at its goal " + at;
        return "ended short at " + at + ": " + reason(calc, linesAt, linesNow, lastLine);
    }

    /** Baritone's reason for a short end (never the bare word "unknown"). */
    public static String reason(Calc calc, long linesAt, long linesNow, String lastLine) {
        String said = linesNow > linesAt && lastLine != null && !lastLine.isBlank() ? FactChange.baritoneSaid(linesAt, linesNow, lastLine) : null;
        String path = null;
        if (calc != null && calc.kept()) {
            if (calc.exhausted()) path = "no path (the graph exhausted)";
            else if (calc.budget()) path = "the path search ran out of its budget (stop " + calc.stop() + ")";
            else if (calc.stop() != null && !calc.stop().isBlank() && !"unknown".equalsIgnoreCase(calc.stop().strip())) path = "the path search stopped (" + calc.stop() + ")";
        }
        if (path != null && said != null) return path + "; Baritone last said '" + said + "'";
        if (path != null) return path + "; Baritone said nothing since the walk started";
        if (said != null) return "Baritone last said '" + said + "'";
        return "Baritone's process went idle off the goal with no path end reported and no line said since the walk started";
    }
}
