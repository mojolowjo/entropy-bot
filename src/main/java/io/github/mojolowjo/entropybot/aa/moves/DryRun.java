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
        return look(steps, from, needs, bag, true).shortage();
    }

    /**
     * 0.39.z62 item 3b (z61 review: "no shortage" read the same whether the walk checked every step or stopped at the first
     * mine): the dry run's answer with where it stopped or what it skipped. {@code shortage}: the first one (null: none
     * found); {@code note}: what was and was not checked, in aa why's words ("checked steps 3-5; stopped at step 6 (strip
     * ...): its yield is not known, nothing after it checked; step 4 (craft ...) not checked: recipe guessed (...)").
     * FV fix (run FV 23:44-23:46: a repair's gap "cut 1" of unnamed wood before a kept "craft oak_planks 4" stood, the cut
     * gave acacia, the craft was refused twice and the order parked): {@code stoppedAt} the index (in the run) of the step
     * whose yield is not known, where the walk stopped (-1: it walked to the end), {@code stop} its words - so a caller can
     * tell "no shortage found" from "no shortage looked for" (rule 15).
     */
    public record Outcome(Shortage shortage, String note, int stoppedAt, String stop) {
        Outcome(Shortage shortage, String note) { this(shortage, note, -1, null); }

        /** The walk reached {@code last} (index in the run) with every step before it checked or skipped: nothing stopped it short of there. */
        public boolean walkedTo(int last) { return stoppedAt < 0 || stoppedAt >= last; }
    }

    /**
     * As {@link #check}, with the note. {@code judgeFirst} false (0.39.z62 item 3a, z61 review: a client bag a tick behind a
     * pickup re-planned a healthy chain): the running step's own needs are not judged here - its tool and items are checked
     * by its start (toolLack) and its end (StepNeeds.itemShort) - only taken off the copy as far as the bag holds them; the
     * later steps are judged.
     */
    public static Outcome look(List<Step> steps, int from, List<StepNeeds> needs, Map<String, Integer> bag, boolean judgeFirst) {
        if (bag == null) return new Outcome(null, "not checked: no bag in these hands");
        if (steps == null || needs == null) return new Outcome(null, "not checked: no steps");
        Map<String, Integer> b = new LinkedHashMap<>();
        for (var e : bag.entrySet()) if (e.getValue() != null && e.getValue() > 0) b.merge(e.getKey(), e.getValue(), Integer::sum);
        int lastChange = -1;                     // the last step that changed the bag (-1: none, "now")
        List<String> skipped = new ArrayList<>();
        int last = from - 1;
        for (int k = 0; k < needs.size() && from + k < steps.size(); k++) {
            int i = from + k;
            Step s = steps.get(i);
            StepNeeds n = needs.get(k);
            boolean judged = judgeFirst || k > 0;
            boolean changed = false;
            if (n == null) skipped.add("step " + (i + 1) + " (" + s.text() + ") not checked: its needs could not be read");
            else if (n.guess() != null) skipped.add("step " + (i + 1) + " (" + s.text() + ") not checked: " + n.guess());
            else if (n.countsUnknown() != null) skipped.add("step " + (i + 1) + " (" + s.text() + ") not checked: counts not loaded (" + n.countsUnknown() + ")");
            else {
                Map<String, Integer> all = new LinkedHashMap<>(n.consumes());
                n.tagUses().forEach((w, c) -> all.merge(w, c, Integer::sum));
                for (var e : all.entrySet()) {
                    int have = count(b, e.getKey());
                    if (have < e.getValue() && judged) return new Outcome(shortage(i, s, e.getKey(), e.getValue(), have, lastChange), null);
                    take(b, e.getKey(), Math.min(have, e.getValue()));
                    changed = true;
                }
                if (n.fuelTicks() > 0 && !ItemWorth.fuels().isEmpty()) {
                    // 0.39.z75 item 2 (run AF 18:21:43-46: the bag's mix summed to the smelt's ticks, the furnace job found
                    // "nothing left in the bag burns"): the burn is ONE kind that covers it, never a tool - the job's rule
                    String kind = oneKind(b, n.fuelTicks(), laterWords(needs, k + 1));
                    if (kind == null && judged) return new Outcome(shortage(i, s, Reservations.FUEL, n.fuelTicks(), (int) Math.min(Integer.MAX_VALUE, oneKindTicks(b)), lastChange), null);
                    if (kind != null) take(b, kind, (n.fuelTicks() + burnOf(kind) - 1) / burnOf(kind));
                    else burnOff(b, (int) Math.min(n.fuelTicks(), burn(b)), laterWords(needs, k + 1));
                    changed = true;
                }
            }
            Map<String, Integer> made = produces(s);
            if (made == null) {                  // its yield is not known: nothing after it is checked
                String stop = "stopped at step " + (i + 1) + " (" + s.text() + "): its yield is not known, nothing after it checked";
                return new Outcome(null, noteOf(from, last, judgeFirst, stop, skipped), i, stop);
            }
            for (var e : made.entrySet()) if (e.getValue() > 0) { b.merge(e.getKey(), e.getValue(), Integer::sum); changed = true; }
            if (changed) lastChange = i;
            last = i;
        }
        return new Outcome(null, noteOf(from, last, judgeFirst, null, skipped));
    }

    private static String noteOf(int from, int last, boolean judgeFirst, String stop, List<String> skipped) {
        int first = judgeFirst ? from : from + 1;
        List<String> parts = new ArrayList<>();
        parts.add(last < first ? "no later step checked" : "checked steps " + (first + 1) + (last > first ? "-" + (last + 1) : ""));
        if (!judgeFirst) parts.add("step " + (from + 1) + " (running) left to its own checks");
        if (stop != null) parts.add(stop);
        parts.addAll(skipped);
        return String.join("; ", parts);
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
            case Step.ToStation w -> Map.of();             // 0.39.z65: a walk
            case Step.ToCave w -> Map.of();                // J6b run EF: a walk
            case Step.ToSurface w -> Map.of();             // 0.39.z68: a walk
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

    /**
     * 0.39.z75 item 2: the bag entry the furnace job would burn for {@code ticks} - one kind whose count covers them, never a
     * damageable item (CraftPlanner.pickFuel's rule); one no later step names first, then the one burning longest a piece.
     * Null: no one kind covers them.
     */
    static String oneKind(Map<String, Integer> b, int ticks, List<String> later) {
        String best = null;
        for (var e : b.entrySet()) {
            int per = burnOf(e.getKey());
            if (e.getValue() <= 0 || per <= 0 || ItemWorth.damageable(e.getKey()) || (long) e.getValue() * per < ticks) continue;
            if (best == null) { best = e.getKey(); continue; }
            boolean nb = named(later, best), ne = named(later, e.getKey());
            if (nb != ne ? nb : per > burnOf(best)) best = e.getKey();
        }
        return best;
    }

    /** 0.39.z75 item 2: the most burn ticks one kind of the bag gives (never a damageable item). */
    static long oneKindTicks(Map<String, Integer> b) {
        long best = 0;
        for (var e : b.entrySet()) if (e.getValue() > 0 && !ItemWorth.damageable(e.getKey())) best = Math.max(best, (long) e.getValue() * burnOf(e.getKey()));
        return best;
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
