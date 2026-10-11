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
    public record Cand(int[] base, long cost, boolean reachable) {}

    /** Reachable trunks first, each group nearest first (stable). */
    public static List<Cand> order(List<Cand> in) {
        List<Cand> out = new ArrayList<>(in);
        out.sort(Comparator.comparing((Cand c) -> !c.reachable()).thenComparingLong(Cand::cost));
        return out;
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
