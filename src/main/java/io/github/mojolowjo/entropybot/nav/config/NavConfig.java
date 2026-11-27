package io.github.mojolowjo.entropybot.nav.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * nav step 2 (to-do/nav-steps-1-6.md): the settings our planner reads, passed in instead of read from Baritone's global
 * {@code Baritone.settings()}. Each field has the SAME name and default as the Baritone setting it replaces (fork jar
 * 1.11.3-entropy), so a reader of Baritone's code finds it at once; {@code NavConfigTest} checks every default against
 * the jar's {@code baritone.api.Settings}. Only the settings the planning half reads are here (62 of the fork's 283, the search timeouts included);
 * the driving half's (overshootTraverse, walkWhileBreaking, assumeSafeWalk, assumeStep, sprintInWater,
 * pauseMiningForFallingBlocks) come with the pilot in step 8, and Baritone's debug-only slowPath* are left out.
 * The values are immutable: a run that wants other rules builds another config ({@code with*}), never a global edit.
 *
 * <p>Pure (JUnit: NavConfigTest). Loader notes: block references only (Blocks, common side, Mojang names).
 */
public record NavConfig(Search search, Moves moves, Breaking breaking, Placing placing) {

    /** How long and how wide the search may run, and how it picks partial paths. */
    public record Search(long primaryTimeoutMS, long failureTimeoutMS, long planAheadPrimaryTimeoutMS, long planAheadFailureTimeoutMS,
                         int planningTickLookahead, int pathingMaxChunkBorderFetch, boolean minimumImprovementRepropagation,
                         int pathingMapDefaultSize, float pathingMapLoadFactor, double costHeuristic,
                         double backtrackCostFavoringCoefficient, double pathCutoffFactor, int pathCutoffMinimumLength,
                         boolean cutoffAtLoadBoundary, boolean pathThroughCachedOnly, boolean avoidance,
                         double mobSpawnerAvoidanceCoefficient, int mobSpawnerAvoidanceRadius,
                         double mobAvoidanceCoefficient, int mobAvoidanceRadius) {}

    /** Which moves exist and what they cost on top of walking. */
    public record Moves(boolean allowSprint, boolean allowParkour, boolean allowParkourPlace, boolean allowParkourAscend,
                        boolean allowDiagonalDescend, boolean allowDiagonalAscend, boolean allowDownward, boolean allowJumpAtBuildLimit,
                        boolean allowVines, boolean allowWalkOnBottomSlab, boolean allowWalkOnMagmaBlocks,
                        boolean assumeWalkOnWater, boolean assumeWalkOnLava, boolean allowWaterBucketFall,
                        int maxFallHeightNoWater, int maxFallHeightBucket, double jumpPenalty, double walkOnWaterOnePenalty,
                        boolean pathUnderWater, double lavaCellCost, boolean strictLiquidCheck, List<Block> blocksToAvoid) {
        public Moves { blocksToAvoid = List.copyOf(blocksToAvoid); }
    }

    /** May the route break blocks, which ones, and how the mining time is priced. */
    public record Breaking(boolean allowBreak, List<Block> allowBreakAnyway, double blockBreakAdditionalPenalty,
                           List<Block> blocksToDisallowBreaking, List<Block> blocksToAvoidBreaking, double avoidBreakingMultiplier,
                           boolean allowBreakNextToWater, boolean allowBreakNextToLava, boolean avoidUpdatingFallingBlocks,
                           boolean considerPotionEffects, boolean useSwordToMine, boolean itemSaver, int itemSaverThreshold,
                           boolean autoTool, boolean assumeExternalAutoTool, boolean preferSilkTouch) {
        public Breaking {
            allowBreakAnyway = List.copyOf(allowBreakAnyway);
            blocksToDisallowBreaking = List.copyOf(blocksToDisallowBreaking);
            blocksToAvoidBreaking = List.copyOf(blocksToAvoidBreaking);
        }
    }

    /** May the route place blocks, and at what price. */
    public record Placing(boolean allowPlace, double blockPlacementPenalty, boolean allowPlaceInFluidsSource, boolean allowPlaceInFluidsFlow) {}

    /** Baritone's own defaults (fork jar 1.11.3-entropy). Needs the game's blocks to be registered (bootstrap). */
    public static NavConfig defaults() {
        return new NavConfig(
                new Search(500L, 2000L, 4000L, 5000L, 150, 50, true, 1024, 0.75F, 3.563, 0.5, 0.9, 30, false, false, false,
                        2.0, 16, 1.5, 8),
                new Moves(true, false, false, true, false, false, true, false, false, true, false, false, false, true,
                        3, 20, 2.0, 3.0, false, 0.0, false, new ArrayList<>(List.of(Blocks.TRIPWIRE))),
                new Breaking(true, List.of(), 2.0, List.of(),
                        Arrays.asList(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.CHEST, Blocks.TRAPPED_CHEST), 0.1,
                        false, false, true, true, true, false, 10, true, false, false),
                new Placing(true, 20.0, true, true));
    }

    public NavConfig withBreaking(Breaking b) { return new NavConfig(search, moves, b, placing); }
    public NavConfig withPlacing(Placing p) { return new NavConfig(search, moves, breaking, p); }
    public NavConfig withMoves(Moves m) { return new NavConfig(search, m, breaking, placing); }
    public NavConfig withSearch(Search s) { return new NavConfig(s, moves, breaking, placing); }
}
