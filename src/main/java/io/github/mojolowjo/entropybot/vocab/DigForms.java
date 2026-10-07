package io.github.mojolowjo.entropybot.vocab;

import java.util.List;

/**
 * V1b (VOCABULARY 3): {@code dig <area> [-N|+N] [ores] [junk drop] [water [large]]}. An area with its own heights digs its
 * box; an all-heights area needs -N (N below the surface) or +N (N above it), so no dig goes to bedrock by accident
 * (owner's answer 3). Checked before the confirm question is asked. Pure.
 */
public final class DigForms {
    private DigForms() {}

    public static final List<String> AREA_WORDS = List.of("ores", "junk", "drop", "water", "large");

    /** The signed N of "dig &lt;area&gt; ... -N|+N" ("-3" or "+2"), or null. words: the line after "dig". */
    public static String signed(List<String> words) {
        for (int i = 1; i < words.size(); i++) if (words.get(i).matches("^[+-]\\d{1,4}$")) return words.get(i);
        return null;
    }

    /**
     * Why "dig &lt;area&gt; ..." can't run, or null. exists/round/allY/safe: what the area is (exists false: the rest is
     * ignored).
     */
    public static String areaProblem(String name, boolean exists, boolean round, boolean allY, boolean safe, List<String> words) {
        if (!exists) return "error: I have no area called " + name + " (area list)";
        if (safe) return "error: " + name + " is a safe area - I never dig there";
        if (round) return "error: " + name + " is a circle - make a box area for this";
        for (int i = 1; i < words.size(); i++) {
            String w = words.get(i);
            if (!AREA_WORDS.contains(w) && !w.matches("^[+-]\\d{1,4}$") && !w.equals("confirm"))
                return "usage: dig <area> [-N|+N] [ores] [junk drop] [water [large]]";
        }
        if (allY && signed(words) == null)
            return "error: " + name + " covers all heights: add -N for N below the surface (dig " + name + " -3) or +N above it";
        return null;
    }
}
