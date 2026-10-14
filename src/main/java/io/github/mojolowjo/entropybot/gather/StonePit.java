package io.github.mojolowjo.entropybot.gather;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.28.2 (trial fix package 2, B1): a built-in source for cobblestone (and cobbled deepslate): a walkable staircase
 * quarry from the bot's feet, 3 wide, each column one deeper than the last (as bootstrap's quarry, so the bot walks back
 * up its own steps: no pit), long enough that the stone in it gives {@code n} blocks. Trial 2: "gather cobblestone"
 * answered "no way to get cobblestone" and operators guessed dig boxes from the surface. Pure (JUnit: StonePitTest).
 *
 * <p>Column i (1..) is centred i+1 blocks from the feet in direction dir and dug from feet-1 down to feet-i. Stops at
 * {@link #MAX_COLUMNS}; a column that meets liquid or an unbreakable block ends the plan there.
 */
public final class StonePit {
    private StonePit() {}

    public static final int MAX_COLUMNS = 24;

    public enum Cell { AIR, SOFT, STONE, LIQUID, HARD }

    public interface World {
        Cell at(int x, int y, int z);
    }

    /** cells: x y z to dig (in column order); stone: how many of them are stone; err: why not (with next:). */
    public record Plan(List<int[]> cells, int stone, int columns, String err) {}

    public static Plan plan(World w, int[] feet, int[] dir, int n) {
        int dx = dir[0], dz = dir[1];
        int px = dz != 0 ? 1 : 0, pz = dx != 0 ? 1 : 0;
        List<int[]> cells = new ArrayList<>();
        int stone = 0, cols = 0;
        String stop = null;
        for (int i = 0; i <= MAX_COLUMNS && stone < n; i++) {
            int cx = feet[0] + dx * (i + 1), cz = feet[2] + dz * (i + 1);
            List<int[]> col = new ArrayList<>();
            int colStone = 0;
            for (int a = -1; a <= 1 && stop == null; a++) {
                int x = cx + px * a, z = cz + pz * a;
                // 0.30.1 (package 4, trial 8): on a slope rising ahead, the face in front of me is solid at body height:
                // the room to walk down into column i (feet-i+1 and feet-i+2, at most feet+1) is dug too; column 0 is the
                // face one block ahead (only that room). On flat ground those cells are air and nothing changes.
                int top = Math.max(feet[1] - 1, Math.min(feet[1] + 1, feet[1] - i + 2));
                for (int y = top; y >= feet[1] - i && stop == null; y--) {
                    if (i == 0 && y < feet[1]) break;
                    Cell c = w.at(x, y, z);
                    // column 0 is only walking room: a block I can't break or a fluid there is left, never the plan's end
                    if (i == 0 && (c == Cell.LIQUID || c == Cell.HARD)) continue;
                    if (c == Cell.LIQUID) stop = "water or lava at " + x + " " + y + " " + z;
                    else if (c == Cell.HARD) stop = "a block I can't break at " + x + " " + y + " " + z;
                    else if (c != Cell.AIR) {
                        col.add(new int[]{x, y, z});
                        if (c == Cell.STONE) colStone++;
                    }
                }
            }
            if (stop != null) break;
            cells.addAll(col);
            stone += colStone;
            if (i > 0) cols = i;
        }
        if (stone == 0) {
            String why = stop != null ? stop : "no stone within " + MAX_COLUMNS + " blocks down this way";
            return new Plan(List.of(), 0, cols, "error: no stone to quarry here (" + why + ") - next: goto a hillside or a cave mouth, then gather cobblestone again");
        }
        return new Plan(cells, stone, cols, null);
    }

    /**
     * 0.30.1 (package 4): gather's fallback after the quarry gave up twice: the slope face in front of the bot, 5 wide
     * (2 each side), 3 deep (1..3 ahead in dir), 3 high (feet-1 .. feet+1: the face at body height and one below, so the
     * bot walks back out of the 1-deep cut), as "dig x1 y1 z1 x2 y2 z2" (Baritone's builder by default).
     */
    public static int[] faceBox(int[] feet, int[] dir) {
        int dx = dir[0], dz = dir[1];
        int px = dz != 0 ? 1 : 0, pz = dx != 0 ? 1 : 0;
        int ax = feet[0] + dx - 2 * px, az = feet[2] + dz - 2 * pz;
        int bx = feet[0] + 3 * dx + 2 * px, bz = feet[2] + 3 * dz + 2 * pz;
        return new int[]{Math.min(ax, bx), feet[1] - 1, Math.min(az, bz), Math.max(ax, bx), feet[1] + 1, Math.max(az, bz)};
    }

    /**
     * "dig x1 y1 z1 x2 y2 z2 via engine" for {@link #faceBox}. Through the engine: in the trial 8 retest the same box
     * through Baritone's builder ended "gave up (Baritone found no way to reach the rest)" ("Unable to do it. Pausing.")
     * beside 24 plain dirt/stone cells, cause unknown (open item: the builder's assemble found no breakable goals;
     * possibly the 1-layer snow on top, which the mask treats as air).
     */
    public static String faceDig(int[] feet, int[] dir) {
        int[] b = faceBox(feet, dir);
        return "dig " + b[0] + " " + b[1] + " " + b[2] + " " + b[3] + " " + b[4] + " " + b[5] + " via engine";
    }

    /**
     * The face dig in the first of the four directions (dir first, then the others) whose box has no cell where
     * {@code banned} (a main or safe area) is true; null when every one does.
     */
    public static String faceDigAvoiding(int[] feet, int[] dir, java.util.function.Predicate<int[]> banned) {
        int[][] dirs = {dir, {1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] d : dirs) {
            int[] b = faceBox(feet, d);
            boolean ok = true;
            for (int x = b[0]; x <= b[3] && ok; x++)
                for (int y = b[1]; y <= b[4] && ok; y++)
                    for (int z = b[2]; z <= b[5] && ok; z++) if (banned.test(new int[]{x, y, z})) ok = false;
            if (ok) return faceDig(feet, d);
        }
        return null;
    }

    /** The started note: "a staircase quarry east, 9 columns, about 40 stone". */
    public static String note(Plan p, int[] dir, int n) {
        String d = dir[0] > 0 ? "east" : dir[0] < 0 ? "west" : dir[1] > 0 ? "south" : "north";
        return "a staircase quarry " + d + ", " + p.columns() + " columns, about " + p.stone() + " stone" + (p.stone() < n ? " (less than " + n + ": run it again from the bottom for more)" : "");
    }

    /** The items a quarry is for (its end text says how many of them it got). */
    public static final List<String> COUNTED = List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate");

    /**
     * 0.30.0 (P2): the clear options of "dig stone n": exactly the plan's cells (in the plan's order) as the only list, ores
     * kept, water/lava ends it as "blocked by"; via: Baritone's builder breaks them (BuilderDig), else the engine.
     */
    public static io.github.mojolowjo.entropybot.clear.ClearJob.Options options(Plan p, int n, String label, boolean via) {
        List<io.github.mojolowjo.entropybot.clear.Pos> only = new ArrayList<>();
        for (int[] c : p.cells()) only.add(new io.github.mojolowjo.entropybot.clear.Pos(c[0], c[1], c[2]));
        return new io.github.mojolowjo.entropybot.clear.ClearJob.Options().only(only).collect(false).label(label).liquidBlocks(true)
                .line("dig stone " + n).viaBaritone(via);
    }

    /** "; got N cobblestone" for the end text (N: the counted items gained, never below 0). */
    public static String gotText(int before, int after) {
        int g = Math.max(0, after - before);
        return "; got " + g + " cobblestone";
    }
}
