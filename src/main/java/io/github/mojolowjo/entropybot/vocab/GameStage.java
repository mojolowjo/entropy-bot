package io.github.mojolowjo.entropybot.vocab;

import java.util.Map;

/**
 * V1b (BRAIN_LOOP answer 4.5): the game stage from what the group has (the stock view: the bot's bag, the chests, the
 * RS network, the owner's bag): {@code nothing} (no tools), {@code wood}, {@code stone}, {@code iron}, {@code diamond}
 * (diamond or netherite tools). Shown in {@code status}. Pure.
 */
public final class GameStage {
    private GameStage() {}

    static final String[] TOOLS = {"_pickaxe", "_axe", "_shovel", "_sword", "_hoe"};

    public static String of(Map<String, Integer> totals) {
        int best = 0;
        if (totals != null) {
            for (Map.Entry<String, Integer> e : totals.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                String id = e.getKey();
                boolean tool = false;
                for (String t : TOOLS) if (id.endsWith(t)) tool = true;
                if (!tool) continue;
                best = Math.max(best, tier(id));
            }
        }
        return switch (best) {
            case 0 -> "nothing";
            case 1 -> "wood";
            case 2, 3 -> "stone";
            case 4 -> "iron";
            default -> "diamond";
        };
    }

    /** wooden 1, stone 2, copper/golden 3, iron 4, diamond 5, netherite 6; an unknown modded tool counts as stone. */
    static int tier(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        if (p.startsWith("wooden_")) return 1;
        if (p.startsWith("stone_")) return 2;
        if (p.startsWith("copper_") || p.startsWith("golden_")) return 3;
        if (p.startsWith("iron_")) return 4;
        if (p.startsWith("diamond_")) return 5;
        if (p.startsWith("netherite_")) return 6;
        return 2;
    }

    /** The status part: " | deaths N in the last hour | stage X". */
    public static String statusPart(int deaths, String stage) {
        return " | deaths " + deaths + " in the last hour" + (stage == null ? "" : " | stage " + stage);
    }
}
