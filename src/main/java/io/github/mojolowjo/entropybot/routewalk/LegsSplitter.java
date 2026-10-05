package io.github.mojolowjo.entropybot.routewalk;

import io.github.mojolowjo.entropybot.route.Cell;

import java.util.ArrayList;
import java.util.List;

/**
 * Mode "legs" (plan section 4.4): the route's door cells become walk steps of at most {@link #MAX_LEG} blocks. From
 * where it stands, each leg goes to the furthest door cell (in route order) still within reach in a straight line;
 * when even the next door is further, that door is the leg (the map has nothing in between). The last leg is always
 * the real goal, and a door within {@link #GOAL_SNAP} blocks of the goal is dropped (walk straight to the goal).
 */
public final class LegsSplitter {
    private LegsSplitter() {
    }

    public static final double MAX_LEG = 40;
    public static final double GOAL_SNAP = 8;

    /** The legs' ends in order; never empty, and the last one is {@code goal}. */
    public static List<Cell> split(Cell start, List<Cell> doors, Cell goal) {
        return split(start, doors, goal, MAX_LEG, GOAL_SNAP);
    }

    public static List<Cell> split(Cell start, List<Cell> doors, Cell goal, double maxLeg, double goalSnap) {
        List<Cell> out = new ArrayList<>();
        Cell cur = start;
        int i = 0;
        List<Cell> d = doors == null ? List.of() : doors;
        while (i < d.size() && cur.dist(goal) > maxLeg) {
            int best = -1;
            for (int j = i; j < d.size() && cur.dist(d.get(j)) <= maxLeg; j++) best = j;
            if (best < 0) best = i;
            cur = d.get(best);
            out.add(cur);
            i = best + 1;
        }
        while (!out.isEmpty() && out.get(out.size() - 1).dist(goal) <= goalSnap) out.remove(out.size() - 1);
        out.add(goal);
        return out;
    }
}
