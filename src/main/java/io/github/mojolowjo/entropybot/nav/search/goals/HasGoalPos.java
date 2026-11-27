package io.github.mojolowjo.entropybot.nav.search.goals;

import net.minecraft.core.BlockPos;

/**
 * nav step 6: a goal with one target block (Baritone's {@code IGoalRenderPos}, kept for its use in the fork's goal
 * steering, {@link ClimbGoal} and {@link DigDownGoal}; the rendering half is not ours).
 *
 * <p>Pure (JUnit: GoalsTest). Loader notes: BlockPos (common side, Mojang names).
 */
public interface HasGoalPos {
    BlockPos getGoalPos();
}
