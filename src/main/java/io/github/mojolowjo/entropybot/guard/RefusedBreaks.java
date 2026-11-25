package io.github.mojolowjo.entropybot.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * job-refusal-fact (worlds run 3, Tester 2 w072: 8,115 "guard refused break at -25 -32 -123: structure block outside my
 * areas [rail]" lines in about 60 s, w080 about 600 on a village door): the guard's refused breaks, counted per cell
 * (rule 23: the one store of them). It is a log and count store, never a price: many refusals hang on the moment (no
 * job, the travel cap, water beside the cell outside a mine job), so a later path is never refused from here. The
 * refusals that hold whatever the context (a structure block, a keep-out / no-break cell) are priced where they always
 * were, by BreakPolicy through {@code CostAdvisorHook.breakMultiplier}, and its {@code placeCost} asks that same break
 * decision for a place into a held cell (the w072 pillar).
 * <ul>
 * <li>The guard's line: said once per cell, block and reason; the rest counted.</li>
 * <li>The count is said when the step ends ({@code Steps.next} for a step that ended well, {@code Steps.failOrReplan}
 * carries it on the end fact), and by {@link #clear} on a world change (rule 7: kept and said).</li>
 * </ul>
 * Lifting: a different block or reason at the cell is a new record, said again; {@link #clear} on a world change.
 * Thread-safe. Pure: no game classes (JUnit: RefusedBreaksTest).
 */
public final class RefusedBreaks {
    public static final RefusedBreaks INSTANCE = new RefusedBreaks();

    private static final class Rec {
        final String block, reason;
        int count, told;
        Rec(String block, String reason) { this.block = block; this.reason = reason; }
    }

    private final Map<String, Rec> map = new LinkedHashMap<>();

    static String key(String dim, int x, int y, int z) { return dim + " " + x + " " + y + " " + z; }

    /**
     * The guard refused a break of {@code block} at the cell for {@code reason}. True: the first refusal of this cell,
     * block and reason (say the line); false: counted only.
     */
    public synchronized boolean note(String dim, int x, int y, int z, String block, String reason) {
        String k = key(dim, x, y, z);
        Rec r = map.get(k);
        if (r == null || !Objects.equals(r.block, block) || !Objects.equals(r.reason, reason)) {
            if (r != null && r.count > r.told) unsaid(text(k, r, r.count - r.told), r.count - r.told, cap());   // the old record's unsaid count
            r = new Rec(block, reason);
            map.remove(k);                                     // the new record is the newest (oldest out first)
            map.put(k, r);
            r.count = 1;
            bound(cap());
            return true;
        }
        r.count++;
        map.remove(k);                                         // review: a hot cell is the newest too, so it is let go last
        map.put(k, r);
        return false;
    }

    private final List<String> pending = new ArrayList<>();
    private int foldedLines, foldedCount;   // review sent back again (6): lines folded, one cell may fold more than once

    /** The most cells held (Tuning guard.refusedCells; a test sets its own); 0 or less: the Tuning entry. */
    private int capOverride;

    synchronized void cap(int n) { capOverride = n; }

    private int cap() { return capOverride > 0 ? capOverride : io.github.mojolowjo.entropybot.aa.brain.Tuning.i("guard.refusedCells"); }

    /**
     * job-review-leftovers (8, a long run refused thousands of cells): past {@code cap} cells the oldest goes out; its count
     * not yet said is kept for the next {@link #take} (rule 7), and a cell refused again later is a new record, said again.
     */
    private void bound(int cap) {
        var it = map.entrySet().iterator();
        while (map.size() > Math.max(1, cap) && it.hasNext()) {   // structural: the newest record always stays
            Map.Entry<String, Rec> e = it.next();
            Rec r = e.getValue();
            if (r.count > r.told) {
                // review: pending is bounded by the same cap; past it only the totals are kept, said in one line (rule 7)
                unsaid(text(e.getKey(), r, r.count - r.told) + " (the oldest cell, let go past " + cap + " cells)", r.count - r.told, cap);
            }
            it.remove();
        }
    }

    /** review sent back: every unsaid count goes through here, so pending stays within the cap; past it only totals (rule 7). */
    private void unsaid(String line, int n, int cap) {
        if (pending.size() < Math.max(1, cap)) pending.add(line);
        else { foldedLines++; foldedCount += n; }
    }

    private static String text(String key, Rec r, int n) {
        String cell = key.substring(key.indexOf(' ') + 1);
        return "the guard refused the break of " + (r.block == null ? "a block not read" : r.block) + " at " + cell + " "
                + n + (n == 1 ? " time" : " times") + " (" + r.reason + ")";
    }

    /** Refusals counted since the last take, one text per cell ("the guard refused the break of ... N times"); null: none. */
    public synchronized String take() {
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        if (foldedLines > 0) out.add("the guard refused " + foldedCount + " more breaks in " + foldedLines + " more refusal lines let go (past the cap of lines)");
        foldedLines = 0;
        foldedCount = 0;
        for (Map.Entry<String, Rec> e : map.entrySet()) {
            Rec r = e.getValue();
            int n = r.count - r.told;
            if (n <= 0) continue;
            r.told = r.count;
            out.add(text(e.getKey(), r, n));
        }
        return out.isEmpty() ? null : String.join("; ", out);
    }

    /** A world change: forgets every cell; returns the counts not yet said (null: none) for the caller to log. */
    public synchronized String clear() {
        String unsaid = take();
        map.clear();
        return unsaid;
    }

    public synchronized int size() { return map.size(); }
}
