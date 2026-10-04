package io.github.mojolowjo.entropybot.clear;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * B7e F (2026-10-04): {@code dig ... junk drop}. A tunnel 700+ blocks from the base can't take a full bag home (the
 * /home trip ended "couldn't get there (745 blocks away)" and the chain stopped), so the bag's plain junk blocks are
 * thrown away and the clear goes on. Game-free: which items go.
 *
 * <p>Only plain stone-like blocks ({@link #JUNK}) are ever thrown, and only what a deposit would put away anyway (the
 * caller passes StorageRules.depositables: tools, armor, food, torches, supplies, the hotbar layout and the 64
 * cobblestone kept for pickaxes stay out of it). A floor dig keeps {@link #FLOOR_KEEP} of the block its fill will use.
 */
public final class JunkDrop {
    private JunkDrop() {}

    /** The blocks it may throw away (never valuables, ores, tools, food: none of those is here). */
    public static final Set<String> JUNK = Set.of("minecraft:cobbled_deepslate", "minecraft:cobblestone", "minecraft:deepslate",
            "minecraft:tuff", "minecraft:stone", "minecraft:dirt", "minecraft:gravel", "minecraft:andesite", "minecraft:diorite",
            "minecraft:granite");

    /** What a floor dig keeps of its fill block. */
    public static final int FLOOR_KEEP = 64;

    public static boolean isJunk(String id) {
        return id != null && JUNK.contains(ClearRules.fullId(id));
    }

    /**
     * What to throw (id -> count): the junk among {@code depositables} (StorageRules.depositables: id -> how many a
     * deposit keeps of it), all of it but that keep amount, and at least {@code keep} of {@code keepId} stays
     * ({@code inv}: id -> count carried). keepId null: nothing extra kept.
     */
    public static Map<String, Integer> plan(Map<String, Integer> depositables, Map<String, Integer> inv, String keepId, int keep) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : depositables.entrySet()) {
            String id = e.getKey();
            if (!isJunk(id)) continue;
            int kept = e.getValue() == null ? 0 : e.getValue();
            if (keepId != null && ClearRules.fullId(keepId).equals(ClearRules.fullId(id))) kept = Math.max(kept, keep);
            int n = inv.getOrDefault(id, 0) - kept;
            if (n > 0) out.put(id, n);
        }
        return out;
    }

    /**
     * Whether the clear walks over to pick up a drop of {@code id}: always without "junk drop" or for a non-junk item;
     * a junk drop only on a floor dig, only of the block its fill will use, and only while it carries fewer than twice
     * {@link #FLOOR_KEEP} of it.
     */
    public static boolean chase(String id, ClearJob job, Map<String, Integer> inv) {
        if (!job.junkDrop || !isJunk(id)) return true;
        if (!job.floor) return false;
        String full = ClearRules.fullId(id);
        String fb = FloorFill.chooseBlock(job.floorBlock, inv);
        if (fb == null) return job.floorBlock != null ? ClearRules.fullId(job.floorBlock).equals(full) : FloorFill.FALLBACK.contains(full);
        return fb.equals(full) && inv.getOrDefault(fb, 0) < 2 * FLOOR_KEEP;
    }

    /**
     * The yaw (Minecraft degrees: 0 = +z, 90 = -x) to throw toward: back to where the clear began (x, z; the dug side)
     * when that is more than 2 blocks away, else away from the box's middle.
     */
    public static float throwYaw(double[] start, double x, double z, ClearBox box) {
        double dx, dz;
        if (start != null && Math.hypot(start[0] - x, start[1] - z) > 2) {
            dx = start[0] - x;
            dz = start[1] - z;
        } else {
            dx = x - (box.x1() + box.x2() + 1) / 2.0;
            dz = z - (box.z1() + box.z2() + 1) / 2.0;
            if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) dx = -1;
        }
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
    }

    /** "threw away 320 cobbled_deepslate, 64 tuff". */
    public static String summary(Map<String, Integer> thrown) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : thrown.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(ClearRules.blockName(e.getKey()));
        }
        return sb.length() == 0 ? "nothing to throw away" : "threw away " + sb;
    }
}
