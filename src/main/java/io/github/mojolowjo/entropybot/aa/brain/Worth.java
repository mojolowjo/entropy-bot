package io.github.mojolowjo.entropybot.aa.brain;

import java.util.Set;

/**
 * AA_ROUND2 4: the PROGRESS rungs' values from models, not hand numbers (were table/furnace 70, spare table 50, base 55,
 * bed 45). A station is worth 40 + 40 x the tech tree's share of what it unlocks (as iron's 40 + 30 x share); a bed
 * 20 + 60 x the night's danger (what it saves tonight, {@link Costs#nightDanger} at the current facts); a base 0.8 x
 * the bed (a shelter without the skip); the spare table half the table. Peaceful: danger 0 -> bed 20, base 16 (built
 * late, from boredom and ideas). Pure (JUnit: WorthTest). Loader notes: none.
 */
public final class Worth {
    private Worth() {}

    /** What a furnace makes that recipes go on from (its share is the best of these in the tree). */
    public static final java.util.List<String> SMELTS = java.util.List.of("iron_ingot", "gold_ingot", "copper_ingot", "glass", "charcoal", "smooth_stone");

    /**
     * 40 + 40 x the share of what the table unlocks: the weighted share of the recipes not had that need a 3x3 grid (more
     * than 4 ingredients). A station is no recipe ingredient, so its own norm in the tree would be 0. No tree yet: 1.
     */
    public static double table(TechTree tree, Set<String> have) {
        if (tree == null || tree.size() == 0) return Tuning.get("stationBase") + Tuning.get("stationShare");
        return Tuning.get("stationBase") + Tuning.get("stationShare") * tree.share(r -> r.slots().size() > 4, have == null ? Set.of() : have);
    }

    /** 40 + 40 x the best tree share among what a furnace makes ({@link #SMELTS}). No tree yet: 1. */
    private static TechTree furnaceTree;
    private static Set<String> furnaceHave;
    private static java.util.List<String> furnaceOuts;
    private static double furnaceBest;

    public static synchronized double furnace(TechTree tree, Set<String> have) {
        if (tree == null || tree.size() == 0) return Tuning.get("stationBase") + Tuning.get("stationShare");
        Set<String> h0 = have == null ? Set.of() : have;
        java.util.List<String> o0 = ItemWorth.smelts();
        if (tree == furnaceTree && o0 == furnaceOuts && h0.equals(furnaceHave)) return Tuning.get("stationBase") + Tuning.get("stationShare") * furnaceBest;   // AA_ROUND4 C review: one tree walk per tree/bag snapshot
        double best = 0;
        java.util.List<String> outs = ItemWorth.smelts();      // AA_ROUND4 C: what the recipes say a furnace makes; SMELTS only without data
        for (String s : outs.isEmpty() ? SMELTS : outs) best = Math.max(best, tree.norm(s, have == null ? Set.of() : have));
        furnaceTree = tree; furnaceHave = Set.copyOf(h0); furnaceOuts = o0; furnaceBest = best;
        return Tuning.get("stationBase") + Tuning.get("stationShare") * best;
    }

    public static double spareTable(TechTree tree, Set<String> have) { return 0.5 * table(tree, have); }

    /** 20 + 60 x danger (danger 0..~1.5; at most 99). */
    public static double bed(double danger) { return Math.min(99, 20 + 60 * Math.max(0, danger)); }

    public static double base(double danger) { return 0.8 * bed(danger); }
}
