package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * B1 (docs/BRAIN_PLAN.md 4.3) / B4: every weight and threshold of the brain in one place. Defaults in code; overrides
 * saved in {@code entropybot/brain-config.json} (B4; before that commands.json {@code brain.config}, still read when the
 * file is missing). Whole-number settings have a range each ({@link #RANGES}); need weights {@code need.<name>.weight}
 * are decimals 0..5 (default 1) that multiply that need's score ({@code need.torch.weight} is the owner's
 * {@code need torch}; {@code need.food.weight}, {@code need.goal.weight}, {@code need.upkeep.weight} ...). Unknown keys
 * and values out of range are refused by {@link #set} and named by {@link #load}. Pure.
 */
public final class BrainConfig {
    static final Map<String, Integer> DEFAULTS;
    /** key -> {min, max} for the whole-number settings. */
    static final Map<String, int[]> RANGES;
    /** key -> what it does (the page and brain get show it). */
    static final Map<String, String> HELP;
    public static final Pattern WEIGHT_KEY = Pattern.compile("need\\.([a-z0-9_]{1,40})\\.weight");
    public static final double WEIGHT_MAX = 5;

    static {
        Map<String, Integer> d = new LinkedHashMap<>();
        Map<String, int[]> r = new LinkedHashMap<>();
        Map<String, String> h = new LinkedHashMap<>();
        Object[][] rows = {
                {"tickMs", 2000, 500, 60000, "the loop, ms"},
                {"switchMargin", 20, 0, 100, "a running job is outscored only by this much (momentum)"},
                {"floor", 10, 0, 100, "nothing scores above it: idle"},
                {"parkFailures", 3, 1, 20, "a need failing this often in a row is parked"},
                {"parkMinutes", 30, 1, 1440, "how long a parked need waits"},
                {"nearbyR", 32, 4, 256, "not released and the owner online: jobs within this of the owner"},
                {"copyR", 16, 4, 64, "brain copy: works within this of the owner"},
                {"copyIdleS", 60, 10, 3600, "brain copy stops after the owner does nothing this long"},
                {"dangerHealth", 8, 0, 20, "health below: danger (the reflexes act, the brain waits)"},
                {"foodWant", 8, 0, 64, "edible items wanted in the bag"},
                {"foodWantEarly", 4, 0, 64, "edible items wanted at the stages nothing and wood"},
                {"foodTop", 80, 0, 100, "the food need's top score"},
                {"hungryInterrupt", 6, 0, 20, "food bar at or below: a running brain job stops to eat"},
                {"fullInterrupt", 0, 0, 36, "free slots at or below: a running brain job stops to deposit"},
                {"bagFull", 90, 0, 100, "bag score at 2 or fewer free slots"},
                {"bagLow", 60, 0, 100, "bag score at 4 or fewer free slots"},
                {"bagSome", 25, 0, 100, "bag score at 8 or fewer free slots"},
                {"toolsNone", 70, 0, 100, "tools score with no pickaxe for the stage"},
                {"toolsWorn", 40, 0, 100, "tools score with a worn pickaxe"},
                {"toolsWornPct", 10, 0, 100, "a pickaxe at or below this % counts as worn"},
                {"ownerNeedBase", 50, 0, 100, "your need's base score"},
                {"ownerNeedSpan", 30, 0, 100, "added in proportion to what is missing"},
                {"ageBonusMax", 15, 0, 100, "the age bonus's cap (needs and goals)"},
                {"goalBase", 45, 0, 100, "a goal's base score"},
                {"copyScore", 55, 0, 100, "brain copy's score"},
                {"upkeep", 15, 0, 100, "the idle list's score"},
                {"deadbandPct", 50, 1, 100, "your need gathers again below this % of its count (deadband)"},
                {"night.shelterBelow", NightSafety.FULL_IRON, 0, 40, "night safety score below: shelter at dusk until day (default: full iron + iron sword + 8 food)"},
                {"night.workBelow", NightSafety.FULL_IRON + 4, 0, 40, "night safety score below (and not sheltering): only underground or lit-camp work at night"},
                {"night.duskHours", 2, 0, 6, "in-game hours before dusk with no new surface job in the shelter band"},
        };
        for (Object[] row : rows) {
            d.put((String) row[0], (Integer) row[1]);
            r.put((String) row[0], new int[]{(Integer) row[2], (Integer) row[3]});
            h.put((String) row[0], (String) row[4]);
        }
        DEFAULTS = Collections.unmodifiableMap(d);
        RANGES = Collections.unmodifiableMap(r);
        HELP = Collections.unmodifiableMap(h);
    }

    private final Map<String, Integer> values = new LinkedHashMap<>(DEFAULTS);
    private final Map<String, Double> weights = new TreeMap<>();

    public static BrainConfig defaults() { return new BrainConfig(); }

    public static boolean known(String key) { return DEFAULTS.containsKey(key) || WEIGHT_KEY.matcher(key).matches(); }

    public static Map<String, int[]> ranges() { return RANGES; }

    public static String help(String key) {
        if (HELP.containsKey(key)) return HELP.get(key);
        var m = WEIGHT_KEY.matcher(key);
        return m.matches() ? "multiplies the " + m.group(1) + " need's score (0..5)" : null;
    }

    public int i(String key) {
        Integer v = values.get(key);
        if (v == null) throw new IllegalArgumentException("no brain setting " + key);
        return v;
    }

    /** The weight of a need ("food", "need:torch", "goal:2", "upkeep"); 1 unless set. */
    public double weight(String need) {
        if (need == null) return 1;
        String n = need.startsWith("need:") ? need.substring(5) : need.startsWith("goal:") ? "goal" : need;
        Double w = weights.get("need." + n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_") + ".weight");
        if (w == null && n.equals("gear")) w = weights.get("need.tools.weight");      // 0.23.6: the old name still works
        return w == null ? 1 : w;
    }

    /** Overrides from JSON; returns the keys it ignored (unknown or out of range), "" when none. */
    public String load(JsonObject o) {
        if (o == null) return "";
        StringBuilder bad = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String err;
            try {
                err = set(e.getKey(), e.getValue().getAsString());
            } catch (RuntimeException ex) {
                err = "not a number";
            }
            if (err != null) bad.append(bad.length() == 0 ? "" : ", ").append(e.getKey());
        }
        return bad.toString();
    }

    /** Sets a key from text; null when ok, else why not (unknown key, not a number, out of range). */
    public String set(String key, String text) {
        if (key == null || !known(key)) return "no brain setting " + key + " (brain get lists them)";
        double d;
        try {
            d = Double.parseDouble(text.trim());
        } catch (RuntimeException e) {
            return key + " takes a number, not " + text;
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) return key + " takes a number, not " + text;
        if (WEIGHT_KEY.matcher(key).matches()) {
            if (d < 0 || d > WEIGHT_MAX) return key + " takes 0 to " + (int) WEIGHT_MAX + ", not " + text;
            weights.put(key, Math.round(d * 100) / 100.0);
            return null;
        }
        int[] r = RANGES.get(key);
        if (d != Math.rint(d)) return key + " takes a whole number, not " + text;
        if (d < r[0] || d > r[1]) return key + " takes " + r[0] + " to " + r[1] + ", not " + text;
        values.put(key, (int) d);
        return null;
    }

    public BrainConfig set(String key, int v) {
        String err = set(key, String.valueOf(v));
        if (err != null) throw new IllegalArgumentException(err);
        return this;
    }

    /** Back to the default (a weight: removed). False when the key is unknown. */
    public boolean reset(String key) {
        if (DEFAULTS.containsKey(key)) { values.put(key, DEFAULTS.get(key)); return true; }
        if (WEIGHT_KEY.matcher(key).matches()) { weights.remove(key); return true; }
        return false;
    }

    public Map<String, Integer> all() { return Collections.unmodifiableMap(values); }

    public Map<String, Double> weights() { return Collections.unmodifiableMap(weights); }

    /** The value as text ("1.5", "20"), null when unknown. */
    public String get(String key) {
        if (values.containsKey(key)) return String.valueOf(values.get(key));
        if (WEIGHT_KEY.matcher(key).matches()) return num(weights.getOrDefault(key, 1.0));
        return null;
    }

    static String num(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    /** Only what differs from the defaults (brain-config.json). */
    public JsonObject changed() {
        JsonObject o = new JsonObject();
        values.forEach((k, v) -> { if (!v.equals(DEFAULTS.get(k))) o.addProperty(k, v); });
        weights.forEach(o::addProperty);
        return o;
    }

    /** Every key with value, default, range and help (the page's fields). */
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        values.forEach((k, v) -> {
            JsonObject e = new JsonObject();
            e.addProperty("value", v);
            e.addProperty("default", DEFAULTS.get(k));
            e.addProperty("min", RANGES.get(k)[0]);
            e.addProperty("max", RANGES.get(k)[1]);
            e.addProperty("help", HELP.get(k));
            o.add(k, e);
        });
        weights.forEach((k, v) -> {
            JsonObject e = new JsonObject();
            e.addProperty("value", v);
            e.addProperty("default", 1);
            e.addProperty("min", 0);
            e.addProperty("max", WEIGHT_MAX);
            e.addProperty("help", help(k));
            o.add(k, e);
        });
        return o;
    }
}
