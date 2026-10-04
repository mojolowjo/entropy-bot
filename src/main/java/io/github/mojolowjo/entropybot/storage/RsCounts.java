package io.github.mojolowjo.entropybot.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The counting of "rs take" and "rs put" (the sim's storage run, B7e: pure, JUnit tests it). Counts are exact: a take
 * asks the grid for what is missing (a whole stack, or single items), a put shift-clicks whole stacks that fit and puts
 * one part stack in through the cursor; what the server confirmed (the inventory before and after) decides the end.
 */
public final class RsCounts {
    private RsCounts() {}

    /** Bursts without progress after which a move ends with what it got. */
    public static final int QUIET_END = 3;
    /** Single extracts per burst, and single inserts per part stack. */
    public static final int SINGLES_PER_BURST = 16, INSERTS_PER_PART = 64;

    /** What a take aims for: want (the count, or one stack when none was given), and what the network had when short. */
    public record Take(int want, Integer had) {}

    /** n: the count asked for (null: one stack); amount: what the network holds; stack: the item's stack size. */
    public static Take take(Integer n, long amount, int stack) {
        int want = (int) (n != null ? Math.min(n, amount) : Math.min(amount, stack));
        return new Take(want, n != null && amount < n ? (int) amount : null);
    }

    /** A take is over: it has all it wants, or nothing came for QUIET_END bursts. */
    public static boolean takeDone(int gained, int want, int quiet) {
        return want - gained <= 0 || quiet >= QUIET_END;
    }

    /** The next burst: 0 = one "whole stack" extract (need is a stack or more), else that many single extracts. */
    public static int singles(int need, int stack) {
        return need >= stack ? 0 : Math.min(need, SINGLES_PER_BURST);
    }

    /** The take's report: "took 70 cobblestone", "only took 40 of 70 ... (my inventory is full?)", " - the network had only 30". */
    public static String takeNote(int gained, Take t, String name) {
        return (gained >= t.want() ? "took " + gained + " " + name : "only took " + gained + " of " + t.want() + " " + name + " (my inventory is full?)")
                + (t.had() != null ? " - the network had only " + t.had() : "");
    }

    /**
     * What a put should move, per item. keep != null ("put all"): everything beyond the keep amounts (the deposit keep
     * rules); else id: n of it (at most what the bot has), or all of it without a count.
     */
    public static Map<String, Integer> putWant(Map<String, Integer> before, Map<String, Integer> keep, String id, Integer n) {
        Map<String, Integer> want = new LinkedHashMap<>();
        if (keep != null) {
            for (Map.Entry<String, Integer> e : keep.entrySet()) {
                int w = before.getOrDefault(e.getKey(), 0) - e.getValue();
                if (w > 0) want.put(e.getKey(), w);
            }
        } else {
            int have = before.getOrDefault(id, 0);
            want.put(id, n != null ? Math.min(n, have) : have);
        }
        return want;
    }

    /** How far a put got: moved (counted only up to what was wanted) and what is left per item. */
    public record Progress(int moved, Map<String, Integer> left) {}

    /** lost: what the inventory lost since the start (per item). */
    public static Progress putProgress(Map<String, Integer> want, Map<String, Integer> lost) {
        int moved = 0;
        Map<String, Integer> left = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            int l = lost.getOrDefault(e.getKey(), 0);
            moved += Math.min(l, e.getValue());
            if (e.getValue() - l > 0) left.put(e.getKey(), e.getValue() - l);
        }
        return new Progress(moved, left);
    }

    public static int total(Map<String, Integer> want) {
        int t = 0;
        for (int v : want.values()) t += v;
        return t;
    }

    /** The put's report: "put 45 cobblestone into the RS network", "(all I had)", "only put 10 of 45 ... (is it full?)". */
    public static String putNote(int moved, int total, boolean all, String name, Integer n) {
        return (moved >= total ? "put " : "only put ") + moved + (moved >= total ? "" : " of " + total) + " " + (all ? "items" : name)
                + " into the RS network" + (moved < total ? " (is it full?)" : n != null && n > total ? " (all I had)" : "");
    }

    /** One inventory slot as the menu shows it: its menu index, item id, count. */
    public record Slot(int index, String id, int count) {}

    /** The next burst of a put: whole stacks to shift-click, and at most one part stack (slot, how many) for the cursor. */
    public record Plan(List<Integer> quickMoves, int partSlot, int partCount) {
        public boolean hasPart() { return partSlot >= 0; }
    }

    /** left is not changed. Whole stacks that fit in what is left go first, in slot order; the first that doesn't fit is the part. */
    public static Plan putPlan(List<Slot> slots, Map<String, Integer> left) {
        Map<String, Integer> l = new LinkedHashMap<>(left);
        List<Integer> quick = new ArrayList<>();
        int partSlot = -1, partCount = 0;
        for (Slot s : slots) {
            Integer want = l.get(s.id());
            if (want == null || want <= 0) continue;
            if (s.count() <= want) {
                quick.add(s.index());
                l.put(s.id(), want - s.count());
            } else if (partSlot < 0) {
                partSlot = s.index();
                partCount = want;
                l.put(s.id(), 0);
            }
        }
        return new Plan(quick, partSlot, partCount);
    }
}
