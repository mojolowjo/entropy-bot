package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Water plan (docs/WATER_PLAN.md): one round of dealing with the water that stopped a dig ({@code dig ... water}).
 * Game-free. {@link #round} looks at the water ({@link WaterScan}) and answers what to place before the dig goes on:
 * <ul>
 * <li>water flowing in from outside: a block into each cell where it enters the box (then wait for the rest to drain),</li>
 * <li>sources inside the dig: the next slice's ring, cap and fill ({@link WaterShell}),</li>
 * <li>a large body of water: only once the owner confirmed ({@code dig ... water large}),</li>
 * </ul>
 * or why it can't (lava, a cell it may not or can't place into, nowhere dry to stand, too few blocks). Each placement
 * comes in an order where the cell has a solid face to click when its turn comes (the earlier placements count), with
 * a dry stand spot in reach (never in water). The blocks are plain junk ({@link FloorFill#FALLBACK}, which never counts
 * as a built block, so a later dig may still take them) and the 64 stone pickaxe material stays ({@link FloorFill#usable}).
 */
public final class WaterPlan {
    private WaterPlan() {}

    /** How many water rounds one dig may take (each must get it further). */
    public static final int MAX_ROUNDS = 40;
    /** The most ticks it waits for water to drain after plugging it (30 s). */
    public static final int DRAIN_TICKS = 600;
    /** What a "junk drop" dig with "water" keeps of its junk blocks for sealing (on top of the 64 for pickaxes). */
    public static final int RESERVE = 64;

    /** One block to put down: where, from where (feet; null: wherever reaches), which block, and why. */
    public record Placement(Pos cell, Pos from, String block, WaterShell.Role role) {}

    /**
     * A round's answer. blocked: why it can't go on (null: it can); kind: what the water was; placements: in order;
     * drain: wait for the water left inside to drain before digging again; water / sources: the water cells it seals and
     * the sources among them (for the report).
     */
    public record Round(String blocked, WaterScan.Kind kind, List<Placement> placements, boolean drain, int water, int sources, String what) {
        static Round blocked(WaterScan.Kind k, String why, String what) {
            return new Round(why, k, List.of(), false, 0, 0, what);
        }
    }

    /** What the round needs from the game. */
    public record Inputs(ClearWorld w, ClearBox box, Bot bot, Pos at, boolean large, ClearJob.StandCheck standOk,
                         Predicate<Pos> placeAllowed, Map<String, Integer> inv) {}

    /** One round. {@code at}: the fluid cell next to the block that stopped the dig (ClearEngine.liquidBlock). */
    public static Round round(Inputs in) {
        ClearWorld w = in.w();
        WaterScan.Scan scan = WaterScan.scan(w, in.box(), in.at());
        String what = scan.describe();
        switch (scan.kind()) {
            case NONE:
                return new Round(null, scan.kind(), List.of(), false, 0, 0, what);
            case LAVA:
                return Round.blocked(scan.kind(), "it's lava - I only deal with water", what);
            case FLOWING_IN: {
                List<WaterShell.Cell> cells = new ArrayList<>();
                for (Pos p : scan.plug()) cells.add(new WaterShell.Cell(p, WaterShell.Role.PLUG));
                // nothing enters any more: what is left drains by itself
                if (cells.isEmpty()) return new Round(null, scan.kind(), List.of(), true, 0, 0, what);
                String refused = refused(in, cells);
                if (refused != null) return Round.blocked(scan.kind(), refused, what);
                return finish(in, scan, what, order(w, cells, in.bot(), in.standOk()), true);
            }
            case LARGE:
                if (!in.large()) return Round.blocked(scan.kind(), LARGE_ASK, what);
                return shell(in, scan, what);
            case SOURCES_INSIDE:
                return shell(in, scan, what);
            default:
                return Round.blocked(scan.kind(), "unknown water", what);
        }
    }

    /** The most slices one round seals (as many as it can place from where it may stand). */
    public static final int SLICES = 4;

    /** The shell method: the next slices toward the water that need blocks. */
    private static Round shell(Inputs in, WaterScan.Scan scan, String what) {
        ClearWorld w = in.w();
        WaterShell.Axis a = WaterShell.axisFor(in.box(), in.bot().x(), in.bot().z(), in.at());
        Integer s = WaterShell.startSlice(w, in.box(), a, in.bot().x(), in.bot().z());
        if (s == null) return Round.blocked(scan.kind(), "no slice of the box left to seal", what);
        // the slices that need anything (the water may lie a few slices ahead); a slice it can't seal, or may not place
        // in, ends the list - or the round, when it is the first
        List<List<WaterShell.Cell>> slices = new ArrayList<>();
        for (int t = s, k = 0; WaterShell.inBox(in.box(), a, t) && k < 8 && slices.size() < SLICES; t += a.sign(), k++) {
            WaterShell.SliceNeeds n = WaterShell.sliceNeeds(w, in.box(), a, t);
            String why = !n.problems().isEmpty() ? "I can't seal " + n.problems().get(0) : refused(in, n.cells());
            if (why != null) {
                if (slices.isEmpty()) return Round.blocked(scan.kind(), why, what);
                break;
            }
            if (!n.cells().isEmpty()) slices.add(n.cells());
        }
        if (slices.isEmpty()) return Round.blocked(scan.kind(), "the water isn't where I can seal it slice by slice", what);
        // as many of them as it can place from where it may stand now and has blocks for (the far ones wait until the dig
        // gets closer); the first slice alone says why when even that can't be done
        Ordered best = null;
        int have = available(in.inv());
        for (int n = slices.size(); n >= 1 && (best == null || best.stuck() != null); n--) {
            // one block a cell: a slice's cap is the next slice's fill
            Map<Pos, WaterShell.Cell> uniq = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) for (WaterShell.Cell c : slices.get(i)) uniq.putIfAbsent(c.pos(), c);
            List<WaterShell.Cell> all = new ArrayList<>(uniq.values());
            if (n > 1 && all.size() > have) continue;
            best = order(w, all, in.bot(), in.standOk());
        }
        return finish(in, scan, what, best, false);
    }

    /** The guard's rule for each cell: only within one block of the box, and where the guard lets it place; else why not. */
    private static String refused(Inputs in, List<WaterShell.Cell> cells) {
        ClearBox near = in.box().grow(1);
        for (WaterShell.Cell c : cells) {
            Pos p = c.pos();
            if (!near.contains(p.x(), p.y(), p.z())) return "the water enters at " + p.key() + ", more than a block outside the dig";
            if (in.placeAllowed() != null && !in.placeAllowed().test(p)) return "the guard won't let me place at " + p.key();
        }
        return null;
    }

    /** The ordered cells become placements, with a block each (or why not). */
    private static Round finish(Inputs in, WaterScan.Scan scan, String what, Ordered o, boolean drain) {
        ClearWorld w = in.w();
        if (o.stuck() != null) return Round.blocked(scan.kind(), o.stuck(), what);
        List<String> ids = assignBlocks(o.cells().size(), in.inv());
        if (ids == null) {
            return Round.blocked(scan.kind(), "I need " + o.cells().size() + " blocks to seal it and have " + available(in.inv())
                    + " (cobblestone, cobbled deepslate, stone, dirt...; " + FloorFill.STONE_KEEP + " stay for pickaxes)", what);
        }
        List<Placement> out = new ArrayList<>();
        int water = 0, sources = 0;
        for (int i = 0; i < o.cells().size(); i++) {
            WaterShell.Cell c = o.cells().get(i);
            Pos p = c.pos();
            ClearWorld.FluidCell f = w.fluidCell(p.x(), p.y(), p.z());
            if (f != null) {
                water++;
                if (f.source()) sources++;
            }
            out.add(new Placement(p, o.from().get(i), ids.get(i), c.role()));
        }
        return new Round(null, scan.kind(), out, drain, water, sources, what);
    }

    public static final String LARGE_ASK = "it's a large body of water - I only seal that once you confirm";

    // ---- order, support and stand spots ----

    /** The placement order: cells, their stand spots (same index), or why it is stuck. */
    public record Ordered(List<WaterShell.Cell> cells, List<Pos> from, String stuck) {}

    /**
     * Orders the cells so each has a solid face to click when its turn comes (blocks placed before it count), each with a
     * dry stand spot in reach: ring before cap before fill, then nearest first. The stand spots never lie in a cell that
     * gets a block, never in water, and (fence) only where {@code standOk} lets it stand.
     */
    public static Ordered order(ClearWorld w, List<WaterShell.Cell> cells, Bot bot, ClearJob.StandCheck standOk) {
        Set<Pos> planned = new HashSet<>();
        for (WaterShell.Cell c : cells) planned.add(c.pos());
        Overlay ov = new Overlay(w);
        List<WaterShell.Cell> left = new ArrayList<>(cells);
        left.sort(Comparator.<WaterShell.Cell>comparingInt(c -> c.role().ordinal())
                .thenComparingDouble(c -> sq(c.pos().x() + 0.5 - bot.x()) + sq(c.pos().y() + 0.5 - bot.y()) + sq(c.pos().z() + 0.5 - bot.z())));
        ClearBox around = ClearBox.around(planned.isEmpty() ? List.of(new Pos((int) Math.floor(bot.x()), (int) Math.floor(bot.y()), (int) Math.floor(bot.z()))) : planned);
        ClearGrid g = ClearGrid.build(w, around, bot, null, 5, 3);
        int[] dist = g.walkDistances(bot);
        List<WaterShell.Cell> outCells = new ArrayList<>();
        List<Pos> outFrom = new ArrayList<>();
        String lastWhy = null;
        while (!left.isEmpty()) {
            boolean placed = false;
            for (int i = 0; i < left.size(); i++) {
                WaterShell.Cell c = left.get(i);
                if (!FloorFill.supported(ov, c.pos())) {
                    lastWhy = "nothing to place a block against at " + c.pos().key();
                    continue;
                }
                Pos from = standFor(ov, g, dist, c.pos(), bot, planned, standOk);
                if (from == null) {
                    lastWhy = "nowhere dry to stand within reach of " + c.pos().key();
                    continue;
                }
                outCells.add(c);
                outFrom.add(from);
                ov.solid.add(c.pos());
                left.remove(i);
                placed = true;
                break;
            }
            if (!placed) return new Ordered(outCells, outFrom, lastWhy);
        }
        return new Ordered(outCells, outFrom, null);
    }

    /**
     * A dry stand spot (feet cell) from which a face next to {@code cell} is in reach: feet and head open and not wet,
     * something solid below, neither cell planned for a block, not inside the cell; walkable spots by walking cost, the
     * one it stands on first; with the fence on ({@code standOk}) only walkable ones it may stand on.
     */
    static Pos standFor(ClearWorld ov, ClearGrid g, int[] dist, Pos cell, Bot bot, Set<Pos> planned, ClearJob.StandCheck standOk) {
        int bx = (int) Math.floor(bot.x()), by = (int) Math.floor(bot.y() + 0.01), bz = (int) Math.floor(bot.z());
        // where it stands now, when that reaches (the eye exactly where it is)
        if (!FloorFill.bodyHits(bot.x(), bot.y(), bot.z(), cell) && !ov.fluid(bx, by, bz) && !ov.fluid(bx, by + 1, bz)
                && PlaceRules.placeSide(ov, cell.x(), cell.y(), cell.z(), bot.x(), bot.eyeY(), bot.z()) != null) {
            return new Pos(bx, by, bz);
        }
        Pos best = null;
        double bestCost = Double.MAX_VALUE;
        for (int x = cell.x() - 4; x <= cell.x() + 4; x++) {
            for (int z = cell.z() - 4; z <= cell.z() + 4; z++) {
                for (int y = cell.y() - 4; y <= cell.y() + 3; y++) {
                    if (PlaceRules.bodyIn(x, y, z, cell)) continue;
                    int i = g.idx(x, y, z);
                    if (i < 0 || !g.stand(x, y, z) || g.wet(x, y, z) || g.wet(x, y + 1, z)) continue;
                    if (planned.contains(new Pos(x, y, z)) || planned.contains(new Pos(x, y + 1, z))) continue;
                    if (standOk != null && !standOk.ok(x, y, z)) continue;
                    double cost;
                    if (dist[i] >= 0) cost = dist[i];
                    else if (standOk != null) continue;
                    else cost = 1000 + Math.sqrt(sq(x - bot.x()) + sq(y - bot.y()) + sq(z - bot.z()));
                    if (cost >= bestCost) continue;
                    if (PlaceRules.placeSide(ov, cell.x(), cell.y(), cell.z(), x + 0.5, y + Bot.EYE, z + 0.5) == null) continue;
                    best = new Pos(x, y, z);
                    bestCost = cost;
                }
            }
        }
        return best;
    }

    /** The world with the planned blocks already in: they are solid, full and clickable. */
    static final class Overlay implements ClearWorld {
        final ClearWorld w;
        final Set<Pos> solid = new HashSet<>();

        Overlay(ClearWorld w) { this.w = w; }

        private boolean in(int x, int y, int z) { return solid.contains(new Pos(x, y, z)); }

        @Override public String id(int x, int y, int z) { return in(x, y, z) ? "minecraft:cobblestone" : w.id(x, y, z); }
        @Override public String name(int x, int y, int z) { return in(x, y, z) ? "cobblestone" : w.name(x, y, z); }
        @Override public boolean air(int x, int y, int z) { return !in(x, y, z) && w.air(x, y, z); }
        @Override public boolean fluid(int x, int y, int z) { return !in(x, y, z) && w.fluid(x, y, z); }
        @Override public String fluidKind(int x, int y, int z) { return in(x, y, z) ? null : w.fluidKind(x, y, z); }
        @Override public FluidCell fluidCell(int x, int y, int z) { return in(x, y, z) ? null : w.fluidCell(x, y, z); }
        @Override public boolean replaceable(int x, int y, int z) { return !in(x, y, z) && w.replaceable(x, y, z); }
        @Override public boolean blockEntity(int x, int y, int z) { return !in(x, y, z) && w.blockEntity(x, y, z); }
        @Override public boolean unbreakable(int x, int y, int z) { return !in(x, y, z) && w.unbreakable(x, y, z); }
        @Override public boolean builtBlock(int x, int y, int z) { return !in(x, y, z) && w.builtBlock(x, y, z); }
        @Override public boolean avoided(int x, int y, int z) { return !in(x, y, z) && w.avoided(x, y, z); }
        @Override public boolean ore(int x, int y, int z) { return !in(x, y, z) && w.ore(x, y, z); }
        @Override public boolean noCollision(int x, int y, int z) { return !in(x, y, z) && w.noCollision(x, y, z); }
        @Override public boolean fullBlock(int x, int y, int z) { return in(x, y, z) || w.fullBlock(x, y, z); }
        @Override public double collisionHeight(int x, int y, int z) { return in(x, y, z) ? 1 : w.collisionHeight(x, y, z); }
        @Override public int blockLight(int x, int y, int z) { return w.blockLight(x, y, z); }
        @Override public Hit clip(double fx, double fy, double fz, double tx, double ty, double tz) { return w.clip(fx, fy, fz, tx, ty, tz); }
    }

    // ---- blocks ----

    /** The junk blocks it may place: {@link FloorFill#FALLBACK}, each as far as {@link FloorFill#usable} allows. */
    public static int available(Map<String, Integer> inv) {
        int n = 0;
        Map<String, Integer> left = new LinkedHashMap<>(inv);
        for (String id : FloorFill.FALLBACK) {
            int u = FloorFill.usable(id, left);
            n += u;
            left.put(id, left.getOrDefault(id, 0) - u);
        }
        return n;
    }

    /** A block id for each of n placements (FALLBACK order, as far as each lasts), or null when there aren't enough. */
    public static List<String> assignBlocks(int n, Map<String, Integer> inv) {
        Map<String, Integer> left = new LinkedHashMap<>(inv);
        List<String> out = new ArrayList<>();
        for (String id : FloorFill.FALLBACK) {
            while (out.size() < n && FloorFill.usable(id, left) > 0) {
                out.add(id);
                left.put(id, left.get(id) - 1);
            }
        }
        return out.size() < n ? null : out;
    }

    /**
     * "junk drop" with "water": what to throw after keeping {@link #RESERVE} placeable junk blocks (on top of what
     * {@link FloorFill#usable} keeps for pickaxes). Gives back from the throw, in FALLBACK order, until the reserve is
     * there or nothing is left to give back.
     */
    public static Map<String, Integer> keepReserve(Map<String, Integer> thrown, Map<String, Integer> inv, int reserve) {
        Map<String, Integer> out = new LinkedHashMap<>(thrown);
        Map<String, Integer> after = new LinkedHashMap<>(inv);
        for (Map.Entry<String, Integer> e : out.entrySet()) after.put(e.getKey(), after.getOrDefault(e.getKey(), 0) - e.getValue());
        // one at a time: stone pickaxe material given back fills the pickaxes' 64 before it becomes placeable
        for (String id : FloorFill.FALLBACK) {
            while (out.getOrDefault(id, 0) > 0 && available(after) < reserve) {
                out.merge(id, -1, Integer::sum);
                after.merge(id, 1, Integer::sum);
            }
            if (out.getOrDefault(id, 1) == 0) out.remove(id);
        }
        return out;
    }

    // ---- the report ----

    /** What the water rounds of one dig did, for its end message. */
    public static final class Tally {
        public int rounds, water, sources, placed;
        public Integer lo, hi;
        public String axis = "x";

        public void add(Round r, WaterShell.Axis a, ClearBox box) {
            rounds++;
            water += r.water();
            sources += r.sources();
            placed += r.placements().size();
            boolean alongX = a != null ? a.alongX() : box.x2() - box.x1() >= box.z2() - box.z1();
            axis = alongX ? "x" : "z";
            for (Placement p : r.placements()) {
                int t = alongX ? p.cell().x() : p.cell().z();
                lo = lo == null ? t : Math.min(lo, t);
                hi = hi == null ? t : Math.max(hi, t);
            }
        }

        /** "sealed 14 water cells (3 sources) at x 377-381", or "" when it placed nothing. */
        public String text() {
            if (placed == 0) return "";
            String at = lo == null ? "" : " at " + axis + " " + (lo.equals(hi) ? String.valueOf(lo) : lo + "-" + hi);
            return "sealed " + water + " water cell" + (water == 1 ? "" : "s") + " (" + sources + " source" + (sources == 1 ? "" : "s") + ")" + at
                    + (placed > water ? ", " + placed + " blocks placed" : "");
        }
    }

    private static double sq(double v) { return v * v; }
}
