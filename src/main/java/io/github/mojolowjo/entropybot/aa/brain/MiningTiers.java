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
            int bt = s.blockTier(id), dt = s.dropTier(id);
            int base = bt >= 0 && dt >= 0 ? Math.min(bt, dt) : Math.max(bt, dt);       // either way of getting it from a block
            need.put(id, base >= 0 ? base : made.contains(id) ? UNREACHABLE : 0);
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
        pickTier = Math.max(0, pickTier);                  // no pickaxe = 0, never a negative
        Map<String, Integer> need = cachedNeeds(t, s);
        List<TechTree.Idea> ideas = t.top(have, Math.max(1, top));
        // regression o1: the counted set is every reachable item that needs ANY pickaxe among the top ideas and their mined
        // inputs (the cobble for a furnace, the ore of an ingot); the share is the part of it above the current tier. A
        // fresh start is near 1 (everything pickaxe-bound waits on the first pickaxe), falling as tiers open.
        java.util.LinkedHashSet<String> pool = new java.util.LinkedHashSet<>();
        Set<String> made = new HashSet<>();
        for (TechTree.Recipe r : t.recipes()) made.add(r.result());
        Set<String> h = new HashSet<>();
        for (String x : have) h.add(TechTree.id(x));
        for (TechTree.Idea i : ideas) {
            pool.add(i.item());
            for (TechTree.Recipe r : t.recipes()) {
                if (!r.result().equals(i.item())) continue;
                for (Set<String> slot : r.slots()) {
                    String bestAlt = null;
                    int best = UNREACHABLE;
                    for (String alt : slot) { int n = need.getOrDefault(alt, 0); if (bestAlt == null || n < best) { best = n; bestAlt = alt; } }
                    if (bestAlt != null && !made.contains(bestAlt) && !h.contains(bestAlt)) pool.add(bestAlt);   // a mined input
                }
            }
        }
        List<String> names = new ArrayList<>();
        int of = 0;
        for (String id : pool) {
            int n = need.getOrDefault(id, 0);
            if (n == UNREACHABLE || n < 1) continue;           // needs no pickaxe, or no way known: not the pickaxe's share
            of++;
            if (n > pickTier) names.add(id.startsWith("minecraft:") ? id.substring(10) : id);
        }
        double share = of == 0 ? 0 : (double) names.size() / of;
        String terms = String.format(Locale.ROOT, "%d of %d pickaxe-bound next items (top %d ideas + mined inputs) need better than tier %d%s",
                names.size(), of, ideas.size(), pickTier, names.isEmpty() ? "" : " (" + String.join(", ", names) + ")");
        return new Result(names.size(), of, share, names, terms);
    }
}
