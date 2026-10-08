package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 0.25.1 ore detour (the owner's play, 2026-10-08: a one-minute stop on the way home = an ore seen nearby, grabbed):
 * at the start of a walk home, the nearest ore the bot can see (an exposed face: air or a see-through block beside it)
 * within {@code path.oreDetour} blocks, that its pickaxe can mine ({@code canMine}), inside the areas, and with no water,
 * lava, sand or gravel next to any of its blocks; then its vein (joined face to face, the same rules) up to
 * {@link #CAP} blocks. The clear that mines it keeps the guard's leases and the careful-clear rules. Pure (JUnit on
 * FakeWorld); loader notes: none.
 */
public final class OreDetour {
    private OreDetour() {}

    public static final int CAP = 8, MAX_UP = 4;

    /** The vein to mine (nearest block first), empty when none qualifies. r: the radius (0 = off). */
    public static List<Pos> pick(ClearWorld w, int bx, int by, int bz, int r, Predicate<String> canMine, Veins.Area area) {
        List<Pos> out = new ArrayList<>();
        if (w == null || r <= 0) return out;
        int up = Math.min(r, MAX_UP);
        Pos first = null;
        double best = Double.MAX_VALUE;
        for (int x = bx - r; x <= bx + r; x++) {
            for (int z = bz - r; z <= bz + r; z++) {
                if (!area.contains(x, z)) continue;
                for (int y = by - up; y <= by + up + 1; y++) {
                    double d = (x - bx) * (x - bx) + (y - by) * (y - by) + (z - bz) * (z - bz);
                    if (d > (double) r * r || d >= best || !ok(w, x, y, z, canMine) || !exposed(w, x, y, z)) continue;
                    best = d;
                    first = new Pos(x, y, z);
                }
            }
        }
        if (first == null) return out;
        Set<String> seen = new HashSet<>();
        ArrayDeque<Pos> q = new ArrayDeque<>();
        q.add(first);
        seen.add(first.key());
        while (!q.isEmpty() && out.size() < CAP) {
            Pos c = q.poll();
            out.add(c);
            for (int[] s : ClearEngine.SIDES6) {
                int nx = c.x() + s[0], ny = c.y() + s[1], nz = c.z() + s[2];
                String k = Pos.key(nx, ny, nz);
                if (seen.contains(k) || !area.contains(nx, nz)) continue;
                seen.add(k);
                if (ok(w, nx, ny, nz, canMine)) q.add(new Pos(nx, ny, nz));
            }
        }
        return out;
    }

    /** An ore it may and can mine, with nothing wet, hot or falling beside it. */
    static boolean ok(ClearWorld w, int x, int y, int z, Predicate<String> canMine) {
        if (w.air(x, y, z) || !w.ore(x, y, z) || !ClearEngine.clearableState(w, x, y, z, false)) return false;
        if (canMine != null && !canMine.test(w.id(x, y, z))) return false;
        for (int[] s : ClearEngine.SIDES6) {
            int nx = x + s[0], ny = y + s[1], nz = z + s[2];
            if (w.fluid(nx, ny, nz) || ClearRules.falling(w.name(nx, ny, nz))) return false;
        }
        return true;
    }

    static boolean exposed(ClearWorld w, int x, int y, int z) {
        for (int[] s : ClearEngine.SIDES6) if (w.noCollision(x + s[0], y + s[1], z + s[2])) return true;
        return false;
    }

    /** The why line: "ore on the way home: 3 iron_ore at x y z (5 blocks off), then on". */
    public static String line(ClearWorld w, List<Pos> vein, int bx, int by, int bz) {
        Pos f = vein.get(0);
        int off = (int) Math.round(Math.sqrt((f.x() - bx) * (f.x() - bx) + (f.y() - by) * (f.y() - by) + (f.z() - bz) * (f.z() - bz)));
        return "ore on the way home: " + vein.size() + " " + w.name(f.x(), f.y(), f.z()) + " at " + f.x() + " " + f.y() + " " + f.z()
                + " (" + off + " blocks off, path.oreDetour), then on";
    }
}
