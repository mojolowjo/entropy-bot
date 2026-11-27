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
 * Entropy Bot 2026-11-27: the fork's PricedPath memory glue (now, facts, seen, onBlockChange, onChunkEvent, the
 * SteerMemory), moved out of PricedPath; cells classified with our BlockRules over our BlockView.
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathBuild.Seen;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.goals.HasGoalPos;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.Ternary;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The kept answers: the {@link PathMemory} of goals to prices, what an ask compares a kept answer with ({@link #now}: the
 * feet, the world, the stamp and the cells, each built only when compared), a cell read as {@link Seen} (Baritone's own
 * classification: our {@link BlockRules}), the block-change and chunk-load drops, and per goal whether its last price used a
 * steered search goal. Game thread. Pure but for block states (JUnit: PriceQueueTest). Loader notes: vanilla BlockState
 * (isPathfindable) only.
 */
final class KeptAnswers {
    final PathMemory<Goal, PriceRun, Seen> memory = new PathMemory<>();
    private final PriceWorld world;
    /** Entropy z57 review 2 item 3: stamps built (an ask's compare or a search's start), for the load probe. */
    long stampBuilds;

    KeptAnswers(PriceWorld world) {
        this.world = world;
        // Entropy z60 item 1 (run R 03:34:54): queued first answers start nearest first - the straight line from the feet to a
        // goal with a position orders the queue only, never a price (rule 18); a goal without one sorts last in its class
        memory.distance(g -> {
            if (!(g instanceof HasGoalPos rp)) return Double.POSITIVE_INFINITY;
            BetterBlockPos f = world.start();
            return f == null ? Double.POSITIVE_INFINITY : Math.sqrt(f.distSqr(rp.getGoalPos()));
        });
    }

    /** Entropy z57 review 2 item 3: the stamp and the cells, built only when the memory compares a kept answer (counted). */
    PathMemory.Now<Seen> now(Goal goal, BetterBlockPos at, long tick) {
        Supplier<PathMemory.Stamp> stamp = once(() -> {
            stampBuilds++;
            return world.stampNow();
        });
        Supplier<PathMemory.Blocks<Seen>> blocks = once(this::blocksNow);
        return new PathMemory.Now<>(PathBuild.cell(at), goal.isInGoal(at), world.world(), stamp, tick, blocks);
    }

    /** The memory's compare over the cells read now. */
    PathMemory.Blocks<Seen> blocksNow() {
        BlockView bsi = world.view();
        PathBuild.CellFacts f = facts(world.rules(), bsi);
        return PathBuild.blocks(cell -> seen(bsi, f, cell, null));
    }

    /** A cell read now. */
    Seen read(PathMemory.Cell c) {
        BlockView bsi = world.view();
        return seen(bsi, facts(world.rules(), bsi), c, null);
    }

    /** A supplier read at most once. */
    static <T> Supplier<T> once(Supplier<T> s) {
        Object[] v = new Object[1];
        boolean[] done = new boolean[1];
        return () -> {
            if (!done[0]) {
                v[0] = s.get();
                done[0] = true;
            }
            @SuppressWarnings("unchecked") T t = (T) v[0];
            return t;
        };
    }

    /** Entropy z57 review 2: Baritone's own predicates at a cell (our BlockRules), over this view. */
    static PathBuild.CellFacts facts(BlockRules rules, BlockView bsi) {
        return new PathBuild.CellFacts() {
            @Override
            public boolean walkThrough(BlockState s, int x, int y, int z) {
                return rules.canWalkThrough(bsi, x, y, z, s);
            }

            @Override
            public boolean fullyPassable(BlockState s, int x, int y, int z) {
                Ternary t = BlockRules.fullyPassableBlockState(s);
                return t == Ternary.YES || t == Ternary.MAYBE && s.isPathfindable(PathComputationType.LAND);
            }

            @Override
            public boolean walkOn(BlockState s, int x, int y, int z) {
                return rules.canWalkOn(bsi, x, y, z, s);
            }

            @Override
            public boolean avoidWalkingInto(BlockState s) {
                return rules.avoidWalkingInto(s);
            }
        };
    }

    /**
     * A cell as the view classifies it now (not loaded: its chunk is not really loaded). {@code given}: the state a block
     * update reports (a predicted change may not be in the view yet); null reads the view. The fork read the client level
     * itself (ctx.world().getBlockState); here the view is the one place the cells come from.
     */
    static Seen seen(BlockView bsi, PathBuild.CellFacts f, PathMemory.Cell c, BlockState given) {
        boolean loaded = bsi.worldContainsLoadedChunk(c.x(), c.z());
        BlockState s = !loaded ? null : given != null ? given : bsi.get0(c.x(), c.y(), c.z());
        return PathBuild.classify(s, c.x(), c.y(), c.z(), loaded, f);
    }

    /**
     * Entropy z57 (job item 1b) + review 10: a block update (the game's block and section update packets): the changed cell
     * is looked up in the memory's per-cell index of watched cells, classified from the packet's own state, and a remaining
     * move's cell that changed - not as its own planned work - drops that kept path at once. Returns the paths dropped.
     */
    int blockChanged(BetterBlockPos pos, BlockState state) {
        BetterBlockPos at = world.start();
        if (at == null || world.world() == null || memory.watchedCells() == 0) {
            return 0;
        }
        BlockView bsi = world.view();
        PathBuild.CellFacts f = facts(world.rules(), bsi);
        PathMemory.Blocks<Seen> blocks = PathBuild.blocks(c -> seen(bsi, f, c, null));
        PathMemory.Cell c = PathBuild.cell(pos);
        return memory.blockChanged(c, seen(bsi, f, c, state), PathBuild.cell(at), blocks);
    }

    /**
     * Entropy z56 review 2: per goal (by equals), whether its last price used a steered search goal (DigDownGoal or
     * ClimbGoal). Recorded when a price lands, kept past the kept price, cleared on forget; at most {@link #MAX} goals.
     */
    static final class SteerMemory {
        static final int MAX = 256;   // structural: a bound on goals remembered, far above a chain's walks in flight
        private final LinkedHashMap<Goal, Boolean> m = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Goal, Boolean> e) {
                return size() > MAX;
            }
        };

        void record(Goal g, boolean steered) {
            m.put(g, steered);
        }

        Boolean get(Goal g) {
            return m.get(g);
        }

        void clear() {
            m.clear();
        }
    }
}
