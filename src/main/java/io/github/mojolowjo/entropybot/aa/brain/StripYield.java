package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 0.39.z77 strip (manager's ruling 2026-10-26 ~17:40; runs CK 16:50:31-17:00:31: "strip ... for coal 1, iron 3: timed out
 * after 600 s (... 249 blocks, 5 clusters mined (iron 27))", CF 17:11:31 "112 blocks, 0 clusters mined" to the 600 s cap,
 * CJ 17:25:25 102 cells with no cluster): a strip's amounts are per family. A family that is met leaves the strip (its ores
 * out of the fork's set, {@link #liveOres}); the families left keep the strip digging only while the strip is still their
 * cheapest way, by the planner's own prices ({@link #check}): the time this strip needs for them at the yield it measured
 * against the price of another way for them - the planner's best other way for each family (the strip way's rivals in its
 * choice, or for a playbook descent the family priced with no strip: the charcoal for the coal, a seen vein, a cave;
 * {@link #other}), or the strip way's own price for a tunnel from the feet in another heading. No knob: the prior is the
 * band's own prediction counted as one ore in its predicted cells; the check runs on each new tunnel cell (a new fact).
 * The planner's facts are kept per strip key and family, the last price kept (a re-price of the order refreshes them).
 * Pure (JUnit: Z77StripTest). Loader notes: none (plain values; StripStep reads the fork's IStripProcess.Progress).
 */
public final class StripYield {
    private StripYield() {}

    /**
     * The planner's facts for one family of one strip: the band's ore a chunk slab at the strip's Y ({@code perSlab}), the
     * seconds one tunnel cell costs ({@code cellS}: TUNNEL_HEIGHT breaks of the host), and the best other way's seconds for
     * the family's lack ({@code otherS}; NaN: none priced - rule 15, not known is never cheaper), its words and whether it is
     * itself a strip (another heading or fresh ground: the end then holds the heading, not the ores).
     */
    public record Fact(double perSlab, double cellS, double otherS, String other, boolean otherStrip) {
        public Fact withOther(double s, String w, boolean strip) { return new Fact(perSlab, cellS, s, w, strip); }
    }

    private static final Map<String, Map<String, Fact>> FACTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX = 256;   // structural: a bound on strips priced in one session (as StripOrder's predictions), not a tuning

    private static Map<String, Fact> of(String key) {
        if (FACTS.size() >= MAX && !FACTS.containsKey(key)) FACTS.clear();
        return FACTS.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>());
    }

    /** The strip pricing's own facts for {@code family} of strip {@code key} (the other way kept as it was). */
    public static void priced(String key, String family, double perSlab, double cellS) {
        if (key == null || family == null) return;
        Map<String, Fact> m = of(key);
        Fact was = m.get(family);
        m.put(family, new Fact(perSlab, cellS, was == null ? Double.NaN : was.otherS(), was == null ? null : was.other(), was != null && was.otherStrip()));
    }

    /** The planner's best other way for {@code family} of strip {@code key}: its seconds (NaN: none priced now) and words. */
    public static void other(String key, String family, double seconds, String words, boolean strip) {
        if (key == null || family == null) return;
        Map<String, Fact> m = of(key);
        Fact was = m.get(family);
        m.put(family, was == null ? new Fact(Double.NaN, Double.NaN, seconds, words, strip) : was.withOther(seconds, words, strip));
    }

    /** The facts kept for strip {@code key} (family -> fact); empty: none priced in this session. */
    public static Map<String, Fact> facts(String key) {
        Map<String, Fact> m = key == null ? null : FACTS.get(key);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    public static void clear() { FACTS.clear(); }

    /**
     * The strip's ores whose family still keeps it digging: every ore whose family carries no amount (an owner's "until the
     * bag is full" ore), or whose amount is not met yet by {@code picked}; sorted as given. {@code family}: an ore id -> its
     * count key (StripOrder.countKey). All met: empty (the end rule ends the strip then).
     */
    public static List<String> liveOres(List<String> ores, Map<String, Integer> amounts, Map<String, Integer> picked, Function<String, String> family) {
        List<String> out = new ArrayList<>();
        for (String o : ores) {
            String f = family.apply(o);
            Integer a = amounts.get(f);
            if (a == null || picked.getOrDefault(f, 0) < a) out.add(o);
        }
        return out;
    }

    /** The cells a tunnel from the feet in {@code heading} would dig for {@code left} ore of {@code family} (the strip way's own estimate); negative: not known. */
    @FunctionalInterface
    public interface HeadingCells {
        int cells(String heading, String family, int left);
    }

    /** The check's verdict: end the strip, {@code why} the words; {@code heading}: hold the heading at this Y (else the strip's ores). */
    public record End(String why, boolean heading) {}

    /**
     * The per-family check, on each new cell while the strip runs. {@code amounts}: family -> the strip's amount; {@code
     * picked}: family -> drops found in this tunnel; {@code cells}: the tunnel's cells dug; {@code predicted}: family -> the
     * band's ore a cell the price used; {@code facts}: the planner's facts; {@code heading}: the strip's; {@code headings}:
     * {@link HeadingCells} for a tunnel from the feet in another heading (null: not offered).
     * <p>The strip's time for the families left: one tunnel serves them all, so the most any of them needs - left x the
     * cells an ore it measured x the seconds a cell, where the cells an ore measured = (cells + the predicted cells an ore)
     * / (found + 1) (structural: the band's prediction counts as one ore found in its own cells, so the first cells move
     * the estimate little and a long dry tunnel moves it far). The other ways: (a) each family by its own best other way,
     * summed (every family left must have one priced); (b) one tunnel from the feet in another heading, the strip way's
     * own price (the band x the areas seen along it). The strip ends when the cheaper of them is cheaper than going on;
     * a family with no prediction or no cell price kept: not known, the strip runs on (rule 15). Null: go on.
     * <p>0.39.z77 strip 2 (manager's ruling 2026-10-26 ~19:45; run CS 19:32:56: "iron 0 of 3 after 7 cells (... iron 0 in 7
     * cells against the band's 1 in 57 -> 648.4 s more here; cheaper: a strip from here east (173 cells ...) 588.2 s)" - 0 in
     * 7 is what 1 in 57 predicts): the early end fires only on a real sample - every family left has dug at least the band's
     * own cells an ore ({@code 1 / predicted}) and found fewer ore than the band predicts for those cells; before that, or
     * at a yield at or above the band's, the strip runs on. A tunnel in another heading is priced on the same band prior
     * ({@link #sameBand}): never better than the band but for a measured yield there.
     */
    public static End check(Map<String, Integer> amounts, Map<String, Integer> picked, int cells, Map<String, Double> predicted,
                            Map<String, Fact> facts, String heading, HeadingCells headings) {
        Verdict v = verdict(amounts, picked, cells, predicted, facts, heading, headings);
        return v == null ? null : v.end();
    }

    /**
     * {@link #check}'s verdict with the reason a dry strip digs on: {@code end} (null: go on) or {@code on} - 0.39.z77 strip 3:
     * the sample is in and below the band's, but no other way is priced and no other heading known, so only the step watch
     * (steps.stepMaxS, Steps' "timed out after N s") ends it; the strip's running status carries this, so that end line says it.
     */
    public record Verdict(End end, String on) {}

    public static Verdict verdict(Map<String, Integer> amounts, Map<String, Integer> picked, int cells, Map<String, Double> predicted,
                                  Map<String, Fact> facts, String heading, HeadingCells headings) {
        if (amounts == null || amounts.isEmpty()) return null;
        Map<String, Integer> left = new LinkedHashMap<>();
        List<String> met = new ArrayList<>();
        for (var e : new java.util.TreeMap<>(amounts).entrySet()) {
            int got = picked.getOrDefault(e.getKey(), 0);
            if (got >= e.getValue()) met.add(e.getKey() + " " + got);
            else left.put(e.getKey(), e.getValue() - got);
        }
        if (left.isEmpty()) return null;                     // every amount met: the end rule's DONE
        int dug = Math.max(0, cells);
        double stripS = 0, cellS = 0;
        String worst = null;
        for (var e : left.entrySet()) {
            Double p = predicted.get(e.getKey());
            Fact f = facts.get(e.getKey());
            if (p == null || !(p > 0) || f == null || !(f.cellS() > 0)) return null;      // not known: runs on
            int found = picked.getOrDefault(e.getKey(), 0);
            // structural: from the band prior (no knob) - the sample is the band's own cells an ore, and the measured rate
            // must then be below the band's (0 in 7 at 1 in 57 is the band's own prediction, no evidence)
            if (dug < 1 / p - 1e-9 || !(found < p * dug)) return null;     // structural: epsilon
            double cpo = (dug + 1 / p) / (found + 1);
            double s = e.getValue() * cpo * f.cellS();
            cellS = Math.max(cellS, f.cellS());
            if (s > stripS) {
                stripS = s;
                worst = e.getKey() + " " + picked.getOrDefault(e.getKey(), 0) + " in " + dug + " cells against the band's 1 in " + Math.round(1 / p);
            }
        }
        // (a) each family's own best other way
        double sumA = 0;
        boolean allA = true, aStrip = false;
        List<String> aWords = new ArrayList<>();
        for (String fam : left.keySet()) {
            Fact f = facts.get(fam);
            if (f == null || !Double.isFinite(f.otherS()) || f.otherS() < 0) { allA = false; break; }
            sumA += f.otherS();
            aStrip |= f.otherStrip();
            aWords.add(fam + ": " + f.other() + " " + s(f.otherS()));
        }
        // (b) a tunnel from the feet in another heading
        double bestB = Double.POSITIVE_INFINITY;
        String bWords = null;
        if (headings != null) {
            for (String h : StripOrder.HEADINGS) {
                if (h.equals(heading)) continue;
                int most = -1;
                for (var e : left.entrySet()) {
                    int c = headings.cells(h, e.getKey(), e.getValue());
                    if (c < 0) { most = -1; break; }
                    most = Math.max(most, c);
                }
                if (most < 0) continue;
                double sb = most * cellS;
                if (sb < bestB) { bestB = sb; bWords = "a strip from here " + h + " (" + most + " cells at the band and the areas seen along it) " + s(sb); }
            }
        }
        double other = allA ? Math.min(sumA, bestB) : bestB;
        if (Double.isInfinite(other)) return new Verdict(null, "no other way priced: digging on to the cap (steps.stepMaxS "
                + Math.round(Tuning.get("steps.stepMaxS")) + " s; this strip's yield: " + worst + ")");
        if (!(other < stripS)) return null;
        boolean viaA = allA && sumA <= bestB;
        List<String> lefts = new ArrayList<>();
        for (var e : left.entrySet()) lefts.add(e.getKey() + " " + picked.getOrDefault(e.getKey(), 0) + " of " + amounts.get(e.getKey()));
        String why = "met: " + (met.isEmpty() ? "none" : String.join(", ", met)) + "; " + String.join(", ", lefts) + " after " + dug + " cells"
                + " (this strip's yield: " + worst + " -> " + s(stripS) + " more here; cheaper: " + (viaA ? String.join("; ", aWords) : bWords) + ")";
        // the fact the re-plan holds: the strip's ores only for a one-family strip whose cheaper way is no strip (its strip
        // way is then out for that need); else the heading at this Y - a strip of several families is derived again from
        // the bag (the playbook's descent), so only the heading keeps the same tunnel from being taken up again
        return new Verdict(new End(why, !viaA || aStrip || amounts.size() > 1), null);
    }

    /**
     * 0.39.z77 strip 2: a tunnel in another heading for {@code left} ore, priced on the same band prior as this strip, never
     * better: at least {@code left} x the band's own cells an ore ({@code bandCellsPerOre}), unless a yield was measured there
     * ({@code measuredThere}: the wanted ore seen along it - then the areas' factor stands). Cheaper elsewhere can come only
     * from distance or a measured yield there, never from optimism. Negative {@code cells}: not known, kept.
     */
    public static int sameBand(int cells, int left, double bandCellsPerOre, boolean measuredThere) {
        if (cells < 0 || measuredThere || !(bandCellsPerOre > 0)) return cells;
        return Math.max(cells, (int) Math.ceil(Math.max(0, left) * bandCellsPerOre - 1e-9));   // structural: epsilon
    }

    private static String s(double v) { return String.format(Locale.ROOT, "%.1f s", v); }
}
