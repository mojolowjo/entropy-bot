package io.github.mojolowjo.entropybot.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * job-refusal-fact (worlds run 3, Tester 2 w072: 8,115 "guard refused break at -25 -32 -123: structure block outside my
 * areas [rail]" lines in about 60 s, w080 about 600 on a village door): a break the guard refused at a cell is a fact
 * about that cell, kept here (rule 23: the one store of refused breaks). Readers:
 * <ul>
 * <li>Baritone's path cost through the existing mod hook ({@code CostAdvisorHook.breakMultiplier} and its
 * {@code placeCost}, which the fork's moves ask): a held cell is COST_INF, so the next path does not plan that break,
 * nor a place into that cell (a place there needs the break first).</li>
 * <li>The step that ran while it was refused ({@code Steps.failOrReplan} via {@code Hands.refusedBreaks}): its end
 * fact and reason name the cell, the block and the count, so the planner's held fact says why.</li>
 * <li>The guard's own log line: said once per cell; the count is kept and said when the step ends (rule 7).</li>
 * </ul>
 * Lifting: a cell's fact holds while the same block stands there; the block read different (broken by someone else,
 * changed) is a new cell fact, said again. Not known (the block not read, null) keeps the fact: the guard's refusal is
 * the last thing known there (rule 15: no guess that it is free). Thread-safe (the click thread writes, the pathfinder
 * thread reads). Pure: no game classes (JUnit: RefusedBreaksTest).
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
     * The guard refused a break of {@code block} at the cell for {@code reason}. True: the first refusal of this fact (say
     * the line); false: counted only.
     */
    public synchronized boolean note(String dim, int x, int y, int z, String block, String reason) {
        String k = key(dim, x, y, z);
        Rec r = map.get(k);
        if (r == null || !Objects.equals(r.block, block)) {
            r = new Rec(block, reason);
            map.put(k, r);
            r.count = 1;
            return true;
        }
        r.count++;
        return false;
    }

    /** The cell's refusal holds with {@code blockNow} there (null: not read, the fact holds). */
    public synchronized boolean refused(String dim, int x, int y, int z, String blockNow) {
        if (dim == null) return false;
        Rec r = map.get(key(dim, x, y, z));
        if (r == null) return false;
        if (blockNow != null && r.block != null && !blockNow.equals(r.block)) {
            map.remove(key(dim, x, y, z));                 // the cause is gone: lifted
            return false;
        }
        return true;
    }

    /** Refusals counted since the last take, one text per cell ("the guard refused the break of ... N times"); null: none. */
    public synchronized String take() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Rec> e : map.entrySet()) {
            Rec r = e.getValue();
            int n = r.count - r.told;
            if (n <= 0) continue;
            r.told = r.count;
            String cell = e.getKey().substring(e.getKey().indexOf(' ') + 1);
            out.add("the guard refused the break of " + (r.block == null ? "a block not read" : r.block) + " at " + cell + " "
                    + n + (n == 1 ? " time" : " times") + " (" + r.reason + "; held for every path: Baritone's cost refuses that cell)");
        }
        return out.isEmpty() ? null : String.join("; ", out);
    }

    public synchronized int size() { return map.size(); }
}
