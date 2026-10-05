package io.github.mojolowjo.entropybot.route;

import java.util.HashMap;
import java.util.Map;

/**
 * A synthetic world for the route tests: blocks are AIR, SOLID or WATER; everything not set is AIR, except below
 * {@code bedrockY} which is SOLID. Moves imitate Baritone's shape (not its exact numbers): traverse, ascend,
 * descend, fall up to 3, diagonal, swimming; costs in ticks.
 */
final class FakeWorld implements CellMoves {
    static final byte AIR = 0, SOLID = 1, WATER = 2;
    static final double WALK = 4.633, WATER_WALK = 15.0, ASCEND = 8.0, DESCEND = 5.0, DIAG = 6.55;

    private final Map<Long, Byte> blocks = new HashMap<>();
    private final int bedrockY;

    FakeWorld(int bedrockY) {
        this.bedrockY = bedrockY;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    byte get(int x, int y, int z) {
        Byte b = blocks.get(key(x, y, z));
        if (b != null) return b;
        return y < bedrockY ? SOLID : AIR;
    }

    FakeWorld set(int x, int y, int z, byte b) {
        blocks.put(key(x, y, z), b);
        return this;
    }

    FakeWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, byte b) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, b);
        return this;
    }

    private boolean passable(int x, int y, int z) {
        return get(x, y, z) != SOLID;
    }

    @Override
    public boolean standable(int x, int y, int z) {
        if (!passable(x, y, z) || !passable(x, y + 1, z)) return false;
        return get(x, y, z) == WATER || get(x, y - 1, z) == SOLID;
    }

    private boolean water(int x, int y, int z) {
        return get(x, y, z) == WATER;
    }

    @Override
    public void forEachMove(int x, int y, int z, MoveSink s) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            if (standable(nx, y, nz)) {
                s.move(nx, y, nz, water(x, y, z) || water(nx, y, nz) ? WATER_WALK : WALK);
            } else if (standable(nx, y + 1, nz) && passable(x, y + 2, z) && !water(x, y, z)) {
                s.move(nx, y + 1, nz, ASCEND);
            } else if (passable(nx, y, nz) && passable(nx, y + 1, nz)) {
                for (int k = 1; k <= 3; k++) {
                    if (!passable(nx, y - k, nz)) break;
                    if (standable(nx, y - k, nz)) {
                        s.move(nx, y - k, nz, k == 1 ? DESCEND : DESCEND + 2 * k);
                        break;
                    }
                }
            }
        }
        int[][] diags = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : diags) {
            int nx = x + d[0], nz = z + d[1];
            if (standable(nx, y, nz) && passable(nx, y, z) && passable(nx, y + 1, z)
                    && passable(x, y, nz) && passable(x, y + 1, nz))
                s.move(nx, y, nz, water(nx, y, nz) ? WATER_WALK * 1.414 : DIAG);
        }
    }
}
