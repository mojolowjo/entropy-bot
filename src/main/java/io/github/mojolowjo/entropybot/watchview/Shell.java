package io.github.mojolowjo.entropybot.watchview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Camera v2: the shell of the known-air cells, i.e. every face between a known-air cell and a solid neighbour. That is
 * all the tunnel view draws, so nothing the bot never opened (an ore one block behind a tunnel wall, a cave it never
 * reached) can show: such a block borders no known-air cell. Faces between two known cells are not produced (it is
 * air on both sides), nor faces towards air the bot doesn't know, nor anything in a chunk that is not loaded.
 * Pure Java (JUnit: ShellTest).
 */
public final class Shell {
    /** What a cell is: open (air, a torch, water...), a solid full block, or not known (chunk not loaded). */
    public static final int OPEN = 0, SOLID = 1, UNKNOWN = 2;

    /** Directions in Minecraft's order (Direction.get3DDataValue): down, up, north (-z), south (+z), west (-x), east (+x). */
    public static final int[][] OFF = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    public interface World {
        int kind(int x, int y, int z);
    }

    /** A face to draw: the solid block at x y z, its side {@code side} (the one facing the known-air cell). */
    public record Face(int x, int y, int z, int side) {
        public int airX() { return x + OFF[side][0]; }

        public int airY() { return y + OFF[side][1]; }

        public int airZ() { return z + OFF[side][2]; }
    }

    public record Result(List<Face> faces, boolean capped, int cellsUsed) {}

    private Shell() {}

    public static int opposite(int d) { return d ^ 1; }

    /**
     * The shell around {@code cells} (known-air keys), nearest cells to cx cy cz first, at most maxFaces faces.
     * A known cell that is solid now (filled in since) is not air: it is skipped, and the faces of its known
     * neighbours towards it are drawn (the fill is a wall now).
     */
    public static Result build(long[] cells, World w, int cx, int cy, int cz, int maxFaces) {
        // nearest first without boxing (0.16.1): distance squared in the high bits, the index in the low 32
        long[] order = new long[cells.length];
        for (int i = 0; i < cells.length; i++) {
            long dx = CellKey.x(cells[i]) - cx, dy = CellKey.y(cells[i]) - cy, dz = CellKey.z(cells[i]) - cz;
            order[i] = Math.min(Integer.MAX_VALUE, dx * dx + dy * dy + dz * dz) << 32 | i;
        }
        Arrays.sort(order);
        List<Face> out = new ArrayList<>();
        int used = 0;
        for (long o : order) {
            long k = cells[(int) (o & 0xFFFFFFFFL)];
            int x = CellKey.x(k), y = CellKey.y(k), z = CellKey.z(k);
            if (w.kind(x, y, z) != OPEN) continue;
            used++;
            for (int d = 0; d < 6; d++) {
                int nx = x + OFF[d][0], ny = y + OFF[d][1], nz = z + OFF[d][2];
                int kind = w.kind(nx, ny, nz);
                if (kind != SOLID) continue;                  // open (known or not) or not loaded: no face
                if (out.size() >= maxFaces) return new Result(out, true, used);
                out.add(new Face(nx, ny, nz, opposite(d)));
            }
        }
        return new Result(out, false, used);
    }
}
