package io.github.mojolowjo.entropybot.clear;

import java.util.List;

/**
 * B7e F (2026-10-04): the words of {@code dig x1 y1 z1 x2 y2 z2 [force] [ores] [floor [block]] [junk drop] [water [large]]},
 * in any order after the six numbers. {@code floor} may be followed by a block name (any word that isn't one of the other
 * words). Water plan: {@code water} seals water that stops the dig and digs on; {@code large} (after it) also takes on a
 * large body of water (what the confirm question runs). Game-free; the block is resolved and checked by the verb.
 *
 * <p>0.19.5: every coordinate may be {@code ~}, {@code ~N} ({@link RelCoord}, relative to the base: the bot's feet
 * block). The surface form {@code dig x1 z1 x2 z2 down|up N [words]} ({@link SurfaceDig}): {@code n} then holds
 * x1 z1 x2 z2, {@code surface} is "down" or "up" and {@code depth} is N (clamped to 1..{@link SurfaceDig#MAX_N}).
 * {@code floor} is a box-only word (the verb refuses it with the surface form).
 */
public record DigArgs(int[] n, boolean force, boolean ores, boolean floor, String floorBlock, boolean junkDrop, boolean water, boolean large,
                      String surface, int depth) {
    public static final String USAGE = "error: usage dig x1 z1 x2 z2 -N|+N [words below, no floor] (N down or up from the surface; any coordinate may be ~ or ~N, from my feet)"
            + " | dig x1 y1 z1 x2 y2 z2 [force] [ores] [floor [block]] [junk drop] [water [large]]";

    private static final List<String> WORDS = List.of("force", "ores", "floor", "junk", "drop", "water", "large");

    public DigArgs(int[] n, boolean force, boolean ores, boolean floor, String floorBlock, boolean junkDrop, boolean water, boolean large) {
        this(n, force, ores, floor, floorBlock, junkDrop, water, large, null, 0);
    }

    /** True for {@code dig x1 z1 x2 z2 +N|-N}. */
    public boolean surfaceForm() { return surface != null; }

    /**
     * "down" for "-N", "up" for "+N" in word 5 of a surface dig, else null. A "-N" is a box's y2 when a sixth
     * coordinate follows; "+N" is always the surface form.
     */
    public static String signedForm(String[] w) {
        if (w.length < 5 || !w[4].matches("^[+-]\\d{1,4}$")) return null;
        if (w[4].startsWith("+")) return "up";
        if (w.length > 5 && RelCoord.parse(w[5], 0) != null) return null;
        return "down";
    }

    /** The new form of an old "dig x1 z1 x2 z2 down|up N [words]" (V1b), or null when the line isn't one. */
    public static String oldSurfaceHint(String rest) {
        String[] w = rest == null ? new String[0] : rest.trim().split("\\s+");
        if (w.length < 6 || !(w[4].equalsIgnoreCase("down") || w[4].equalsIgnoreCase("up")) || !w[5].matches("^\\d{1,4}$")) return null;
        StringBuilder sb = new StringBuilder("dig ");
        for (int i = 0; i < 4; i++) sb.append(w[i]).append(' ');
        sb.append(w[4].equalsIgnoreCase("down") ? "-" : "+").append(w[5]);
        for (int i = 6; i < w.length; i++) sb.append(' ').append(w[i]);
        return sb.toString();
    }

    /** The parsed words with {@code ~} taken from 0 0 0, or null (see {@link #parse(String, int[])}). */
    public static DigArgs parse(String rest) {
        return parse(rest, null);
    }

    /** The parsed words, or null for a line that isn't a dig (the verb answers {@link #USAGE}). base: x y z for {@code ~} (null: 0 0 0). */
    public static DigArgs parse(String rest, int[] base) {
        int[] b = base == null ? new int[3] : base;
        String[] w = rest == null ? new String[0] : rest.trim().split("\\s+");
        if (w.length < 5) return null;
        String surface = null;
        int depth = 0, from;
        int[] n;
        // V1b (0.22.2): "dig x z x2 z2 +N|-N": N up or down from the surface (the old "down|up N" words are gone)
        String signed = signedForm(w);
        if (signed != null) {
            n = new int[4];
            int[] axis = {0, 2, 0, 2};
            for (int i = 0; i < 4; i++) {
                Integer v = RelCoord.parse(w[i], b[axis[i]]);
                if (v == null) return null;
                n[i] = v;
            }
            try {
                depth = Integer.parseInt(w[4].substring(1));
            } catch (NumberFormatException e) {
                return null;
            }
            depth = SurfaceDig.clampN(depth);
            surface = signed;
            from = 5;
        } else if (w.length < 6 || w[4].equalsIgnoreCase("down") || w[4].equalsIgnoreCase("up")) {
            return null;
        } else {
            n = new int[6];
            for (int i = 0; i < 6; i++) {
                Integer v = RelCoord.parse(w[i], b[i % 3]);
                if (v == null) return null;
                n[i] = v;
            }
            from = 6;
        }
        boolean force = false, ores = false, floor = false, junk = false, water = false, large = false;
        String block = null;
        for (int i = from; i < w.length; i++) {
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
                case "water" -> water = true;
                case "large" -> large = true;          // "water large" (or "large" anywhere: water too)
                default -> { return null; }
            }
        }
        return new DigArgs(n, force, ores, floor, block, junk, water || large, large, surface, depth);
    }
}
