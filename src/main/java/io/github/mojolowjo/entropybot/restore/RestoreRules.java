package io.github.mojolowjo.entropybot.restore;

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

    /** Stone-like terrain: its own item first, cobblestone when the bot has none. */
    static final Set<String> STONY = Set.of("stone", "cobblestone", "deepslate", "cobbled_deepslate", "tuff", "calcite", "andesite", "diorite",
            "granite", "sandstone", "red_sandstone", "dripstone_block", "blackstone", "basalt", "smooth_basalt", "netherrack", "end_stone",
            "terracotta");

    /** Soft terrain: its own item (or dirt). */
    static final Set<String> SOFT = Set.of("dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "clay", "sand",
            "red_sand", "gravel", "soul_sand", "soul_soil", "moss_block");

    public static String shortId(String id) { return id == null ? "" : id.replaceFirst("^minecraft:", ""); }

    /** Coloured terrain terracotta (never the glazed kind, which is built). */
    static boolean terracotta(String s) { return s.endsWith("_terracotta") && !s.endsWith("glazed_terracotta"); }

    public static boolean natural(String blockId) {
        if (blockId == null || (blockId.contains(":") && !blockId.startsWith("minecraft:"))) return false;
        String s = shortId(blockId);
        return STONY.contains(s) || SOFT.contains(s) || terracotta(s);
    }

    /** The items that fill the hole, best first; empty for a block that is never put back. */
    public static List<String> itemsFor(String blockId) {
        List<String> out = new ArrayList<>();
        if (!natural(blockId)) return out;
        String s = shortId(blockId);
        switch (s) {
            case "stone" -> out.add(COBBLE);
            case "deepslate" -> { out.add("minecraft:cobbled_deepslate"); out.add(COBBLE); }
            case "grass_block", "podzol", "mycelium", "clay" -> out.add("minecraft:dirt");
            case "mud", "moss_block" -> { out.add("minecraft:" + s); out.add("minecraft:dirt"); }
            default -> {
                out.add("minecraft:" + s);
                if (STONY.contains(s) || terracotta(s)) {
                    if (!s.equals("cobblestone")) out.add(COBBLE);
                }
            }
        }
        return out;
    }

    /** 0.24.4: stone a stone tool or a furnace takes (the stone_tool_materials family) and plain dirt. */
    static final Set<String> TOOL_STONE = Set.of("stone", "cobblestone", "deepslate", "cobbled_deepslate", "blackstone");
    static final Set<String> DIRT = Set.of("dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium");

    /**
     * 0.24.4 restore keeps purpose blocks: while a job gathers a terrain block (target: cobblestone for a pickaxe, dirt),
     * a broken block of the same family is the job's purpose, never re-placed (live: the ledger put back the cobblestone
     * the bot had dug to craft a pickaxe). An ore target keeps every other break a path break.
     */
    public static boolean purposeBreak(String targetId, String brokenId) {
        if (targetId == null || brokenId == null) return false;
        String t = shortId(targetId), b = shortId(brokenId);
        if (t.equals(b)) return true;
        if (!natural(targetId) || !natural(brokenId)) return false;
        return (TOOL_STONE.contains(t) && TOOL_STONE.contains(b)) || (DIRT.contains(t) && DIRT.contains(b));
    }

    /** Why a broken block is never put back: "an ore" or "not terrain"; null when it is restorable. */
    public static String whyNot(String blockId, boolean ore) {
        if (ore || shortId(blockId).endsWith("_ore") || shortId(blockId).equals("ancient_debris")) return "an ore";
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
}
