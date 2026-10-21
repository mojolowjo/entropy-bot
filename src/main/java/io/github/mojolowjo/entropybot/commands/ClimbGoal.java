package io.github.mojolowjo.entropybot.commands;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * Builder placements (floor fill climb-out): "feet at level y or higher inside this footprint, where the fence lets me
 * stand" ({@link ClimbRule}). A goal (what and where); Baritone picks the column and the way up. The fence answer is
 * cached per cell. Heuristic: Baritone's own (GoalYLevel for the climb, GoalXZ into the footprint).
 *
 * <p>Loader notes: Baritone API Goal only (the same on Fabric).
 */
final class ClimbGoal implements Goal {
    private final ClimbRule rule;
    private final Map<Long, Boolean> seen = new HashMap<>();

    ClimbGoal(ClimbRule rule) {
        this.rule = rule;
    }

    @Override
    public boolean isInGoal(int x, int fy, int z) {
        long k = ((long) x & 0x3FFFFFF) << 38 | ((long) fy & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
        return seen.computeIfAbsent(k, q -> rule.in(x, fy, z));
    }

    @Override
    public double heuristic(int x, int fy, int z) {
        return (fy >= rule.y ? 0 : GoalYLevel.calculate(rule.y, fy)) + GoalXZ.calculate(rule.dx(x), rule.dz(z));
    }

    @Override
    public String toString() {
        return "ClimbGoal{y>=" + rule.y + " in " + rule.box + "}";
    }
}