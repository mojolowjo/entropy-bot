package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Desire;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.Scorer;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BRAIN_PLAN B1: the ladder's rungs and the idle options as desires of one {@link Brain}. B1 scores reproduce the old
 * order: a rung that is unmet scores by its ladder place ({@link #RANK}, 60 down to 38), met = -1; the idle options
 * keep their {@link AaScorer} numbers scaled by {@link #IDLE_SCALE} (25 at most), so every unmet rung still comes first.
 * The owner's {@code aa do} puts its rung at 100.
 */
public final class AaDesires {
    private AaDesires() {}

    public static final double IDLE_SCALE = 0.25;
    /** The ladder place of each rung (higher runs first). */
    public static final Map<String, Double> RANK = Map.ofEntries(
            Map.entry("needs.area", 60.0), Map.entry("needs.woodArea", 58.0), Map.entry("needs.tools", 56.0),
            Map.entry("needs.surface", 54.0), Map.entry("needs.food", 52.0), Map.entry("needs.tableFurnace", 50.0),
            Map.entry("needs.spareTable", 48.0), Map.entry("needs.torches", 46.0), Map.entry("needs.base", 44.0),
            Map.entry("needs.bed", 42.0), Map.entry("needs.bag", 40.0), Map.entry("needs.iron", 38.0));

    static Desire.Kind kindOf(String rungId) {
        return switch (rungId) {
            case "needs.area", "needs.woodArea", "needs.surface" -> Desire.Kind.GATE;
            case "needs.tools", "needs.food", "needs.torches", "needs.bag" -> Desire.Kind.UPKEEP;
            default -> Desire.Kind.PROGRESS;
        };
    }

    /** A rung's score now: 100 for the owner's aa do, -1 when skipped or met, else its rank. */
    static double rungScore(Rung r, Blackboard bb, double value) {
        AaManual man = AaManual.of(bb);
        if (r.id.equals(man.doRung)) return 100;
        if (man.skipped(r.id)) return -1;
        return r.unmet(bb) ? value : -1;
    }

    public static Desire rung(Rung r) {
        double v = RANK.getOrDefault(r.id, 30.0);
        return new Desire(r.id, kindOf(r.id), r, bb -> rungScore(r, bb, v));
    }

    public static Desire idle(TaskKind kind, Node node, Scorer scorer) {
        return new Desire("idle." + kind.name().toLowerCase(java.util.Locale.ROOT), Desire.Kind.UPKEEP, node,
                bb -> {
                    // a cooling option is left out (the idle chooser's rule), so the next one gets its turn
                    if (node instanceof io.github.mojolowjo.entropybot.aa.core.Cooldown cd && cd.cooling(bb.clock.now())) return -1;
                    double s = scorer.score(bb.in)[kind.ordinal()];
                    return s < 0 ? -1 : s * IDLE_SCALE;
                }).lastTry();
    }

    /** The brain over the rungs and the idle options. */
    public static Brain build(List<Rung> rungs, List<Map.Entry<TaskKind, Node>> idle, Scorer scorer) {
        List<Desire> ds = new ArrayList<>();
        for (Rung r : rungs) ds.add(rung(r));
        for (var e : idle) ds.add(idle(e.getKey(), e.getValue(), scorer));
        return new Brain("brain", "the scored desires", ds.toArray(new Desire[0]));
    }
}
