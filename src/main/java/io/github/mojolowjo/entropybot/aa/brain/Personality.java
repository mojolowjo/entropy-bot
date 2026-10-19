package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BRAIN_PLAN 2.4 (B3): the bot's personality, tunable without code: a weight per want, a factor per upkeep, the
 * commitment margin, the owner base (B4), the night shift. The adapter keeps it in {@code entropybot/aa-personality.json}
 * (written with the defaults on first run) and puts it on the blackboard ({@link #FACT}). Bad keys or values fall back
 * to the default and are listed in {@link #errors} (aa status, the log). Pure.
 *
 * <p>Default (owner 2026-10-18: careful worker): wants low (build 20, explore 15, tidy 10), upkeep x1.
 */
public final class Personality {
    public static final String FACT = "aa.personality";

    /** key -> {default, min, max, what}. */
    private static final Map<String, double[]> RANGE = new LinkedHashMap<>();
    private static final Map<String, String> WHAT = new LinkedHashMap<>();

    private static void def(String k, double d, double min, double max, String what) {
        RANGE.put(k, new double[]{d, min, max});
        WHAT.put(k, what);
    }

    static {
        def("build", 20, 0, 100, "want: build a house where only a shelter stands");
        def("explore", 15, 0, 100, "want: walk new land (explore 5)");
        def("tidy", 10, 0, 100, "want: put loot away in the base chests");
        def("food", 1, 0.5, 2, "upkeep factor");
        def("tools", 1, 0.5, 2, "upkeep factor (tools stay under 75)");
        def("torches", 1, 0.5, 2, "upkeep factor");
        def("bag", 1, 0.5, 2, "upkeep factor");
        def("sleep", 60, 0, 74, "night: go to bed (bed known, rises from dusk)");
        def("shelter", 55, 0, 74, "night: shelter (no bed, at the surface)");
        def("night", 10, 0, 30, "night: + for mining, - for surface work");
        def("margin", 15, 0, 50, "commitment bonus a challenger must beat");
        def("ownerBase", 70, 0, 95, "owner goals (B4)");
        def("boredomDays", 1, 0.25, 10, "in-game days until a want is fully bored");
    }

    private final Map<String, Double> v = new LinkedHashMap<>();
    public final List<String> errors = new ArrayList<>();

    public Personality() {
        for (var e : RANGE.entrySet()) v.put(e.getKey(), e.getValue()[0]);
    }

    /** From the file's numbers (null map: defaults); unknown keys and bad values are reported, defaults kept. */
    public static Personality of(Map<String, ?> m) {
        Personality p = new Personality();
        if (m == null) return p;
        for (var e : m.entrySet()) {
            String err = e.getValue() instanceof Number n ? p.set(e.getKey(), n.doubleValue())
                    : "\"" + e.getKey() + "\" is not a number";
            if (err != null) p.errors.add(err);
        }
        return p;
    }

    /** The blackboard's personality (defaults when none was put there). */
    public static Personality of(Blackboard bb) {
        return bb.fact(FACT) instanceof Personality p ? p : DEFAULT;
    }

    private static final Personality DEFAULT = new Personality();

    public double get(String key) {
        Double d = v.get(key);
        if (d == null) throw new IllegalArgumentException("no personality key " + key);
        return d;
    }

    /** Sets one value; the error text (unknown key, out of range) or null. */
    public String set(String key, double value) {
        String k = key.toLowerCase(Locale.ROOT).equals("ownerbase") ? "ownerBase" : key.toLowerCase(Locale.ROOT).equals("boredomdays") ? "boredomDays" : key.toLowerCase(Locale.ROOT);
        double[] r = RANGE.get(k);
        if (r == null) return "unknown \"" + key + "\" (known: " + String.join(", ", RANGE.keySet()) + ")";
        if (Double.isNaN(value) || value < r[1] || value > r[2]) return k + " " + value + " out of range " + fmt(r[1]) + ".." + fmt(r[2]);
        v.put(k, value);
        return null;
    }

    public boolean has(String key) { return RANGE.containsKey(key); }

    /** "build 20 (want: ...)" lines for aa like. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (var e : v.entrySet()) out.add(e.getKey() + " " + fmt(e.getValue()) + (e.getValue() != RANGE.get(e.getKey())[0] ? " (default " + fmt(RANGE.get(e.getKey())[0]) + ")" : "") + " - " + WHAT.get(e.getKey()));
        return out;
    }

    public String toJson() {
        StringBuilder b = new StringBuilder("{\n");
        int i = 0;
        for (var e : v.entrySet()) b.append("  \"").append(e.getKey()).append("\": ").append(fmt(e.getValue())).append(++i < v.size() ? ",\n" : "\n");
        return b.append("}\n").toString();
    }

    public static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
}
