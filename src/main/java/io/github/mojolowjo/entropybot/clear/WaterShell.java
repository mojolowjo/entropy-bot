package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.List;

/**
 * Water plan, "the shell method" (docs/WATER_PLAN.md). Game-free geometry. A dig box is worked in slices across its
 * long horizontal axis, away from the bot: slice t is the box's cross-section in the plane at t (3x3 for the tunnel).
 * Before a slice is dug:
 * <ol>
 * <li>its <b>ring</b> (the cells one block around the cross-section in the same plane, corners too: the 5x5 ring of a
 * 3x3 tunnel) gets a block in every water or air cell,</li>
 * <li>the <b>cap</b> (the next plane's cross-section, ahead) gets a block in every water cell (every water or air cell
 * when that plane lies past the box's end),</li>
 * <li>its own cross-section gets a block in every water cell (the <b>fill</b>),</li>
 * </ol>
 * and then the slice is dug the normal way: water can't reach it, since the ring and the cap are solid. The ring stays as
 * the tunnel's wall. Every cell lies in the box or within one block of it.
 */
public final class WaterShell {
    private WaterShell() {}

    public enum Role { RING, CAP, FILL, PLUG }

    /** A cell that needs a block, and why. */
    public record Cell(Pos pos, Role role) {}

    /**
     * The slicing: along x (else z), digging toward +1 or -1. u is the other horizontal axis.
     */
    public record Axis(boolean alongX, int sign) {
        public int t(int x, int z) { return alongX ? x : z; }

        public int u(int x, int z) { return alongX ? z : x; }

        public Pos at(int t, int u, int y) { return alongX ? new Pos(t, y, u) : new Pos(u, y, t); }

        public int tMin(ClearBox b) { return alongX ? b.x1() : b.z1(); }

        public int tMax(ClearBox b) { return alongX ? b.x2() : b.z2(); }

        public int uMin(ClearBox b) { return alongX ? b.z1() : b.x1(); }

        public int uMax(ClearBox b) { return alongX ? b.z2() : b.x2(); }

        /** "x" or "z". */
        public String name() { return alongX ? "x" : "z"; }
    }

    /**
     * The box's long horizontal axis (x on a tie), digging from the bot's side toward {@code toward} (the water); when
     * they share that coordinate, from the box's nearer end.
     */
    public static Axis axisFor(ClearBox box, double botX, double botZ, Pos toward) {
        boolean alongX = box.x2() - box.x1() >= box.z2() - box.z1();
        double b = alongX ? botX : botZ;
        int t = alongX ? toward.x() : toward.z();
        int sign, bt = (int) Math.floor(b);
        if (t > bt) sign = 1;
        else if (t < bt) sign = -1;
        else {
            double mid = alongX ? (box.x1() + box.x2() + 1) / 2.0 : (box.z1() + box.z2() + 1) / 2.0;
            sign = b <= mid ? 1 : -1;
        }
        return new Axis(alongX, sign);
    }

    /** The cross-section of plane t: every (u, y) of the box. */
    public static List<Pos> cross(ClearBox box, Axis a, int t) {
        List<Pos> out = new ArrayList<>();
        for (int y = box.y1(); y <= box.y2(); y++) {
            for (int u = a.uMin(box); u <= a.uMax(box); u++) out.add(a.at(t, u, y));
        }
        return out;
    }

    /** The ring of plane t: one block around the cross-section, corners included (16 cells for a 3x3). */
    public static List<Pos> ring(ClearBox box, Axis a, int t) {
        List<Pos> out = new ArrayList<>();
        int u1 = a.uMin(box) - 1, u2 = a.uMax(box) + 1, y1 = box.y1() - 1, y2 = box.y2() + 1;
        for (int y = y1; y <= y2; y++) {
            for (int u = u1; u <= u2; u++) {
                if (y > y1 && y < y2 && u > u1 && u < u2) continue;
                out.add(a.at(t, u, y));
            }
        }
        return out;
    }

    /** The plane ahead of slice t (t + sign), within the cross-section. */
    public static List<Pos> cap(ClearBox box, Axis a, int t) {
        return cross(box, a, t + a.sign());
    }

    /** Plane t lies in the box. */
    public static boolean inBox(ClearBox box, Axis a, int t) {
        return t >= a.tMin(box) && t <= a.tMax(box);
    }

    /** Water (or the given fluid) that a block placed there would replace: a fluid cell the game counts as replaceable. */
    static boolean water(ClearWorld w, Pos p) {
        return w.fluid(p.x(), p.y(), p.z()) && "water".equals(w.fluidKind(p.x(), p.y(), p.z())) && w.replaceable(p.x(), p.y(), p.z());
    }

    /** What the cells of one slice need, and the cells that stop it ("x y z (why)"). */
    public record SliceNeeds(List<Cell> cells, List<String> problems) {}

    /**
     * Slice t's cells that need a block: the ring's water and air cells, the cap's water cells (water and air past the
     * box's end), the cross-section's water cells. A lava cell, or water in a block that can't be replaced (a waterlogged
     * block), is a problem: the slice can't be sealed.
     */
    public static SliceNeeds sliceNeeds(ClearWorld w, ClearBox box, Axis a, int t) {
        List<Cell> cells = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Pos p : ring(box, a, t)) {
            if (w.air(p.x(), p.y(), p.z()) || water(w, p)) cells.add(new Cell(p, Role.RING));
            else problem(w, p, problems);
        }
        boolean past = !inBox(box, a, t + a.sign());
        for (Pos p : cap(box, a, t)) {
            if (water(w, p) || (past && w.air(p.x(), p.y(), p.z()))) cells.add(new Cell(p, Role.CAP));
            else problem(w, p, problems);
        }
        for (Pos p : cross(box, a, t)) {
            if (water(w, p)) cells.add(new Cell(p, Role.FILL));
            else problem(w, p, problems);
        }
        return new SliceNeeds(cells, problems);
    }

    private static void problem(ClearWorld w, Pos p, List<String> problems) {
        if (!w.fluid(p.x(), p.y(), p.z())) return;
        String kind = w.fluidKind(p.x(), p.y(), p.z());
        if (!"water".equals(kind)) problems.add(p.key() + " (" + kind + ")");
        else problems.add(p.key() + " (" + w.name(p.x(), p.y(), p.z()) + " with water in it)");
    }

    /**
     * The slice to seal next: from the bot's plane (inside the box's range) toward the water, the first plane whose
     * cross-section still holds something (not air), or whose ring or cap holds water. Null when there is none.
     */
    public static Integer startSlice(ClearWorld w, ClearBox box, Axis a, double botX, double botZ) {
        int t = (int) Math.floor(a.alongX() ? botX : botZ);
        t = Math.max(a.tMin(box), Math.min(a.tMax(box), t));
        for (; inBox(box, a, t); t += a.sign()) {
            for (Pos p : cross(box, a, t)) if (!w.air(p.x(), p.y(), p.z())) return t;
            for (Pos p : ring(box, a, t)) if (w.fluid(p.x(), p.y(), p.z())) return t;
            for (Pos p : cap(box, a, t)) if (w.fluid(p.x(), p.y(), p.z())) return t;
        }
        return null;
    }

    /** How many blocks slice t needs (its ring, cap and fill as they are now). */
    public static int count(ClearWorld w, ClearBox box, Axis a, int t) {
        return sliceNeeds(w, box, a, t).cells().size();
    }
}
