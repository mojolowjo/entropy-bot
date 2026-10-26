package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.NeedChain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.39.z73 (design D2 3.4, Job D; van der Krogt and de Weerdt 2005: remove the broken part, plan the gap; run P 23:50:29 P7:
 * the strip's pickaxe broke and the whole chain was re-chosen into an explore): two priced candidates for a failed step.
 * REPAIR = the gap (the planner's chain for what the failed step served, on the facts of now) + the kept tail (the old steps
 * after the served step, at their old rough prices - NeedChain.Chain#stepSeconds); FULL = today's whole re-plan. The kept
 * tail stands only if {@link DryRun} (the one simulator, D1's) walks gap + tail on the real bag with no shortfall.
 * Pick (rule 1, by price): REPAIR unless FULL is cheaper by {@code replan.switchMarginS} (the margin re-prices use; no new
 * number); a gap that does not stand (no way, or an explore), a tail whose prices are not known, a shortfall, or a bag not
 * readable (rule 15) -> FULL. Every pick is said with both prices (rule 7). Pure (JUnit: Z73RepairTest). Loader notes: none.
 */
public final class Repair {
    private Repair() {}

    /**
     * Where the remaining steps split: {@code served} the index of the step the failed one (index 0) was for, its (item, n),
     * the tail after it; {@code why} non-null: no repair (nothing known that it served).
     */
    public record Split(int served, String item, int n, String why) {}

    /** The split of {@code left} (the failed step first). */
    public static Split split(List<Step> left) {
        if (left == null || left.isEmpty()) return new Split(-1, null, 0, "no steps left");
        int k = Serves.servedIndex(left, 0);
        if (k < 0) return new Split(-1, null, 0, "the failed step leads to nothing after it");
        Map<String, Integer> m = Serves.of(left, 0);
        if (m == null) return new Split(k, null, 0, "what " + left.get(k).text() + " is for is not known");
        if (m.size() != 1) return new Split(k, null, 0, left.get(k).text() + " serves " + m.size() + " things");
        var e = m.entrySet().iterator().next();
        return new Split(k, e.getKey(), e.getValue(), null);
    }

    /** The pick: REPAIR's steps and uses (null steps: FULL), the line said, both prices (NaN: not known). */
    public record Pick(List<Step> steps, List<Map<String, Integer>> uses, double repairS, double fullS, String line, int kept, List<Double> gapSeconds) {
        public boolean repair() { return steps != null; }
    }

    /**
     * REPAIR or FULL. {@code left}: the remaining steps (the failed one first) with {@code leftUses} parallel;
     * {@code gap}: the planner's chain for the split's (item, n) (null: none asked); {@code tailSeconds}: the kept tail's old
     * seconds (NaN: not known); {@code full}: the whole re-plan (null: no facts); {@code bag}: the bag's stacks (item id ->
     * count; null: not readable); {@code margin}: replan.switchMarginS.
     */
    public static Pick pick(List<Step> left, List<Map<String, Integer>> leftUses, Split sp, NeedChain.Chain gap, double tailSeconds,
                            NeedChain.Chain full, Map<String, Integer> bag, double margin) {
        boolean fullOk = full != null && full.ok() && !full.steps().isEmpty();
        double fullS = fullOk ? full.seconds() : Double.NaN;
        String fullText = fullOk ? "full re-plan " + s(fullS) + (full.explore() ? " (ends in an explore)" : "") : "no full re-plan stands";
        if (sp == null || sp.why() != null) return full("no repair: " + (sp == null ? "no split" : sp.why()), fullText, fullS);
        if (gap == null || !gap.ok() || gap.steps().isEmpty()) return full("no repair: the gap for " + sp.n() + " " + StepNeeds.sh(sp.item()) + " does not stand" + (gap == null ? "" : " (" + gap.why() + ")"), fullText, fullS);
        if (gap.explore()) return full("no repair: the gap for " + sp.n() + " " + StepNeeds.sh(sp.item()) + " ends in an explore", fullText, fullS);
        if (Double.isNaN(tailSeconds)) return full("no repair: the kept tail's prices are not known", fullText, fullS);
        List<Step> tail = left.subList(sp.served() + 1, left.size());
        List<Step> steps = new ArrayList<>(gap.steps());
        steps.addAll(tail);
        List<Map<String, Integer>> uses = new ArrayList<>();
        for (int i = 0; i < gap.steps().size(); i++) uses.add(i < gap.uses().size() ? gap.uses().get(i) : null);
        for (int i = sp.served() + 1; i < left.size(); i++) uses.add(leftUses != null && i < leftUses.size() ? leftUses.get(i) : null);
        if (bag == null) return full("no repair: the bag is not readable for the dry run of the kept tail", fullText, fullS);
        List<StepNeeds> needs = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) needs.add(StepNeeds.of(steps.get(i)).withUses(uses.get(i)));
        DryRun.Outcome dry = DryRun.look(steps, 0, needs, bag, true);
        if (dry.shortage() != null) return full("no repair: the kept tail runs short - " + dry.shortage().text(), fullText, fullS);
        double repairS = gap.seconds() + tailSeconds;
        String gapText = gap.steps().size() + " step" + (gap.steps().size() == 1 ? "" : "s") + " (" + Step.label(gap.steps()) + ")";
        if (fullOk && !full.explore() && repairS - fullS >= margin)
            return new Pick(null, null, repairS, fullS, fullText + ": cheaper than the repair " + s(repairS) + " by " + s(repairS - fullS)
                    + " (replan.switchMarginS " + Math.round(margin) + ")", tail.size(), List.of());
        return new Pick(steps, uses, repairS, fullS, "repaired: gap " + gapText + " " + s(gap.seconds()) + ", kept " + tail.size() + " step"
                + (tail.size() == 1 ? "" : "s") + " (" + s(tailSeconds) + "); repair " + s(repairS) + ", " + fullText, tail.size(), gap.stepSeconds());
    }

    private static Pick full(String why, String fullText, double fullS) {
        return new Pick(null, null, Double.NaN, fullS, fullText + " (" + why + ")", 0, List.of());
    }

    static String s(double seconds) { return String.format(Locale.ROOT, "%.1f s", seconds); }
}
