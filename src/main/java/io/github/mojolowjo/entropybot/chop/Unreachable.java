package io.github.mojolowjo.entropybot.chop;

import java.util.HashMap;
import java.util.Map;

/**
 * 0.36.3 (AA 40-minute runs: one dark oak nobody could stand next to stayed "the only tree" for 40 minutes): trees whose
 * chop ended "couldn't get at its trunk" are remembered per world for {@link #TTL_MS}, by trunk column (a cell within
 * 1 block counts as the same tree). The chopper's pick, its far-tree pick and the planner's trees fact leave them out.
 * Pure (the world key and the clock come from the caller); one bot per game, so a static is enough.
 */
public final class Unreachable {
    private Unreachable() {}

    public static final long TTL_MS = 30 * 60_000;
    private static final Map<String, Long> UNTIL = new HashMap<>();
    private static String world = "";

    /** The world the next calls are about (a change forgets the other world's trees). */
    public static synchronized void world(String w) {
        String k = w == null ? "" : w;
        if (!k.equals(world)) {
            UNTIL.clear();
            world = k;
        }
    }

    public static synchronized void mark(int x, int z, long now) { UNTIL.put(x + " " + z, now + TTL_MS); }

    /** The column (or one next to it) is a remembered unreachable trunk. */
    public static synchronized boolean blocked(int x, int z, long now) {
        if (UNTIL.isEmpty()) return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                Long u = UNTIL.get((x + dx) + " " + (z + dz));
                if (u != null && u > now) return true;
            }
        return false;
    }

    public static synchronized int size(long now) {
        UNTIL.values().removeIf(u -> u <= now);
        return UNTIL.size();
    }

    public static synchronized void clear() { UNTIL.clear(); }
}
