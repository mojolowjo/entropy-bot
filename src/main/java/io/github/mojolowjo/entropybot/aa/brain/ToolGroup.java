package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * ruling (b), 0.39.y1: {@code aa want stone tools} names a group, not an item: every item of the game's tool tags whose material
 * is the word's (read from the items themselves: the id minus the tool kind), ordered by tag and then by the tier the item's own
 * data gives. {@code aa want tools} is every material. No item name is listed here; the NeedChain prices the group as one job.
 * Pure (JUnit: ToolGroupTest).
 */
public final class ToolGroup {
    private ToolGroup() {}

    /** game fact: the item tags the game puts the tools in, in the order the owner lists them (pickaxes, axes, hoes, swords, shovels). */
    static final List<String> TAGS = List.of("pickaxes", "axes", "hoes", "swords", "shovels");
    /** game fact: the word that makes a group ("stone tools"; alone, every material). */
    public static final String WORD = "tools";

    /** The group a normalised word ("stone_tools", "tools") means: the items in run order, empty when the word is no group or no tool has that material. */
    public static List<String> items(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        String material;
        if (w.equals(WORD)) material = "";
        else if (w.endsWith("_" + WORD)) material = w.substring(0, w.length() - WORD.length() - 1);
        else return List.of();
        List<String> out = new ArrayList<>();
        for (String tag : TAGS) {
            String kind = tag.substring(0, tag.length() - 1);           // the tag names the kind in the plural
            List<String> ids = new ArrayList<>();
            for (String id : ItemWorth.withTag(tag)) {
                String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                if (!p.endsWith("_" + kind)) continue;
                String m = p.substring(0, p.length() - kind.length() - 1);
                if (material.isEmpty() || m.equals(material) || m.startsWith(material)) ids.add(id);      // wood -> wooden, gold -> golden
            }
            ids.sort(Comparator.<String>comparingInt(id -> MiningTiers.toolTier(id, kind)).thenComparing(Comparator.naturalOrder()));
            out.addAll(ids);
        }
        return out;
    }

    public static boolean isGroup(String word) { return !items(word).isEmpty(); }
}
