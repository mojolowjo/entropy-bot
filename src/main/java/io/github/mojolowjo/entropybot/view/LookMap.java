package io.github.mojolowjo.entropybot.view;

import java.util.List;

/**
 * 0.32.0 (operator view, package 2): the {@code look} verb's text map. Above ground a top-down map (north up, one
 * character a column); under ground the feet layer plus two side slices (north-south and west-east through the bot).
 * Plain Java over a {@link Cells} view of the loaded world (JUnit). Loader API: none (the game side reads vanilla
 * Level blocks and entities).
 */
public final class LookMap {
    private LookMap() {}

    public static final String LEGEND_TOP = ". ground  # stone  ~ water  ! lava  T tree  W built  D door  C chest  ^ v 2+ higher/lower  @ me  z hostile  ? not loaded";
    public static final String LEGEND_CUT = ". air  # solid  o ore  ~ water  ! lava  W built  D door  C chest  @ me  z hostile  ? not loaded";

    /** The world as the map sees it. */
    public interface Cells {
        /** The column's top as a map character ('?' when not loaded). */
        char top(int x, int z);

        /** The column's top block y (any value when not loaded). */
        int topY(int x, int z);

        /** One block as a slice character ('?' when not loaded). */
        char at(int x, int y, int z);
    }

    static boolean mob(List<int[]> mobs, int x, int y, int z, boolean anyY) {
        if (mobs == null) return false;
        for (int[] m : mobs) if (m[0] == x && m[2] == z && (anyY || m[1] == y || m[1] == y - 1)) return true;
        return false;
    }

    /** Top-down, north (-z) up, (2r+1) columns square around cx cz. */
    public static String overhead(Cells c, int cx, int cz, int feetY, int r, int[] me, List<int[]> mobs, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(": x ").append(cx - r).append("..").append(cx + r).append(", z ").append(cz - r).append("..").append(cz + r)
                .append(" (north up, me at y ").append(feetY).append(")\n");
        for (int z = cz - r; z <= cz + r; z++) {
            for (int x = cx - r; x <= cx + r; x++) {
                char ch;
                if (me != null && me[0] == x && me[2] == z) ch = '@';
                else if (mob(mobs, x, 0, z, true)) ch = 'z';
                else {
                    ch = c.top(x, z);
                    if ((ch == '.' || ch == '#') && ch != '?') {
                        int dy = c.topY(x, z) + 1 - feetY;
                        if (dy >= 2) ch = '^';
                        else if (dy <= -2) ch = 'v';
                    }
                }
                sb.append(ch);
            }
            sb.append('\n');
        }
        return sb.append(LEGEND_TOP).toString();
    }

    /** Under ground: the feet layer, then the north-south and west-east side slices (y up), h above and below. */
    public static String slices(Cells c, int bx, int by, int bz, int r, int h, List<int[]> mobs) {
        StringBuilder sb = new StringBuilder();
        sb.append("under ground at ").append(bx).append(' ').append(by).append(' ').append(bz).append(": feet layer y ").append(by)
                .append(", x ").append(bx - r).append("..").append(bx + r).append(" (north up)\n");
        for (int z = bz - r; z <= bz + r; z++) {
            for (int x = bx - r; x <= bx + r; x++) sb.append(cell(c, x, by, z, bx, by, bz, mobs));
            sb.append('\n');
        }
        sb.append("side north-south (x ").append(bx).append(", z ").append(bz - r).append(" left .. ").append(bz + r).append(" right, y ")
                .append(by + h).append(" top)\n");
        for (int y = by + h; y >= by - h; y--) {
            for (int z = bz - r; z <= bz + r; z++) sb.append(cell(c, bx, y, z, bx, by, bz, mobs));
            sb.append('\n');
        }
        sb.append("side west-east (z ").append(bz).append(", x ").append(bx - r).append(" left .. ").append(bx + r).append(" right)\n");
        for (int y = by + h; y >= by - h; y--) {
            for (int x = bx - r; x <= bx + r; x++) sb.append(cell(c, x, y, bz, bx, by, bz, mobs));
            sb.append('\n');
        }
        return sb.append(LEGEND_CUT).toString();
    }

    private static char cell(Cells c, int x, int y, int z, int bx, int by, int bz, List<int[]> mobs) {
        if (x == bx && z == bz && (y == by || y == by + 1)) return '@';
        if (mob(mobs, x, y, z, false)) return 'z';
        return c.at(x, y, z);
    }
}
