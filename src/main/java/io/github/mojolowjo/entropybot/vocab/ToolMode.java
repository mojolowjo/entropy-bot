package io.github.mojolowjo.entropybot.vocab;

import java.util.Locale;

/**
 * V1b (VOCABULARY 6): {@code tools mode best|cheapest|stone}. best (the default): the highest tier that does the job;
 * cheapest: the lowest tier that does the job (it wears out); stone: a stone tool for everything, except blocks (ores)
 * that need a higher tier, which get the tier they need. Stored in commands.json "toolMode"; the old "toolOres"
 * "cheapest" reads as cheapest. The engines get the word as their tool-policy string ({@code clear.Tools},
 * {@code cave.MineRules}), where the legacy "iron" still means the 0.17 policy. Pure.
 */
public final class ToolMode {
    private ToolMode() {}

    public static final String BEST = "best", CHEAPEST = "cheapest", STONE = "stone";
    public static final String USAGE = "usage: tools mode best|cheapest|stone (best: the best tool for the job, the default; cheapest: the weakest that does it, so it wears out; "
            + "stone: stone tools for everything except ores that need a higher tier)";

    /** The mode from the stored keys (mode wins; else the old toolOres "cheapest"; else best). */
    public static String of(String storedMode, String storedOres) {
        String m = storedMode == null ? "" : storedMode.toLowerCase(Locale.ROOT);
        if (m.equals(BEST) || m.equals(CHEAPEST) || m.equals(STONE)) return m;
        return "cheapest".equals(storedOres) ? CHEAPEST : BEST;
    }

    /** "mode best|cheapest|stone" -> the mode, or null (the usage). */
    public static String parse(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^mode\\s+(best|cheapest|stone)$").matcher(t);
        return m.find() ? m.group(1) : null;
    }

    public static String text(String mode) {
        return switch (mode) {
            case CHEAPEST -> "tools mode cheapest: the weakest tool that does the job (it wears out first)";
            case STONE -> "tools mode stone: stone tools for everything; ores that need more get the tier they need";
            default -> "tools mode best: the best tool I have for the job";
        } + " - tools mode best|cheapest|stone";
    }

    /**
     * The pick among candidate tiers (each a tool that does the job), by mode: the index into tiers, -1 for none.
     * Ties: the first. Legacy "iron" behaves as cheapest here (its ore rule lives in the engines).
     */
    public static int pick(int[] tiers, String mode) {
        int best = -1;
        for (int i = 0; i < tiers.length; i++) {
            if (best < 0) { best = i; continue; }
            int t = tiers[i], b = tiers[best];
            boolean better = switch (mode == null ? BEST : mode) {
                case BEST -> t > b;
                case STONE -> rankStone(t) < rankStone(b);
                default -> t < b;
            };
            if (better) best = i;
        }
        return best;
    }

    /** stone mode's order: stone (2) first, then the next tiers up, wooden (1) last. */
    static int rankStone(int tier) {
        return tier >= 2 ? tier : 99;
    }

    /** Should a dig make stone pickaxes so the better one is kept? Never in best mode. */
    public static boolean makesStonePicks(String mode) {
        return !BEST.equals(mode);
    }
}
