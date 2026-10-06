package io.github.mojolowjo.entropybot.farm;

import java.util.Locale;
import java.util.Map;

/**
 * P5 cooking: "cook beef 8" is "smelt cooked_beef 8" (the furnace path finds the raw item and the fuel like any smelt).
 * Raw names map to their cooked item; a cooked name or anything else goes through as it is. Pure Java.
 */
public final class Cooking {
    private Cooking() {}

    static final Map<String, String> COOKED = Map.ofEntries(
            Map.entry("beef", "cooked_beef"), Map.entry("porkchop", "cooked_porkchop"), Map.entry("pork", "cooked_porkchop"),
            Map.entry("chicken", "cooked_chicken"), Map.entry("mutton", "cooked_mutton"), Map.entry("rabbit", "cooked_rabbit"),
            Map.entry("cod", "cooked_cod"), Map.entry("salmon", "cooked_salmon"), Map.entry("potato", "baked_potato"),
            Map.entry("potatoes", "baked_potato"), Map.entry("kelp", "dried_kelp"));

    public static final String USAGE = "usage: cook <food> [n] (beef, porkchop, chicken, mutton, rabbit, cod, salmon, potato, kelp, or the cooked item)";

    /** "beef 8" -> "cooked_beef 8"; "cooked_beef 8" stays; null for an empty line. */
    public static String smeltText(String rest) {
        String t = rest == null ? "" : rest.trim();
        if (t.isEmpty()) return null;
        String[] w = t.split("\\s+", 2);
        String name = w[0].toLowerCase(Locale.ROOT);
        String bare = name.startsWith("minecraft:") ? name.substring(10) : name;
        String cooked = COOKED.get(bare);
        String item = cooked != null ? "minecraft:" + cooked : w[0];
        return item + (w.length > 1 ? " " + w[1] : "");
    }
}
