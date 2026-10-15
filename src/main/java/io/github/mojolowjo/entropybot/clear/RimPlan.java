package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 0.30.1 (package 4, trial 8 finding 1): after Baritone's placing builder gave up (or went idle) on a {@code build
 * floor|roof|walls|fill|shell}, the mod places the cells it left, one at a time, the way a player builds a floor: stand
 * on a placed or solid neighbour with the feet at the cell's level + 1, look at the top face of the block under the cell
 * and click it. Baritone's GoalAdjacent needs a stand cell next to the target at its level or below, which a floor cut
 * into a hillside does not have at the rim.
 *
 * <p>This class decides: which cells the shape has, which are still missing, which one to do next (supported cells,
 * lowest layer first, nearest first, each tried at most {@link #MAX_TRIES} times), where to stand, the caps and the end
 * texts. The clicks are the game side's ({@code commands.Clearing}, through {@code Clearing.placeAt}). Game-free.
 *
 * <p>Loader notes: none (ClearWorld only).
 */
public final class RimPlan {
    private RimPlan() {}

    /** Tries per cell (a walk that ends out of reach or a click that did not stay both count). */
    public static final int MAX_TRIES = 2;
    /** The rim placer's cap per layer, in ticks (90 s). */
    public static final int LAYER_CAP_TICKS = 90 * 20;
    /** Reach used to choose the stand spot (PlaceRules.STAND_REACH: Baritone ends a walk a little off the centre). */
    public static final double REACH = PlaceRules.STAND_REACH;

    /** Why a cell is left. */
    public enum Why {
        NO_SUPPORT("no block under it"), NO_NEIGHBOUR("no solid neighbour"), NO_STAND("nowhere to stand"), NO_BLOCKS("out of blocks"),
        TRIES("it did not stay after " + MAX_TRIES + " tries"), TIME("out of time");

        public final String text;

        Why(String text) { this.text = text; }
    }

    /** The cells of a build shape over the box (floor: bottom layer, roof: top layer, fill: all, walls, shell). */
    public static List<Pos> shapeCells(String shape, ClearBox b) {
        List<Pos> out = new ArrayList<>();
        for (int y = b.y1(); y <= b.y2(); y++) {
            for (int x = b.x1(); x <= b.x2(); x++) {
                for (int z = b.z1(); z <= b.z2(); z++) {
                    boolean edge = x == b.x1() || x == b.x2() || z == b.z1() || z == b.z2();
                    boolean cap = y == b.y1() || y == b.y2();
                    boolean in = switch (shape) {
                        case "floor" -> y == b.y1();
                        case "roof" -> y == b.y2();
                        case "walls" -> edge;
                        case "shell" -> edge || cap;
                        default -> true;
                    };
                    if (in) out.add(new Pos(x, y, z));
                }
            }
        }
        return out;
    }

    /**
     * 0.30.2: the shape's cells less a door's two cells (x z at floor + 1 and + 2); door null: all of them. The missing
     * count and the rim placer skip them (Baritone's own "sel walls" may still fill them: the house's door dig cuts them).
     */
    public static List<Pos> withoutDoor(List<Pos> cells, int[] doorXz, int floorY) {
        if (doorXz == null) return cells;
        List<Pos> out = new ArrayList<>(cells);
        out.remove(new Pos(doorXz[0], floorY + 1, doorXz[1]));
        out.remove(new Pos(doorXz[0], floorY + 2, doorXz[1]));
        return out;
    }

    /** The cells still to place: the ones that take a block now (air, grass, snow layers...). */
    public static List<Pos> missing(ClearWorld w, List<Pos> cells) {
        List<Pos> out = new ArrayList<>();
        for (Pos c : cells) if (PlaceRules.free(w, c.x(), c.y(), c.z()) && !sealed(w, c)) out.add(c);
        return out;
    }

    /**
     * 0.30.4 (house on open terrain, live: a wall corner against the hill): an air cell whose six neighbours are all
     * solid. No click can reach it (every face that borders it is hidden behind it) and it leaves no gap, so it is not
     * missing: the shape stands around it.
     */
    public static boolean sealed(ClearWorld w, Pos c) {
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : six) {
            int x = c.x() + d[0], y = c.y() + d[1], z = c.z() + d[2];
            if (!w.loaded(x, y, z) || w.noCollision(x, y, z) || w.fluid(x, y, z)) return false;
        }
        return true;
    }

    /** The cell has a block under it to click on its top face. */
    public static boolean supported(ClearWorld w, Pos c) {
        return PlaceRules.clickable(w, c.x(), c.y() - 1, c.z());
    }

    /** The bot can stand with its feet at x y z: something solid under them, room for the body, no fluid. */
    public static boolean standable(ClearWorld w, int x, int y, int z) {
        if (w.noCollision(x, y - 1, z) || w.fluid(x, y - 1, z)) return false;
        return w.noCollision(x, y, z) && w.noCollision(x, y + 1, z) && !w.fluid(x, y, z) && !w.fluid(x, y + 1, z);
    }

    /** The top face of the block under the cell is in reach from feet at x y z (eye 1.62 above). */
    public static boolean reaches(Pos c, double fx, double fy, double fz, double reach) {
        double dx = c.x() + 0.5 - fx, dy = c.y() - (fy + 1.62), dz = c.z() + 0.5 - fz;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * Where to stand for cell c: feet on a neighbour column at the cell's level + 1 (standing on a placed or solid block
     * at the cell's level), the nearest to the bot's feet bx by bz first; null when none.
     */
    public static Pos standFor(ClearWorld w, Pos c, double bx, double by, double bz) {
        Pos best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] d : AROUND) {
            int x = c.x() + d[0], y = c.y() + 1, z = c.z() + d[1];
            if (!standable(w, x, y, z)) continue;
            double dd = sq(x + 0.5 - bx) + sq(y - by) + sq(z + 0.5 - bz);
            if (dd < bestD) {
                bestD = dd;
                best = new Pos(x, y, z);
            }
        }
        return best;
    }

    private static double sq(double v) { return v * v; }

    /**
     * 0.30.2 (package 5, trial 8: walls): the neighbours of cell c a block can be placed against: a clickable block
     * below or on a side (never above), in PlaceRules.SIDES order (below first).
     */
    public static List<PlaceRules.Side> anchors(ClearWorld w, Pos c) {
        List<PlaceRules.Side> out = new ArrayList<>();
        for (PlaceRules.Side s : PlaceRules.SIDES) {
            if (s.dy() > 0) continue;
            if (PlaceRules.clickable(w, c.x() + s.dx(), c.y() + s.dy(), c.z() + s.dz())) out.add(s);
        }
        return out;
    }

    /** The cell has a solid neighbour below or on a side to place against. */
    public static boolean anchored(ClearWorld w, Pos c) { return !anchors(w, c).isEmpty(); }

    /** The eye is in front of the anchor face that borders cell c (the face points from the anchor into the cell). */
    static boolean inFront(Pos c, PlaceRules.Side s, double ex, double ey, double ez) {
        double hx = c.x() + 0.5 + s.dx() * 0.5, hy = c.y() + 0.5 + s.dy() * 0.5, hz = c.z() + 0.5 + s.dz() * 0.5;
        return (ex - hx) * -s.dx() + (ey - hy) * -s.dy() + (ez - hz) * -s.dz() > 0.05;
    }

    /** The centre of the anchor face that borders cell c is within reach of the eye. */
    public static boolean faceInReach(Pos c, PlaceRules.Side s, double ex, double ey, double ez, double reach) {
        double hx = c.x() + 0.5 + s.dx() * 0.5, hy = c.y() + 0.5 + s.dy() * 0.5, hz = c.z() + 0.5 + s.dz() * 0.5;
        return sq(hx - ex) + sq(hy - ey) + sq(hz - ez) <= reach * reach;
    }

    /**
     * An eye at ex ey ez can click the face of anchor s that borders cell c: the eye is in front of that face, its
     * centre is in reach and the ray to it reaches the anchor block first (nothing in between).
     */
    public static boolean sees(ClearWorld w, Pos c, PlaceRules.Side s, double ex, double ey, double ez, double reach) {
        if (!inFront(c, s, ex, ey, ez) || !faceInReach(c, s, ex, ey, ez, reach)) return false;
        double hx = c.x() + 0.5 + s.dx() * 0.55, hy = c.y() + 0.5 + s.dy() * 0.55, hz = c.z() + 0.5 + s.dz() * 0.55;
        // a snow layer or grass (no collision; the cell's own one is replaced by the placement) does not block the click:
        // the ray goes on from where it leaves that block
        double sx = ex, sy = ey, sz = ez;
        for (int i = 0; i < 6; i++) {
            ClearWorld.Hit h = w.clip(sx, sy, sz, hx, hy, hz);
            if (h == null) return false;
            if (h.x() == c.x() + s.dx() && h.y() == c.y() + s.dy() && h.z() == c.z() + s.dz()) return true;
            if (!replaceable(w, h.x(), h.y(), h.z())) return false;
            double t = exitT(sx, sy, sz, hx, hy, hz, h.x(), h.y(), h.z());
            if (t < 0 || t >= 1) return false;
            double nx = sx + (hx - sx) * t, ny = sy + (hy - sy) * t, nz = sz + (hz - sz) * t;
            sx = nx; sy = ny; sz = nz;
        }
        return false;
    }

    /**
     * review b: a block a ray (and a placement) may pass: no collision, not air, no fluid, and neither a built block
     * nor a block entity (a snow layer, grass, a fern; never a torch, a button, a rail or a crop).
     */
    public static boolean replaceable(ClearWorld w, int x, int y, int z) {
        return !w.air(x, y, z) && w.noCollision(x, y, z) && !w.fluid(x, y, z) && !w.builtBlock(x, y, z) && !w.blockEntity(x, y, z);
    }

    /** The segment from a to b leaves the unit cube at x y z at parameter t (a little past it); -1 when it never does. */
    static double exitT(double ax, double ay, double az, double bx, double by, double bz, int x, int y, int z) {
        double t = Double.MAX_VALUE;
        double[] a = {ax, ay, az}, d = {bx - ax, by - ay, bz - az};
        int[] lo = {x, y, z};
        for (int k = 0; k < 3; k++) {
            if (d[k] > 1e-9) t = Math.min(t, (lo[k] + 1 - a[k]) / d[k]);
            else if (d[k] < -1e-9) t = Math.min(t, (lo[k] - a[k]) / d[k]);
        }
        return t == Double.MAX_VALUE || t < 0 ? -1 : t + 1e-4;
    }

    /** The side stance: feet beside the cell and the anchor face to click from there. */
    public record Stance(Pos feet, PlaceRules.Side side) {}

    /**
     * The second stance (walls, the upper rows of fill and shell, a roof cell over a wall): stand on the floor beside
     * the cell, feet at the cell's level or one below, on the 8 neighbours or one further out (inside or outside the
     * wall line), and click an anchor's face that borders the cell. Stances from which the face is seen come first, then
     * the ones that only reach it; nearest to the bot's feet bx by bz first. null when none.
     */
    public static Stance sideStand(ClearWorld w, Pos c, double bx, double by, double bz) {
        List<PlaceRules.Side> an = anchors(w, c);
        if (an.isEmpty()) return null;
        Stance best = null, blind = null;
        double bestD = Double.MAX_VALUE, blindD = Double.MAX_VALUE;
        for (int fy = c.y(); fy >= c.y() - 1; fy--) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int x = c.x() + dx, z = c.z() + dz;
                    if (PlaceRules.bodyIn(x, fy, z, c) || !standable(w, x, fy, z)) continue;
                    double ex = x + 0.5, ey = fy + 1.62, ez = z + 0.5;
                    double dd = sq(ex - bx) + sq(fy - by) + sq(ez - bz);
                    for (PlaceRules.Side s : an) {
                        if (sees(w, c, s, ex, ey, ez, REACH)) {
                            if (dd < bestD) { bestD = dd; best = new Stance(new Pos(x, fy, z), s); }
                            break;
                        }
                        if (dd < blindD && inFront(c, s, ex, ey, ez) && faceInReach(c, s, ex, ey, ez, REACH)) {
                            blindD = dd;
                            blind = new Stance(new Pos(x, fy, z), s);
                        }
                    }
                }
            }
        }
        return best != null ? best : blind;
    }

    /** An anchor face of cell c the eye at ex ey ez sees within place reach, or null. */
    public static PlaceRules.Side seenFrom(ClearWorld w, Pos c, double ex, double ey, double ez) {
        for (PlaceRules.Side s : anchors(w, c)) if (sees(w, c, s, ex, ey, ez, PlaceRules.PLACE_REACH)) return s;
        return null;
    }

    /**
     * The next cell's plan: where to stand (null: place from where the bot stands), or why the cell is left. side: the
     * anchor face to click (the side stance; null: placeAt picks, the floor first).
     */
    public record Pick(Pos cell, Pos stand, Why why, boolean filler, PlaceRules.Side side) {
        public Pick(Pos cell, Pos stand, Why why, boolean filler) { this(cell, stand, why, filler, null); }
        public Pick(Pos cell, Pos stand, Why why) { this(cell, stand, why, false, null); }
    }

    /** Filler blocks for a hole under a cell, in order (then the build block itself). */
    public static final List<String> FILLERS = List.of("minecraft:cobblestone", "minecraft:dirt");

    /** The filler to use: cobblestone, dirt, else the build block; null when the bag has none. */
    public static String filler(Map<String, Integer> bag, String block) {
        for (String f : FILLERS) if (bag.getOrDefault(f, 0) > 0) return f;
        return bag.getOrDefault(block, 0) > 0 ? block : null;
    }

    /**
     * The next cell to place among {@code missing}: lowest layer first, then supported ones nearest the bot; a cell
     * tried {@link #MAX_TRIES} times is skipped. null when nothing is left to try. A cell in reach from the bot's feet
     * needs no walk (stand null) on its first try only (a second try always walks to a stand spot); one with no stand spot is a Pick with NO_STAND (the caller marks it tried out).
     */
    public static Pick next(ClearWorld w, List<Pos> missing, Map<Pos, Integer> tries, double bx, double by, double bz) {
        List<Pos> open = new ArrayList<>();
        for (Pos c : missing) if (tries.getOrDefault(c, 0) < MAX_TRIES && (supported(w, c) || anchored(w, c))) open.add(c);
        boolean filler = false;
        if (open.isEmpty()) {
            // package 4 (8): a cell over a one-block hole: fill the hole first (its own cell, with a block under it)
            for (Pos c : missing) {
                Pos below = new Pos(c.x(), c.y() - 1, c.z());
                if (tries.getOrDefault(c, 0) < MAX_TRIES && !supported(w, c) && PlaceRules.free(w, below.x(), below.y(), below.z())
                        && tries.getOrDefault(below, 0) < MAX_TRIES && supported(w, below)) open.add(below);
            }
            filler = true;
        }
        if (open.isEmpty()) return null;
        int low = open.stream().mapToInt(Pos::y).min().getAsInt();
        open.removeIf(c -> c.y() != low);
        open.sort(Comparator.comparingDouble(c -> sq(c.x() + 0.5 - bx) + sq(c.y() - by) + sq(c.z() + 0.5 - bz)));
        Pos c = open.get(0);
        // the feet are on a block of the cell's level (or higher) and the cell is not under the body: place from here
        int fx = (int) Math.floor(bx), fy = (int) Math.floor(by), fz = (int) Math.floor(bz);
        boolean sup = supported(w, c);
        boolean first = tries.getOrDefault(c, 0) == 0 && !PlaceRules.bodyIn(fx, fy, fz, c);
        if (first && sup && fy > c.y() && reaches(c, bx, by, bz, PlaceRules.PLACE_REACH)) return new Pick(c, null, null, filler);
        // stance 1 (on top): a cell with a block under it and a standable neighbour at its level + 1
        Pos s = sup ? standFor(w, c, bx, by, bz) : null;
        if (s != null) return new Pick(c, s, null, filler);
        // stance 2 (beside, 0.30.2): an anchor face seen from here on the first try, else walk to a feet cell beside it
        if (first) {
            PlaceRules.Side here = seenFrom(w, c, bx, by + 1.62, bz);
            if (here != null) return new Pick(c, null, null, filler, here);
        }
        Stance st = sideStand(w, c, bx, by, bz);
        return st == null ? new Pick(c, null, Why.NO_STAND, filler) : new Pick(c, st.feet(), null, filler, st.side());
    }

    /** Why a missing cell was left at the end (no support, else the reason it was given up with). */
    public static Why whyLeft(ClearWorld w, Pos c, Map<Pos, Why> gaveUp) {
        if (!supported(w, c) && !anchored(w, c)) return Why.NO_NEIGHBOUR;
        Why g = gaveUp.get(c);
        return g != null ? g : Why.TIME;
    }

    /** "done: floor of spruce_planks (Baritone placed N, I placed M)". */
    public static String doneText(String shape, String block, int baritone, int mine) {
        return "done: " + shape + " of " + block + " (Baritone placed " + baritone + ", I placed " + mine + ")";
    }

    /** Cells of the shape that hold something other than block {@code id} (dirt, stone...: nothing to place there). */
    public static int otherBlocks(ClearWorld w, List<Pos> cells, String id) {
        int n = 0;
        for (Pos c : cells) if (!PlaceRules.free(w, c.x(), c.y(), c.z()) && !id.equals(w.id(c.x(), c.y(), c.z()))) n++;
        return n;
    }

    /** Nothing to place: "done: the floor of X already stands", or "done: nothing to place in the floor area (N cells hold other blocks)". */
    public static String nothingText(String shape, String block, int other) {
        return other == 0 ? standsText(shape, block)
                : "done: nothing to place in the " + shape + " area (" + other + " cell" + (other == 1 ? " holds" : "s hold") + " other blocks)";
    }
    /** "done: the floor of spruce_planks already stands" (a rerun of a finished step: nothing is started). */
    public static String standsText(String shape, String block) {
        return "done: the " + shape + " of " + block + " already stands";
    }

    /**
     * "stopped: floor of spruce_planks: K cells left, e.g. x y z (why) - next: ...". left: the missing cells, first
     * the example; why: its reason.
     */
    public static String stoppedText(String shape, String block, int baritone, int mine, List<Pos> left, Why why, String area) {
        Pos e = left.get(0);
        String next = switch (why) {
            case NO_BLOCKS -> "get " + block + " 64, then build " + shape + " " + block + " " + area + " again";
            case NO_SUPPORT -> "place a block under " + e.key() + " (or fill the hole below it), then build " + shape + " " + block + " " + area + " again";
            case NO_NEIGHBOUR -> "place a block under or beside " + e.key() + ", then build " + shape + " " + block + " " + area + " again";
            case NO_STAND -> "dig a step next to " + e.key() + " or place it by hand, then build " + shape + " " + block + " " + area + " again";
            default -> "build " + shape + " " + block + " " + area + " again, or place " + block + " " + e.key();
        };
        return "stopped: " + shape + " of " + block + " (Baritone placed " + baritone + ", I placed " + mine + "): "
                + left.size() + " cell" + (left.size() == 1 ? "" : "s") + " left, e.g. " + e.key() + " (" + why.text + ") - next: " + next;
    }
}
