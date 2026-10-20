package io.github.mojolowjo.entropybot.chop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 0.26.4: the per-tree break loop as a pure state machine, TreeChop-aware. With TreeChop (mod id {@code treechop}) a broken
 * log does not drop: it turns into {@code treechop:chopped_log} (its chop count in a block entity) and the whole tree falls
 * (every log drops) once the chops reach the count TreeChop works out from the tree's size. So a round where the trunk
 * block turned into a chopped log, or a chopped log's data changed, or the clear reported a break while a chopped log
 * still stands, is a chop: progress, never a failure. Felling = the tree's logs are gone (or logs came into the bag).
 * Without TreeChop the old rule holds (progress = fewer logs standing). Loader notes: none (plain Java); the adapter in
 * {@code commands/Chopping} reads the block ids and the block entity data. JUnit: {@code ChopLoopTest}.
 */
public final class ChopLoop {
    private ChopLoop() {}

    public static final int DEFAULT_MAX_CHOPS = 24, MIN_MAX_CHOPS = 4, MAX_MAX_CHOPS = 64;
    public static final int DEFAULT_MAX_TREES = 8, MIN_MAX_TREES = 3, MAX_MAX_TREES = 20;
    /** Rounds without any progress before a tree is given up. */
    public static final int STALL_ROUNDS = 3;

    public enum Next { AGAIN, FELLED, PARTIAL, UNREACHABLE }

    /** What one break round saw: logs standing before/after, the trunk cell's id and data signature before/after, the clear's breaks, logs gained. */
    public record Round(int leftBefore, int leftAfter, String idBefore, String idAfter, String sigBefore, String sigAfter, int broke, int gained) {}

    /** One tree's tally. */
    public static final class State {
        public int rounds, chops, stalls, logsDown;
        public boolean progressed() { return chops > 0 || logsDown > 0; }
    }

    public static boolean chopped(String id) { return id != null && id.endsWith(":chopped_log"); }

    /** Did this round chop (TreeChop): the trunk became a chopped log, its data changed, or a break left a chopped log. */
    public static int chopsIn(Round r) {
        if (chopped(r.idAfter()) && !chopped(r.idBefore())) return Math.max(1, r.broke());
        if (chopped(r.idAfter()) && r.sigBefore() != null && !r.sigBefore().equals(r.sigAfter())) return Math.max(1, r.broke());
        if (chopped(r.idAfter()) && r.broke() > 0) return r.broke();
        return 0;
    }

    /** Folds one round in and says what comes next. roundCap: the non-TreeChop round cap (ChopRules.ROUNDS). */
    public static Next step(State s, Round r, boolean treeChop, int maxChops, int roundCap) {
        s.rounds++;
        boolean progress = false;
        if (r.leftAfter() < r.leftBefore()) {
            s.logsDown += r.leftBefore() - r.leftAfter();
            progress = true;
        }
        if (r.gained() > 0) progress = true;
        if (treeChop) {
            int c = chopsIn(r);
            if (c > 0) {
                s.chops += c;
                progress = true;
            }
        }
        if (r.leftAfter() == 0) return Next.FELLED;
        if (progress) s.stalls = 0;
        else s.stalls++;
        int cap = treeChop ? clamp(maxChops, MIN_MAX_CHOPS, MAX_MAX_CHOPS) : roundCap;
        int used = treeChop ? Math.max(s.chops, s.rounds) : s.rounds;
        if (s.stalls < STALL_ROUNDS && used < cap) return Next.AGAIN;
        return s.progressed() || r.gained() > 0 ? Next.PARTIAL : Next.UNREACHABLE;
    }

    public static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** A tree candidate: where, how far (squared, the order's cost), whether a free standing cell touches its trunk base. */
    /** aa-litter: {@code extraS} = the seconds the tree's scaffold adds (a tall tree's pillar: min(litter, cleanup) or the skipped logs), 0 for none. */
    public record Cand(int[] base, long cost, boolean reachable, boolean stump, double extraS) {
        public Cand(int[] base, long cost, boolean reachable, boolean stump) { this(base, cost, reachable, stump, 0); }

        public Cand(int[] base, long cost, boolean reachable) { this(base, cost, reachable, false, 0); }

        /** The price the order sorts by within a group: the walk in seconds (blocks at 4.3 per second) plus the extra. */
        public double seconds() { return Math.sqrt(Math.max(0, cost)) / WALK_BPS + extraS; }
    }

    /**
     * Real trees before stumps/lone logs (0.27.1), then reachable trunks first, each group nearest first (stable). 0.27.1:
     * when no real tree has a free cell next to its trunk, the plain nearest order (the 0.26.3 behaviour: the walk decides).
     */
    public static List<Cand> order(List<Cand> in) {
        List<Cand> out = new ArrayList<>(in);
        boolean anyReach = false;
        for (Cand c : in) if (c.reachable() && !c.stump()) anyReach = true;
        final boolean useReach = anyReach;
        out.sort(Comparator.comparing(Cand::stump).thenComparing((Cand c) -> useReach && !c.reachable()).thenComparingDouble(Cand::seconds));
        return out;
    }

    /** 0.27.2: a stump for the order: any one-log column (leaves or not), or 0.27.1's leafless stump. */
    public static boolean isStump(int logs, boolean leaflessStump) {
        return logs <= 1 || leaflessStump;
    }

    public static final int STUMP_SKIP_RADIUS = 48;
    /** Walking speed for turning a candidate's distance into seconds. */
    public static final double WALK_BPS = 4.3;

    /**
     * 0.27.2: the order, but with every stump dropped when a real tree (2+ logs) stands within 48 blocks (flat) of me;
     * stumps always after every real tree (order).
     */
    public static List<Cand> order(List<Cand> in, int[] me) {
        boolean realNear = false;
        for (Cand c : in) {
            if (c.stump() || me == null) continue;
            long dx = c.base()[0] - me[0], dz = c.base()[2] - me[2];
            if (dx * dx + dz * dz <= (long) STUMP_SKIP_RADIUS * STUMP_SKIP_RADIUS) realNear = true;
        }
        List<Cand> keep = new ArrayList<>();
        for (Cand c : in) if (!(realNear && c.stump())) keep.add(c);
        return order(keep);
    }

    /** 0.27.1: only stumps are left (the caller then tries them, nearest first). */
    public static boolean onlyStumps(List<Cand> in) {
        for (Cand c : in) if (!c.stump()) return false;
        return !in.isEmpty();
    }

    /**
     * 0.27.1: the cells around a trunk base to look for a standing spot: the 8 around it, and with wide (the base in
     * leaves or grass) also the ring 2 out. Each is tried at levels -1..+1 by the caller. {dx, dz} pairs.
     */
    public static List<int[]> standOffsets(boolean wide) {
        List<int[]> out = new ArrayList<>();
        int r = wide ? 2 : 1;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                if (dx != 0 || dz != 0) out.add(new int[]{dx, dz});
        out.sort(Comparator.comparingInt(o -> Math.max(Math.abs(o[0]), Math.abs(o[1]))));
        return out;
    }

    /** 0.27.1: why a tree where nothing came down is skipped: a failed walk or an out-of-reach clear = "couldn't get at its trunk". */
    public static String skipNote(String at, boolean walkFailed, String clearWhy) {
        if (walkFailed || clearWhy != null) return "tree at " + at + ": skipped - I couldn't get at its trunk" + (clearWhy != null ? " (" + clearWhy + ")" : "");
        return "tree at " + at + ": skipped - nothing came down";
    }

    /** The stop reason once this many trees failed in a row: reach failures may go on to maxTrees, others stop at fails. */
    public static String giveUp(int failsInRow, int reachFails, int maxTrees, String nearestReachable) {
        int cap = clamp(maxTrees, MIN_MAX_TREES, MAX_MAX_TREES);
        boolean allReach = failsInRow == reachFails;
        if (failsInRow < (allReach ? cap : ChopRules.FAILS)) return null;
        return "tried " + failsInRow + " trees in a row and none came down"
                + (nearestReachable != null ? " - nearest reachable tree at " + nearestReachable : " - no tree with a free spot next to its trunk");
    }

    /** "felled 3 trees (TreeChop: 17 chops)", the head note of a TreeChop run. */
    public static String felledNote(int trees, int chops) {
        return "felled " + trees + (trees == 1 ? " tree" : " trees") + " (TreeChop: " + chops + (chops == 1 ? " chop)" : " chops)");
    }
}
