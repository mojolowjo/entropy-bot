package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AA_ROUND4 C / PROGRESSION_PLAN P1: {@code Costs.blocked} from data. The pickaxe tier each tech-tree item needs is worked
 * out from what the game says about the blocks ({@link Source}: the needs_*_tool / incorrect_for_* tags, modded tiers
 * included) and the recipes: an item that is a block needs the tier that mines it; a made item needs the best (lowest)
 * recipe's worst ingredient; anything neither a block nor made (mob drops, raw loot) needs nothing. No block or item is
 * named here. The share is the part of the top ideas the current tier cannot mine. Pure. Loader notes: none (the tag
 * adapter is {@code commands/MiningTierSource}).
 * Tier scale = the bot's pickTier: 0 hand, 1 wooden, 2 stone, 3 iron, 4 diamond (a modded tier lands where its tags put it).
 */
public final class MiningTiers {
    private MiningTiers() {}

    public static final int UNKNOWN = -1, UNREACHABLE = Integer.MAX_VALUE;

    /** The game's mining data. */
    public interface Source {
        /** The pickaxe tier that mines the block this item places (0 none needed), or -1 if the item is not a block. */
        int blockTier(String itemId);
    }

    private static volatile Source source;

    /** Installs the data (the adapter, once per recipe reload). */
    public static void use(Source s) { source = s; }

    public static boolean ready() { return source != null; }

    public static Source source() { return source; }

    /** The result: how many of the top ideas the tier holds back, which, and the line for {@code aa why}. */
    public record Result(int blocked, int of, double share, List<String> names, String terms) {}

    private static TechTree cachedTree;
    private static Source cachedSource;
    private static Map<String, Integer> cachedNeeds = Map.of();

    /** The least pickaxe tier each item needs (items only made from nothing mineable map to 0). */
    public static Map<String, Integer> needs(TechTree t, Source s) {
        Map<String, Integer> need = new HashMap<>();
        Set<String> made = new HashSet<>();
        Set<String> all = new HashSet<>();
        for (TechTree.Recipe r : t.recipes()) {
            made.add(r.result());
            all.add(r.result());
            for (Set<String> slot : r.slots()) all.addAll(slot);
        }
        for (String id : all) {
            int bt = s.blockTier(id);
            need.put(id, bt >= 0 ? bt : made.contains(id) ? UNREACHABLE : 0);
        }
        boolean changed = true;
        for (int pass = 0; changed && pass < 64; pass++) {
            changed = false;
            for (TechTree.Recipe r : t.recipes()) {
                int worst = 0;
                for (Set<String> slot : r.slots()) {
                    int best = UNREACHABLE;
                    for (String alt : slot) best = Math.min(best, need.getOrDefault(alt, 0));
                    worst = Math.max(worst, best);
                }
                if (worst < need.get(r.result())) { need.put(r.result(), worst); changed = true; }
            }
        }
        return need;
    }

    private static synchronized Map<String, Integer> cachedNeeds(TechTree t, Source s) {
        if (t != cachedTree || s != cachedSource) {
            cachedNeeds = needs(t, s);
            cachedTree = t;
            cachedSource = s;
        }
        return cachedNeeds;
    }

    /**
     * The share of the top {@code top} ideas (not had, best first) whose needed tier is above {@code pickTier}. Items
     * no recipe or block gives a way to get ({@link #UNREACHABLE}) are not counted as the pickaxe's fault.
     */
    public static Result blocked(TechTree t, Source s, Set<String> have, int pickTier, int top) {
        Map<String, Integer> need = cachedNeeds(t, s);
        List<TechTree.Idea> ideas = t.top(have, Math.max(1, top));
        List<String> names = new ArrayList<>();
        for (TechTree.Idea i : ideas) {
            int n = need.getOrDefault(i.item(), 0);
            if (n != UNREACHABLE && n > pickTier) names.add(i.item().startsWith("minecraft:") ? i.item().substring(10) : i.item());
        }
        double share = ideas.isEmpty() ? 0 : (double) names.size() / ideas.size();
        String terms = String.format(Locale.ROOT, "%d of the top %d ideas need a better pickaxe than tier %d%s",
                names.size(), ideas.size(), pickTier, names.isEmpty() ? "" : " (" + String.join(", ", names) + ")");
        return new Result(names.size(), ideas.size(), share, names, terms);
    }
}
