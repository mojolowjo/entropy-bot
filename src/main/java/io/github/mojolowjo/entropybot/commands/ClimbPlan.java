package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.28.1 (trial fix C, docs/TRIAL_FIX_PLAN.md): {@code climb}, the way out of a hole. Trial 1 (both operators) ended
 * with the bot at the bottom of its own pit: goto failed "couldn't get there", and nothing said how to get out.
 *
 * <p>Built in the smaller form the plan allows: a 1-wide staircase dug up with the pickaxe ({@link StairPlan#upSteps},
 * the same steps as {@code dig stairs up}), one dig and one walk per step, run as a chain so every step keeps the dig's
 * guard, leases and water rules. The direction is the one whose cells are all diggable (no fluid, no block entity, no
 * built block, nothing the guard refuses), fewest blocks to break first. The jump-place pillar (placing blocks under
 * the bot mid-jump) is not built: it needs a timed jump and an under-foot place the mod has nowhere yet.
 *
 * <p>Pure (JUnit: {@code TrialFixTest}); the world comes in through {@link Cells}. Loader notes: none.
 */
public final class ClimbPlan {
    private ClimbPlan() {}

    /** A hole this deep or more under the surface counts as "in a hole" (check, the walk's hint). */
    public static final int HOLE = 3;
    /** Deeper than this a failed walk gets no climb hint (a mine, not a pit). */
    public static final int HINT_MAX = 24;

    public enum Kind { OPEN, DIG, BLOCKED }

    public interface Cells { Kind at(int x, int y, int z); }

    /** steps: the chain (empty when not in a hole); dir: e.g. "east"; up: steps; err: why not (with a next hint). */
    public record Plan(List<String> steps, String dir, int up, int dig, String err, int[] end) {
        Plan(List<String> steps, String dir, int up, int dig, String err) { this(steps, dir, up, dig, err, null); }
    }

    /** How far under the surface the feet are (0 when at or above it). */
    public static int depth(int feetY, int surfaceY) {
        return Math.max(0, surfaceY - feetY);
    }

    /** In a hole: {@link #HOLE}+ under the surface, no open sky over the head. */
    public static boolean inHole(int feetY, int surfaceY, boolean sky) {
        return !sky && depth(feetY, surfaceY) >= HOLE;
    }

    /**
     * 0.30.2 (trial 8: "I'm in a hole" inside the roofed house, and climb would dig out through the roof): indoors when
     * the first solid block over the head (up to depth + 1 above the feet) is a built block (planks, cobblestone... the
     * protected set), not natural stone, dirt or snow.
     */
    public static boolean indoors(io.github.mojolowjo.entropybot.clear.ClearWorld w, int x, int y, int z, int depth) {
        for (int yy = y + 2; yy <= y + 1 + Math.max(1, depth); yy++) {
            if (w.noCollision(x, yy, z)) continue;
            String id = w.id(x, yy, z);
            return w.builtBlock(x, yy, z) || (id != null && id.endsWith("cobblestone"));   // a cobblestone house's roof too
        }
        return false;
    }

    /** The cells step i (1..n) of a staircase up from me in (dx, dz) breaks: the 2x2 between column i-1 and i at y+i..y+i+1. */
    static List<int[]> stepCells(int x, int y, int z, int dx, int dz, int i) {
        List<int[]> out = new ArrayList<>();
        int px = x + dx * (i - 1), pz = z + dz * (i - 1), cx = x + dx * i, cz = z + dz * i;
        for (int yy = y + i; yy <= y + i + 1; yy++) {
            out.add(new int[]{px, yy, pz});
            out.add(new int[]{cx, yy, cz});
        }
        return out;
    }

    /**
     * The plan from feet (x, y, z) up to surfaceY (the feet height on the surface). hasPickaxe: one in the bag. The
     * staircase may start from a standable cell next to me (a 3x3 pit: from its edge, into the wall). With nothing
     * above: an empty plan and no error ("not in a hole").
     */
    public static Plan plan(Cells c, int x, int y, int z, int surfaceY, boolean hasPickaxe) {
        int up = StairPlan.upCount(y, surfaceY);
        if (up <= 0) return new Plan(List.of(), null, 0, 0, null);
        String[] names = {"north", "south", "east", "west"};
        int bestK = -1, bestDig = Integer.MAX_VALUE;
        int[] bestStart = null;
        String firstWhy = null;
        for (int sx = -1; sx <= 1; sx++) for (int sz = -1; sz <= 1; sz++) {
            int ox = x + sx, oz = z + sz;
            boolean here = sx == 0 && sz == 0;
            if (!here && !(c.at(ox, y, oz) == Kind.OPEN && c.at(ox, y + 1, oz) == Kind.OPEN && c.at(ox, y - 1, oz) != Kind.OPEN)) continue;
            int move = here ? 0 : 1;
            for (int k = 0; k < names.length; k++) {
                int[] d = StairPlan.dir(names[k]);
                int dig = 0;
                String why = null;
                for (int i = 1; i <= up && why == null; i++) {
                    // the floor under each new step must hold (an open one is a gap that would need a block)
                    if (c.at(ox + d[0] * i, y + i - 1, oz + d[1] * i) == Kind.OPEN) why = "a gap under step " + i;
                    for (int[] cell : stepCells(ox, y, oz, d[0], d[1], i)) {
                        Kind kk = c.at(cell[0], cell[1], cell[2]);
                        if (kk == Kind.BLOCKED) { why = "a block I may not break at " + cell[0] + " " + cell[1] + " " + cell[2]; break; }
                        if (kk == Kind.DIG) dig++;
                    }
                }
                if (why != null) {
                    if (firstWhy == null && here) firstWhy = names[k] + ": " + why;
                    continue;
                }
                if (dig + move < bestDig) { bestK = k; bestDig = dig + move; bestStart = new int[]{ox, y, oz, move}; }
            }
        }
        if (bestK < 0) return new Plan(List.of(), null, up, 0, "error: no way up from " + x + " " + y + " " + z + " ("
                + (firstWhy == null ? "every side is blocked" : firstWhy) + ") - next: dig stairs up <dir> <n> another way, or ask the owner");
        int digN = bestDig - bestStart[3];
        if (digN > 0 && !hasPickaxe) return new Plan(List.of(), names[bestK], up, digN,
                "error: climbing " + up + " up needs " + digN + " blocks dug and I have no pickaxe - next: craft wooden_pickaxe 1, then climb");
        int[] d = StairPlan.dir(names[bestK]);
        int ox = bestStart[0], oz = bestStart[2];
        List<String> steps = new ArrayList<>();
        if (bestStart[3] > 0) steps.add("goto " + ox + " " + y + " " + oz);
        List<String> all = StairPlan.upSteps(ox, y, oz, d[0], d[1], up);
        for (int i = 1; i <= up; i++) {
            boolean anyDig = false;
            for (int[] cell : stepCells(ox, y, oz, d[0], d[1], i)) if (c.at(cell[0], cell[1], cell[2]) == Kind.DIG) anyDig = true;
            if (anyDig) steps.add(all.get(2 * (i - 1)));
            steps.add(all.get(2 * (i - 1) + 1));
        }
        return new Plan(steps, names[bestK], up, digN, null, new int[]{ox + d[0] * up, y + up, oz + d[1] * up});
    }
    /** 0.28.2 (D): a cell that is one of my own blocks (the restore/build ledger, my shelter) or inside my main area. */
    public interface Own { boolean at(int x, int y, int z); }

    /** The surface way out: the side, the cells to dig (feet and head), or why not. */
    public record Out(String dir, List<int[]> cells, String err) {}

    /**
     * 0.28.2 (trial fix package 2, D): walled in at surface level (trial 2, Sonnet: its own walls around it, "climb"
     * said it was no hole). Null when not walled in (a side with feet and head open). Else the side whose blocking
     * cells are all my own and fewest, with open air (feet and head) beyond, a floor under it; {@code prefer} (a side
     * name, e.g. the door side) wins a tie. Out.err when every side holds a block that is not mine.
     */
    public static Out walledIn(Cells c, Own own, int x, int y, int z, String prefer) {
        String[] names = {"north", "south", "east", "west"};
        for (String n : names) {
            int[] d = StairPlan.dir(n);
            if (c.at(x + d[0], y, z + d[1]) == Kind.OPEN && c.at(x + d[0], y + 1, z + d[1]) == Kind.OPEN) return null;
        }
        Out best = null;
        int bestN = Integer.MAX_VALUE;
        String firstWhy = null;
        for (String n : names) {
            int[] d = StairPlan.dir(n);
            int ax = x + d[0], az = z + d[1];
            List<int[]> cells = new ArrayList<>();
            String why = null;
            for (int yy = y; yy <= y + 1 && why == null; yy++) {
                Kind k = c.at(ax, yy, az);
                if (k == Kind.OPEN) continue;
                if (k == Kind.BLOCKED || !own.at(ax, yy, az)) why = "the block at " + ax + " " + yy + " " + az + " is a built block that is not mine (or water, a container, bedrock)";
                else cells.add(new int[]{ax, yy, az});
            }
            int bx = ax + d[0], bz = az + d[1];
            if (why == null && (c.at(bx, y, bz) == Kind.BLOCKED || c.at(bx, y + 1, bz) == Kind.BLOCKED)) why = "no way on beyond it";
            if (why == null && c.at(ax, y - 1, az) == Kind.OPEN) why = "no floor there";
            if (why != null) {
                if (firstWhy == null) firstWhy = n + ": " + why;
                continue;
            }
            int score = cells.size() * 2 + (n.equals(prefer) ? 0 : 1);
            if (score < bestN) {
                bestN = score;
                best = new Out(n, cells, null);
            }
        }
        if (best != null) return best;
        return new Out(null, List.of(), "error: I'm walled in at " + x + " " + y + " " + z + " and no side is my own wall or natural ground (" + firstWhy
                + ") - next: dig x y z x y+1 z force next to me (if the wall is yours), or ask the owner");
    }

    /** The dig for {@link #walledIn}'s cells: "dig x y z x y2 z force" (force: my own planks are built blocks). */
    public static String outDig(Out o) {
        int[] a = o.cells().get(0), b = o.cells().get(o.cells().size() - 1);
        return "dig " + a[0] + " " + a[1] + " " + a[2] + " " + b[0] + " " + b[1] + " " + b[2] + " force" + StairPlan.VIA_ENGINE;
    }

    /** The start reply: "started: climb 6 up (a staircase east, 14 blocks to dig) to 3 70 -2". */
    public static String startText(Plan p) {
        int[] e = p.end();
        return "climb " + p.up() + " up (a staircase " + p.dir() + ", " + p.dig() + " blocks to dig) to " + e[0] + " " + e[1] + " " + e[2];
    }

    /** The hint a failed walk or dig gets when the bot ends in a hole. */
    public static String holeHint(int depth) {
        return "I'm in a hole (the rim is " + depth + " above me) - next: climb";
    }
}
