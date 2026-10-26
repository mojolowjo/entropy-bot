package io.github.mojolowjo.entropybot.aa.moves;

import java.util.Map;

/**
 * 0.39.z75 (rule 7, project manager; run AI 20:10:05 "collect 2 raw_iron: ... picked up 1 of 2 (1 drop left, unreachable)
 * (got 1 of 2)", AI 20:06:10 the station take, run AC 17:06:21 - each a plain stop): a step that ends short of its count
 * ends with the typed fact "short by N: reason" in the bag slot of what it was for; the bag counts what arrived and the
 * order re-plans for the remainder on the bag and the sources of now (Steps.failOrReplan). Pure (JUnit:
 * Z75UndergroundFuelTest). Loader notes: none.
 */
public final class StepsShort {
    private StepsShort() {}

    /**
     * What a step gets or makes, by need word and count: a collect, a craft, a smelt, a station take, a hunt, a cut (a cut of
     * an unnamed log counts any log, the game's logs tag); null: no count (a walk, a place, an explore, a strip, a mine).
     */
    public static Map<String, Integer> wants(Step s) {
        if (s instanceof Step.ChopTree t && t.log() == null) return Map.of("#" + io.github.mojolowjo.entropybot.aa.NeedChain.LOGS_TAG, Math.max(0, t.logs()));
        Map<String, Integer> m = DryRun.produces(s);
        return m == null || m.isEmpty() ? null : m;
    }

    /** The bag's counts of {@code wants}' words in {@code items} (the bag's stacks); null: none to count, or no bag. */
    public static Map<String, Integer> counts(Map<String, Integer> wants, Map<String, Integer> items) {
        if (wants == null || items == null) return null;
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String k : wants.keySet()) out.put(k, DryRun.count(items, k));
        return out;
    }

    /** The short fact from the step's wants and the bag's counts at its start and now; null: not short, or not known. */
    public static FactChange.Fact of(Map<String, Integer> wants, Map<String, Integer> start, Map<String, Integer> now, String reason) {
        if (wants == null || start == null || now == null) return null;
        for (var e : wants.entrySet()) {
            int arrived = Math.max(0, now.getOrDefault(e.getKey(), 0) - start.getOrDefault(e.getKey(), 0));
            if (arrived >= e.getValue()) continue;
            String word = StepNeeds.sh(e.getKey()).replaceFirst("^#", "");
            return new FactChange.Fact("bag " + word, "short by " + (e.getValue() - arrived) + " of " + e.getValue() + " " + word
                    + " (" + arrived + " arrived): " + (reason == null ? "" : reason.replaceFirst("^error: ", "")));
        }
        return null;
    }
}
