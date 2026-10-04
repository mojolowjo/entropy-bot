package io.github.mojolowjo.entropybot.strip;

import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B7d D2: a strip mine's cells (the bridge's DIRS, mineCells, stripSteps' cell/box and stripLegs). The mine starts at
 * S going F; L is the left of F. cell(i, j) = S + F*i + L*j. Branch k: the corridor from cell(3k-2, 0) to cell(3k, 0),
 * the branches from cell(3k, +-1) to cell(3k, +-len), all 2 high. Pure.
 */
public final class MineGeom {
    /** north, south, west, east as {dx, dz}. */
    public static final Map<String, int[]> DIRS = new LinkedHashMap<>();
    static {
        DIRS.put("north", new int[]{0, -1});
        DIRS.put("south", new int[]{0, 1});
        DIRS.put("west", new int[]{-1, 0});
        DIRS.put("east", new int[]{1, 0});
    }

    /** A run that starts further than this from its first box (or LEG_LEVELS levels off) walks there in legs. */
    public static final int LEG_FAR = 48, LEG_LEVELS = 10, LEG_LEN = 40;

    public final int x, y, z;
    public final String dir;
    public final int fx, fz, lx, lz;

    public MineGeom(int x, int y, int z, String dir) {
        int[] f = DIRS.get(dir);
        if (f == null) throw new IllegalArgumentException("no direction " + dir);
        this.x = x;
        this.y = y;
        this.z = z;
        this.dir = dir;
        this.fx = f[0];
        this.fz = f[1];
        this.lx = f[1];
        this.lz = -f[0];
    }

    public Pos start() { return new Pos(x, y, z); }

    public Pos cell(int i, int j) { return new Pos(x + fx * i + lx * j, y, z + fz * i + lz * j); }

    /** A box from cell a to cell b, h blocks tall (from a's y). */
    public static ClearBox box(Pos a, Pos b, int h) {
        return new ClearBox(Math.min(a.x(), b.x()), a.y(), Math.min(a.z(), b.z()), Math.max(a.x(), b.x()), a.y() + h - 1, Math.max(a.z(), b.z()));
    }

    public ClearBox corridor(int k) { return box(cell(3 * k - 2, 0), cell(3 * k, 0), 2); }

    /** side 1 = left, -1 = right. */
    public ClearBox branch(int k, int side, int length) { return box(cell(3 * k, side), cell(3 * k, side * length), 2); }

    /** The x/z bounds of branch k's three boxes (collecting needs all of it inside the areas). */
    public ClearBox union(int k, int length) {
        ClearBox c = corridor(k), l = branch(k, 1, length), r = branch(k, -1, length);
        return new ClearBox(Math.min(c.x1(), Math.min(l.x1(), r.x1())), c.y1(), Math.min(c.z1(), Math.min(l.z1(), r.z1())),
                Math.max(c.x2(), Math.max(l.x2(), r.x2())), c.y2(), Math.max(c.z2(), Math.max(l.z2(), r.z2())));
    }

    /** A torch at the corridor's end on every second branch. */
    public List<Pos> corridorTorches(int k) { return k % 2 == 0 ? List.of(cell(3 * k, 0)) : List.of(); }

    /** Torches every 6 back from just before the branch's end (the very end is dug last). */
    public List<Pos> branchTorches(int k, int side, int length) {
        List<Pos> t = new ArrayList<>();
        for (int j = length - 1; j >= 4; j -= 6) t.add(cell(3 * k, side * j));
        return t;
    }

    /** How far along the corridor (cells from S) a block position lies; negative behind the entrance. */
    public int along(int[] p) { return (p[0] - x) * fx + (p[2] - z) * fz; }

    /** How far to the left of the corridor's line a block position lies (negative: to the right). */
    public int side(int[] p) { return (p[0] - x) * lx + (p[2] - z) * lz; }

    /** The corridor index of a leg's (or any corridor cell's) position. */
    public int indexOf(Pos p) { return along(new int[]{p.x(), p.y(), p.z()}); }

    public static String dirOf(int dx, int dz) {
        for (Map.Entry<String, int[]> e : DIRS.entrySet()) if (e.getValue()[0] == dx && e.getValue()[1] == dz) return e.getKey();
        return null;
    }

    public String leftDir() { return dirOf(lx, lz); }

    public String rightDir() { return dirOf(-lx, -lz); }

    /** One leg of the walk to the mine: walk near pos, end within `within`, two tries, "why" names it. */
    public record Leg(Pos pos, int within, int tries, String why) {}

    /**
     * stripLegs: the walk to branch k when the bot (at me, block coords) is far from it (more than 48 blocks from its
     * corridor box, or 10 levels off): the entrance, then the corridor in legs of 40 at most, to its end. Empty when near.
     */
    public List<Leg> legs(int k, int[] me) {
        List<Leg> out = new ArrayList<>();
        Pos a = cell(3 * k - 2, 0), b = cell(3 * k, 0);
        ClearBox first = new ClearBox(Math.min(a.x(), b.x()), y, Math.min(a.z(), b.z()), Math.max(a.x(), b.x()), y + 1, Math.max(a.z(), b.z()));
        if (first.distTo(me[0], me[1], me[2]) <= LEG_FAR && Math.abs(me[1] - y) <= LEG_LEVELS) return out;
        out.add(new Leg(start(), 8, 2, "the mine entrance"));
        int end = 3 * k - 3;
        for (int i = LEG_LEN; i < end; i += LEG_LEN) out.add(new Leg(cell(i, 0), 8, 2, "the corridor end"));
        if (end > 0) out.add(new Leg(cell(end, 0), 6, 2, "the corridor end"));
        return out;
    }

    /** "x y z dir": the key of a mine's progress note. */
    public String key() { return x + " " + y + " " + z + " " + dir; }

    public static String fmt(Pos p) { return p.x() + " " + p.y() + " " + p.z(); }
}
