package io.github.mojolowjo.entropybot.clear;

import java.util.List;
import java.util.Map;

/**
 * The filler blocks the bot may place when nothing is named (shelter and rim fills, restore scaffolds): junk by the
 * game's data that is also a block. The floor dig, its fill and its bridging engine are gone (rm-ungated).
 */
public final class FloorFill {
    private FloorFill() {}

    /**
     * Without a named block: the junk blocks it carries (plain stone-like filler by the game's tags and hardness, soft blocks
     * by worth; nothing that falls or opens by the cell rules), stone-like first, then dearer first, then by id.
     */
    public static List<String> fallback(Map<String, Integer> inv) {
        List<String> out = new java.util.ArrayList<>();
        for (var e : inv.entrySet()) if (e.getValue() != null && e.getValue() > 0 && isFallback(ClearRules.fullId(e.getKey()))) out.add(ClearRules.fullId(e.getKey()));
        List<String> mat = stoneMaterial();
        // stone-like that is no pickaxe material first (spared), then the pickaxe material, then soft blocks; by id inside
        out.sort(java.util.Comparator.comparingInt((String id) -> soft(id) ? 2 : mat.contains(id) ? 1 : 0).thenComparing(id -> id));
        return out;
    }

    /** Soft filler (dirt, gravel, sand): worth under junk.maxWorth. */
    static boolean soft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.valueOr(id, 1) < io.github.mojolowjo.entropybot.aa.brain.Tuning.get("junk.maxWorth"); }

    /** A block the bot may place as filler: junk by the data (Junk) and a block. */
    public static boolean isFallback(String fullId) {
        return fullId != null && io.github.mojolowjo.entropybot.aa.brain.Junk.is(fullId) && io.github.mojolowjo.entropybot.aa.brain.ItemWorth.block(fullId);
    }

    /** Stone pickaxe material (the game's stone_tool_materials tag). */
    public static List<String> stoneMaterial() { return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.withTag("stone_tool_materials"); }
}