package io.github.mojolowjo.entropybot.move;

/**
 * 0.23.3 movement, "no dead stops" (the owner's rule): what a long walk does when Baritone's search for a leg fails
 * ({@code IBaritoneProcess.onTick(calcFailed=true, ...)}). It never stands and waits: every answer but REPORT keeps the
 * bot moving by the route map's direction (raw input) while the next search runs from the new spot.
 * <ol>
 *   <li>1st failure in a row: NEXT_WAYPOINT (skip the leg that failed, its map edge penalised);</li>
 *   <li>2nd: DETOUR (the whole route planned again from here, the failed edges now cost more);</li>
 *   <li>3rd: DIG_OUT when underground inside the areas and not tried yet in this run of failures, else REPORT;</li>
 *   <li>after a dig-out, the next failure: REPORT ({@code couldn't get there (stopped at x y z: <reason>)}).</li>
 * </ol>
 * A leg reached resets the count. Pure.
 */
public final class NoDeadStop {
    public enum Action { NEXT_WAYPOINT, DETOUR, DIG_OUT, REPORT }

    public static final int MAX_FAILS = 3;

    private int fails;
    private boolean dugOut;
    private int total;

    public int failsInRow() { return fails; }

    public int totalFails() { return total; }

    /** A leg search failed; canDigOut: underground and inside the areas. */
    public Action onFail(boolean canDigOut) {
        return onFail(canDigOut, false);
    }

    /**
     * 0.23.4: boxedIn: no free step next to the bot (a sealed cell). Skipping waypoints or re-planning can't help
     * there, so the dig-out comes on the first failure (live run 10 stood ~14 s through two useless searches first).
     */
    public Action onFail(boolean canDigOut, boolean boxedIn) {
        fails++;
        total++;
        if (boxedIn && canDigOut && !dugOut) {
            dugOut = true;
            return Action.DIG_OUT;
        }
        if (fails == 1) return Action.NEXT_WAYPOINT;
        if (fails == 2) return Action.DETOUR;
        if (fails == MAX_FAILS && canDigOut && !dugOut) {
            dugOut = true;
            return Action.DIG_OUT;
        }
        return Action.REPORT;
    }

    /** 0.23.4: a dig-out started before any search (the walk began sealed in): it counts as this run's one dig-out. */
    public void markDugOut() { dugOut = true; }

    /** The dig-out could not start (no safe way out): the walk ends here. */
    public Action digOutFailed() { return Action.REPORT; }

    /** A leg was reached: the run of failures is over. */
    public void onLegReached() {
        fails = 0;
        dugOut = false;
    }

    /** Whether the bot keeps moving by raw input after this action. */
    public static boolean keepsMoving(Action a) {
        return a == Action.NEXT_WAYPOINT || a == Action.DETOUR;
    }
}
