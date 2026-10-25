package io.github.mojolowjo.entropybot.aa.moves;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.39.z70 (design D2 3.1, Job A; run M 18:58-19:00: ~130 "priced again" lines and 4 restarts with no measure of how much
 * each one changed): how much a switch changed the plan - the old remaining steps against the new ones by {@link StepId}.
 * {@code kept} = the common prefix + the common suffix + the multiset overlap of the middle; {@code changed} = the removed
 * + the added steps (Fox et al. 2006: plan distance = the differing actions). Logged on every switch (a failure re-plan and
 * a re-price) and summed per order for {@code aa status}. A measure only: it decides nothing. Pure (JUnit: Z70StepIdTest).
 * Loader notes: none.
 */
public record PlanDiff(int kept, List<String> removed, List<String> added) {
    public PlanDiff {
        removed = List.copyOf(removed);
        added = List.copyOf(added);
    }

    /** Removed + added steps. */
    public int changed() { return removed.size() + added.size(); }

    /** The diff of {@code old} (the steps left of the old plan) and {@code neu} (the new plan's steps). */
    public static PlanDiff of(List<? extends Step> old, List<? extends Step> neu) {
        List<? extends Step> a = old == null ? List.of() : old, b = neu == null ? List.of() : neu;
        List<StepId> x = StepId.of(a), y = StepId.of(b);
        int p = 0;
        while (p < x.size() && p < y.size() && java.util.Objects.equals(x.get(p), y.get(p))) p++;
        int s = 0;
        while (s < x.size() - p && s < y.size() - p && java.util.Objects.equals(x.get(x.size() - 1 - s), y.get(y.size() - 1 - s))) s++;
        // the middle as two multisets: a step whose identity the other side still holds is kept, the rest changed
        List<String> removed = middle(x, y, a, p, s), added = middle(y, x, b, p, s);
        int overlap = (x.size() - p - s) - removed.size();
        return new PlanDiff(p + s + overlap, removed, added);
    }

    /** The lines of {@code mine}'s middle steps that {@code other}'s middle does not hold (counted: each copy once). */
    private static List<String> middle(List<StepId> mine, List<StepId> other, List<? extends Step> steps, int p, int s) {
        Map<StepId, Integer> pool = new HashMap<>();
        for (int i = p; i < other.size() - s; i++) pool.merge(other.get(i), 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (int i = p; i < mine.size() - s; i++) {
            int n = pool.getOrDefault(mine.get(i), 0);
            if (n > 0) pool.put(mine.get(i), n - 1);
            else out.add(steps.get(i).text());
        }
        return out;
    }

    /** "re-plan 2: kept 3, changed 2 (removed: craft a 1; added: craft b 1)"; {@code k}: the order's re-plan number. */
    public String line(int k) {
        return "re-plan " + k + ": kept " + kept + ", changed " + changed()
                + (changed() == 0 ? "" : " (removed: " + (removed.isEmpty() ? "none" : String.join(", ", removed))
                + "; added: " + (added.isEmpty() ? "none" : String.join(", ", added)) + ")");
    }
}
