package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 0.39.z76 J3-mod item 2 (rule 18 amended: rank rough, walk exact; the fork's J3 {@code IPricedPath.cancel}): which of the
 * mod's pending priced-path asks to withdraw at one sweep (LivePathPrice, once per AA tick). An ask is withdrawn when nobody
 * asked for its goal since the last sweep AND either the chain ranked it out of the ways it prices (a way dropped) or the
 * bot stands more than {@code moved} blocks from the cell it was asked from (failures.moved: the planner's own "moved");
 * never the running step's walk. An ask asked again stays (z57 review 2: its answer lands from the feet). Pure (JUnit:
 * Z76RankRoughTest). Loader notes: none.
 */
public final class AskSweep {
    private AskSweep() {}

    /** 0.39.z76: the prefix of an ask whose feet end IN the cell (a band walk, GoalBlock), not by it. */
    public static final String INTO = "in:";

    /** 0.39.z76: an ask's goal key - "x,y,z" (stand by the block), "in:x,y,z" (the feet in the cell); the running walk uses the same. */
    public static String key(int x, int y, int z, boolean into) { return (into ? INTO : "") + x + "," + y + "," + z; }

    /** One pending ask: its goal key ("x,y,z") and the feet cell it was asked from (null: not known). */
    public record Ask(String key, int[] from) {}

    /** The goal keys to withdraw now. {@code feet}: the feet now (null: not known - no move rule, rule 15). */
    public static List<String> due(List<Ask> pending, Set<String> asked, Set<String> rankedOut, int[] feet, double moved, String running) {
        List<String> out = new ArrayList<>();
        for (Ask a : pending) {
            if (a.key() == null || asked.contains(a.key()) || a.key().equals(running)) continue;
            boolean dropped = rankedOut.contains(a.key());
            boolean left = feet != null && a.from() != null && dist(feet, a.from()) > moved;
            if (dropped || left) out.add(a.key());
        }
        return out;
    }

    /**
     * Review (two planners on staggered ticks withdrew and restarted the same goal): the asked keys of the last TWO sweep
     * windows count as "asked again", so a goal one planner asks every other AA tick is never withdrawn under it; the ranked-out
     * keys are this window's. One per LivePathPrice; game thread.
     */
    public static final class Window {
        private Set<String> asked = new java.util.HashSet<>(), before = new java.util.HashSet<>();
        private final Set<String> rankedOut = new java.util.HashSet<>();

        /** structural: a memory bound on the keys of one window (a chain asks a few dozen; no sweep runs while AA is off) */
        private static final int MAX = 1024;

        public void asked(String key) {
            if (asked.size() > MAX) asked.clear();
            asked.add(key);
        }

        public void rankedOut(String key) {
            if (rankedOut.size() > MAX) rankedOut.clear();
            rankedOut.add(key);
        }

        public boolean wasRankedOut(String key) { return rankedOut.contains(key); }

        /** The keys to withdraw now ({@link #due} over this window and the one before), then the next window starts. */
        public List<String> sweep(List<Ask> pending, int[] feet, double moved, String running) {
            Set<String> recent = new java.util.HashSet<>(asked);
            recent.addAll(before);
            List<String> out = due(pending, recent, rankedOut, feet, moved, running);
            next();
            return out;
        }

        /** The next window: this window's asks become the previous ones, the ranked-out keys go. */
        public void next() {
            Set<String> t = before;
            before = asked;
            asked = t;
            asked.clear();
            rankedOut.clear();
        }
    }

    static double dist(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
