package io.github.mojolowjo.entropybot.aa.core;

import java.util.ArrayList;
import java.util.List;

/**
 * AA v0 (AA_PLAN 1 [2d], 5): the idle chooser. When no option runs it scores every option through the {@link Scorer}
 * (fixed inputs in, one score per kind out), gives the last winner {@code bonus} (Halo 2's winner bonus, 15) and
 * takes the highest; a tie goes to the earlier option (Altoclef's strictly-greater rule; aa-lessons 1, 4). The
 * chosen option then runs to its end (memory), like the task slot. Options scoring below 0 are skipped with a reason.
 */
public final class UtilitySelector extends Node {
    public record Option(TaskKind kind, Node child) {}

    public static final double BONUS = 15;
    private final List<Option> options;
    private final Scorer scorer;
    private final double bonus;
    private int running = -1, lastWinner = -1;
    private double[] lastScores;

    public UtilitySelector(String id, String label, Scorer scorer, double bonus, Option... options) {
        super(id, label);
        this.options = List.of(options);
        this.scorer = scorer;
        this.bonus = bonus;
    }

    @Override protected Status run(Blackboard bb) {
        if (running >= 0) {
            Status s = options.get(running).child().tick(bb);
            if (s == Status.RUNNING) return s;
            String why = options.get(running).child().reason();
            running = -1;
            return s == Status.FAILURE ? fail(why) : s;
        }
        double[] sc = scorer.score(bb.in);
        lastScores = sc;
        int best = -1;
        double bestV = Double.NEGATIVE_INFINITY;
        long now = bb.clock.now();
        for (int i = 0; i < options.size(); i++) {
            double v = sc[options.get(i).kind().ordinal()];
            if (v < 0) continue;
            // 0.36.2: an option cooling down after a failed task is skipped (the next best runs meanwhile)
            if (options.get(i).child() instanceof Cooldown cd && cd.cooling(now)) continue;
            if (i == lastWinner) v += bonus;
            if (v > bestV) {
                bestV = v;
                best = i;
            }
        }
        if (best < 0) return fail("no idle option can run");
        lastWinner = best;
        bb.pendingReason("idle: " + options.get(best).kind() + " scored " + Math.round(bestV) + scoresText(sc));
        Status s = options.get(best).child().tick(bb);
        if (s == Status.RUNNING) running = best;
        return s == Status.FAILURE ? fail(options.get(best).child().reason()) : s;
    }

    private String scoresText(double[] sc) {
        StringBuilder b = new StringBuilder(" (");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(options.get(i).kind()).append(' ').append(Math.round(sc[options.get(i).kind().ordinal()]));
        }
        return b.append(')').toString();
    }

    public double[] lastScores() { return lastScores; }

    public int lastWinner() { return lastWinner; }

    @Override public void halt(String why) {
        if (running >= 0) options.get(running).child().halt(why);
        running = -1;
    }

    @Override public List<Node> children() {
        List<Node> k = new ArrayList<>();
        for (Option o : options) k.add(o.child());
        return k;
    }
}
