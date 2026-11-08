package io.github.mojolowjo.entropybot.aa.trail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * D4 (the trail home, on D11's route memory): the way the bot walked down under ground, kept as segments between the places
 * it stood - the cell it left the surface from, the top and foot of every one-way stretch (a drop of two or more levels, a
 * dug-down step). A walk segment keeps its two ends and its walked seconds (the fork's RouteStore keeps the cells of a walk
 * Baritone ran to its goal); a one-way segment keeps its cells as well, since no fork route exists for a drop or a dig down.
 *
 * <p>The return price is the segments' walked seconds plus the climb work of the one-way levels, each level priced from
 * Baritone's own costs by the caller ({@link Climb}: a pillar level or a dug stair level, the cheaper). The planner weighs
 * it against the fresh search ({@link #pick}): a PRICED path wins when cheaper, an ESTIMATE never beats a whole trail
 * (rule 18), a search still computing is waited for (rule 15). A segment walked back that fails marks the trail broken
 * with its reason (rule 7); a broken trail is no longer offered. Cleared once the bot is out (back at its start).
 *
 * <p>Pure (JUnit: D4TrailTest). One per world and dimension, held on the brain's board ({@link Trails}, rule 23).
 */
public final class Trail {
    public record Cell(int x, int y, int z) {
        public String text() { return x + " " + y + " " + z; }
        public static Cell of(int[] p) { return new Cell(p[0], p[1], p[2]); }
        public int[] arr() { return new int[]{x, y, z}; }
    }

    /**
     * One segment of the way down: its ends, the walked seconds (the walk's own time: ticks spent breaking blocks on it are
     * left out, kept apart as {@code breakS}), the levels lost one way (0: a walk), its cells (one-way only).
     */
    public record Seg(Cell from, Cell to, double walkedS, int drop, List<Cell> cells, boolean forkRoute, double breakS) {
        public Seg { cells = cells == null ? List.of() : List.copyOf(cells); }
        public Seg(Cell from, Cell to, double walkedS, int drop, List<Cell> cells, boolean forkRoute) { this(from, to, walkedS, drop, cells, forkRoute, 0); }
        public boolean oneWay() { return drop > 0; }
    }

    public enum State { NOT_LOADED, CLOSED, OPEN }

    /**
     * Baritone's price of one level up, in the chain's seconds: a pillar level, a dug stair level; NaN = not known.
     * {@code throwaway}: the bag's throwaway (junk) blocks; a pillar places one a level, so it is priced only for as many
     * levels as there are blocks (Baritone's MovementPillar is COST_INF with no throwaway in the hotbar).
     */
    public record Climb(double pillarS, double stairS, int throwaway) {
        public Climb(double pillarS, double stairS) { this(pillarS, stairS, Integer.MAX_VALUE); }
        public double levelS() { return levelS(0); }
        /** One level's price on a climb of {@code levels}: the pillar only when the bag holds a block for each level. */
        public double levelS(int levels) {
            boolean p = known(pillarS) && throwaway >= levels, s = known(stairS);
            if (p && s) return Math.min(pillarS, stairS);
            return p ? pillarS : s ? stairS : Double.NaN;
        }
        /** job-climb: the way {@link #levelS(int)} priced a climb of {@code levels} (null: no level price). Ties go to the pillar. */
        public ClimbHow how(int levels) {
            boolean p = known(pillarS) && throwaway >= levels, s = known(stairS);
            if (p && (!s || pillarS <= stairS)) return ClimbHow.PILLAR;
            return s ? ClimbHow.STAIRS : null;
        }
        /** Why no level price for {@code levels} (rule 7). */
        public String noneWhy(int levels) {
            if (known(pillarS) && throwaway < levels)
                return "no way up by the trail: no blocks to pillar (" + throwaway + " throwaway in the bag for " + levels + " level" + (levels == 1 ? "" : "s") + ") and no stair price";
            return "the climb work is not known (no Baritone price for a level up)";
        }
        private static boolean known(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }
    }

    /**
     * The return price: total = walked + climb + on (the way on from the trail's start to the target: the fork's stored route
     * or the estimate, {@code onHow}); {@code broken} != null: no price, the reason. {@code breakS}: seconds spent breaking on
     * the way down, said, not priced.
     */
    public record Price(double seconds, double walkedS, double climbS, int segs, int levels, String broken, double onS, String onHow, double breakS) {
        public Price(double seconds, double walkedS, double climbS, int segs, int levels, String broken) {
            this(seconds, walkedS, climbS, segs, levels, broken, 0, null, 0);
        }
        public boolean whole() { return broken == null; }
        /** The price with the way on to the target added once (NaN: not known, the trail has no whole price, rule 18). */
        public Price on(double s, String how) {
            if (!whole() || onHow != null) return this;
            if (Double.isNaN(s) || Double.isInfinite(s))
                return new Price(Double.NaN, walkedS, climbS, segs, levels, "the way on from its start is not known (" + how + ")", Double.NaN, how, breakS);
            return new Price(seconds + s, walkedS, climbS, segs, levels, null, s, how, breakS);
        }
        public String line() {
            if (!whole()) return "home by the trail: none (" + broken + ")";
            return String.format(Locale.ROOT, "home by the trail: %d segment%s, %.0f s, climb work %.0f s (%d level%s)%s%s", segs, segs == 1 ? "" : "s",
                    seconds, climbS, levels, levels == 1 ? "" : "s",
                    onHow == null ? "" : String.format(Locale.ROOT, ", on from its start %.0f s (%s)", onS, onHow),
                    breakS > 0 ? String.format(Locale.ROOT, ", %.0f s of breaking on the way down not priced", breakS) : "");
        }
    }

    /**
     * The fork's stored routes ({@code IPricedPath.routeList} lines: "to GOAL from X Y Z, N cells, T ticks, ...") that start
     * within {@code reach} blocks of {@code from} and whose goal text names {@code to}'s cell: the cheapest one's ticks; NaN: none.
     */
    public static double routeTicks(List<String> lines, Cell from, int[] to, int reach) {
        double best = Double.NaN;
        if (lines == null || from == null || to == null) return best;
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile("^to (.*) from (-?\\d+) (-?\\d+) (-?\\d+), \\d+ cells, (\\d+) ticks");
        for (String l : lines) {
            if (l == null) continue;
            var m = pat.matcher(l);
            if (!m.find()) continue;
            int x = Integer.parseInt(m.group(2)), y = Integer.parseInt(m.group(3)), z = Integer.parseInt(m.group(4));
            if (Math.abs(x - from.x) > reach || Math.abs(y - from.y) > reach || Math.abs(z - from.z) > reach) continue;
            if (!namesCell(m.group(1), to)) continue;
            double t = Double.parseDouble(m.group(5));
            if (Double.isNaN(best) || t < best) best = t;
        }
        return best;
    }

    /** The goal's text holds the three numbers of {@code c} in a row (GoalGetToBlock{x=1,y=2,z=3}, GoalBlock, ...). */
    static boolean namesCell(String goal, int[] c) {
        var m = java.util.regex.Pattern.compile("-?\\d+").matcher(goal);
        List<Integer> n = new ArrayList<>();
        while (m.find()) {
            try { n.add(Integer.parseInt(m.group())); } catch (NumberFormatException e) { n.add(Integer.MIN_VALUE); }
        }
        for (int i = 0; i + 2 < n.size(); i++) if (n.get(i) == c[0] && n.get(i + 1) == c[1] && n.get(i + 2) == c[2]) return true;
        return false;
    }

    /** One goal of the walk back: the cell, the segment it ends ({@code from} -> {@code to} of the trail's way down). */
    public record Goal(Cell at, Cell segFrom, Cell segTo, boolean up) {
        public Goal(Cell at, Cell segFrom, Cell segTo) { this(at, segFrom, segTo, false); }
    }

    /** job-climb: how the price climbs a one-way segment (the cheaper of the two it priced, {@link Climb#how}). */
    public enum ClimbHow { PILLAR, STAIRS }

    /**
     * job-climb (run JBC 09:00: the deposit priced an 18-level shaft as climb work, then asked Baritone for a plain walk to the
     * top and got no path): how one goal of the walk back runs. {@code place} / {@code breaks}: the walk's lease (null = as the
     * walk leaves it, TRAVEL prices it); {@code exact}: GoalBlock on the cell (a pillar ends standing on it) instead of
     * GoalGetToBlock; {@code how}: the words.
     */
    public record Leg(Boolean place, Boolean breaks, boolean exact, String how) {
        public static final Leg WALK = new Leg(null, null, false, "walk");
    }

    /**
     * job-climb: a goal up a one-way segment runs as the price chose: a pillar = placing on (the bag's throwaway), no breaks,
     * GoalBlock on the top; dug stairs = breaks on, GoalBlock on the top; any other goal (or no climb price) a plain walk.
     * Baritone picks every move (rule 2). Pure.
     */
    public static Leg leg(Goal g, ClimbHow how) {
        if (g == null || !g.up() || how == null) return Leg.WALK;
        return how == ClimbHow.PILLAR ? new Leg(true, false, true, "pillar (placing on, no breaks)")
                : new Leg(null, true, true, "dug stairs (breaks on)");
    }

    /** job-climb: a leg that ended short, with Baritone's own end (WalkEndText.reason) and how the leg ran. Pure. */
    public static String breakText(String goal, Leg leg, String at, String why) {
        return "Baritone's " + (leg == null || leg == Leg.WALK ? "walk" : leg.how()) + " to " + goal + " ended at " + at + " short of it: " + why;
    }

    /** The one-way levels of the recorded segments (the climb the price counted). */
    public int levels() {
        int n = 0;
        for (Seg s : segs) n += s.drop();
        return n;
    }

    private State state = State.NOT_LOADED;
    private Cell start;
    private final List<Seg> segs = new ArrayList<>();
    private Cell segFrom, last;
    private long segTick = -1, lastTick = -1;
    private boolean segFork;
    /** Break ticks (the game's break stage seen on a tick) since the game started, and their count at segTick / lastTick. */
    private long brk, segBrk, lastBrk;
    private String broken;
    private boolean dirty;
    /** The probe's counts since the game started: segments recorded, trail chosen, segments walked, breaks. */
    public long recorded, chosen, walked, breaks;

    public State state() { return state; }
    public Cell start() { return start; }
    public List<Seg> segs() { return List.copyOf(segs); }
    public String broken() { return broken; }
    public boolean dirty() { return dirty; }
    public void saved() { dirty = false; }

    /** Not loaded -> loaded with nothing (no file yet): CLOSED. */
    public void loadedEmpty() {
        if (state == State.NOT_LOADED) state = State.CLOSED;
    }

    /** A walk segment's fork route: the fork saved a route while this segment was walked (a walk Baritone ran to its goal). */
    public void forkSaved() { segFork = true; }

    /** One game tick spent breaking a block (the break stage the step monitor sees): kept apart from the walked seconds. */
    public void breakTick() { brk++; }

    /**
     * One on-ground feet cell at game tick {@code tick}; {@code out}: the bot is on the surface (the one below-surface reader).
     * Returns a line to log (opened, closed, cut, a one-way segment), null: nothing to say.
     */
    public String offer(Cell f, long tick, boolean out, int jumpBlocks) { return offer(f, tick, out ? 0 : 1, jumpBlocks, 0, 1, 0); }

    /** Job-price hysteresis: when the bot went under ground (-1: out) and came out (-1: under) by game tick; the open said. */
    private long underSince = -1, outSince = -1;
    private boolean openSaid;

    /**
     * Job-price (run JAO: 113 opens and clears in 13 minutes, often within one second, the bot at a cave lip flickering
     * across the surface reader): {@code depth} below the surface (0: out). The trail records from the first cell under
     * ground, but says "opened" only after {@code openAfterS} seconds under (trail.openAfterS) or {@code openDepth} deep
     * (trail.openDepth); a pop-out before that resets it quietly. Open, it clears only after {@code clearAfterS} seconds
     * out (trail.clearAfterS); a shorter pop-out keeps recording. One line per real open and clear.
     */
    public String offer(Cell f, long tick, int depth, int jumpBlocks, double openAfterS, int openDepth, double clearAfterS) {
        if (state == State.NOT_LOADED || f == null) return null;
        if (state == State.OPEN && !openSaid && underSince < 0 && outSince < 0) openSaid = true;   // loaded open from a file
        if (state != State.OPEN) openSaid = false;                                // cleared from outside (the chest, the owner)
        boolean out = depth <= 0;
        if (out) { underSince = -1; if (outSince < 0) outSince = tick; }
        else { outSince = -1; if (underSince < 0) underSince = tick; }
        if (out) {
            if (state == State.OPEN && openSaid && (tick - outSince) / (double) GameTimes.TPS < clearAfterS) return core(f, tick, false, jumpBlocks);
            boolean quiet = state == State.OPEN && !openSaid;
            String r = core(f, tick, true, jumpBlocks);
            openSaid = false;
            return quiet && r != null && r.startsWith("trail: cleared, out at") ? null : r;
        }
        String r = core(f, tick, false, jumpBlocks);
        if (state != State.OPEN) { openSaid = false; return r; }
        if (!openSaid && ((tick - underSince) / (double) GameTimes.TPS >= openAfterS || depth >= openDepth)) {
            openSaid = true;
            String o = "trail: opened under ground, the way down starts at " + start.text();
            return r == null ? o : o + "; " + r;
        }
        return r;
    }

    private String core(Cell f, long tick, boolean out, int jumpBlocks) {
        if (state == State.NOT_LOADED || f == null) return null;
        if (segTick < 0) { segTick = tick; segBrk = brk; }  // loaded from a file: the tail's clock starts now
        String said = null;
        if (last != null) {
            double d = Math.sqrt(sq(f.x - last.x) + sq(f.y - last.y) + sq(f.z - last.z));
            if (d > jumpBlocks) said = clear(String.format(Locale.ROOT, "moved %.0f blocks in one tick (a teleport or respawn)", d));
        }
        if (out) {
            String r = state == State.OPEN ? clear("out at " + f.text()) : null;
            state = State.CLOSED;
            start = f;
            segFrom = f;
            segTick = tick;
            last = f;
            lastTick = tick;
            segBrk = lastBrk = brk;
            return said != null ? said : r;
        }
        if (state == State.CLOSED) {
            state = State.OPEN;
            if (start == null) { start = last != null ? last : f; segFrom = start; }
            if (segFrom == null) segFrom = start;
            dirty = true;
            // job-price: the "opened" line is offer's, said once the stay under ground is real
        }
        Cell p = last;
        long pt = lastTick, pb = lastBrk;
        last = f;
        lastTick = tick;
        lastBrk = brk;
        if (p == null || p.equals(f)) return said;
        boolean oneWay = f.y <= p.y - 2 || (f.y == p.y - 1 && f.x == p.x && f.z == p.z);
        if (oneWay) {
            if (!p.equals(segFrom)) {
                long b = Math.max(0, pb - segBrk);
                addSeg(new Seg(segFrom, p, Math.max(0, pt - segTick - b) / (double) GameTimes.TPS, 0, List.of(), segFork, b / (double) GameTimes.TPS));
            }
            Seg tail = segs.isEmpty() ? null : segs.get(segs.size() - 1);
            long b = Math.max(0, brk - pb);
            double s = Math.max(0, tick - pt - b) / (double) GameTimes.TPS, bs = b / (double) GameTimes.TPS;
            if (tail != null && tail.oneWay() && tail.to().equals(p)) {
                List<Cell> cells = new ArrayList<>(tail.cells());
                cells.add(f);
                segs.set(segs.size() - 1, new Seg(tail.from(), f, tail.walkedS() + s, tail.drop() + p.y - f.y, cells, false, tail.breakS() + bs));
            } else addSeg(new Seg(p, f, s, p.y - f.y, List.of(p, f), false, bs));
            segFrom = f;
            segTick = tick;
            segBrk = brk;
            segFork = false;
            dirty = true;
            return said;
        }
        // the loop cut: back beside the start of an older segment (within a neighbour cell, the same level) - the way between is dropped
        for (int k = 0; k < segs.size(); k++) {
            Cell s = segs.get(k).from();
            if (s.y == f.y && Math.abs(s.x - f.x) <= 1 && Math.abs(s.z - f.z) <= 1) {
                int n = segs.size() - k;
                segs.subList(k, segs.size()).clear();
                segFrom = s;
                segTick = tick;
                segBrk = brk;
                segFork = false;
                dirty = true;
                return "trail: back by " + s.text() + ", " + n + " segment" + (n == 1 ? "" : "s") + " cut (" + segs.size() + " kept)";
            }
        }
        return said;
    }

    private void addSeg(Seg s) {
        segs.add(s);
        recorded++;
    }

    private static double sq(double v) { return v * v; }

    /** Cleared (back at its start: out, the chest reached, a teleport): the line said. */
    public String clear(String why) {
        int n = segs.size();
        boolean was = state == State.OPEN;
        segs.clear();
        broken = null;
        segFork = false;
        if (state != State.NOT_LOADED) state = State.CLOSED;
        start = null;
        segFrom = null;
        dirty = true;
        return was || n > 0 ? "trail: cleared, " + why + " (" + n + " segment" + (n == 1 ? "" : "s") + " dropped)" : null;
    }

    /** A segment walked back failed: the trail is broken there with the reason (rule 7); the planner re-prices without it. */
    public String breakAt(Goal g, String why) {
        broken = "broken at " + (g == null ? "?" : g.segTo().text() + " -> " + g.segFrom().text()) + ": " + why;
        breaks++;
        dirty = true;
        return "trail: " + broken;
    }

    /**
     * The return price from the feet at {@code tick}: the tail (feet back to the last segment's end) and every segment's walked
     * seconds, plus the one-way levels times {@code climb}'s level price. Null: no trail (not OPEN or nothing recorded).
     */
    public Price price(Climb climb, long tick) {
        if (state != State.OPEN || start == null) return null;
        if (broken != null) return new Price(Double.NaN, Double.NaN, Double.NaN, segs.size(), 0, broken);
        long tb = Math.max(0, brk - segBrk);
        double walkedS = segTick < 0 ? 0 : Math.max(0, tick - segTick - tb) / (double) GameTimes.TPS;
        double breakS = segTick < 0 ? 0 : tb / (double) GameTimes.TPS;
        int levels = 0;
        for (Seg s : segs) { walkedS += s.walkedS(); breakS += s.breakS(); levels += s.drop(); }
        double lv = climb == null ? Double.NaN : climb.levelS(levels);
        if (levels > 0 && Double.isNaN(lv))
            return new Price(Double.NaN, walkedS, Double.NaN, segs.size(), levels, climb == null ? "the climb work is not known (no Baritone price for a level up)" : climb.noneWhy(levels), 0, null, breakS);
        double climbS = levels == 0 ? 0 : levels * lv;
        return new Price(walkedS + climbS, walkedS, climbS, segs.size(), levels, null, 0, null, breakS);
    }

    /**
     * The walk back as Baritone goals, newest first: the last segment's end, then each segment's start; inside a one-way
     * segment every {@code legUp} levels of its own cells (an up search in a shaft must reach its goal inside one segment
     * search, D4 section 1).
     */
    public List<Goal> way(int legUp) {
        List<Goal> out = new ArrayList<>();
        if (state != State.OPEN) return out;
        int up = Math.max(1, legUp);
        Cell tailEnd = segFrom != null ? segFrom : start;
        if (tailEnd != null && !tailEnd.equals(last)) out.add(new Goal(tailEnd, tailEnd, last == null ? tailEnd : last));
        for (int i = segs.size() - 1; i >= 0; i--) {
            Seg s = segs.get(i);
            if (s.oneWay() && s.cells().size() > 2) {
                List<Cell> c = s.cells();
                int base = c.get(c.size() - 1).y;
                for (int k = c.size() - 2; k > 0; k--) if ((c.get(k).y - base) % up == 0) out.add(new Goal(c.get(k), s.from(), s.to(), true));
            }
            out.add(new Goal(s.from(), s.from(), s.to(), s.oneWay()));
        }
        return out;
    }

    public enum Pick { DIRECT, TRAIL }

    /** The scorer's pick (rules 1, 18) and its words. */
    public record Choice(Pick pick, String why) {}

    /**
     * The trail against the fresh search's answer: a PRICED path wins when it is cheaper or as cheap; a search still computing
     * is waited for (rule 15); an ESTIMATE, no path, or chunks not loaded lose to a whole trail (an estimate is not a price,
     * rule 18). A trail that is not whole is never picked.
     */
    public static Choice pick(PathPrice.Kind direct, double directS, Price trail) {
        if (trail == null) return new Choice(Pick.DIRECT, "no trail");
        if (!trail.whole()) return new Choice(Pick.DIRECT, trail.line());
        if (direct == PathPrice.Kind.PRICED && !Double.isNaN(directS))
            return directS <= trail.seconds() ? new Choice(Pick.DIRECT, String.format(Locale.ROOT, "the priced path %.0f s beats %s", directS, trail.line()))
                    : new Choice(Pick.TRAIL, String.format(Locale.ROOT, "%s beats the priced path %.0f s", trail.line(), directS));
        if (direct == PathPrice.Kind.COMPUTING) return new Choice(Pick.DIRECT, "the fresh search is still computing (" + trail.line() + " waits)");
        String d = direct == null ? "no answer" : direct.name().toLowerCase(Locale.ROOT);
        return new Choice(Pick.TRAIL, trail.line() + " against the fresh search's " + d + (Double.isNaN(directS) ? "" : String.format(Locale.ROOT, " (%.0f s, not a price)", directS)));
    }

    // ---- the file (per world: trail.json holds one entry per dimension) ----------------------------------------------------

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (start != null) o.add("start", cell(start));
        if (segFrom != null) o.add("segFrom", cell(segFrom));
        if (last != null) o.add("last", cell(last));
        o.addProperty("open", state == State.OPEN);
        if (broken != null) o.addProperty("broken", broken);
        JsonArray a = new JsonArray();
        for (Seg s : segs) {
            JsonObject j = new JsonObject();
            j.add("from", cell(s.from()));
            j.add("to", cell(s.to()));
            j.addProperty("s", s.walkedS());
            j.addProperty("drop", s.drop());
            j.addProperty("fork", s.forkRoute());
            if (s.breakS() > 0) j.addProperty("breakS", s.breakS());
            if (!s.cells().isEmpty()) {
                JsonArray c = new JsonArray();
                for (Cell x : s.cells()) c.add(cell(x));
                j.add("cells", c);
            }
            a.add(j);
        }
        o.add("segs", a);
        return o;
    }

    /** Read from the file (null: none for this dimension): CLOSED and empty, or as saved; the tail's clock starts at the next feet. */
    public void load(JsonObject o) {
        segs.clear();
        broken = null;
        start = segFrom = last = null;
        segTick = -1;
        lastTick = -1;
        segBrk = lastBrk = brk;
        state = State.CLOSED;
        dirty = false;
        if (o == null) return;
        start = o.has("start") ? cell(o.get("start")) : null;
        segFrom = o.has("segFrom") ? cell(o.get("segFrom")) : start;
        last = o.has("last") ? cell(o.get("last")) : null;
        broken = o.has("broken") ? o.get("broken").getAsString() : null;
        for (JsonElement e : o.getAsJsonArray("segs")) {
            JsonObject j = e.getAsJsonObject();
            List<Cell> c = new ArrayList<>();
            if (j.has("cells")) for (JsonElement x : j.getAsJsonArray("cells")) c.add(cell(x));
            segs.add(new Seg(cell(j.get("from")), cell(j.get("to")), j.get("s").getAsDouble(), j.get("drop").getAsInt(), c, j.get("fork").getAsBoolean(),
                    j.has("breakS") ? j.get("breakS").getAsDouble() : 0));
        }
        if (o.get("open").getAsBoolean() && start != null) state = State.OPEN;
    }

    private static JsonArray cell(Cell c) {
        JsonArray a = new JsonArray();
        a.add(c.x);
        a.add(c.y);
        a.add(c.z);
        return a;
    }

    private static Cell cell(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new Cell(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());   // structural: x y z
    }
}
