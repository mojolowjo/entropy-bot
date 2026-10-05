package io.github.mojolowjo.entropybot.route;

import java.util.List;

/**
 * One built box: its doors and the crossing matrix (ticks from entering at door i to leaving through door j).
 *
 * <p><b>How crossings are measured (review R3, box plus margin):</b> one Dijkstra per enterable door over the standable
 * cells of the box <i>plus a one-cell margin</i> on every side (so a path may hug a wall outside the box for a step),
 * starting at the door's entry point (the entry cell nearest its middle, cost 0); the cost to door j is
 * {@code dist(exit point of j) + its exit move} (HPA*-style door points, see {@link BoxBuilder}). Doors stay on the
 * box's faces; the margin is only walked through. The exit move's cost is included, the entry move's is not (it was
 * counted as the neighbour's exit).
 *
 * <p>Costs are held as uint16 quarter-ticks like the file: {@link #IMPOSSIBLE} = no way, capped at 16383 ticks.
 * Immutable once made (shared with Baritone's path thread through {@link CostToGo}).
 *
 * @param walkHash 64-bit hash of which cells of the box are standable; a rebuild with the same hash keeps everything.
 * @param crossing n x n quarter-ticks, row-major {@code [i * n + j]}, 0..65534, {@code 0xFFFF} = impossible.
 */
public record SectionRecord(SectionKey key, Quality quality, long builtAt, long walkHash,
                            List<Door> doors, char[] crossing) {

    /** Where the terrain came from. */
    public enum Quality {
        /** From loaded chunks. */
        LIVE,
        /** From Baritone's chunk cache only (2 bits a block); rebuilt from live data when loaded. */
        COARSE
    }

    public static final char IMPOSSIBLE = 0xFFFF;
    public static final int CAP_TICKS = 16383;

    public SectionRecord {
        doors = List.copyOf(doors);
        if (crossing == null || crossing.length != doors.size() * doors.size())
            throw new IllegalArgumentException("crossing must be n*n for " + doors.size() + " doors");
    }

    public int doorCount() {
        return doors.size();
    }

    /** True for an all-solid or all-air box: no doors, stored as one byte. */
    public boolean empty() {
        return doors.isEmpty();
    }

    /** Ticks from entering through door i to leaving through door j, or +infinity. */
    public double ticks(int i, int j) {
        char q = crossing[i * doors.size() + j];
        return q == IMPOSSIBLE ? Double.POSITIVE_INFINITY : q / 4.0;
    }

    /** Ticks to the stored quarter-tick form (rounded down so a stored cost never overstates; capped). */
    public static char quarterTicks(double ticks) {
        if (!(ticks < Double.POSITIVE_INFINITY)) return IMPOSSIBLE;
        double t = Math.min(Math.max(ticks, 0), CAP_TICKS);
        return (char) Math.floor(t * 4);
    }
}
