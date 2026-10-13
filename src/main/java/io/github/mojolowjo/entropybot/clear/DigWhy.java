package io.github.mojolowjo.entropybot.clear;

import java.util.Collection;
import java.util.Map;

/**
 * 0.28.2 (trial fix package 2, B2): a dig that broke 0 blocks says why, with the next step (trial 2: "broke 0 blocks; N
 * left" twice with no reason). The first reason met, in this order: no tool (pickaxe, shovel or axe), nowhere to stand, the fence/guard, a
 * protected block, gave up on a block (too slow, the server puts it back), water or lava next to it. Pure.
 */
public final class DigWhy {
    private DigWhy() {}

    /**
    * @param prefix   the end's prefix as given to finishMessage (null: a plain end)
    * @param broken   blocks broken
    * @param left     targets left at the last scan
    * @param skip     cell key -&gt; why it was skipped
    * @param fails    cell key -&gt; why it was given up
    * @param noSpot   cells with nowhere to stand
    * @param protectedLeft built blocks left alone (key -&gt; name)
    * @return " - why - next: ..." text to append, or null (it broke something, or the prefix says why already)
    */
    public static String reason(String prefix, int broken, int left, Map<String, String> skip, Map<String, String> fails,
                                Collection<String> noSpot, Map<String, String> protectedLeft) {
        if (broken > 0) return null;
        if (prefix != null && prefix.contains("next:")) return null;
        if (prefix != null && (prefix.contains("refused") || prefix.contains("fence"))) return "next: area list (the dig must be inside one of my areas)";
        for (Map.Entry<String, String> e : skip.entrySet()) {
            String w = e.getValue() == null ? "" : e.getValue();
            if (w.startsWith("needs a better tool")) {
                String block = w.replaceFirst("^needs a better tool \\(?", "").replaceFirst("\\)$", "");
                if (block.matches(".*(dirt|sand|gravel|snow|clay|mud|soul_sand|soul_soil|podzol|mycelium|farmland|grass_block|rooted_dirt|coarse_dirt).*")) {
                    return "needs a shovel for " + block + " - next: craft wooden_shovel 1";
                }
                else if (block.matches(".*(_log|_wood|_planks|_stem|_hyphae|_fence|_door|_trapdoor|bookshelf|chest|crafting_table|barrel).*")) {
                    return "needs an axe for " + block + " - next: craft wooden_axe 1";
                }
                else {
                    return block.matches(".*(stone|cobble|deepslate|ore|andesite|diorite|granite|tuff).*") && !block.contains("diamond") && !block.contains("obsidian")
                            ? "needs a pickaxe for " + block + " - next: craft wooden_pickaxe 1"
                            : "needs a better pickaxe for " + block + " - next: craft stone_pickaxe 1 (iron_pickaxe for diamond, gold, redstone)";
                }
            }
        }
        if (prefix != null && prefix.contains("out of pickaxes")) return "needs a pickaxe - next: craft wooden_pickaxe 1";
        for (String k : noSpot) return "nowhere to stand within reach of " + k + " - next: goto " + k + " (stand next to it), then dig again";
        for (Map.Entry<String, String> e : fails.entrySet()) {
            String w = e.getValue() == null ? "" : e.getValue();
            if (w.contains("guard") || w.contains("fence") || w.contains("lease") || w.contains("area")) return "the fence refused " + e.getKey() + " (outside my areas) - next: area list (dig inside one of them)";
        }
        for (Map.Entry<String, String> e : protectedLeft.entrySet()) return e.getKey() + " is protected (a built block: " + e.getValue() + ") - next: dig " + e.getKey() + " " + e.getKey() + " force (if it is yours)";
        for (Map.Entry<String, String> e : skip.entrySet()) {
            String w = e.getValue() == null ? "" : e.getValue();
            if (w.contains("putting it back")) return "the server keeps putting " + e.getKey() + " back (a claim or protection?) - next: dig somewhere else";
            if (w.contains("water") || w.contains("lava")) return e.getKey() + " is next to water or lava - next: the same dig with the word water";
            if (w.contains("too long")) return gaveUp(e.getKey());
        }
        for (Map.Entry<String, String> e : fails.entrySet()) return gaveUp(e.getKey()) + (e.getValue() == null || e.getValue().isEmpty() ? "" : " [" + e.getValue() + "]");
        if (prefix != null) return null;           // a stop with its own reason ("stuck - ...", "blocked by water ...")
        if (left == 0) return "nothing to dig there (air, or done already)";
        return "no block could be started (" + left + " left) - next: check, then dig again from closer";
    }

    static String gaveUp(String key) {
        return "gave up on " + key + " after 10 s (bedrock? a block I can't break?) - next: dig around it";
    }
}
