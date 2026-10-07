package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.CellMoves;
import io.github.mojolowjo.entropybot.route.MoveSink;
import io.github.mojolowjo.entropybot.surface.SurfaceFamily;

/**
 * chunks-0.23.5: walking moves estimated from surface chunks (the companion's, see {@link SurfaceChunk}), for boxes of
 * quality SURFACE: the bot has not loaded that ground, Baritone can't see it, but the owner's client scanned it.
 *
 * <p>Standable: feet on top of the top ground run ({@code g + 1}) with no canopy in the feet or head block, or on top of
 * the run below ({@code g2 + 1}) with two blocks of room under the top run's underside ({@code u}). Lava never; water is
 * swum (its surface counts as ground). Moves: the 4 sides at the same level, one up (a jump) or down to 3 (a fall), and
 * the 4 diagonals at the same level when both sides are open. Costs are Baritone's own constants (ActionCosts) so the
 * boxes compare with live ones: a sprint step 3.564 ticks, a diagonal x sqrt 2, up a block +6.0 (jump), each block of
 * fall +2.0 (Baritone's FALL_N costs are 1.5-3 a block), in water 9.09 a block. A column of an unknown chunk is not
 * standable, so the box's edges only open where the neighbour's chunk is known too.
 *
 * <p>Plain Java, thread-safe as long as the lookup is (RouteRuntime: SurfaceInbox's ConcurrentHashMap). Loader notes: none.
 */
public final class SurfaceCellMoves implements CellMoves {
    /** Where a chunk's surface comes from: null when unknown. */
    @FunctionalInterface
    public interface Lookup {
        SurfaceChunk chunk(int cx, int cz);
    }

    public static final double SPRINT = 3.564, WATER = 9.09, JUMP = 6.0, FALL_PER_BLOCK = 2.0, DIAGONAL = Math.sqrt(2);
    public static final int MAX_FALL = 3;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Lookup lookup;

    public SurfaceCellMoves(Lookup lookup) {
        this.lookup = lookup;
    }

    private SurfaceChunk at(int x, int z) {
        try {
            return lookup.chunk(x >> 4, z >> 4);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean standable(int x, int y, int z) {
        SurfaceChunk s = at(x, z);
        if (s == null) return false;
        int i = SurfaceChunk.index(x, z);
        int g = s.g()[i];
        if (g != -1 && y == g + 1) {
            if (s.f()[i] == SurfaceFamily.LAVA) return false;
            int c = s.c()[i];
            return !(c == y || c == y + 1);
        }
        int g2 = s.g2()[i];
        if (g2 != -1 && y == g2 + 1) {
            int u = s.u()[i];
            return u >= y + 2;
        }
        return false;
    }

    private boolean water(int x, int y, int z) {
        SurfaceChunk s = at(x, z);
        if (s == null) return false;
        int i = SurfaceChunk.index(x, z);
        return s.g()[i] + 1 == y && s.f()[i] == SurfaceFamily.WATER;
    }

    @Override
    public void forEachMove(int x, int y, int z, MoveSink sink) {
        boolean fromWater = water(x, y, z);
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            for (int dy = 1; dy >= -MAX_FALL; dy--) {
                int ny = y + dy;
                if (!standable(nx, ny, nz)) continue;
                boolean wet = fromWater || water(nx, ny, nz);
                double cost = wet ? WATER : SPRINT;
                if (dy > 0) {
                    if (fromWater && !water(nx, ny, nz)) cost += JUMP;   // climbing out of water
                    else if (!wet) cost += JUMP;
                    else continue;                                       // no swimming up a block
                }
                if (dy < 0) cost += FALL_PER_BLOCK * -dy;
                sink.move(nx, ny, nz, cost);
                break;                                                   // the highest landing only
            }
        }
        for (int[] d : DIAG) {
            int nx = x + d[0], nz = z + d[1];
            if (!standable(nx, y, nz) || !standable(x + d[0], y, z) || !standable(x, y, z + d[1])) continue;
            boolean wet = fromWater || water(nx, y, nz);
            sink.move(nx, y, nz, (wet ? WATER : SPRINT) * DIAGONAL);
        }
    }
}
