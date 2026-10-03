package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which tool to break with (the bridge's toolTier and holdBestTool's choice) and what to do with none
 * (noTool): the cheapest tool that does the job, so stone pickaxes wear out first and better ones are kept for
 * blocks that need them; the fastest of equals.
 */
public final class Tools {
    private Tools() {}

    private static final Pattern[] TIERS = {
            Pattern.compile("(^|:)wooden_"), Pattern.compile("(^|:)stone_"), Pattern.compile("(^|:)(copper|golden)_"),
            Pattern.compile("(^|:)iron_"), Pattern.compile("(^|:)diamond_"), Pattern.compile("(^|:)netherite_") };
    private static final int[] TIER_OF = { 1, 2, 3, 4, 5, 6 };

    /** Tool material, cheapest first: wooden 1, stone 2, copper/golden 3, iron 4, diamond 5, netherite 6; unknown 4. */
    public static int toolTier(String id) {
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].matcher(id).find()) return TIER_OF[i];
        return 4;
    }

    public static boolean isPickaxe(String id) {
        return id.endsWith("_pickaxe");
    }

    /** One non-empty inventory slot (0..35) and what its item does to the block at hand. */
    public record Slot(int index, String id, boolean correctForDrops, float speed) {}

    /**
     * holdBestTool's choice: the slot to hold, or -1 for none. Swords are never used. When the block only drops
     * with the right tool ({@code needsCorrectTool}) only such tools count; otherwise only real tools for it
     * (speed above 1).
     */
    public static int choose(List<Slot> slots, boolean needsCorrectTool) {
        int best = -1, bestTier = 99;
        float bestSpeed = 0;
        for (Slot s : slots) {
            if (s.id().endsWith("_sword")) continue;
            if (needsCorrectTool && !s.correctForDrops()) continue;
            if (!needsCorrectTool && s.speed() <= 1) continue;       // not a tool for this block
            int tier = toolTier(s.id());
            if (tier < bestTier || (tier == bestTier && s.speed() > bestSpeed)) {
                best = s.index();
                bestTier = tier;
                bestSpeed = s.speed();
            }
        }
        return best;
    }

    /** The tier an ore pickaxe has to have under "tools ores iron": iron or better (unknown modded tools count as iron). */
    public static final int ORE_TIER = 4;

    /**
     * The tool policy (package B, 2026-10-03): on an ore under {@code toolOres} "iron" (the default) the cheapest tool
     * of iron tier or better that does the job, else (no such tool, "cheapest", or not an ore) {@link #choose}'s
     * cheapest. So stone pickaxes wear out on stone and the iron one is kept for ores.
     */
    public static int choose(List<Slot> slots, boolean needsCorrectTool, boolean ore, String toolOres) {
        if (ore && !"cheapest".equals(toolOres)) {
            List<Slot> iron = new ArrayList<>();
            for (Slot s : slots) if (toolTier(s.id()) >= ORE_TIER) iron.add(s);
            int best = choose(iron, needsCorrectTool);
            if (best >= 0) return best;
        }
        return choose(slots, needsCorrectTool);
    }

    /** What the clear crafts so it doesn't wear the ore pickaxe out on stone: three stone pickaxes. */
    public static final String STONE_PICKS = "minecraft:stone_pickaxe 3";

    /**
     * Keep stone pickaxes stocked: true when the tool {@link #choose} picked is a pickaxe of iron tier or better, the
     * block is no ore it is meant for ("tools ores iron"), a stone pickaxe could break it as well, and the job hasn't
     * tried this yet. (A cheaper pickaxe would have been chosen if the bot had one.) The caller crafts {@link
     * #STONE_PICKS} first, or, if that can't start, goes on with the pickaxe it has.
     */
    public static boolean stonePicksFirst(String chosenId, boolean ore, String toolOres, boolean stoneCanBreak, boolean triedAlready) {
        if (triedAlready || chosenId == null || !isPickaxe(chosenId) || toolTier(chosenId) < ORE_TIER || !stoneCanBreak) return false;
        return !(ore && !"cheapest".equals(toolOres));
    }

    /** The whisper while it makes them ({@code using}: the pickaxe it would have used). */
    public static String stonePicksWhisper(String using) {
        return "no stone pickaxes left - making 3 so the " + using.replaceFirst("^[^:]*:", "") + " is kept for ores, then back to it";
    }

    /** holdBestTool's answer when {@link #choose} found nothing: fine for blocks that drop anyway. */
    public static boolean okWithout(boolean needsCorrectTool) {
        return !needsCorrectTool;
    }

    /**
     * noTool's decision when no tool can harvest the block: with a pickaxe left, skip the block ({@link #skip});
     * after two tries at crafting, stop ({@link #stop}); else craft 3 pickaxes, stone first, then the kind it was
     * using ({@link #craft}, craft texts like "minecraft:stone_pickaxe 3", tried in order).
     */
    public record NoTool(String skip, String stop, List<String> craft) {}

    public static NoTool noTool(ClearJob job, boolean hasPickaxe, String blockName) {
        if (hasPickaxe) return new NoTool("needs a better tool (" + blockName + ")", null, null);
        if (job.craftTries >= 2) return new NoTool(null, "stopped: out of pickaxes (" + blockName + " needs one)", null);
        job.craftTries++;
        List<String> tries = new ArrayList<>();
        tries.add("minecraft:stone_pickaxe 3");
        if (job.lastPick != null && !job.lastPick.equals("minecraft:stone_pickaxe")) tries.add(job.lastPick + " 3");
        return new NoTool(null, null, tries);
    }

    /** Every craft try failed to start ({@code lastError} = the last reply). */
    public static String cantCraftMessage(String lastError) {
        return "stopped: out of pickaxes and I can't make more (" + lastError.replaceFirst("^error: ", "") + ")";
    }

    /** Back from crafting with still no pickaxe ({@code craftReply} = how the craft job ended). */
    public static String craftFailedMessage(String craftReply) {
        return "stopped: out of pickaxes and making more failed (" + String.valueOf(craftReply == null ? "" : craftReply).replaceFirst("^error: ", "") + ")";
    }

    /** The whisper while it crafts ("craft" text as given to the crafter). */
    public static String craftingWhisper(String craftText) {
        String id = craftText.replaceFirst(" \\d+$", "");
        return "out of pickaxes - making 3 " + id.replaceFirst("^minecraft:", "") + ", then back to clearing";
    }
}
