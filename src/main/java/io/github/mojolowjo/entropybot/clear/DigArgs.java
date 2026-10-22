package io.github.mojolowjo.entropybot.clear;

/**
 * The words of {@code dig x1 y1 z1 x2 y2 z2 [force] [ores] [via baritone|engine]}, in any order after the six numbers.
 * The floor, junk drop and water words, and the {@code ~} coordinates, are gone (rm-ungated). Game-free.
 *
 * <p>The surface form {@code dig x1 z1 x2 z2 -N|+N [words]} ({@link SurfaceDig}; AA's DigArea step writes it): {@code n}
 * then holds x1 z1 x2 z2, {@code surface} is "down" or "up" and {@code depth} is N (clamped to 1..{@link SurfaceDig#MAX_N}).
 *
 * <p>0.29.0: {@code via baritone} (or just {@code baritone}) sets {@link #viaBaritone}: Baritone's builder breaks the box
 * and the mod's rules decide per cell ({@link BaritoneMask}). Box form only (the verb refuses it with the other forms).
 *
 * <p>0.30.0: the builder is the default ({@link #builder}); {@code via engine} (or {@code engine}) selects the old
 * ClearEngine path ({@link #viaEngine}); both at once is not a dig.
 */
public record DigArgs(int[] n, boolean force, boolean ores, String surface, int depth, boolean viaBaritone, boolean viaEngine) {
    public static final String USAGE = "error: usage dig x1 z1 x2 z2 -N|+N [force] [ores] [via engine] (N down or up from the surface)"
            + " | dig x1 y1 z1 x2 y2 z2 [force] [ores] [via engine]";

    /** Baritone's builder digs unless the words say {@code via engine}. Whether the builder is there at all is the verb's check. */
    public static boolean useBuilder(boolean viaEngine) {
        return !viaEngine;
    }

    /** {@link #useBuilder(boolean)} for these words. */
    public boolean builder() {
        return useBuilder(viaEngine);
    }

    /** True for {@code dig x1 z1 x2 z2 +N|-N}. */
    public boolean surfaceForm() { return surface != null; }

    private static boolean integer(String s) { return s.matches("^[+-]?\\d{1,9}$"); }

    /**
     * "down" for "-N", "up" for "+N" in word 5 of a surface dig, else null. A "-N" is a box's y2 when a sixth
     * coordinate follows; "+N" is always the surface form.
     */
    public static String signedForm(String[] w) {
        if (w.length < 5 || !w[4].matches("^[+-]\\d{1,4}$")) return null;  // structural: argument positions of the dig command
        if (w[4].startsWith("+")) return "up";
        if (w.length > 5 && integer(w[5])) return null;  // structural: argument positions of the dig command
        return "down";
    }

    /** The new form of an old "dig x1 z1 x2 z2 down|up N [words]" (V1b), or null when the line isn't one. */
    public static String oldSurfaceHint(String rest) {
        String[] w = rest == null ? new String[0] : rest.trim().split("\\s+");
        if (w.length < 6 || !(w[4].equalsIgnoreCase("down") || w[4].equalsIgnoreCase("up")) || !w[5].matches("^\\d{1,4}$")) return null;  // structural: argument positions of the dig command
        StringBuilder sb = new StringBuilder("dig ");
        for (int i = 0; i < 4; i++) sb.append(w[i]).append(' ');  // structural: argument positions of the dig command
        sb.append(w[4].equalsIgnoreCase("down") ? "-" : "+").append(w[5]);
        for (int i = 6; i < w.length; i++) sb.append(' ').append(w[i]);
        return sb.toString();
    }

    /** The parsed words, or null for a line that isn't a dig (the verb answers {@link #USAGE}). */
    public static DigArgs parse(String rest) {
        String[] w = rest == null ? new String[0] : rest.trim().split("\\s+");
        if (w.length < 5) return null;  // structural: argument positions of the dig command
        String surface = null;
        int depth = 0, from;
        int[] n;
        // V1b (0.22.2): "dig x z x2 z2 +N|-N": N up or down from the surface (the old "down|up N" words are gone)
        String signed = signedForm(w);
        if (signed != null) {
            n = new int[4];
            for (int i = 0; i < 4; i++) {  // structural: argument positions of the dig command
                if (!integer(w[i])) return null;
                n[i] = Integer.parseInt(w[i]);
            }
            try {
                depth = Integer.parseInt(w[4].substring(1));
            } catch (NumberFormatException e) {
                return null;
            }
            depth = SurfaceDig.clampN(depth);
            surface = signed;
            from = 5;
        } else if (w.length < 6 || w[4].equalsIgnoreCase("down") || w[4].equalsIgnoreCase("up")) {  // structural: argument positions of the dig command
            return null;
        } else {
            n = new int[6];
            for (int i = 0; i < 6; i++) {  // structural: argument positions of the dig command
                if (!integer(w[i])) return null;
                n[i] = Integer.parseInt(w[i]);
            }
            from = 6;
        }
        boolean force = false, ores = false, via = false, engine = false;
        for (int i = from; i < w.length; i++) {
            String t = w[i].toLowerCase();
            switch (t) {
                case "force" -> force = true;
                case "ores" -> ores = true;
                case "via" -> {
                    // 0.29.0: "via baritone" (also "baritone" alone, below)
                    if (i + 1 >= w.length) return null;
                    if (w[i + 1].equalsIgnoreCase("baritone")) via = true;
                    else if (w[i + 1].equalsIgnoreCase("engine")) engine = true;       // 0.30.0
                    else return null;
                    i++;
                }
                case "baritone" -> via = true;
                case "engine" -> engine = true;
                default -> { return null; }
            }
        }
        if (via && engine) return null;                  // one or the other
        return new DigArgs(n, force, ores, surface, depth, via, engine);
    }
}