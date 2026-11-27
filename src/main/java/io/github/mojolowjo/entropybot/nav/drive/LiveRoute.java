package io.github.mojolowjo.entropybot.nav.drive;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Nav step 9a: plans a route in the live world with our planner (step 6) and answers the pilot's questions about the
 * live cells. The config is Baritone's defaults with breaking and placing off (9a has no hands in the route); the body
 * is the player's hotbar, food and effects. The search runs on the game thread with a small budget (a 9a test verb
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

    /** The planner's config for 9a: the fork's defaults, breaking and placing off. */
    static NavConfig config() {
        NavConfig d = NavConfig.defaults();
        NavConfig.Breaking b = d.breaking();
        NavConfig.Placing p = d.placing();
        return d.withBreaking(new NavConfig.Breaking(false, b.allowBreakAnyway(), b.blockBreakAdditionalPenalty(), b.blocksToDisallowBreaking(),
                        b.blocksToAvoidBreaking(), b.avoidBreakingMultiplier(), b.allowBreakNextToWater(), b.allowBreakNextToLava(),
                        b.avoidUpdatingFallingBlocks(), b.considerPotionEffects(), b.useSwordToMine(), b.itemSaver(), b.itemSaverThreshold(),
                        b.autoTool(), b.assumeExternalAutoTool(), b.preferSilkTouch()))
                .withPlacing(new NavConfig.Placing(false, p.blockPlacementPenalty(), p.allowPlaceInFluidsSource(), p.allowPlaceInFluidsFlow()));
    }

    private static final NavConfig CONFIG = config();
    private static final BlockRules RULES = new BlockRules(CONFIG);

    static LiveRoute of(LocalPlayer p) {
        LiveBlockView view = new LiveBlockView((net.minecraft.client.multiplayer.ClientLevel) p.level());
        List<ItemStack> bar = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {                                     // structural: the hotbar's 9 slots
            bar.add(p.getInventory().getItem(i));
        }
        var haste = p.getEffect(MobEffects.DIG_SPEED);
        var fatigue = p.getEffect(MobEffects.DIG_SLOWDOWN);
        BodySnapshot body = new BodySnapshot(bar, p.getInventory().selected, haste == null ? -1 : haste.getAmplifier(),
                fatigue == null ? -1 : fatigue.getAmplifier(), 0, 1f, p.getFoodData().getFoodLevel(), false, false,
                p.level().dimension() == Level.NETHER);
        return new LiveRoute(view, new SearchContext(CONFIG, RULES, view, body, null));
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
        };
    }
}
