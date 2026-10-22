package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * ruling (b), 0.39.y2: {@code aa want stone tools} names a group, not an item: every item of the game's tool tags whose tier is the
 * tier the word names. The word picks one of the game's own tiers ({@link Tier}, read from {@code net.minecraft.world.item.Tiers} by the adapter; by its name: stone, wood/wooden, gold/golden...);
 * an item is of that tier when its own data says so: a digging tool's mining speed equals the tier's speed, its durability equals the tier's, a sword's too (so a modded tool with the same tier data is in, whatever it is called). {@code aa want tools} is every
 * tier. Ordered by tag, then by the item's tier. No item name is read or listed; the NeedChain prices the group as one job.
 * Pure given the item and tier data (JUnit: ScenariosTest).
 */
public final class ToolGroup {
    private ToolGroup() {}

    /** game fact: the item tags the game puts the tools in, in the order the owner lists them (pickaxes, axes, hoes, swords, shovels). */
    static final List<String> TAGS = List.of("pickaxes", "axes", "hoes", "swords", "shovels");
    /** game fact: the word that makes a group ("stone tools"; alone, every tier). */
    public static final String WORD = "tools";
    /** One game tier: its name, mining speed and durability (the adapter reads them from the game's tier enum). */
    public record Tier(String name, float speed, int uses) {}

    private static volatile List<Tier> tiers = List.of();

    /** Installs the game's tiers (the adapter, once; tests with the vanilla numbers). */
    public static void use(List<Tier> t) { tiers = t == null ? List.of() : List.copyOf(t); }

    private static final float SAME = 1e-3f;      // structural: float compare of a speed read from item data

    /** The group a normalised word ("stone_tools", "tools") means: the items in run order, empty when the word is no group or no tool has that tier. */
    public static List<String> items(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        Tier tier = null;
        if (!w.equals(WORD)) {
            if (!w.endsWith("_" + WORD)) return List.of();
            String m = w.substring(0, w.length() - WORD.length() - 1);
            for (Tier t : tiers) {
                String n = t.name().toLowerCase(Locale.ROOT);
                if (!m.isEmpty() && (n.startsWith(m) || m.startsWith(n))) { tier = t; break; }      // wood -> wooden, gold -> golden
            }
            if (tier == null) return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tag : TAGS) {
            String kind = tag.substring(0, tag.length() - 1);           // the tag names the kind in the plural
            List<String> ids = new ArrayList<>();
            for (String id : ItemWorth.withTag(tag)) if (tier == null || ofTier(id, kind, tier)) ids.add(id);
            ids.sort(Comparator.<String>comparingInt(id -> MiningTiers.toolTier(id, kind)).thenComparing(Comparator.naturalOrder()));
            out.addAll(ids);
        }
        return out;
    }

    private static boolean ofTier(String id, String kind, Tier t) {
        var f = ItemWorth.facts(id);
        boolean made = Math.abs(f.durability() - t.uses()) < 0.5;           // the tier's durability is the tool's own
        return made && (kind.equals("sword") || (f.digSpeed() > 0 && Math.abs(f.digSpeed() - t.speed()) < SAME));
    }
    public static boolean isGroup(String word) { return !items(word).isEmpty(); }
}
