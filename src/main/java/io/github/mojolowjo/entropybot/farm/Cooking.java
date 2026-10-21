package io.github.mojolowjo.entropybot.farm;

import java.util.Locale;
import java.util.Map;

/**
 * P5 cooking: "cook beef 8" is "smelt cooked_beef 8" (the furnace path finds the raw item and the fuel like any smelt).
 * Raw names map to their cooked item; a cooked name or anything else goes through as it is. Pure Java.
 */
public final class Cooking {
    private Cooking() {}

    public static final String USAGE = "usage: cook <food> [n] (beef, porkchop, chicken, mutton, rabbit, cod, salmon, potato, kelp, or the cooked item)";

    /** The cooked item of a raw food word ("beef", "pork", "potatoes"): the game's cooking recipes; a prefix or a plural of the raw item's name also finds it. */
    static String cookedOf(String word) {
        var cooks = io.github.mojolowjo.entropybot.aa.brain.FoodModel.cooks();
        String w = word.toLowerCase(Locale.ROOT);
        if (cooks.containsKey(w)) return cooks.get(w);
        for (var e : cooks.entrySet()) if (e.getKey().startsWith(w) && w.length() >= 4) return e.getValue();
        for (String suffix : new String[]{"es", "s"}) if (w.endsWith(suffix) && cooks.containsKey(w.substring(0, w.length() - suffix.length()))) return cooks.get(w.substring(0, w.length() - suffix.length()));
        return null;
    }

    /** "beef 8" -> "cooked_beef 8"; "cooked_beef 8" stays; null for an empty line. */
    public static String smeltText(String rest) {
        String t = rest == null ? "" : rest.trim();
        if (t.isEmpty()) return null;
        String[] w = t.split("\\s+", 2);
        String name = w[0].toLowerCase(Locale.ROOT);
        String bare = name.startsWith("minecraft:") ? name.substring(10) : name;
        String cooked = cookedOf(bare);
        String item = cooked != null ? "minecraft:" + cooked : w[0];
        return item + (w.length > 1 ? " " + w[1] : "");
    }
}
