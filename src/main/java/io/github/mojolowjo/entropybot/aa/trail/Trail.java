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

    /** One segment of the way down: its ends, the walked seconds, the levels lost one way (0: a walk), its cells (one-way only). */
    public record Seg(Cell from, Cell to, double walkedS, int drop, List<Cell> cells, boolean forkRoute) {
        public Seg { cells = cells == null ? List.of() : List.copyOf(cells); }
        public boolean oneWay() { return drop > 0; }
    }

    public enum State { NOT_LOADED, CLOSED, OPEN }

    /** Baritone's price of one level up, in the chain's seconds: a pillar level, a dug stair level; NaN = not known. */
    public record Climb(double pillarS, double stairS) {
        public double levelS() {
            boolean p = !Double.isNaN(pillarS) && !Double.isInfinite(pillarS), s = !Double.isNaN(stairS) && !Double.isInfinite(stairS);
            if (p && s) return Math.min(pillarS, stairS);
            return p ? pillarS : s ? stairS : Double.NaN;
        }
    }

    /** The return price: total = walked + climb; {@code broken} != null: no price, the reason. */
    public record Price(double seconds, double walkedS, double climbS, int segs, int levels, String broken) {
        public boolean whole() { return broken == null; }
        public String line() {
            if (!whole()) return "home by the trail: none (" + broken + ")";
            return String.format(Locale.ROOT, "home by the trail: %d segment%s, %.0f s, climb work %.0f s (%d level%s)", segs, segs == 1 ? "" : "s",
                    seconds, climbS, levels, levels == 1 ? "" : "s");
        }
    }

    /** One goal of the walk back: the cell, the segment it ends ({@code from} -> {@code to} of the trail's way down). */
    public record Goal(Cell at, Cell segFrom, Cell segTo) {}

    private State state = State.NOT_LOADED;
    private Cell start;
    private final List<Seg> segs = new ArrayList<>();
    private Cell segFrom, last;
    private long segTick = -1, lastTick = -1;
    private boolean segFork;
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

    /**
     * One on-ground feet cell at game tick {@code tick}; {@code out}: the bot is on the surface (the one below-surface reader).
     * Returns a line to log (opened, closed, cut, a one-way segment), null: nothing to say.
     */
    public String offer(Cell f, long tick, boolean out, int jumpBlocks) {
        if (state == State.NOT_LOADED || f == null) return null;
        if (segTick < 0) segTick = tick;                    // loaded from a file: the tail's clock starts now
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
            return said != null ? said : r;
        }
        if (state == State.CLOSED) {
            state = State.OPEN;
            if (start == null) { start = last != null ? last : f; segFrom = start; }
            if (segFrom == null) segFrom = start;
            dirty = true;
            said = "trail: opened under ground, the way down starts at " + start.text();
        }
        Cell p = last;
        long pt = lastTick;
        last = f;
        lastTick = tick;
        if (p == null || p.equals(f)) return said;
        boolean oneWay = f.y <= p.y - 2 || (f.y == p.y - 1 && f.x == p.x && f.z == p.z);
        if (oneWay) {
            if (!p.equals(segFrom)) addSeg(new Seg(segFrom, p, (pt - segTick) / (double) GameTimes.TPS, 0, List.of(), segFork));
            Seg tail = segs.isEmpty() ? null : segs.get(segs.size() - 1);
            double s = Math.max(0, tick - pt) / (double) GameTimes.TPS;
            if (tail != null && tail.oneWay() && tail.to().equals(p)) {
                List<Cell> cells = new ArrayList<>(tail.cells());
                cells.add(f);
                segs.set(segs.size() - 1, new Seg(tail.from(), f, tail.walkedS() + s, tail.drop() + p.y - f.y, cells, false));
            } else addSeg(new Seg(p, f, s, p.y - f.y, List.of(p, f), false));
            segFrom = f;
            segTick = tick;
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
        double walkedS = segTick < 0 ? 0 : Math.max(0, tick - segTick) / (double) GameTimes.TPS;
        int levels = 0;
        for (Seg s : segs) { walkedS += s.walkedS(); levels += s.drop(); }
        double lv = climb == null ? Double.NaN : climb.levelS();
        if (levels > 0 && Double.isNaN(lv)) return new Price(Double.NaN, walkedS, Double.NaN, segs.size(), levels, "the climb work is not known (no Baritone price for a level up)");
        double climbS = levels == 0 ? 0 : levels * lv;
        return new Price(walkedS + climbS, walkedS, climbS, segs.size(), levels, null);
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
                for (int k = c.size() - 2; k > 0; k--) if ((c.get(k).y - base) % up == 0) out.add(new Goal(c.get(k), s.from(), s.to()));
            }
            out.add(new Goal(s.from(), s.from(), s.to()));
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
            segs.add(new Seg(cell(j.get("from")), cell(j.get("to")), j.get("s").getAsDouble(), j.get("drop").getAsInt(), c, j.get("fork").getAsBoolean()));
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
