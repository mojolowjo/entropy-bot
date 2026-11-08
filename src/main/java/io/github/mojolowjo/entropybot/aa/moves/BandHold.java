package io.github.mojolowjo.entropybot.aa.moves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * job-dig3 (run JAV 06:10:30 to the end: one reused column, "Baritone's goal ended at y 65 without an exhausted search"
 * every 2 s, 100+ re-plans): rule 15 - not known is never unreachable, but a not-known that repeats at the same cell is a
 * FACT about that cell. A dig leg that ends not known at the same column {@code band.notKnownTries} times within
 * {@code band.notKnownWindowS} holds that column (its words name "column x z", so BandEnd.heldColumn reads it and the band
 * way side-steps); a band Y whose feet column and side-step column are both held is dropped with that reason.
 * State per order run (cleared with {@link #clear}). Pure (JUnit: Dig3Test). Loader notes: none.
 */
public final class BandHold {
    private BandHold() {}

    private static final Map<String, ArrayDeque<Long>> ENDS = new HashMap<>();
    private static final Map<Integer, Set<String>> HELD = new HashMap<>();

    private static String key(int x, int y, int z) { return x + " " + y + " " + z; }

    /**
     * One not-known end of the dig leg at column x z (band Y y) at {@code nowMs}: true when this end makes
     * {@code tries} within {@code windowS} (the column is held from now on).
     */
    public static synchronized boolean notKnown(int x, int y, int z, long nowMs, int tries, double windowS) {
        ArrayDeque<Long> q = ENDS.computeIfAbsent(key(x, y, z), k -> new ArrayDeque<>());
        long win = (long) (windowS * 1000);     // structural: s to ms
        q.addLast(nowMs);
        while (!q.isEmpty() && nowMs - q.peekFirst() > win) q.removeFirst();
        if (tries <= 0 || q.size() < tries) return false;
        HELD.computeIfAbsent(y, k -> new HashSet<>()).add(x + " " + z);
        q.clear();
        return true;
    }

    /** Is column x z held for band Y y. */
    public static synchronized boolean held(int x, int y, int z) {
        Set<String> s = HELD.get(y);
        return s != null && s.contains(x + " " + z);
    }

    /** The held columns at band Y y ("x z" each). */
    public static synchronized List<String> heldAt(int y) {
        Set<String> s = HELD.get(y);
        return s == null ? List.of() : new ArrayList<>(s);
    }

    /** The hold's fact words (name the column so BandEnd.heldColumn reads it). */
    public static String words(int x, int z, int tries, double windowS) {
        return "the column at " + x + " " + z + ": Baritone's search ended at its start " + tries + " times in "
                + Math.round(windowS) + " s (not known); held, the side-step picks another column (column " + x + " " + z + ")";
    }

    /** The drop words for a band Y whose dig columns are all held. */
    public static String dropWords(int y, List<String> cols) {
        return "y " + y + ": every dig column tried is held (" + String.join(", ", cols) + ": Baritone's search ended at its start, band.notKnownTries)";
    }

    public static synchronized void clear() { ENDS.clear(); HELD.clear(); }
}
