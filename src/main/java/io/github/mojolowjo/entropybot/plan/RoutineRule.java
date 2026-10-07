package io.github.mojolowjo.entropybot.plan;

/**
 * B3: what happens to the routine {@code goal_<name>} when a goal is planned. The planner writes it; the owner may read,
 * edit or delete it ({@code routine show|save|delete}). An edited one is the owner's: it is the ready chain from then on
 * and never overwritten. Pure.
 */
public final class RoutineRule {
    private RoutineRule() {}

    public enum Decision { SAVE, OVERWRITE, KEEP }

    /**
     * existing: the routine's text now (null: none); planned: the text the planner saved last under that name (null:
     * never). Same text -> the planner's own, so a fresh plan overwrites it; anything else -> the owner's, kept.
     */
    public static Decision decide(String existing, String planned) {
        if (existing == null) return Decision.SAVE;
        if (planned != null && existing.trim().equals(planned.trim())) return Decision.OVERWRITE;
        return Decision.KEEP;
    }
}
