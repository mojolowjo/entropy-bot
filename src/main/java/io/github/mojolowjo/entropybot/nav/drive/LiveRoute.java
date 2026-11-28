package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.BodySnapshot;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.cost.MutableMoveResult;
import io.github.mojolowjo.entropybot.nav.search.AStarPathFinder;
import io.github.mojolowjo.entropybot.nav.search.Favoring;
import io.github.mojolowjo.entropybot.nav.search.PathCalculationResult;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.LiveBlockView;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Nav step 9a: plans a route in the live world with our planner (step 6) and answers the pilot's questions about the
 * live cells. The config is Baritone's defaults (9b: break and place on, parkour off unless asked); the body is the
 * player's hotbar, food and effects, and a full block anywhere in the bag counts as a throwaway (the fork reads its
 * acceptableThrowawayItems list; ours reads the item's block shape, no name list). The search runs on the game thread with a small budget (a 9a test verb
 * plans tens of blocks). Pure (JUnit: none, the adapter). Loader notes: vanilla {@code LocalPlayer} inventory, food and
 * effects, {@code Level.NETHER}; Fabric the same.
 */
final class LiveRoute {
    /** The search's budget on the game thread. */
    static final long PRIMARY_MS = 200, FAILURE_MS = 1000;              // structural: a test verb's search budget on the game thread
    static final int MAX_NODES = 200_000;                                // structural: the same budget in nodes

    final LiveBlockView view;
    final SearchContext context;

    private LiveRoute(LiveBlockView view, SearchContext context) {
        this.view = view;
        this.context = context;
    }

    /**
     * The planner's config: the fork's defaults (9b: break and place on, parkour off), parkour on when asked, and 9c's
     * "walk" (break and place off: the lane course and the route checker's plain trips).
     */
    static NavConfig config(boolean parkour, boolean walk) {
        NavConfig d = NavConfig.defaults();
        if (Tuning.i("steer.on") != 0) {                                     // steering S3: the steerer drives water, so the fork's under-water pathing is on (SwimRouteTest)
            NavConfig.Moves m = d.moves();
            d = d.withMoves(new NavConfig.Moves(m.allowSprint(), m.allowParkour(), m.allowParkourPlace(), m.allowParkourAscend(),
                    m.allowDiagonalDescend(), m.allowDiagonalAscend(), m.allowDownward(), m.allowJumpAtBuildLimit(), m.allowVines(),
                    m.allowWalkOnBottomSlab(), m.allowWalkOnMagmaBlocks(), m.assumeWalkOnWater(), m.assumeWalkOnLava(),
                    m.allowWaterBucketFall(), m.maxFallHeightNoWater(), m.maxFallHeightBucket(), m.jumpPenalty(),
                    m.walkOnWaterOnePenalty(), true, m.lavaCellCost(), m.strictLiquidCheck(), m.blocksToAvoid()));
        }
        if (parkour) {
            NavConfig.Moves m = d.moves();
            d = d.withMoves(new NavConfig.Moves(m.allowSprint(), true, m.allowParkourPlace(), m.allowParkourAscend(),
                    m.allowDiagonalDescend(), m.allowDiagonalAscend(), m.allowDownward(), m.allowJumpAtBuildLimit(), m.allowVines(),
                    m.allowWalkOnBottomSlab(), m.allowWalkOnMagmaBlocks(), m.assumeWalkOnWater(), m.assumeWalkOnLava(),
                    m.allowWaterBucketFall(), m.maxFallHeightNoWater(), m.maxFallHeightBucket(), m.jumpPenalty(),
                    m.walkOnWaterOnePenalty(), m.pathUnderWater(), m.lavaCellCost(), m.strictLiquidCheck(), m.blocksToAvoid()));
        }
        if (walk) {
            NavConfig.Breaking b = d.breaking();
            NavConfig.Placing pl = d.placing();
            d = d.withBreaking(new NavConfig.Breaking(false, b.allowBreakAnyway(), b.blockBreakAdditionalPenalty(), b.blocksToDisallowBreaking(),
                            b.blocksToAvoidBreaking(), b.avoidBreakingMultiplier(), b.allowBreakNextToWater(), b.allowBreakNextToLava(),
                            b.avoidUpdatingFallingBlocks(), b.considerPotionEffects(), b.useSwordToMine(), b.itemSaver(), b.itemSaverThreshold(),
                            b.autoTool(), b.assumeExternalAutoTool(), b.preferSilkTouch()))
                    .withPlacing(new NavConfig.Placing(false, pl.blockPlacementPenalty(), pl.allowPlaceInFluidsSource(), pl.allowPlaceInFluidsFlow()));
        }
        return d;
    }

    /** The four configs and the rules built for each (the search context wants rules built for its own config). */
    private static final NavConfig[] CONFIGS = new NavConfig[4];                // structural: parkour x walk
    private static final BlockRules[] RULESETS = new BlockRules[4];
    static {
        for (int i = 0; i < 4; i++) {                                         // structural: parkour x walk
            CONFIGS[i] = config((i & 1) != 0, (i & 2) != 0);
            RULESETS[i] = new BlockRules(CONFIGS[i]);
        }
    }
    private static final BlockRules RULES = RULESETS[0];

    static LiveRoute of(LocalPlayer p, boolean parkour, boolean walk) {
        LiveBlockView view = new LiveBlockView((net.minecraft.client.multiplayer.ClientLevel) p.level());
        List<ItemStack> bar = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {                                     // structural: the hotbar's 9 slots
            bar.add(p.getInventory().getItem(i));
        }
        var haste = p.getEffect(MobEffects.DIG_SPEED);
        var fatigue = p.getEffect(MobEffects.DIG_SLOWDOWN);
        boolean throwaway = false;
        for (int i = 0; i < 36 && !throwaway; i++) {                       // structural: the player inventory's 36 slots
            throwaway = DriveLive.fullBlock(p.getInventory().getItem(i));
        }
        BodySnapshot body = new BodySnapshot(bar, p.getInventory().selected, haste == null ? -1 : haste.getAmplifier(),
                fatigue == null ? -1 : fatigue.getAmplifier(), 0, 1f, p.getFoodData().getFoodLevel(), false, throwaway,
                p.level().dimension() == Level.NETHER);
        return new LiveRoute(view, new SearchContext(CONFIGS[(parkour ? 1 : 0) | (walk ? 2 : 0)], RULESETS[(parkour ? 1 : 0) | (walk ? 2 : 0)], view, body, null));
    }

    /** Plans from the feet to the goal. */
    PathCalculationResult plan(BetterBlockPos feet, Goal goal) {
        AStarPathFinder f = new AStarPathFinder(feet, feet.x, feet.y, feet.z, goal, Favoring.none(), context).costQuery(MAX_NODES, Double.POSITIVE_INFINITY);
        return f.calculate(PRIMARY_MS, FAILURE_MS);
    }

    /** The move asked again before it starts: null when it is still possible and still lands where planned. */
    String impossible(Step s) {
        MutableMoveResult r = new MutableMoveResult();
        s.move().apply(context, s.src().x, s.src().y, s.src().z, r);
        if (r.cost >= ActionCosts.COST_INF) {
            return "its cells changed (no longer passable)";
        }
        if (r.x != s.dest().x || r.y != s.dest().y || r.z != s.dest().z) {
            return "it now lands at " + r.x + " " + r.y + " " + r.z + " instead of " + s.dest().x + " " + s.dest().y + " " + s.dest().z;
        }
        return null;
    }

    /** The pilot's world: BlockRules on the live view, read fresh each tick (PR 4 review: no stale chunk). */
    MoveDriver.MoveWorld world() {
        view.reset();
        return new MoveDriver.MoveWorld() {
            @Override public boolean canWalkThrough(int x, int y, int z) { return RULES.canWalkThrough(view, x, y, z); }
            @Override public boolean canWalkOn(int x, int y, int z) { return RULES.canWalkOn(view, x, y, z); }
            @Override public boolean avoidWalkingInto(int x, int y, int z) { return RULES.avoidWalkingInto(view.get0(x, y, z)); }
            @Override public boolean isLiquid(int x, int y, int z) { return BlockRules.isLiquid(view.get0(x, y, z)); }
            @Override public boolean isWater(int x, int y, int z) { return BlockRules.isWater(view.get0(x, y, z)); }
            @Override public boolean isReplaceable(int x, int y, int z) { BlockState st = view.get0(x, y, z); return st.isAir() || st.canBeReplaced(); }
            @Override public boolean isClimbable(int x, int y, int z) { return BlockRules.isClimbable(view.get0(x, y, z).getBlock()); }
        };
    }
}
