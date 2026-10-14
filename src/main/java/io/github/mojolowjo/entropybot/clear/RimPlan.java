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
        NO_SUPPORT("no block under it"), NO_STAND("nowhere to stand"), NO_BLOCKS("out of blocks"),
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

    /** The cells still to place: the ones that take a block now (air, grass, snow layers...). */
    public static List<Pos> missing(ClearWorld w, List<Pos> cells) {
        List<Pos> out = new ArrayList<>();
        for (Pos c : cells) if (PlaceRules.free(w, c.x(), c.y(), c.z())) out.add(c);
        return out;
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

    /** The next cell's plan: where to stand (null: place from where the bot stands), or why the cell is left. */
    public record Pick(Pos cell, Pos stand, Why why) {}

    /**
     * The next cell to place among {@code missing}: lowest layer first, then supported ones nearest the bot; a cell
     * tried {@link #MAX_TRIES} times is skipped. null when nothing is left to try. A cell in reach from the bot's feet
     * needs no walk (stand null); one with no stand spot is a Pick with NO_STAND (the caller marks it tried out).
     */
    public static Pick next(ClearWorld w, List<Pos> missing, Map<Pos, Integer> tries, double bx, double by, double bz) {
        List<Pos> open = new ArrayList<>();
        for (Pos c : missing) if (tries.getOrDefault(c, 0) < MAX_TRIES && supported(w, c)) open.add(c);
        if (open.isEmpty()) return null;
        int low = open.stream().mapToInt(Pos::y).min().getAsInt();
        open.removeIf(c -> c.y() != low);
        open.sort(Comparator.comparingDouble(c -> sq(c.x() + 0.5 - bx) + sq(c.y() - by) + sq(c.z() + 0.5 - bz)));
        Pos c = open.get(0);
        // the feet are on a block of the cell's level (or higher) and the cell is not under the body: place from here
        int fx = (int) Math.floor(bx), fy = (int) Math.floor(by), fz = (int) Math.floor(bz);
        if (fy > c.y() && !PlaceRules.bodyIn(fx, fy, fz, c) && reaches(c, bx, by, bz, PlaceRules.PLACE_REACH)) return new Pick(c, null, null);
        Pos s = standFor(w, c, bx, by, bz);
        return s == null ? new Pick(c, null, Why.NO_STAND) : new Pick(c, s, null);
    }

    /** Why a missing cell was left at the end (no support, else the reason it was given up with). */
    public static Why whyLeft(ClearWorld w, Pos c, Map<Pos, Why> gaveUp) {
        if (!supported(w, c)) return Why.NO_SUPPORT;
        Why g = gaveUp.get(c);
        return g != null ? g : Why.TIME;
    }

    /** "done: floor of spruce_planks (Baritone placed N, I placed M)". */
    public static String doneText(String shape, String block, int baritone, int mine) {
        return "done: " + shape + " of " + block + " (Baritone placed " + baritone + ", I placed " + mine + ")";
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
            case NO_STAND -> "dig a step next to " + e.key() + " or place it by hand, then build " + shape + " " + block + " " + area + " again";
            default -> "build " + shape + " " + block + " " + area + " again, or place " + block + " " + e.key();
        };
        return "stopped: " + shape + " of " + block + " (Baritone placed " + baritone + ", I placed " + mine + "): "
                + left.size() + " cell" + (left.size() == 1 ? "" : "s") + " left, e.g. " + e.key() + " (" + why.text + ") - next: " + next;
    }
}
