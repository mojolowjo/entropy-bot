package io.github.mojolowjo.entropybot.clear;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The offline simulator's world (minecraft-bot/test/sim.js), block for block: stone up to y 52, air above,
 * plus whatever the builders set. Block names are the sim's ("stone", "oritech:nickel_ore"). The rules mirror the
 * sim's mocks: what has no collision (NONSOLID), what has a block entity, destroy speeds (bedrock unbreakable),
 * ores by "_ore", and its voxel ray cast (every block but air, water and torches is a full-cube outline).
 */
final class FakeWorld implements ClearWorld {
    static final Set<String> NONSOLID = Set.of("air", "water", "lava", "short_grass", "torch", "mysticalagriculture:inferium_crop");
    static final Set<String> CONTAINERS = Set.of("chest", "barrel", "furnace", "sophisticatedstorage:chest", "machine", "crafting_table",
            "corpse", "display_chest", "botanypots:hopper_botany_pot", "refinedstorage:grid");
    static final Map<String, Double> HARD = new HashMap<>();
    static {
        HARD.put("stone", 1.5); HARD.put("oak_planks", 2.0); HARD.put("dirt", 0.5); HARD.put("grass_block", 0.6); HARD.put("short_grass", 0.0);
        HARD.put("gravel", 0.6); HARD.put("iron_ore", 3.0); HARD.put("coal_ore", 3.0); HARD.put("diamond_ore", 3.0); HARD.put("chest", 2.5);
        HARD.put("crafting_table", 2.5); HARD.put("torch", 0.0); HARD.put("bedrock", -1.0); HARD.put("water", 100.0); HARD.put("lava", 100.0);
        HARD.put("air", 0.0);
    }

    final Map<Long, String> blocks = new HashMap<>();
    final Set<String> avoid = new HashSet<>();
    final Set<Long> lit = new HashSet<>();

    static long k(int x, int y, int z) {
        return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
    }

    String get(int x, int y, int z) {
        String n = blocks.get(k(x, y, z));
        if (n != null) return n;
        return y <= 52 ? "stone" : "air";
    }

    String get(Pos p) { return get(p.x(), p.y(), p.z()); }

    void set(int x, int y, int z, String name) { blocks.put(k(x, y, z), name); }

    void set(Pos p, String name) { set(p.x(), p.y(), p.z(), name); }

    void fill(int x1, int x2, int y1, int y2, int z1, int z2, String name) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) set(x, y, z, name);
    }

    static double hardness(String name) {
        Double h = HARD.get(name);
        return h == null ? 1 : h;
    }

    /** The sim's Baritone standable(): feet and head free (not water), something solid below. */
    boolean standable(int x, int y, int z) {
        return NONSOLID.contains(get(x, y, z)) && !get(x, y, z).equals("water") && NONSOLID.contains(get(x, y + 1, z))
                && !NONSOLID.contains(get(x, y - 1, z));
    }

    @Override public String id(int x, int y, int z) { return ClearRules.fullId(get(x, y, z)); }

    @Override public String name(int x, int y, int z) { return get(x, y, z); }

    @Override public boolean air(int x, int y, int z) { return get(x, y, z).equals("air"); }

    @Override public boolean fluid(int x, int y, int z) {
        String n = get(x, y, z);
        return n.equals("water") || n.equals("lava");
    }

    @Override public boolean blockEntity(int x, int y, int z) {
        String n = get(x, y, z);
        return CONTAINERS.contains(n) || n.endsWith("_bed") || n.endsWith("_sign");
    }

    @Override public boolean unbreakable(int x, int y, int z) { return hardness(get(x, y, z)) < 0; }

    /** The bridge's protected list over the sim's blocks: ores never; block entities; PROTECT_RE on the id. */
    @Override public boolean builtBlock(int x, int y, int z) {
        if (ore(x, y, z)) return false;
        return blockEntity(x, y, z) || ClearRules.builtId(id(x, y, z));
    }

    @Override public boolean avoided(int x, int y, int z) { return avoid.contains(get(x, y, z)); }

    @Override public boolean ore(int x, int y, int z) { return get(x, y, z).endsWith("_ore"); }

    @Override public boolean noCollision(int x, int y, int z) { return NONSOLID.contains(get(x, y, z)); }

    @Override public boolean fullBlock(int x, int y, int z) {
        String n = get(x, y, z);
        return !NONSOLID.contains(n) && !n.equals("torch");
    }

    @Override public double collisionHeight(int x, int y, int z) {
        String n = get(x, y, z);
        return NONSOLID.contains(n) ? 0 : n.equals("moss_carpet") ? 0.0625 : 1;
    }

    @Override public int blockLight(int x, int y, int z) { return lit.contains(k(x, y, z)) ? 14 : 0; }

    /** The sim's clip(): a voxel walk from the eye; the first cell (after the start) that isn't air, water or a torch. */
    @Override public Hit clip(double fx, double fy, double fz, double tx, double ty, double tz) {
        int x = (int) Math.floor(fx), y = (int) Math.floor(fy), z = (int) Math.floor(fz);
        int ex = (int) Math.floor(tx), ey = (int) Math.floor(ty), ez = (int) Math.floor(tz);
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        int sx = (int) Math.signum(dx), sy = (int) Math.signum(dy), sz = (int) Math.signum(dz);
        double tdx = sx != 0 ? Math.abs(1 / dx) : Double.POSITIVE_INFINITY;
        double tdy = sy != 0 ? Math.abs(1 / dy) : Double.POSITIVE_INFINITY;
        double tdz = sz != 0 ? Math.abs(1 / dz) : Double.POSITIVE_INFINITY;
        double tmx = sx != 0 ? (sx > 0 ? x + 1 - fx : fx - x) * tdx : Double.POSITIVE_INFINITY;
        double tmy = sy != 0 ? (sy > 0 ? y + 1 - fy : fy - y) * tdy : Double.POSITIVE_INFINITY;
        double tmz = sz != 0 ? (sz > 0 ? z + 1 - fz : fz - z) * tdz : Double.POSITIVE_INFINITY;
        Face face = null;
        for (int n = 0; n < 64; n++) {
            String nm = get(x, y, z);
            if (n > 0 && !nm.equals("air") && !nm.equals("water") && !nm.equals("torch")) return new Hit(x, y, z, face);
            if (x == ex && y == ey && z == ez) break;
            if (tmx < tmy && tmx < tmz) {
                if (tmx > 1) break;
                x += sx; tmx += tdx; face = sx > 0 ? Face.WEST : Face.EAST;
            } else if (tmy < tmz) {
                if (tmy > 1) break;
                y += sy; tmy += tdy; face = sy > 0 ? Face.DOWN : Face.UP;
            } else {
                if (tmz > 1) break;
                z += sz; tmz += tdz; face = sz > 0 ? Face.NORTH : Face.SOUTH;
            }
        }
        return null;
    }
}
