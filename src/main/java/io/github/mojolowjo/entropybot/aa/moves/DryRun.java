package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.39.z61 (design D1 J2 3.4 step 2, JARVIS-1's plan check; run N z54 21:24:13: "craft furnace" found its cobblestone gone
 * only when it ran, after a 48 m walk): before a step starts, the remaining steps are walked on a copy of the bag - each
 * step's uses come off (its plain items, its tag slots by their members, its burn by the fuel table), what it makes goes in
 * ({@link #produces}: a collect's n, a craft's n, a smelt's n, a station taken, a hunt's count - a floor, never more). The
 * first shortage is a readable bag fact ("step 5 (craft furnace) needs 8 of stone_crafting_materials; the bag will hold 6
 * after step 2") that re-plans the order before the step runs, not after it fails.
 * <p>The check only says what it knows: a step whose yield is not known (a mine, a strip, a chop of an unnamed log, an
 * explore, a plan not expanded yet) ends the walk there (nothing after it is checked); a step whose recipe was guessed or
 * whose counts are not loaded (rule 15) is not checked itself. Pure (JUnit: Z61ReservationsTest). Loader notes: none (the
 * tag index and the fuel table through ItemWorth / NeedChain).
 */
public final class DryRun {
    private DryRun() {}

    /** The first shortage: the step (index in the run), the need word, how many it needs and the bag will hold, the fact's words. */
    public record Shortage(int step, String word, int need, int have, String text) {
        /** The fact's key ("bag stone_crafting_materials"), as the bag facts write it. */
        public String key() { return "bag " + StepNeeds.sh(word).replaceFirst("^#", ""); }
    }

    /**
     * The first shortage of {@code steps} from {@code from} on, {@code needs} parallel to {@code steps.subList(from, ...)}, on
     * {@code bag} (item id -> count, the bag now); null: none found (or {@code bag} null: nothing to check).
     */
    public static Shortage check(List<Step> steps, int from, List<StepNeeds> needs, Map<String, Integer> bag) {
        if (bag == null || steps == null || needs == null) return null;
        Map<String, Integer> b = new LinkedHashMap<>();
        for (var e : bag.entrySet()) if (e.getValue() != null && e.getValue() > 0) b.merge(e.getKey(), e.getValue(), Integer::sum);
        int lastChange = -1;                     // the last step that changed the bag (-1: none, "now")
        for (int k = 0; k < needs.size() && from + k < steps.size(); k++) {
            int i = from + k;
            Step s = steps.get(i);
            StepNeeds n = needs.get(k);
            boolean changed = false;
            if (n != null && n.guess() == null && n.countsUnknown() == null) {
                Map<String, Integer> all = new LinkedHashMap<>(n.consumes());
                n.tagUses().forEach((w, c) -> all.merge(w, c, Integer::sum));
                for (var e : all.entrySet()) {
                    int have = count(b, e.getKey());
                    if (have < e.getValue()) return shortage(i, s, e.getKey(), e.getValue(), have, lastChange);
                    take(b, e.getKey(), e.getValue());
                    changed = true;
                }
                if (n.fuelTicks() > 0 && !ItemWorth.fuels().isEmpty()) {
                    long burn = burn(b);
                    if (burn < n.fuelTicks()) return shortage(i, s, Reservations.FUEL, n.fuelTicks(), (int) Math.min(Integer.MAX_VALUE, burn), lastChange);
                    burnOff(b, n.fuelTicks(), laterWords(needs, k + 1));
                    changed = true;
                }
            }
            Map<String, Integer> made = produces(s);
            if (made == null) return null;       // its yield is not known: nothing after it is checked
            for (var e : made.entrySet()) if (e.getValue() > 0) { b.merge(e.getKey(), e.getValue(), Integer::sum); changed = true; }
            if (changed) lastChange = i;
        }
        return null;
    }

    private static Shortage shortage(int i, Step s, String word, int need, int have, int lastChange) {
        String what = word.equals(Reservations.FUEL) ? need + " burn ticks of fuel" : need + " of " + StepNeeds.sh(word).replaceFirst("^#", "");
        String when = lastChange < 0 ? "the bag holds " + have + " now" : "the bag will hold " + have + " after step " + (lastChange + 1);
        return new Shortage(i, word, need, have, "step " + (i + 1) + " (" + s.text() + ") needs " + what + "; " + when);
    }

    /**
     * What a step puts in the bag, by need word (a floor); empty: nothing (a walk, a placement); null: not known (the walk
     * stops there).
     */
    public static Map<String, Integer> produces(Step s) {
        return switch (s) {
            case Step.Collect k -> Map.of(NeedChain.word(k.item()), Math.max(0, k.n()));
            case Step.Craft k -> Map.of(NeedChain.word(k.item()), Math.max(0, k.n()));
            case Step.Smelt k -> Map.of(NeedChain.word(k.item()), Math.max(0, k.n()));
            case Step.TakeStation t -> Map.of(NeedChain.word(t.item()), 1);
            case Step.HuntAt h -> h.forItem() == null ? null : Map.of(NeedChain.word(h.forItem()), Math.max(0, h.forCount()));
            case Step.ChopTree t -> t.log() == null ? null : Map.of(NeedChain.word(t.log()), Math.max(0, t.logs()));
            case Step.Walk w -> Map.of();
            case Step.PlaceNear p -> Map.of();
            default -> null;
        };
    }

    /** How many the bag holds for a need word: its own token, and every entry the word takes (a tag's members). */
    static int count(Map<String, Integer> b, String word) {
        int n = 0;
        for (var e : b.entrySet()) if (e.getValue() > 0 && (e.getKey().equals(word) || NeedChain.gives(word, e.getKey()))) n += e.getValue();
        return n;
    }

    /** Takes {@code n} for {@code word}: its own token first, then the entries it takes in bag order. */
    static void take(Map<String, Integer> b, String word, int n) {
        int left = n;
        List<String> keys = new ArrayList<>();
        if (b.containsKey(word)) keys.add(word);
        for (String k : b.keySet()) if (!keys.contains(k) && NeedChain.gives(word, k)) keys.add(k);
        for (String k : keys) {
            if (left <= 0) break;
            int t = Math.min(left, b.getOrDefault(k, 0));
            if (t > 0) { b.merge(k, -t, Integer::sum); left -= t; }
        }
    }

    /** An entry's burn ticks per item: the fuel table's, a need token's least member burn (0: none). */
    static int burnOf(String entry) {
        Map<String, Integer> fuels = ItemWorth.fuels();
        Integer t = fuels.get(entry);
        if (t != null) return Math.max(0, t);
        if (!entry.startsWith("#") && entry.indexOf('|') < 0) return 0;
        int least = Integer.MAX_VALUE;
        for (String m : NeedChain.membersOf(entry)) least = Math.min(least, Math.max(0, fuels.getOrDefault(StepNeeds.full(m), 0)));
        return least == Integer.MAX_VALUE ? 0 : least;
    }

    private static long burn(Map<String, Integer> b) {
        long s = 0;
        for (var e : b.entrySet()) if (e.getValue() > 0) s += (long) e.getValue() * burnOf(e.getKey());
        return s;
    }

    /** The need words of the steps from {@code k} on (the burn takes what they do not name first). */
    private static List<String> laterWords(List<StepNeeds> needs, int k) {
        List<String> out = new ArrayList<>();
        for (int j = k; j < needs.size(); j++) {
            StepNeeds n = needs.get(j);
            if (n == null) continue;
            out.addAll(n.consumes().keySet());
            out.addAll(n.tagUses().keySet());
        }
        return out;
    }

    /** Burns {@code ticks}: entries no later step names first, the longest burning first. */
    private static void burnOff(Map<String, Integer> b, int ticks, List<String> later) {
        List<String> order = new ArrayList<>();
        for (var e : b.entrySet()) if (e.getValue() > 0 && burnOf(e.getKey()) > 0) order.add(e.getKey());
        order.sort((x, y) -> {
            boolean nx = named(later, x), ny = named(later, y);
            if (nx != ny) return nx ? 1 : -1;
            return Integer.compare(burnOf(y), burnOf(x));
        });
        long left = ticks;
        for (String k : order) {
            if (left <= 0) break;
            int per = burnOf(k);
            int t = (int) Math.min(b.get(k), (left + per - 1) / per);
            b.merge(k, -t, Integer::sum);
            left -= (long) t * per;
        }
    }

    private static boolean named(List<String> words, String entry) {
        for (String w : words) if (w.equals(entry) || NeedChain.gives(w, entry)) return true;
        return false;
    }
}
