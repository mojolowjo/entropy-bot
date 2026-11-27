/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified for Entropy Bot 2026-11-27: renamed SearchContext; built from a NavConfig, a BlockView and a BodySnapshot instead of IBaritone; the fork's cost advisor hook ported as an instance field, no static
 */

package io.github.mojolowjo.entropybot.nav.rules;

import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.cost.ActionCosts;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.BetterWorldBorder;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import io.github.mojolowjo.entropybot.nav.world.BrokenCellView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static io.github.mojolowjo.entropybot.nav.cost.ActionCosts.COST_INF;

/**
 * Everything one route search reads, fixed when the search starts (Baritone's {@code CalculationContext}).
 *
 * <p>Entropy Bot: built from a {@link NavConfig} (the settings), a {@link BlockView} (the world), a
 * {@link BodySnapshot} (the hotbar, effects, enchantments) and the {@link BlockRules} (which carry the kept cells);
 * no {@code IBaritone}, no live player, no global settings. Field names are Baritone's, so the lifted moves read the
 * same. The fork's cost advisor (the mod's break policy inside A*) is an instance field here, not a static: one
 * {@link CostAdvisor} per search, or null. An advisor that throws prices the cell COST_INF as the fork did, and the
 * first such failure is kept in {@link #advisorFailure()} so the search can say why.
 * Dimension facts (min y, height, border) come from the view, never from a Level.
 *
 * <p>Pure (JUnit: SearchContextTest, RulesMatchForkTest). Loader notes: BlockState, Block, BlockPos, fastutil (common
 * side, Mojang names).
 *
 * @author Brady
 * @since 8/7/2018
 */
public class SearchContext {

    public final NavConfig config;
    public final BlockRules rules;
    public final BlockView bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;

    public final PrecomputedData precomputedData;

    /** The fork's cost advisor for this search, or null (then breaks cost x1 and places blockPlacementPenalty). */
    public final CostAdvisor advisor;
    /** The fork's {@code pricesMoves}: the advisor adds per-cell move prices (false skips the two calls per move). */
    public final boolean pricesMoves;

    private Long2ObjectOpenHashMap<BreakAnswer> breakAnswers;
    private BlockView advisorView;
    private BrokenCellView brokenView;
    private String advisorFailure;

    public SearchContext(NavConfig config, BlockView world, BodySnapshot body) {
        this(config, new BlockRules(config), world, body, null);
    }

    public SearchContext(NavConfig config, BlockRules rules, BlockView world, BodySnapshot body, CostAdvisor advisor) {
        if (rules.config != config) throw new IllegalArgumentException("the rules were built for another config");
        this.config = config;
        this.rules = rules;
        this.precomputedData = rules.precomputed;
        this.bsi = world;
        NavConfig.Moves m = config.moves();
        this.toolSet = new ToolSet(body, config.breaking());
        this.hasThrowaway = config.placing().allowPlace() && body.hasThrowaway();
        this.hasWaterBucket = m.allowWaterBucketFall() && body.waterBucketOnHotbar() && !body.nether();
        this.canSprint = m.allowSprint() && body.foodLevel() > 6;
        this.placeBlockCost = config.placing().blockPlacementPenalty();
        this.allowBreak = config.breaking().allowBreak();
        this.allowBreakAnyway = new ArrayList<>(config.breaking().allowBreakAnyway());
        this.allowParkour = m.allowParkour();
        this.allowParkourPlace = m.allowParkourPlace();
        this.allowJumpAtBuildLimit = m.allowJumpAtBuildLimit();
        this.allowParkourAscend = m.allowParkourAscend();
        this.assumeWalkOnWater = m.assumeWalkOnWater();
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        this.frostWalker = body.frostWalker();
        this.allowDiagonalDescend = m.allowDiagonalDescend();
        this.allowDiagonalAscend = m.allowDiagonalAscend();
        this.allowDownward = m.allowDownward();
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        this.maxFallHeightNoWater = m.maxFallHeightNoWater();
        this.maxFallHeightBucket = m.maxFallHeightBucket();
        float waterSpeedMultiplier = body.waterSpeedMultiplier();
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        this.breakBlockAdditionalCost = config.breaking().blockBreakAdditionalPenalty();
        this.backtrackCostFavoringCoefficient = config.search().backtrackCostFavoringCoefficient();
        this.jumpPenalty = m.jumpPenalty();
        this.walkOnWaterOnePenalty = m.walkOnWaterOnePenalty();
        this.allowWalkOnMagmaBlocks = m.allowWalkOnMagmaBlocks();
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = world.worldBorder;
        this.advisor = advisor;
        boolean pm = false;
        if (advisor != null) {
            try {
                pm = advisor.pricesMoves();
            } catch (RuntimeException e) {
                pm = true; // the fork's answer when the advisor cannot say: assume it does
                failed("pricesMoves", e);
            }
        }
        this.pricesMoves = pm;
    }

    /** The lowest block y of the dimension (fork: {@code dimensionMinY}, the dimension type's minY). */
    public int dimensionMinY() {
        return bsi.minBuildHeight();
    }

    /** The dimension's height (fork: {@code dimensionHeight}). */
    public int dimensionHeight() {
        return bsi.height();
    }

    /** Fork: {@code minBuildHeight}, the level's lowest build y (same as the dimension's min y in vanilla). */
    public int minBuildHeight() {
        return bsi.minBuildHeight();
    }

    /** Fork: {@code searchBorder}, the border the search must stay inside. */
    public BetterWorldBorder searchBorder() {
        return worldBorder;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!config.placing().allowPlaceInFluidsSource() && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!config.placing().allowPlaceInFluidsFlow() && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (advisor != null) { // fork: the policy prices the place; NaN = no say
            try {
                double c = advisor.placeCost(x, y, z, current, bsi, placeBlockCost);
                if (!Double.isNaN(c)) {
                    return c;
                }
            } catch (RuntimeException e) {
                failed("placeCost at " + x + " " + y + " " + z, e);
                return COST_INF;
            }
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (advisor == null) {
            return 1;
        }
        // fork: one answer per cell per search, unless the advisor is looking at a "broken above" view
        boolean keep = advisorView == null;
        long key = keep ? BetterBlockPos.longHash(x, y, z) : 0L;
        if (keep) {
            if (breakAnswers == null) {
                breakAnswers = new Long2ObjectOpenHashMap<>();
            }
            BreakAnswer had = breakAnswers.get(key);
            if (had != null && had.state == current) {
                return had.multiplier;
            }
        }
        double out;
        try {
            double m = advisor.breakMultiplier(x, y, z, current, advisorView == null ? bsi : advisorView);
            out = Double.isNaN(m) ? 1 : m;
        } catch (RuntimeException e) {
            failed("breakMultiplier at " + x + " " + y + " " + z, e);
            out = COST_INF;
        }
        if (keep) {
            breakAnswers.put(key, new BreakAnswer(current, out));
        }
        return out;
    }

    /** Fork: until {@link #advisorSeesTheMap()}, the advisor judges breaks as if x y z were already air. */
    public void advisorSeesBrokenAt(int x, int y, int z) {
        if (brokenView == null) {
            brokenView = new BrokenCellView(bsi);
        }
        advisorView = brokenView.at(x, y, z);
    }

    public void advisorSeesTheMap() {
        advisorView = null;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        // TODO more protection logic here; see #220
        return false;
    }

    /** Fork: the advisor's price factor for standing in x y z (NaN = no say, COST_INF if it threw). */
    public double moveMultiplier(int x, int y, int z) {
        if (advisor == null) {
            return Double.NaN;
        }
        try {
            return advisor.moveMultiplier(x, y, z);
        } catch (RuntimeException e) {
            failed("moveMultiplier at " + x + " " + y + " " + z, e);
            return COST_INF;
        }
    }

    /** Fork: the advisor's added ticks for standing in x y z (NaN = no say, COST_INF if it threw). */
    public double moveExtra(int x, int y, int z) {
        if (advisor == null) {
            return Double.NaN;
        }
        try {
            return advisor.moveExtra(x, y, z, bsi);
        } catch (RuntimeException e) {
            failed("moveExtra at " + x + " " + y + " " + z, e);
            return COST_INF;
        }
    }

    /** The first advisor failure in this search as text ("breakMultiplier at 1 2 3: ..."), or null when none threw. */
    public String advisorFailure() {
        return advisorFailure;
    }

    private void failed(String what, RuntimeException e) {
        if (advisorFailure == null) {
            advisorFailure = what + ": " + e;
        }
    }

    private record BreakAnswer(BlockState state, double multiplier) {
    }
}
