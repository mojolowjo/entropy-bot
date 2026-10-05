package io.github.mojolowjo.entropybot.route;

import java.util.Map;

/**
 * The result of one backward Dijkstra: for every known box, for every door, the ticks left to the goal after
 * <b>leaving</b> the box through that door ({@code +infinity} when unknown or no way). Immutable: it is read on
 * Baritone's path thread by {@link RouteGoalMath#heuristic} while the game goes on.
 *
 * <p>Lookups are by block position, without boxing (an open-addressing table over {@link SectionKey#packed()}).
 */
public final class CostToGo {

    /** One box of the table. {@code exitTicks[e]} belongs to {@code doors[e]}. */
    public record BoxCosts(SectionKey key, boolean stale, Door[] doors, double[] exitTicks) {
    }

    private static final CostToGo EMPTY = new CostToGo(0, null, null, Map.of());

    private final int dim;
    private final SectionKey goalBox;
    private final Cell goal;
    private final long[] keys;
    private final BoxCosts[] vals;
    private final int mask;
    private final int size;

    public CostToGo(int dim, SectionKey goalBox, Cell goal, Map<SectionKey, BoxCosts> boxes) {
        this.dim = dim;
        this.goalBox = goalBox;
        this.goal = goal;
        int cap = Integer.highestOneBit(Math.max(4, boxes.size() * 2 + 1)) << 1;
        keys = new long[cap];
        vals = new BoxCosts[cap];
        mask = cap - 1;
        int n = 0;
        for (Map.Entry<SectionKey, BoxCosts> e : boxes.entrySet()) {
            if (e.getKey().dim() != dim) continue;
            long k = e.getKey().packed();
            int i = slot(k);
            while (vals[i] != null && keys[i] != k) i = (i + 1) & mask;
            if (vals[i] == null) n++;
            keys[i] = k;
            vals[i] = e.getValue();
        }
        size = n;
    }

    public static CostToGo empty() {
        return EMPTY;
    }

    private int slot(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40) & mask;
    }

    public int dim() { return dim; }

    /** The goal's box, or null for the empty table. */
    public SectionKey goalBox() { return goalBox; }

    public Cell goal() { return goal; }

    public int size() { return size; }

    /** The box holding block (x, y, z), or null when the table doesn't know it. */
    public BoxCosts box(int x, int y, int z) {
        if (size == 0) return null;
        long k = SectionKey.pack(x >> 4, y >> 4, z >> 4);
        int i = slot(k);
        while (vals[i] != null) {
            if (keys[i] == k) return vals[i];
            i = (i + 1) & mask;
        }
        return null;
    }

    public boolean inGoalBox(int x, int y, int z) {
        return goalBox != null && goalBox.contains(x, y, z);
    }
}
