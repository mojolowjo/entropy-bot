package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.39.z73 (design D2 3.4, Job D; triangle-table kernels, PLANEX - research round 4 #2): for a run's steps, what the suffix
 * from step j needs from the world, and the check "is a later suffix already possible from the bag of now?".
 * <ul>
 * <li>The suffix's needs: its consumes (plain items and counted slots, {@link StepNeeds}) less what its own earlier steps
 *     make ({@link DryRun#produces}); its tool: the highest tier a suffix step needs that no earlier suffix step makes.</li>
 * <li>Skipping steps cur..j-1 keeps what they were for: the bag must hold, per need word, the larger of the suffix's need
 *     and what the skipped steps would have left in it (their net output - a spare tool, a table crafted for later), and a
 *     tool the suffix uses that the skipped steps make counts one more of it (the one the suffix wears).</li>
 * <li>A skipped step's output must be known: an explore, a mine, a strip, an unexpanded plan is never skipped past (rule
 *     15); a placement is never skipped (its effect is a station in the world, which the bag cannot show); a leg only
 *     with the step it leads to.</li>
 * </ul>
 * Needs not readable (a guessed recipe, counts not loaded) make the kernel "not known", never "met". The check decides
 * nothing by kind: it reads the steps' own data and the bag (rule 1, rule 4). Pure (JUnit: Z73RepairTest). Loader notes:
 * none (MiningTiers' tool tiers and the tag index through NeedChain).
 */
public final class Kernels {
    private Kernels() {}

    /**
     * A skip: run step {@code to} next; {@code said}: one line per skipped step ("craft stick 4 skipped: 4 stick already in
     * the bag"). 0.39.z73 review 2: {@code to} -1 with {@code noCheck}: the check could not run (the bag not readable, rule
     * 7 / 15: said, never a quiet "nothing holds").
     */
    public record Skip(int to, List<String> said, String noCheck) {
        public Skip(int to, List<String> said) { this(to, said, null); }

        static Skip notChecked(String why) { return new Skip(-1, List.of(), why); }

        public boolean skips() { return to >= 0; }
    }

    /**
     * The latest j in (cur, steps.size()) whose suffix is possible from {@code bag} now with the skipped steps' outputs kept;
     * null: none (the run goes on at {@code cur}). {@code needs}: parallel to {@code steps.subList(cur, ...)} (null entries:
     * not readable). The last step is never skipped (it is what the run is for). {@code bag} null or without its stacks: null.
     */
    public static Skip latest(List<Step> steps, int cur, List<StepNeeds> needs, StepNeeds.Bag bag) {
        if (bag == null || steps == null || needs == null || cur < 0) return null;     // these hands keep no bag: nothing to check
        Map<String, Integer> items;
        try {
            items = bag.items();
        } catch (RuntimeException e) {
            return Skip.notChecked("the bag could not be read: " + e);     // rule 15: never read as "nothing holds"
        }
        if (items == null) return Skip.notChecked("the bag's stacks are not listed");
        for (int j = steps.size() - 1; j > cur; j--) {
            if (!skippable(steps, cur, j)) continue;
            Map<String, Integer> need = required(steps, cur, j, needs);
            if (need == null) continue;
            String[] tool = toolNeed(steps, cur, j, needs);
            if (tool == null) continue;          // not known
            if (!tool[0].isEmpty()) {            // a tool the skipped steps make and the suffix wears: one more of it (the spare kept)
                int t = Integer.parseInt(tool[1]);
                for (int i = cur; i < j; i++) {
                    Map<String, Integer> p = DryRun.produces(steps.get(i));
                    if (p != null) for (String w : p.keySet()) if (MiningTiers.toolTier(w, tool[0]) >= t) need.merge(w, 1, Integer::sum);
                }
            }
            boolean ok = holds(need, items);
            if (ok && !tool[0].isEmpty() && bag.bestTier(tool[0]) < Integer.parseInt(tool[1])) ok = false;
            if (ok) return new Skip(j, said(steps, cur, j, items));
        }
        return null;
    }

    /**
     * The bag holds every need at once: each taken off a copy in turn (plain items first, then counted slots), so two words
     * one pile answers (9 cobblestone for a pickaxe's 3 and a furnace's 8) are never both met by the same blocks.
     */
    static boolean holds(Map<String, Integer> need, Map<String, Integer> items) {
        Map<String, Integer> b = new LinkedHashMap<>();
        for (var e : items.entrySet()) if (e.getValue() != null && e.getValue() > 0) b.merge(e.getKey(), e.getValue(), Integer::sum);
        List<Map.Entry<String, Integer>> order = new ArrayList<>(need.entrySet());
        order.sort((x, y) -> Boolean.compare(!io.github.mojolowjo.entropybot.aa.NeedChain.plain(x.getKey()), !io.github.mojolowjo.entropybot.aa.NeedChain.plain(y.getKey())));
        for (var e : order) {
            if (DryRun.count(b, e.getKey()) < e.getValue()) return false;
            DryRun.take(b, e.getKey(), e.getValue());
        }
        return true;
    }

    /** Steps cur..j-1 may be skipped: each one's output known, no placement, a leg only with the step it leads to. */
    static boolean skippable(List<Step> steps, int cur, int j) {
        for (int i = cur; i < j; i++) {
            Step s = steps.get(i);
            if (s instanceof Step.PlaceNear || s instanceof Step.Build) return false;
            if (Serves.leg(s)) {
                int k = Serves.servedIndex(steps, i);
                if (k < 0 || k >= j) return false;  // it leads to a step that still runs
                continue;
            }
            Map<String, Integer> m = DryRun.produces(s);
            if (m == null || m.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Per need word what the bag must hold to start at j instead of cur: max(the suffix's need from the bag, the skipped
     * steps' net output). Null: a suffix step's needs are not readable (rule 15).
     */
    static Map<String, Integer> required(List<Step> steps, int cur, int j, List<StepNeeds> needs) {
        // the skipped steps' net output: what they make less what they use of their own making
        Map<String, Integer> made = new LinkedHashMap<>();
        for (int i = cur; i < j; i++) {
            StepNeeds n = at(needs, i - cur);
            if (n != null) for (var e : uses(n).entrySet()) DryRun.take(made, e.getKey(), Math.min(DryRun.count(made, e.getKey()), e.getValue()));
            Map<String, Integer> p = DryRun.produces(steps.get(i));
            if (p != null) p.forEach((w, c) -> made.merge(w, c, Integer::sum));
        }
        // the suffix's need from the bag: its uses less what its own earlier steps make
        Map<String, Integer> avail = new LinkedHashMap<>(), need = new LinkedHashMap<>();
        for (int i = j; i < steps.size(); i++) {
            StepNeeds n = at(needs, i - cur);
            if (n == null || n.guess() != null || n.countsUnknown() != null) return null;
            for (var e : uses(n).entrySet()) {
                int have = DryRun.count(avail, e.getKey()), take = Math.min(have, e.getValue());
                DryRun.take(avail, e.getKey(), take);
                if (e.getValue() > take) need.merge(e.getKey(), e.getValue() - take, Integer::sum);
            }
            Map<String, Integer> p = DryRun.produces(steps.get(i));
            if (p != null) p.forEach((w, c) -> avail.merge(w, c, Integer::sum));
        }
        Map<String, Integer> out = new LinkedHashMap<>(need);
        made.forEach((w, c) -> { if (c > 0) out.merge(w, c, Math::max); });
        return out;
    }

    /**
     * The suffix's tool: {kind, tier} ("" kind: none needed or made by the suffix itself); a tool the skipped steps make that
     * meets it is counted in the bag as one more of that item (it is worn by the suffix). Null: not known.
     */
    static String[] toolNeed(List<Step> steps, int cur, int j, List<StepNeeds> needs) {
        String kind = "";
        int tier = 0;
        for (int i = j; i < steps.size(); i++) {
            StepNeeds n = at(needs, i - cur);
            if (n == null) return null;
            if (n.hasTool() && n.minTier() > tier && !madeBefore(steps, j, i, n.toolKind(), n.minTier())) { kind = n.toolKind(); tier = n.minTier(); }
        }
        return new String[]{kind, String.valueOf(tier)};
    }

    /** A step from {@code from} up to {@code before} makes a tool of {@code kind} meeting {@code tier}. */
    private static boolean madeBefore(List<Step> steps, int from, int before, String kind, int tier) {
        for (int i = from; i < before; i++) {
            Map<String, Integer> p = DryRun.produces(steps.get(i));
            if (p != null) for (String w : p.keySet()) if (MiningTiers.toolTier(w, kind) >= tier) return true;
        }
        return false;
    }

    /** A step's counted uses: its plain consumes and its counted slots. */
    static Map<String, Integer> uses(StepNeeds n) {
        Map<String, Integer> all = new LinkedHashMap<>(n.consumes());
        n.tagUses().forEach((w, c) -> all.merge(w, c, Integer::sum));
        return all;
    }

    private static StepNeeds at(List<StepNeeds> needs, int k) { return k >= 0 && k < needs.size() ? needs.get(k) : null; }

    /** "craft stick 4 skipped: 4 stick already in the bag"; a leg: "goto ... skipped: it led to step k (skipped)". */
    static List<String> said(List<Step> steps, int cur, int j, Map<String, Integer> items) {
        List<String> out = new ArrayList<>();
        for (int i = cur; i < j; i++) {
            Step s = steps.get(i);
            if (Serves.leg(s)) { out.add(s.text() + " skipped: it led to step " + (Serves.servedIndex(steps, i) + 1) + " (skipped)"); continue; }
            List<String> have = new ArrayList<>();
            Map<String, Integer> p = DryRun.produces(s);
            if (p != null) for (String w : p.keySet()) have.add(DryRun.count(items, w) + " " + StepNeeds.sh(w));
            out.add(s.text() + " skipped: " + String.join(", ", have) + " already in the bag");
        }
        return out;
    }
}
