package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.NeedChain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.39.z73 (design D2 3.4, Job D): what a step of a plan is FOR - the (item, n) it contributes, from its own data: a collect /
 * craft / smelt / cook / take / hunt for an item its item and count, a tree its logs (the game's logs tag when no log is
 * named), a strip its amounts. A leg (a walk, a station walk, a walk up, the strip walk, the band walk, the walk back, a
 * placement) serves what the next non-leg step serves. Null: nothing the step's data names (a mine of a family, an explore:
 * not known, rule 15). No name list (rule 4): only the step records' own fields and {@link DryRun#produces}. Pure (JUnit:
 * Z73RepairTest). Loader notes: none.
 */
public final class Serves {
    private Serves() {}

    /** A leg: a step that only takes the bot (or a station) to where the next step works. */
    public static boolean leg(Step s) {
        return s instanceof Step.Walk || s instanceof Step.ToStrip || s instanceof Step.ToBand || s instanceof Step.ToStation
                || s instanceof Step.ToSurface || s instanceof Step.PlaceNear || s instanceof Step.StripBack;
    }

    /** The index of the step {@code i} serves: itself, or for a leg the next non-leg step; -1: a leg with nothing after it. */
    public static int servedIndex(List<? extends Step> steps, int i) {
        for (int k = i; k < steps.size(); k++) if (!leg(steps.get(k))) return k;
        return -1;
    }

    /** What step {@code i} of {@code steps} serves, need word -> n; null: not known (or a leg with nothing after it). */
    public static Map<String, Integer> of(List<? extends Step> steps, int i) {
        int k = servedIndex(steps, i);
        return k < 0 ? null : own(steps.get(k));
    }

    /** What a non-leg step contributes by its own data; null: not known. */
    static Map<String, Integer> own(Step s) {
        return switch (s) {
            case Step.Strip st -> {
                if (st.amounts().isEmpty()) yield null;      // until stopped: no count
                Map<String, Integer> out = new LinkedHashMap<>();
                for (var e : new java.util.TreeMap<>(st.amounts()).entrySet()) out.put(NeedChain.word(e.getKey()), e.getValue());
                yield out;
            }
            case Step.ChopTree t -> Map.of(NeedChain.word(t.log() != null ? t.log() : "#minecraft:logs"), Math.max(0, t.logs()));   // the game's logs tag
            case Step.Cook k -> null;                        // the raw item is named, not what it cooks into
            default -> {
                Map<String, Integer> m = DryRun.produces(s);
                yield m == null || m.isEmpty() ? null : m;
            }
        };
    }
}
