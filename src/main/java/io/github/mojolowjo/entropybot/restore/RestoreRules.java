package io.github.mojolowjo.entropybot.restore;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * P1: which broken blocks are terrain the bot puts back, and with what. Only vanilla terrain is restorable; a stone hole
 * may be filled with cobblestone (the owner's answer 2, 2026-10-05), so stone-like blocks fall back to it. Ores are
 * never put back (Baritone must not break them off-purpose; if it does, the entry is kept and reported). Loader-neutral.
 */
public final class RestoreRules {
    private RestoreRules() {}

    public static final String COBBLE = "minecraft:cobblestone";

    public static String shortId(String id) { return id == null ? "" : id.replaceFirst("^minecraft:", ""); }

    /**
     * 0.39.z75 put-back off (owner 2026-10-25 19:40: "turn off putting back blocks I broke on the way for now"): Tuning
     * {@code restore.putBack} - 1: a job's end puts back what it broke on the way (restore mode auto), as before; 0: no
     * put-back run starts at a job's end and nothing waits on the ledger ("restore pending" not shown). The ledger still
     * notes the breaks (the owner's "restore now", the placed blocks' take-down).
     */
    public static boolean putBackOn() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("restore.putBack") >= 1; }

    /** 0.39.z75: the job end's words when put-back is off and the job broke {@code broken} blocks; null: on, or none broken. */
    public static String putBackOffNote(int broken) {
        if (putBackOn() || broken <= 0) return null;
        return "put-back off (restore.putBack 0): " + broken + (broken == 1 ? " broken block" : " broken blocks") + " left";
    }

    /** 0.39.z75: a put-back run may start at a job's end - put-back on, restore mode auto, something broken. */
    public static boolean startsAfterJob(String mode, int broken) { return putBackOn() && "auto".equals(mode) && broken > 0; }

    private static String full(String id) { return id.contains(":") ? id : "minecraft:" + id; }

    /** The stone a stone tool or a furnace takes: the stone tool materials and the overworld base stone. */
    static boolean stoneFamily(String id) { return ItemWorth.hasTag(id, "stone_tool_materials") || ItemWorth.hasTag(id, "base_stone_overworld"); }

    /** Soft terrain by the game's tags: dirt, sand, mud, clay and their kin. */
    static boolean soft(String id) {
        for (String t : List.of("dirt", "sand", "mud", "mineable/shovel")) if (ItemWorth.hasTag(id, t) && !ItemWorth.hasTag(id, "c:ores")) return true;
        return false;
    }

    /** Terrain: stone-like blocks (base-stone tags, soft pickaxe blocks), soft blocks, terracotta (the terracotta tag, never the glazed kind: it is built). */
    public static boolean natural(String blockId) {
        if (blockId == null || (blockId.contains(":") && !blockId.startsWith("minecraft:"))) return false;
        String id = full(blockId);
        return ItemWorth.stoneLike(id) || soft(id) || ItemWorth.hasTag(id, "terracotta");
    }

    /** The items that fill the hole, best first: the block's own item, then the cheap stone (stone-like) or dirt (soft) the bot most likely carries; empty for a block never put back. */
    public static List<String> itemsFor(String blockId) {
        List<String> out = new ArrayList<>();
        if (!natural(blockId)) return out;
        String id = full(blockId);
        out.add(id);
        if (ItemWorth.stoneLike(id) || ItemWorth.hasTag(id, "terracotta")) { for (String m : ItemWorth.withTag("stone_tool_materials")) if (!out.contains(m)) out.add(m); }
        else for (String m : ItemWorth.withTag("dirt")) { if (!out.contains(m) && ItemWorth.stoneLike(m)) { out.add(m); break; } }
        return out;
    }

    /**
     * 0.24.4 restore keeps purpose blocks: while a job gathers a terrain block (target: cobblestone for a pickaxe, dirt),
     * a broken block of the same family is the job's purpose, never re-placed. Families: the stone tool materials; the
     * game's dirt tag. An ore target keeps every other break a path break.
     */
    public static boolean purposeBreak(String targetId, String brokenId) {
        if (targetId == null || brokenId == null) return false;
        if (shortId(targetId).equals(shortId(brokenId))) return true;
        if (!natural(targetId) || !natural(brokenId)) return false;
        String t = full(targetId), b = full(brokenId);
        return (stoneFamily(t) && stoneFamily(b)) || (ItemWorth.hasTag(t, "dirt") && ItemWorth.hasTag(b, "dirt"));
    }
    /** Why a broken block is never put back: "an ore" or "not terrain"; null when it is restorable. */
    public static String whyNot(String blockId, boolean ore) {
        if (ore || (blockId != null && ItemWorth.hasTag(full(blockId), "c:ores"))) return "an ore";
        return natural(blockId) ? null : "not terrain";
    }

    /**
     * 0.19.7: nothing solid over the bot's head (surfaceY: the first free cell over the column's top non-leaf block,
     * the MOTION_BLOCKING_NO_LEAVES heightmap): an open pit under a tree crown counts as sky.
     */
    public static boolean openAbove(int surfaceY, int feetY) {
        return surfaceY <= feetY + 1;
    }

    /** P1 answer 3: underground = no sky over the bot and at least 3 blocks under the surface there. */
    public static boolean underground(boolean skyVisible, int surfaceY, int feetY) {
        return !skyVisible && feetY <= surfaceY - 3;
    }

    /**
     * 0.34.3: the one under-ground rule (HERE, look, the food run, the retrace, the dig-out): surfaceY is the top
     * non-leaf block over the column; sky seen or open just above = not under ground. Returns how far the first air over
     * the surface is above the feet ("surface N up"), or 0 when not under ground.
     */
    public static int rockOverHead(boolean skyVisible, int surfaceY, int feetY) {
        boolean sky = skyVisible || openAbove(surfaceY, feetY);
        return underground(sky, surfaceY, feetY) ? surfaceY + 1 - feetY : 0;
    }
}
