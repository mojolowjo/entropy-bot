package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.30.4 (trial 9 finding 2: a house on open terrain): the ground under a house. Two pure decisions:
 * <ul>
 *   <li>{@link #floorY}: an area without heights gets its floor at the lowest surface block of its columns (the top
 *   solid block that is not a snow layer), 5 high ({@link #HEIGHT}).</li>
 *   <li>{@link #pillars}: after the house box was dug, the free cells under each floor cell down to the first solid
 *   block, at most {@link #MAX_DEPTH} deep; a deeper column is "too deep" and the step stops.</li>
 * </ul>
 * JUnit: {@code HouseGroundTest}. Loader notes: none (no game classes; the caller reads the world).
 */
public final class HouseGround {
    private HouseGround() {}

    /** The deepest pillar under a floor cell. */
    public static final int MAX_DEPTH = 4;
    /** A house chosen from the terrain is this many blocks high (floor to roof). */
    public static final int HEIGHT = 5;
    /** A column with no surface found (not loaded). */
    public static final int NONE = Integer.MIN_VALUE;

    /** The world as the pillar planner sees it: a cell takes a block (air, a snow layer, grass, water). */
    public interface Free {
        boolean free(int x, int y, int z);
    }

    /** The floor y from the columns' top surface blocks (tops[i] = y of column i, NONE = unknown): the lowest; NONE when none. */
    public static int floorY(int[] tops) {
        int low = NONE;
        for (int t : tops) if (t != NONE && (low == NONE || t < low)) low = t;
        return low;
    }

    /** The pillars: cells to fill (lowest first per column) and the columns that are too deep ("x z (N deep)"). */
    public record Pillars(List<int[]> cells, List<String> tooDeep) {
        public String stopText(String area) {
            return "stopped: under the floor of " + area + ": " + tooDeep.size() + " column" + (tooDeep.size() == 1 ? " is" : "s are")
                    + " too deep (more than " + MAX_DEPTH + " blocks), e.g. " + tooDeep.get(0)
                    + " - next: fill it by hand or move the area (area x z x2 z2 " + area + " house y1 y2 with a higher y1), then house " + area;
        }
    }

    /** The free cells under the floor layer floorY of x1..x2, z1..z2, each column down to its first solid block. */
    public static Pillars pillars(int x1, int z1, int x2, int z2, int floorY, Free w) {
        List<int[]> cells = new ArrayList<>();
        List<String> deep = new ArrayList<>();
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                int d = 0;
                while (d <= MAX_DEPTH && w.free(x, floorY - 1 - d, z)) d++;
                if (d > MAX_DEPTH) {
                    deep.add(x + " " + z + " (" + d + "+ deep)");
                    continue;
                }
                for (int i = d; i >= 1; i--) cells.add(new int[]{x, floorY - i, z});
            }
        }
        return new Pillars(cells, deep);
    }
}
