package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The pickaxe tier a block asks for, from what the game says about the blocks ({@link Source}: the needs_*_tool /
 * incorrect_for_* tags, modded tiers included), and the tier a tool item has, from its own data. No block or item is named
 * here. The tools-need's blocked share went with that need (0.39.y1): a chain's dig asks for the tier its block needs
 * ({@code NeedChain}). Pure. Loader notes: none (the tag adapter is {@code commands/MiningTierSource}).
 * Tier scale = the bot's pickTier: 0 hand, 1 wooden, 2 stone, 3 iron, 4 diamond (a modded tier lands where its tags put it).
 */
public final class MiningTiers {
    private MiningTiers() {}

    public static final int UNKNOWN = -1; // structural
    public static final int UNREACHABLE = Integer.MAX_VALUE; // structural

    /** The game's mining data. */
    public interface Source {
        /** The pickaxe tier that mines the block this item places (0 none needed), or -1 if the item is not a block. */
        int blockTier(String itemId);

        /** The least tier among the blocks whose drops include this item (diamond from its ore), or -1 if no block drops it / unknown. */
        default int dropTier(String itemId) { return -1; }
    }

    /**
     * Pickaxe tiers from how much each pickaxe is locked out of: {@code lockedCounts} (one per pickaxe, the number of blocks
     * it cannot mine) become 1 for the most locked up to N for the least (equal counts share a tier). A modded tier that mines
     * what diamond cannot lands above it by its own tag rules.
     */
    public static List<Integer> rankTiers(List<Integer> lockedCounts) {
        List<Integer> distinct = new ArrayList<>(new java.util.TreeSet<>(lockedCounts));
        java.util.Collections.reverse(distinct);          // most locked first
        List<Integer> out = new ArrayList<>();
        for (int c : lockedCounts) out.add(distinct.indexOf(c) + 1);
        return out;
    }

    private static volatile Source source;

    /** Installs the data (the adapter, once per recipe reload). */
    public static void use(Source s) { source = s; }

    public static boolean ready() { return source != null; }

    public static Source source() { return source; }

    /**
     * The one tier switch (rm-names): the pickaxe tier of a held digging tool of {@code kind} (pickaxe, axe, shovel, hoe), on
     * the bot's scale (1 wooden .. 4 diamond; a modded tier lands where its needs_* tags put it), from the item's own TOOL
     * component and tags ({@link ItemWorth#facts}); 0 when the item is no such tool; no data: 0, said once.
     */
    public static int toolTier(String itemId, String kind) {
        if (itemId == null || kind == null) return 0;
        if (!ItemWorth.hasData()) { ItemWorth.warnOnce("tier", "no item data loaded: no tool has a tier"); return 0; }
        var f = ItemWorth.facts(itemId);
        if (kind.equalsIgnoreCase("sword")) return f.role() == ItemWorth.Role.WEAPON ? (int) Math.max(1, Math.round(f.attack() - Tuning.i("miningTiers.swordBase"))) : 0;      // a weapon's tier is its hit: 4 -> 1 ... 8 -> 5
        if (f.digSpeed() <= 0 || !f.role().name().equalsIgnoreCase(kind)) return 0;
        return f.digLevel() + 1;
    }

    /** The tier of any digging tool (pickaxe, axe, shovel, hoe) on the same scale: 0 when it digs nothing or no data is loaded (said once). */
    public static int anyToolTier(String itemId) {
        if (itemId == null) return 0;
        if (!ItemWorth.hasData()) { ItemWorth.warnOnce("tier", "no item data loaded: no tool has a tier"); return 0; }
        var f = ItemWorth.facts(itemId);
        return f.digSpeed() > 0 ? f.digLevel() + 1 : 0;
    }

    /**
     * The tier a mining need word asks of the pickaxe. A need names the pickaxe material that does the job ("stone", "iron",
     * "diamond", or a modded material id): its tier is that pickaxe's own, read from its TOOL data. An unreadable need is
     * {@code tools.unknownNeedTier}.
     */
    public static int needTier(String need) {
        int t = need == null ? 0 : anyToolTier(need.contains("_pickaxe") ? need : (need.contains(":") ? need.replace(":", ":") + "_pickaxe" : "minecraft:" + need + "_pickaxe"));
        if (t <= 0) { ItemWorth.warnOnce("need:" + need, "no mining tier for the need \"" + need + "\": using tools.unknownNeedTier"); return (int) Tuning.get("tools.unknownNeedTier"); }
        return Math.max(1, t);
    }

    /**
     * The collect hand's guard (owner-watched 2026-10-22: it walked to coal ore with no pickaxe and dug): of the blocks a dig
     * may take ({@code blockIds}: each block's item id), those the bag's best pickaxe tier {@code have} can dig, in order.
     * Without mining data every block stays (the guard cannot judge; the caller says so).
     */
    public static List<String> diggable(List<String> blockIds, int have) {
        List<String> out = new ArrayList<>();
        for (String b : blockIds) {
            int t = source == null ? 0 : blockTierOf(b);
            if (t <= have) out.add(b);
        }
        return out;
    }

    /** The refusal when none of the blocks can be dug: "coal_ore needs tier 1, I have 0" (the least tier among them); null when one can. */
    public static String digRefusal(List<String> blockIds, int have) {
        if (blockIds.isEmpty() || !diggable(blockIds, have).isEmpty()) return null;
        String least = null;
        int lt = Integer.MAX_VALUE;
        for (String b : blockIds) {
            int t = blockTierOf(b);
            if (t < lt) { lt = t; least = b; }
        }
        String name = least.startsWith("minecraft:") ? least.substring(10) : least;
        return name + " needs tier " + (lt == UNREACHABLE ? "none of the game's pickaxes has" : String.valueOf(lt)) + ", I have " + have;
    }

    /** The pickaxe tier the block (or the block an item places) needs, from the game's tags; {@link #UNKNOWN} (said once) without data. */
    public static int blockTierOf(String id) {
        Source s = source;
        if (s == null) { ItemWorth.warnOnce("tier", "no mining-tier data loaded: no block has a tier"); return UNKNOWN; }
        return s.blockTier(id.indexOf(':') < 0 ? "minecraft:" + id : id);
    }

}



