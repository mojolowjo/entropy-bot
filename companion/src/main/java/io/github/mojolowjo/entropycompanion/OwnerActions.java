package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonObject;

/**
 * Companion 0.3.1 (assist): what the owner did last. The block they broke, the block they placed, when they last hit
 * an entity, and from those plus the held item a one-word guess of what they are doing: mining, chopping, farming,
 * building, fighting or idle. Plain Java (the mixin on the client's game mode feeds it), so the guess is unit-tested.
 * Loader notes: no loader API here; the feeding mixin is loader-neutral.
 */
public final class OwnerActions {
    /** An event counts for the guess this long. */
    public static final long RECENT_MS = 10_000;

    /** One block event: the id (block for broke, item for placed), where, when (epoch ms). */
    public record Ev(String id, int x, int y, int z, long at) {
        JsonObject json(long now, Integer left) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("age", Math.max(0, now - at));
            if (left != null) o.addProperty("left", left);
            return o;
        }
    }

    /** What is under the crosshair: a block (id, pos) or an entity (type id, block pos). */
    public record Target(String id, int x, int y, int z, boolean entity) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("kind", entity ? "entity" : "block");
            return o;
        }
    }

    private volatile Ev broke, placed;
    private volatile long attackedAt = Long.MIN_VALUE / 2;

    public void broke(String id, int x, int y, int z, long now) {
        if (valid(id)) broke = new Ev(id, x, y, z, now);
    }

    public void placed(String id, int x, int y, int z, long now) {
        if (valid(id)) placed = new Ev(id, x, y, z, now);
    }

    public void attacked(long now) { attackedAt = now; }

    public Ev broke() { return broke; }

    public Ev placed() { return placed; }

    public long attackedAt() { return attackedAt; }

    static boolean valid(String id) { return id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && id.length() <= 100; }

    static String path(String id) { return id == null ? "" : id.substring(id.indexOf(':') + 1); }

    static boolean isLog(String p) { return p.endsWith("_log") || p.endsWith("_stem") || p.endsWith("_wood") || p.endsWith("_hyphae") || p.endsWith("_leaves"); }

    static boolean isOre(String p) { return p.endsWith("_ore") || p.equals("ancient_debris"); }

    static final java.util.Set<String> STONE = java.util.Set.of("stone", "deepslate", "cobblestone", "cobbled_deepslate", "andesite", "diorite",
            "granite", "tuff", "calcite", "netherrack", "blackstone", "basalt", "sandstone", "dripstone_block", "end_stone", "obsidian");

    static boolean isStone(String p) { return STONE.contains(p); }

    static boolean isCrop(String p) {
        return p.equals("wheat") || p.equals("carrots") || p.equals("potatoes") || p.equals("beetroots") || p.endsWith("_crop") || p.equals("nether_wart")
                || p.equals("melon") || p.equals("pumpkin") || p.equals("sugar_cane") || p.equals("sweet_berry_bush");
    }

    /** What a placed item says about farming: seeds, saplings, the planted crops. */
    static boolean isPlanting(String p) {
        return p.endsWith("_seeds") || p.endsWith("_sapling") || p.equals("carrot") || p.equals("potato") || p.equals("nether_wart") || p.endsWith("_seed") || isCrop(p);
    }

    static boolean isWeapon(String p) {
        return p.endsWith("_sword") || p.equals("bow") || p.equals("crossbow") || p.equals("trident") || p.equals("mace");
    }

    /**
     * The guess: a hit on an entity within 10 s = fighting; else the newer of the last broken and placed block within
     * 10 s (logs and leaves = chopping, ores and stone = mining, crops = farming; placing seeds = farming, other blocks =
     * building; another broken block = mining with a pickaxe or shovel held, else building); else a weapon held =
     * fighting; else idle.
     */
    public static String guess(String held, Ev broke, Ev placed, long attackedAt, long now) {
        if (now - attackedAt <= RECENT_MS) return "fighting";
        Ev b = broke != null && now - broke.at() <= RECENT_MS ? broke : null;
        Ev p = placed != null && now - placed.at() <= RECENT_MS ? placed : null;
        String h = path(held);
        if (p != null && (b == null || p.at() >= b.at())) return isPlanting(path(p.id())) ? "farming" : "building";
        if (b != null) {
            String id = path(b.id());
            if (isLog(id)) return "chopping";
            if (isOre(id) || isStone(id)) return "mining";
            if (isCrop(id)) return "farming";
            return h.endsWith("_pickaxe") || h.endsWith("_shovel") ? "mining" : "building";
        }
        if (isWeapon(h)) return "fighting";
        return "idle";
    }
}
