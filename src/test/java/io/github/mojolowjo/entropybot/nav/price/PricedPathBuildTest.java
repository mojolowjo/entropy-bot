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
 * Entropy Bot 2026-11-27: the fork's PricedPathBuildTest (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathMemory.Cell;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Kind;
import io.github.mojolowjo.entropybot.nav.price.PathBuild.Seen;
import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entropy z57 review 11: the priced path built from real segments - our planner's Steps (traverse, ascend, diagonal,
 * fall; nav step 7: the fork built Baritone's Movement objects, MoveShape names the same cells) in two segments, as
 * PriceQueue.finish builds it - priced at each index through the memory, the walk's
 * suffix at each index, the watched cells (a diagonal's corners, an ascend's head at y + 2, a fall's shaft), the
 * left-the-path rule mid-fall, Baritone's classification (a closed fence gate drops, a crop growing does not), the
 * expected-result exemption, VOID_AIR = not loaded. Hand numbers: moves 4.6 + 9.0 (segment 1, by node costs), 6.5 + 12.0
 * (segment 2, by the movements' own costs: the search kept no nodes), total 32.1.
 */
public class PricedPathBuildTest {

    @BeforeAll
    public static void boot() {
        io.github.mojolowjo.entropybot.sim.SimSearch.boot();
    }

    static final BetterBlockPos P0 = new BetterBlockPos(0, 64, 0), P1 = new BetterBlockPos(1, 64, 0), P2 = new BetterBlockPos(2, 65, 0),
            P3 = new BetterBlockPos(3, 65, 1), P4 = new BetterBlockPos(4, 61, 1);

    private final Map<Cell, BlockState> world = new HashMap<>();
    private final java.util.Set<Cell> unloaded = new java.util.HashSet<>();

    private BlockState at(Cell c) {
        return world.getOrDefault(c, c.y() <= 63 && !(c.x() == 4 && c.y() >= 61) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }

    /** Test predicates in Baritone's spirit: a gate is walked through open or closed (Baritone opens it). */
    static final PathBuild.CellFacts FACTS = new PathBuild.CellFacts() {
        @Override public boolean walkThrough(BlockState s, int x, int y, int z) { return !s.blocksMotion() || s.getBlock() instanceof FenceGateBlock; }
        @Override public boolean fullyPassable(BlockState s, int x, int y, int z) { return s.isAir(); }
        @Override public boolean walkOn(BlockState s, int x, int y, int z) { return s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO); }
        @Override public boolean avoidWalkingInto(BlockState s) { return false; }
    };

    private Seen read(Cell c) {
        return PathBuild.classify(unloaded.contains(c) ? null : at(c), c.x(), c.y(), c.z(), !unloaded.contains(c), FACTS);
    }

    private Path seg0() {
        return Path.of(List.of(P0, P1, P2), List.of(new Step(P0, P1, MoveCatalog.TRAVERSE_EAST, 4.6), new Step(P1, P2, MoveCatalog.ASCEND_EAST, 9.0)), null);
    }

    private Path seg1() {
        return Path.of(List.of(P2, P3, P4), List.of(new Step(P2, P3, MoveCatalog.DIAGONAL_SOUTHEAST, 6.5),
                new Step(P3, P4, MoveCatalog.DESCEND_EAST, 12.0)), null);    // 4 down: MovementFall's shape
    }

    private PathBuild.Built<Seen> built;
    private List<Path> segs;

    private PathBuild.Built<Seen> build() {
        Path a = seg0(), b = seg1();
        segs = List.of(a, b);
        Map<BetterBlockPos, Double> nodes = Map.of(P0, 0.0, P1, 4.6, P2, 13.6);
        double[] ga = PathBuild.along(p -> nodes.getOrDefault(p, Double.NaN), a);
        double[] gb = PathBuild.along(p -> Double.NaN, b);           // no nodes kept: the movements' own costs
        built = PathBuild.build(segs, List.of(ga, gb), List.of(PathBuild.snapshot(a, this::read), PathBuild.snapshot(b, this::read)));
        return built;
    }

    private final PathMemory<String, Object, Seen> m = new PathMemory<>();
    private static final IPricedPath.Limits LIM = new IPricedPath.Limits(6000, 1200, 16, 4);
    private static final PathMemory.Stamp STAMP = new PathMemory.Stamp(Map.of("canSprint", true), Map.of(), Map.of());

    private PathMemory.Decision<Object> ask(Cell feet) {
        return m.ask("G", new PathMemory.Now<>(feet, feet.equals(PathBuild.cell(P4)), "w", () -> STAMP, 1, () -> PathBuild.blocks(this::read)), LIM);
    }

    private void land() {
        build();
        m.ask("G", new PathMemory.Now<>(PathBuild.cell(P0), false, "w", () -> STAMP, 1, () -> PathBuild.blocks(this::read)), LIM);
        m.started("G", PathBuild.cell(P0), "run");
        assertTrue(m.landed("G", "run", IPathCost.Answer.complete(32.1), built.path(), PathBuild.cell(P0), "w", STAMP, 1, null));
    }

    @Test
    public void alongUsesTheNodeCostsElseTheMovementsOwn() {
        build();
        assertArrayEquals(new double[]{0, 4.6, 13.6}, PathBuild.along(p -> Map.of(P0, 0.0, P1, 4.6, P2, 13.6).get(p), seg0()), 1e-9);
        assertArrayEquals(new double[]{0, 6.5, 18.5}, PathBuild.along(p -> Double.NaN, seg1()), 1e-9);
        assertEquals(List.of(PathBuild.cell(P0), PathBuild.cell(P1), PathBuild.cell(P2), PathBuild.cell(P3), PathBuild.cell(P4)), built.cells());
        assertEquals(32.1, built.path().total(), 1e-9);
    }

    @Test
    public void thePriceAtEachIndexIsThePathsRemainingCost() {
        land();
        double[] rest = {32.1, 27.5, 18.5, 12.0};                  // 32.1 - 4.6, - 9.0, - 6.5 (hand)
        BetterBlockPos[] at = {P0, P1, P2, P3};
        for (int i = 0; i < 4; i++) {
            PathMemory.Decision<Object> d = ask(PathBuild.cell(at[i]));
            assertEquals(i == 0 ? Kind.EXACT : Kind.SUFFIX, d.kind());
            assertEquals(rest[i], d.answer().ticks, 1e-9);
        }
        assertEquals(Kind.ZERO, ask(PathBuild.cell(P4)).kind());
    }

    @Test
    public void midFallTheFeetAreOnThePathAndTheWalkFitsThere() {
        land();
        Cell shaft = new Cell(4, 63, 1);                           // the fall's valid positions: its column
        PathMemory.Decision<Object> d = ask(shaft);
        assertEquals(Kind.SUFFIX, d.kind());
        assertEquals(12.0, d.answer().ticks, 1e-9);
        List<Path> suffix = new PathBuild.SegInfo(segs, built.segOf(), built.posOf(), null, false).suffix(3);
        assertEquals(P3, suffix.get(0).getSrc());                  // the walk's fit check (PricedPath.fits) moves with the walk (step 9)
        // Entropy D11: a step off the path is a re-join now (the kept path stays), never a drop
        PathMemory.Decision<Object> off = ask(new Cell(5, 63, 1));
        assertEquals(Kind.REJOIN, off.kind());
        assertEquals("left the path at 5 63 1 (on no cell and no move position of it)", off.dropped());
        assertEquals(3, off.index());
    }

    /** Entropy D11: a kept suffix segment priced along from the kept path's own costs (the join's landing splices it). */
    @Test
    public void aKeptSuffixIsPricedAlongFromTheKeptCosts() {
        build();
        PathBuild.SegInfo info = new PathBuild.SegInfo(segs, built.segOf(), built.posOf(), null, false);
        List<Path> s1 = info.suffix(1);
        assertArrayEquals(new double[]{0, 9.0}, PathBuild.alongKept(built.path(), s1.get(0), 1), 1e-9);        // P1 -> P2: 13.6 - 4.6
        assertArrayEquals(new double[]{0, 6.5, 18.5}, PathBuild.alongKept(built.path(), s1.get(1), 1), 1e-9);  // P2 -> P3 -> P4
        PathBuild.Built<Seen> spliced = PathBuild.build(s1, List.of(PathBuild.alongKept(built.path(), s1.get(0), 1), PathBuild.alongKept(built.path(), s1.get(1), 1)),
                List.of(PathBuild.snapshot(s1.get(0), this::read), PathBuild.snapshot(s1.get(1), this::read)));
        assertEquals(27.5, spliced.path().total(), 1e-9, "the suffix from cell 1 costs the kept rest");
        assertEquals(List.of(PathBuild.cell(P1), PathBuild.cell(P2), PathBuild.cell(P3), PathBuild.cell(P4)), spliced.cells());
    }

    @Test
    public void theSuffixAtEachIndexStartsThere() {
        build();
        PathBuild.SegInfo info = new PathBuild.SegInfo(segs, built.segOf(), built.posOf(), null, false);
        assertEquals(2, info.suffix(0).size());
        assertSame(segs.get(0), info.suffix(0).get(0));
        List<Path> s1 = info.suffix(1);
        assertEquals(2, s1.size());
        assertEquals(P1, s1.get(0).getSrc());                      // segment 1 cut at cell 1 (CutoffPath)
        assertEquals(1, s1.get(0).steps().size());
        assertEquals(List.of(segs.get(1)), info.suffix(2));        // the joint: segment 2 whole
        assertEquals(P3, info.suffix(3).get(0).getSrc());
        assertEquals(List.of(), info.suffix(4));                   // the end
    }

    @Test
    public void everyCellAMoveDependsOnIsWatched() {
        build();
        Map<Cell, PathMemory.Watch<Seen>> w = built.path().watched();
        assertTrue(w.containsKey(new Cell(2, 65, 1)) && w.containsKey(new Cell(3, 65, 0)));    // the diagonal's corners
        assertTrue(w.containsKey(new Cell(1, 66, 0)));                                         // the ascend's head at y + 2
        for (int y = 61; y <= 65; y++) assertTrue(w.containsKey(new Cell(4, y, 1)), "shaft " + y);
        assertTrue(w.containsKey(new Cell(4, 60, 1)));                                         // the fall's floor
        assertTrue(w.get(new Cell(1, 63, 0)).places());                                        // the traverse's bridge cell
        assertTrue(w.get(new Cell(2, 65, 0)).breaks());                                        // the ascend's dest
        assertEquals(3, w.get(new Cell(4, 62, 1)).lastMove());
    }

    @Test
    public void aClosedFenceGateDropsAndACropGrowingDoesNot() {
        world.put(new Cell(3, 65, 0), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true));   // a diagonal corner
        world.put(new Cell(2, 65, 1), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0));                     // the other corner
        land();
        world.put(new Cell(2, 65, 1), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        assertEquals(Kind.SUFFIX, ask(PathBuild.cell(P1)).kind());
        world.put(new Cell(3, 65, 0), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, false));
        String why = ask(PathBuild.cell(P1)).dropped();
        assertTrue(why.startsWith("block changed at 3 65 0 (minecraft:oak_fence_gate [walk-through, open] -> minecraft:oak_fence_gate [walk-through]"));
    }

    @Test
    public void thePlannedWorkMustReachItsExpectedResult() {
        world.put(new Cell(2, 65, 0), Blocks.DIRT.defaultBlockState());            // the ascend breaks its dest
        land();
        world.put(new Cell(2, 65, 0), Blocks.AIR.defaultBlockState());             // broken: expected
        world.put(new Cell(1, 63, 0), Blocks.COBBLESTONE.defaultBlockState());     // the bridge cell placed (was stone): solid, expected
        assertEquals(Kind.SUFFIX, ask(PathBuild.cell(P1)).kind());
        world.put(new Cell(2, 65, 0), Blocks.WATER.defaultBlockState());           // water in the broken cell: not the expected air
        assertTrue(ask(PathBuild.cell(P1)).dropped().startsWith("block changed at 2 65 0"));
    }

    @Test
    public void voidAirAndAnUnloadedChunkAreNotLoadedNeverAir() {
        assertSame(Seen.NOT_LOADED, PathBuild.classify(Blocks.VOID_AIR.defaultBlockState(), 0, 64, 0, true, FACTS));
        assertSame(Seen.NOT_LOADED, PathBuild.classify(null, 0, 64, 0, false, FACTS));
        assertFalse(PathBuild.blocks(c -> Seen.NOT_LOADED).broken(Seen.NOT_LOADED));
        assertFalse(PathBuild.blocks(c -> Seen.NOT_LOADED).placed(Seen.NOT_LOADED));
        land();
        unloaded.add(new Cell(4, 60, 1));                                          // the fall's floor chunk gone
        PathMemory.Decision<Object> d = ask(PathBuild.cell(P1));
        assertEquals(Kind.NOT_LOADED, d.kind());
        assertEquals(IPathCost.Answer.CHUNKS, d.answer().reason);
        unloaded.clear();
        assertEquals(Kind.SUFFIX, ask(PathBuild.cell(P1)).kind());
    }
}
