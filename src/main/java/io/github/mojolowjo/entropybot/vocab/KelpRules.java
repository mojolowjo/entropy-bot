package io.github.mojolowjo.entropybot.vocab;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.Locale;

/**
 * aa-kelpfish: {@code gather kelp [n] [cook]}, the food way "kelp from the water" (brain-b1: skipped, no verb). Pure.
 *
 * <p>rm-final: the cuts are one step of the Steps machine ({@code Step.KelpCut}: Baritone's MineProcess on the game's kelp
 * blocks, counted in the bag); the air reflex owns surfacing; with {@code cook} the kelp is smelted to dried kelp after
 * ({@code Step.Kelp} = the cut, then {@code cook kelp n}). The time limit is the Steps machine's per-step limit.
 *
 * <p>Loader notes: none here; the game side is {@code commands.StepHands} (vanilla kelp block classes, Baritone's MineProcess).
 */public final class KelpRules {
    private KelpRules() {}

    public static int defaultN() { return Tuning.i("kelpRules.defaultN"); }

    /** cook: smelt the kelp into dried kelp at the end (the word cook after the numbers). */
    public record Parsed(int n, boolean cook) {}

    /** True when the gather line is about kelp: first word "kelp". */
    public static boolean isKelp(String rest) {
        String[] w = words(rest);
        return w.length > 0 && w[0].equals("kelp");
    }

    private static String[] words(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        return r.isEmpty() ? new String[0] : r.split("\\s+");
    }

    /** "kelp [n] [cook]" or "kelp status": {n, cook}; null = bad (status is handled by the caller). */
    public static Parsed parse(String rest) {
        String[] w = words(rest);
        if (w.length == 0 || !w[0].equals("kelp")) return null;
        int n = defaultN();
        boolean cook = false;
        for (int i = 1; i < w.length; i++) {
            if (w[i].matches("^\\d{1,3}$") && i == 1) {
                n = Integer.parseInt(w[i]);
                if (n < 1) return null;
            } else if (w[i].equals("cook") && i == w.length - 1) cook = true;
            else return null;
        }
        return new Parsed(n, cook);
    }

}
