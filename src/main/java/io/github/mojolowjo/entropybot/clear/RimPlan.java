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

    /**
     * review b: a block a ray (and a placement) may pass: no collision, not air, no fluid, and neither a built block
     * nor a block entity (a snow layer, grass, a fern; never a torch, a button, a rail or a crop).
     */
    public static boolean replaceable(ClearWorld w, int x, int y, int z) {
        return !w.air(x, y, z) && w.noCollision(x, y, z) && !w.fluid(x, y, z) && !w.builtBlock(x, y, z) && !w.blockEntity(x, y, z);
    }

    /**
     * The next cell's plan: the cell (Baritone's builder picks where to stand and the face), or why it is left.
     */
    public record Pick(Pos cell, Why why, boolean filler) {}

    /** Filler blocks for a hole under a cell, in order (then the build block itself). */
    public static final List<String> FILLERS = List.of("minecraft:cobblestone", "minecraft:dirt");

    /** The filler to use: cobblestone, dirt, else the build block; null when the bag has none. */
    public static String filler(Map<String, Integer> bag, String block) {
        for (String f : FILLERS) if (bag.getOrDefault(f, 0) > 0) return f;
        return bag.getOrDefault(block, 0) > 0 ? block : null;
    }

    /**
     * The next cell to place among {@code missing}: lowest layer first, then supported ones nearest the bot; a cell
     * tried {@link #MAX_TRIES} times is skipped. null when nothing is left to try (builder placements: no stand spot here).
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
        return new Pick(open.get(0), null, filler);
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
