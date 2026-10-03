package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.List;

/**
 * The offline simulator's scenario worlds (minecraft-bot/test/sim.js): the hillside zone ("hill", "bumpy",
 * "lowtools", and the "safety" scenario's world), the strip mine ("strip", "strip2", "stripout", "strip pit"), and
 * the mapped area the "veins" scenario and the strip mine's ore collection use.
 */
final class SimWorlds {
    private SimWorlds() {}

    /** The work zone of the hill scenarios (sim: ZONE = { x1: -26, y1: 53, z1: 164, x2: -2, y2: 58, z2: 140 }). */
    static final ClearBox ZONE = ClearBox.of(-26, 53, 164, -2, 58, 140);
    static final String ZONE_TEXT = "zone -26 53 164 to -2 58 140 (25x6x25)";

    /** Where the sim's bot starts (and the safety scenario's "home"). */
    static Bot home() {
        return Bot.at(-14.5, 53, 167.5);
    }

    /** The mapped area without the mod (the bridge's MAP_AREA, x -272..223, z -64..431). */
    static final Veins.Area MAP_AREA = (x, z) -> x >= -272 && x <= 223 && z >= -64 && z <= 431;

    /** sim buildHill(): a hill rising to the north (smaller z), plus a gravel pocket, a chest, water and an iron ore in the zone. */
    static FakeWorld hill(boolean bumpy) {
        FakeWorld w = new FakeWorld();
        for (int x = -40; x <= 12; x++) {
            for (int z = 125; z <= 180; z++) {
                int h = 52 + Math.max(0, Math.min(10, Math.floorDiv(165 - z, 2)));
                if (bumpy) h += (Math.abs(x * 7 + z * 3) % 3) - 1;
                for (int y = 53; y <= h; y++) w.set(x, y, z, y == h ? "grass_block" : (y >= h - 2 ? "dirt" : "stone"));
                if (h >= 53 && ((x * 13 + z * 7) % 11 == 0)) w.set(x, h + 1, z, "short_grass");
            }
        }
        w.set(-10, 57, 150, "gravel"); w.set(-10, 58, 150, "gravel"); w.set(-10, 56, 150, "gravel");
        w.set(-20, 54, 158, "chest");
        w.set(-5, 55, 145, "water");
        w.set(-15, 55, 150, "iron_ore");
        return w;
    }

    /** sim countLeft(): blocks other than air and water left in the zone. */
    static int countLeft(FakeWorld w) {
        int n = 0;
        for (int x = -26; x <= -2; x++) for (int y = 53; y <= 58; y++) for (int z = 140; z <= 164; z++) {
            String nm = w.get(x, y, z);
            if (!nm.equals("air") && !nm.equals("water")) n++;
        }
        return n;
    }

    /**
     * The strip scenarios' world: the mine starts at ox 40 0 going north, underground. Ores: iron in branch 1's
     * left path, coal in a wall between branches 1 and 2, diamond in branch 3 left's wall, iron in the corridor;
     * lava above branch 2 right at x = ox + 8. strip2 adds bedrock in run 2's corridor; pit a coal vein 2 deep right
     * under branch 1 left's floor.
     */
    static FakeWorld strip(int ox, boolean bedrock, boolean pit) {
        FakeWorld w = new FakeWorld();
        if (bedrock) w.set(0, 40, -5, "bedrock");
        w.set(ox, 40, 0, "air"); w.set(ox, 41, 0, "air");
        w.set(ox - 7, 40, -3, "iron_ore");
        w.set(ox + 5, 40, -4, "coal_ore");
        w.set(ox - 2, 41, -8, "diamond_ore");
        w.set(ox + 8, 42, -6, "lava");
        w.set(ox, 41, -5, "iron_ore");
        if (pit) { w.set(ox - 4, 39, -3, "coal_ore"); w.set(ox - 4, 38, -3, "coal_ore"); }
        return w;
    }

    /**
     * The bridge's stripSteps geometry for a mine at s going north (F = (0, -1), left = -x): cell(i, j) is i blocks
     * along the corridor and j to the left.
     */
    static final class Mine {
        final int sx, sy, sz;

        Mine(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }

        Pos cell(int i, int j) { return new Pos(sx - j, sy, sz - i); }

        ClearBox box(Pos a, Pos b, int h) {
            return new ClearBox(Math.min(a.x(), b.x()), a.y(), Math.min(a.z(), b.z()), Math.max(a.x(), b.x()), a.y() + h - 1, Math.max(a.z(), b.z()));
        }

        Pos chestA() { return cell(0, 1); }
        Pos chestB() { return cell(-1, 1); }
        Pos table() { return cell(0, -1); }

        ClearBox corridor(int k) { return box(cell(3 * k - 2, 0), cell(3 * k, 0), 2); }

        ClearBox branch(int k, int side, int length) { return box(cell(3 * k, side), cell(3 * k, side * length), 2); }

        List<Pos> corridorTorches(int k) { return k % 2 == 0 ? List.of(cell(3 * k, 0)) : List.of(); }

        List<Pos> branchTorches(int k, int side, int length) {
            List<Pos> t = new ArrayList<>();
            for (int j = length - 1; j >= 4; j -= 6) t.add(cell(3 * k, side * j));
            return t;
        }
    }
}
