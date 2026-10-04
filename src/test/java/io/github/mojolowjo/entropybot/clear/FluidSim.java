package io.github.mojolowjo.entropybot.clear;

import java.util.HashMap;
import java.util.Map;

/**
 * Water plan: a small model of Minecraft's water over a {@link FakeWorld} ("water" cells, their state in
 * {@code flows}), run to a steady state inside a region. The rules, simplified the safe way (water reaches more cells
 * than in the game, never fewer):
 * <ul>
 * <li>a source stays a source until a block replaces it;</li>
 * <li>a cell with water above it is falling water;</li>
 * <li><b>the new-source rule</b>: a cell with at least 2 source blocks beside it and a solid block or a source below
 * it becomes a source;</li>
 * <li>else it takes the strongest water beside it minus one: a source or a falling column feeds 7, flowing water of
 * amount a feeds a - 1; flowing water only spreads sideways when it can't fall (solid or water below it); sources always
 * do. The game's preference for flowing toward holes is left out (it spreads everywhere).</li>
 * </ul>
 * Everything that isn't air or water counts as solid.
 */
final class FluidSim {
    private FluidSim() {}

    static boolean solid(FakeWorld w, int x, int y, int z) {
        return !w.air(x, y, z) && !w.fluid(x, y, z);
    }

    static boolean source(FakeWorld w, int x, int y, int z) {
        ClearWorld.FluidCell f = w.fluidCell(x, y, z);
        return f != null && f.source();
    }

    /** Runs the water inside the region (cells outside it stay as they are) until nothing changes; the steps it took. */
    static int settle(FakeWorld w, ClearBox region) {
        int[][] side = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        for (int step = 0; step < 500; step++) {
            Map<Pos, ClearWorld.FluidCell> next = new HashMap<>();
            Map<Pos, Boolean> dry = new HashMap<>();
            for (int x = region.x1(); x <= region.x2(); x++) {
                for (int y = region.y1(); y <= region.y2(); y++) {
                    for (int z = region.z1(); z <= region.z2(); z++) {
                        if (solid(w, x, y, z)) continue;
                        Pos p = new Pos(x, y, z);
                        ClearWorld.FluidCell now = w.fluidCell(x, y, z);
                        if (now != null && now.source()) continue;
                        ClearWorld.FluidCell want = null;
                        if (w.fluid(x, y + 1, z)) {
                            want = new ClearWorld.FluidCell(false, 8, true);
                        } else {
                            int sources = 0, best = 0;
                            for (int[] s : side) {
                                int nx = x + s[0], nz = z + s[1];
                                ClearWorld.FluidCell n = w.fluidCell(nx, y, nz);
                                if (n == null) continue;
                                if (n.source()) sources++;
                                boolean spreads = n.source() || solid(w, nx, y - 1, nz) || w.fluid(nx, y - 1, nz);
                                if (!spreads) continue;
                                int feed = n.source() || n.falling() ? 7 : n.amount() - 1;
                                best = Math.max(best, feed);
                            }
                            if (sources >= 2 && (solid(w, x, y - 1, z) || source(w, x, y - 1, z))) want = ClearWorld.FluidCell.SOURCE;
                            else if (best >= 1) want = new ClearWorld.FluidCell(false, best, false);
                        }
                        if (want == null) {
                            if (now != null) dry.put(p, true);
                        } else if (!want.equals(now)) {
                            next.put(p, want);
                        }
                    }
                }
            }
            if (next.isEmpty() && dry.isEmpty()) return step;
            for (Pos p : dry.keySet()) w.set(p, "air");
            for (Map.Entry<Pos, ClearWorld.FluidCell> e : next.entrySet()) {
                Pos p = e.getKey();
                ClearWorld.FluidCell f = e.getValue();
                if (f.source()) w.set(p, "water");
                else w.flow(p.x(), p.y(), p.z(), f.amount(), f.falling());
            }
        }
        throw new IllegalStateException("the water never settled");
    }
}
