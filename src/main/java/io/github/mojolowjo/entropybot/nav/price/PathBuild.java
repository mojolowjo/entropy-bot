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
 * Entropy Bot 2026-11-27: the fork's PathBuild (fork-only class, from the fork's source at dd8235b7), over our Path and Step: a move's cells come from MoveShape (no Movement objects)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathMemory;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Cell;
import io.github.mojolowjo.entropybot.nav.moves.MoveShape;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.pathfinder.PathComputationType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Entropy z57 (path memory) + review: how a priced path's segments become the memory's kept path, and what is read at a
 * cell. Built from our planner's segments (Path, its Steps; each move's cells from MoveShape, as Baritone's Movement names
 * them): the cells, the search's cost to each cell, each
 * move's valid positions (where PathExecutor lets the bot stand during it), the WATCHED cells (every cell a move depends
 * on: each valid position with the floor below and the head above it, the planned breaks and the placement - a diagonal's
 * corners, an ascend's head clearance at y + 2, a fall's shaft, a parkour's gap), each read when ITS segment landed; the
 * kept segments' suffix from any cell (the first cut by Baritone's CutoffPath). A cell is read as {@link Seen}: Baritone's
 * own classification (walk through, fully passable, walk on, avoid walking into, water, lava, pathfindable on land = a
 * door / gate / trapdoor open, a bottom slab, the snow layers), so a crop growing or a leaf's distance does not drop a
 * path and a closed gate, a toggled trapdoor or iron door, a slab half or a snow depth does; a cell whose chunk is not
 * loaded is {@link Seen#NOT_LOADED} (rule 15), never air.
 * Pure but for the game's block types (JUnit: PricedPathBuildTest; PricingMatchForkTest against the fork's own build over its
 * Movements). Loader notes: vanilla BlockState
 * (isAir, getFluidState, isPathfindable, getValue), BuiltInRegistries.BLOCK for the name only.
 */
public final class PathBuild {
    private PathBuild() {}

    /** Baritone's predicates at a cell (MovementHelper in the game; a test's own). */
    public interface CellFacts {
        boolean walkThrough(BlockState s, int x, int y, int z);

        boolean fullyPassable(BlockState s, int x, int y, int z);

        boolean walkOn(BlockState s, int x, int y, int z);

        boolean avoidWalkingInto(BlockState s);
    }

    static final int AIR = 1, THROUGH = 2, PASSABLE = 4, ON = 8, AVOID = 16, WATER = 32, LAVA = 64, LAND = 128, BOTTOM_SLAB = 256; // structural: the read's flag bits
    static final int SNOW_SHIFT = 12;   // structural: the snow layers sit above the flag bits

    /** What a cell is, for the path memory: Baritone's classification bits and the block (its break time, its name). */
    public record Seen(int bits, Block block) {
        public static final Seen NOT_LOADED = new Seen(-1, null);

        @Override
        public String toString() {
            if (bits < 0) {
                return "not loaded";
            }
            List<String> f = new ArrayList<>();
            if ((bits & AIR) != 0) f.add("air");
            if ((bits & THROUGH) != 0) f.add("walk-through");
            if ((bits & PASSABLE) != 0) f.add("passable");
            if ((bits & ON) != 0) f.add("walk-on");
            if ((bits & AVOID) != 0) f.add("avoid");
            if ((bits & WATER) != 0) f.add("water");
            if ((bits & LAVA) != 0) f.add("lava");
            if ((bits & LAND) != 0) f.add("open");
            if ((bits & BOTTOM_SLAB) != 0) f.add("bottom slab");
            if (bits >> SNOW_SHIFT != 0) f.add((bits >> SNOW_SHIFT) + " snow layers");
            return (block == null ? "?" : String.valueOf(BuiltInRegistries.BLOCK.getKey(block))) + " " + f;
        }
    }

    /** A cell read (loaded = its chunk is loaded in Baritone's world view). */
    public static Seen classify(BlockState s, int x, int y, int z, boolean loaded, CellFacts f) {
        if (!loaded || s == null || s.is(net.minecraft.world.level.block.Blocks.VOID_AIR)) {
            return Seen.NOT_LOADED;
        }
        int b = 0;
        if (s.isAir()) b |= AIR;
        if (f.walkThrough(s, x, y, z)) b |= THROUGH;
        if (f.fullyPassable(s, x, y, z)) b |= PASSABLE;
        if (f.walkOn(s, x, y, z)) b |= ON;
        if (f.avoidWalkingInto(s)) b |= AVOID;
        if (s.getFluidState().is(FluidTags.WATER)) b |= WATER;
        if (s.getFluidState().is(FluidTags.LAVA)) b |= LAVA;
        if (s.isPathfindable(PathComputationType.LAND)) b |= LAND;
        if (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) b |= BOTTOM_SLAB;
        if (s.hasProperty(SnowLayerBlock.LAYERS)) b |= s.getValue(SnowLayerBlock.LAYERS) << SNOW_SHIFT;
        return new Seen(b, s.getBlock());
    }

    /** The memory's compare over {@link Seen} with {@code read} for the cells now. */
    public static PathMemory.Blocks<Seen> blocks(Function<Cell, Seen> read) {
        return new PathMemory.Blocks<>() {
            @Override
            public Seen read(Cell c) {
                return read.apply(c);
            }

            @Override
            public boolean same(Seen was, Seen now, boolean breakPlanned) {
                if (was == null || now == null) {
                    return was == now;
                }
                return was.bits() == now.bits() && (!breakPlanned || was.block() == now.block());
            }

            @Override
            public boolean notLoaded(Seen s) {
                return s == null || s.bits() < 0;
            }

            @Override
            public boolean broken(Seen s) {
                return s != null && s.bits() >= 0 && (s.bits() & AIR) != 0;
            }

            @Override
            public boolean placed(Seen s) {
                return s != null && s.bits() >= 0 && (s.bits() & AIR) == 0 && (s.bits() & ON) != 0;
            }
        };
    }

    public static Cell cell(BlockPos p) {
        return new Cell(p.getX(), p.getY(), p.getZ());
    }

    /**
     * The search's cost from the segment's start to each of its positions - A*'s own node costs ({@code nodeCost}, the cost
     * hook's multipliers included, as the price's total); where the search kept no node for a position, the movements' own
     * costs (Baritone's Path assembles them from the node costs too). The last one is the segment's cost in the price.
     */
    public static double[] along(ToDoubleFunction<BetterBlockPos> nodeCost, Path p) {
        List<BetterBlockPos> pos = p.positions();
        double[] g = new double[pos.size()];
        boolean nodes = true;
        for (int j = 0; j < pos.size() && nodes; j++) {
            g[j] = nodeCost.applyAsDouble(pos.get(j));
            nodes = !Double.isNaN(g[j]);
        }
        if (nodes) {
            double g0 = g[0];
            for (int j = 0; j < g.length; j++) {
                g[j] -= g0;
            }
            return g;
        }
        List<Step> moves = p.steps();
        g[0] = 0;
        for (int j = 1; j < g.length; j++) {
            g[j] = g[j - 1] + (j - 1 < moves.size() ? moves.get(j - 1).cost() : 0);
        }
        return g;
    }

    /** Where the bot may stand during the move (Movement#getValidPositions, from {@link MoveShape}), src and dest first. */
    public static Set<Cell> valid(Step m) {
        Set<Cell> out = new LinkedHashSet<>();
        out.add(cell(m.src()));
        out.add(cell(m.dest()));
        for (BetterBlockPos p : shape(m).valid()) {
            out.add(cell(p));
        }
        return out;
    }

    /** The move's cells as its Baritone Movement names them. */
    public static MoveShape shape(Step m) {
        return MoveShape.of(m.move(), m.src(), m.dest());
    }

    /** Every cell the move depends on: each valid position with the floor below and the head above, its breaks, its place. */
    public static Set<Cell> watched(Step m) {
        Set<Cell> out = new LinkedHashSet<>();
        for (Cell c : valid(m)) {
            out.add(c.up(-1));
            out.add(c);
            out.add(c.up(1));
        }
        out.addAll(breaks(m));
        Cell place = place(m);
        if (place != null) {
            out.add(place);
        }
        return out;
    }

    static Set<Cell> breaks(Step m) {
        Set<Cell> out = new HashSet<>();
        for (BlockPos b : shape(m).breaks()) {
            out.add(cell(b));
        }
        return out;
    }

    static Cell place(Step m) {
        BetterBlockPos p = shape(m).place();
        return p == null ? null : cell(p);
    }

    /**
     * Entropy D11 (the re-join): a kept path's segment (or its cutoff) priced along from the kept path's own costs - {@code g[k]}
     * = the kept cost at the segment's position k less the kept cost at its first position, the positions found in the kept
     * path from {@code from} on (the join's landing index). A position not on the kept path (never, for its own segments):
     * the previous cost carried (said nowhere: the build's sum then under-prices that one move; the search's cost is kept
     * elsewhere). Pure.
     */
    public static double[] alongKept(PathMemory.Path<?> kept, Path seg, int from) {
        List<BetterBlockPos> pos = seg.positions();
        double[] g = new double[pos.size()];
        int at = Math.max(0, from);
        double base = Double.NaN;
        double last = 0;
        for (int k = 0; k < pos.size(); k++) {
            int i = kept.indexOf(cell(pos.get(k)), at);
            if (i >= 0) {
                at = i;
                double c = kept.cum(i);
                if (Double.isNaN(base)) {
                    base = c;
                }
                last = c - base;
            }
            g[k] = Double.isNaN(base) ? 0 : last;
        }
        return g;
    }

    /** One segment's watched cells read now (when the segment lands: review 4). */
    public static <S> Map<Cell, S> snapshot(Path seg, Function<Cell, S> read) {
        Map<Cell, S> out = new HashMap<>();
        for (Step m : seg.steps()) {
            for (Cell c : watched(m)) {
                out.computeIfAbsent(c, read);
            }
        }
        return out;
    }

    /** The kept path and the flat cells' segment / position, built from the landed segments. */
    public record Built<S>(PathMemory.Path<S> path, List<Cell> cells, int[] segOf, int[] posOf) {}

    /**
     * The memory's path from the segments, each one's cost along it ({@link #along}) and its snapshot ({@link #snapshot},
     * read when it landed). A cell watched by several moves keeps the last move that needs it, its first read, and whether
     * any move breaks or places there.
     */
    public static <S> Built<S> build(List<Path> segs, List<double[]> alongs, List<Map<Cell, S>> snaps) {
        List<Cell> cells = new ArrayList<>();
        List<Double> cum = new ArrayList<>();
        List<Integer> segOf = new ArrayList<>(), posOf = new ArrayList<>();
        List<Set<Cell>> valid = new ArrayList<>();
        Map<Cell, PathMemory.Watch<S>> watch = new LinkedHashMap<>();
        double base = 0;
        int move = 0;
        for (int s = 0; s < segs.size(); s++) {
            Path seg = segs.get(s);
            double[] g = alongs.get(s);
            List<BetterBlockPos> pos = seg.positions();
            for (int j = s == 0 ? 0 : 1; j < pos.size(); j++) {   // a segment's start is the last one's end: once
                cells.add(cell(pos.get(j)));
                cum.add(base + g[j]);
                segOf.add(s);
                posOf.add(j);
            }
            base += g[g.length - 1];
            Map<Cell, S> snap = snaps.get(s);
            for (Step m : seg.steps()) {
                valid.add(valid(m));
                Set<Cell> br = breaks(m);
                Cell pl = place(m);
                for (Cell c : watched(m)) {
                    PathMemory.Watch<S> old = watch.get(c);
                    S was = old != null ? old.was() : snap.get(c);
                    boolean b = (old != null && old.breaks()) || br.contains(c);
                    boolean p = (old != null && old.places()) || c.equals(pl);
                    watch.put(c, new PathMemory.Watch<>(move, was, b, p));
                }
                move++;
            }
        }
        double[] cumA = new double[cum.size()];
        for (int i = 0; i < cumA.length; i++) {
            cumA[i] = cum.get(i);
        }
        return new Built<>(new PathMemory.Path<>(cells, cumA, valid, watch), List.copyOf(cells),
                segOf.stream().mapToInt(Integer::intValue).toArray(), posOf.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * What the fork keeps beside the memory's path for one goal - the segments (for the walk's offer and the mod's probe),
     * each flat cell's segment and position in it, the no-path detail, the DigDownGoal fact.
     */
    public record SegInfo(List<Path> segments, int[] segOf, int[] posOf, String failure, boolean steered) {

        /** The kept segments from flat cell i on: the first one cut to start at cell i (Baritone's CutoffPath). */
        public List<Path> suffix(int i) {
            if (i < 0 || i >= segOf.length || segments.isEmpty()) {
                return List.of();
            }
            int s = segOf[i], p = posOf[i];
            Path seg = segments.get(s);
            if (p >= seg.length() - 1) {                   // a segment's end: the next one starts here
                s++;
                p = 0;
                if (s >= segments.size()) {
                    return List.of();
                }
                seg = segments.get(s);
            }
            List<Path> out = new ArrayList<>();
            out.add(p == 0 ? seg : seg.cutoff(p, seg.length() - 1));
            out.addAll(segments.subList(s + 1, segments.size()));
            return out;
        }
    }
}
