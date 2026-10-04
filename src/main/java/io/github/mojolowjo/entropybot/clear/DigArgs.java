package io.github.mojolowjo.entropybot.clear;

import java.util.List;

/**
 * B7e F (2026-10-04): the words of {@code dig x1 y1 z1 x2 y2 z2 [force] [ores] [floor [block]] [junk drop]}, in any
 * order after the six numbers. {@code floor} may be followed by a block name (any word that isn't one of the other
 * words). Game-free; the block is resolved and checked by the verb.
 */
public record DigArgs(int[] n, boolean force, boolean ores, boolean floor, String floorBlock, boolean junkDrop) {
    public static final String USAGE = "error: usage dig x1 y1 z1 x2 y2 z2 [force] [ores] [floor [block]] [junk drop]";

    private static final List<String> WORDS = List.of("force", "ores", "floor", "junk", "drop");

    /** The parsed words, or null for a line that isn't a dig (the verb answers {@link #USAGE}). */
    public static DigArgs parse(String rest) {
        String[] w = rest == null ? new String[0] : rest.trim().split("\\s+");
        if (w.length < 6) return null;
        int[] n = new int[6];
        try {
            for (int i = 0; i < 6; i++) n[i] = Integer.parseInt(w[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        boolean force = false, ores = false, floor = false, junk = false;
        String block = null;
        for (int i = 6; i < w.length; i++) {
            String t = w[i].toLowerCase();
            switch (t) {
                case "force" -> force = true;
                case "ores" -> ores = true;
                case "floor" -> {
                    floor = true;
                    if (i + 1 < w.length && !WORDS.contains(w[i + 1].toLowerCase())) block = w[++i];
                }
                case "junk" -> {
                    // "junk drop" (also "junkdrop" below)
                    if (i + 1 >= w.length || !w[i + 1].equalsIgnoreCase("drop")) return null;
                    junk = true;
                    i++;
                }
                case "junkdrop" -> junk = true;
                default -> { return null; }
            }
        }
        return new DigArgs(n, force, ores, floor, block, junk);
    }
}
