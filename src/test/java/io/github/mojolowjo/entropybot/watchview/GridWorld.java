package io.github.mojolowjo.entropybot.watchview;

import java.util.HashSet;
import java.util.Set;

/** A test world: solid rock everywhere except the open cells set here; "sky" above skyY is open and sky-lit. */
final class GridWorld implements Shell.World {
    final Set<Long> open = new HashSet<>();
    final Set<Long> unknown = new HashSet<>();
    int skyY = Integer.MAX_VALUE;

    GridWorld open(int x, int y, int z) {
        open.add(CellKey.of(x, y, z));
        return this;
    }

    GridWorld openBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) open(x, y, z);
        return this;
    }

    GridWorld solid(int x, int y, int z) {
        open.remove(CellKey.of(x, y, z));
        return this;
    }

    @Override
    public int kind(int x, int y, int z) {
        long k = CellKey.of(x, y, z);
        if (unknown.contains(k)) return Shell.UNKNOWN;
        if (y >= skyY) return Shell.OPEN;
        return open.contains(k) ? Shell.OPEN : Shell.SOLID;
    }

    boolean sky(int x, int y, int z) { return y >= skyY; }
}
