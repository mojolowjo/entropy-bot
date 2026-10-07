package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B1 (docs/BRAIN_PLAN.md 4.3): every weight and threshold of the brain in one place. Defaults in code; overrides from
 * commands.json {@code brain.config} (B4 edits them with {@code brain set}). Unknown keys and values that are not whole
 * numbers are ignored (and named by {@link #load}). Pure.
 */
public final class BrainConfig {
    static final Map<String, Integer> DEFAULTS;

    static {
        Map<String, Integer> d = new LinkedHashMap<>();
        d.put("tickMs", 2000);          // the loop
        d.put("switchMargin", 20);      // a running job is outscored only by this much (momentum)
        d.put("floor", 10);             // nothing above it: idle
        d.put("parkFailures", 3);       // a need failing this often in a row parks
        d.put("parkMinutes", 30);
        d.put("nearbyR", 32);           // not released and the owner online: jobs within this of the owner
        d.put("copyR", 16);
        d.put("copyIdleS", 60);
        d.put("dangerHealth", 8);       // below: danger (the reflexes act, the brain waits)
        d.put("foodWant", 8);           // edible items in the bag
        d.put("foodWantEarly", 4);      // at the stages nothing and wood
        d.put("foodTop", 80);
        d.put("hungryInterrupt", 6);    // food bar at or below: a running brain job stops to eat
        d.put("fullInterrupt", 0);      // free slots at or below: a running brain job stops to deposit
        d.put("bagFull", 90);           // <= 2 free
        d.put("bagLow", 60);            // <= 4 free
        d.put("bagSome", 25);           // <= 8 free
        d.put("toolsNone", 70);
        d.put("toolsWorn", 40);
        d.put("toolsWornPct", 10);
        d.put("ownerNeedBase", 50);
        d.put("ownerNeedSpan", 30);
        d.put("ageBonusMax", 15);
        d.put("goalBase", 45);
        d.put("copyScore", 55);
        d.put("upkeep", 15);
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private final Map<String, Integer> values = new LinkedHashMap<>(DEFAULTS);

    public static BrainConfig defaults() { return new BrainConfig(); }

    public int i(String key) {
        Integer v = values.get(key);
        if (v == null) throw new IllegalArgumentException("no brain setting " + key);
        return v;
    }

    /** Overrides from JSON; returns the keys it ignored (unknown or not a whole number), "" when none. */
    public String load(JsonObject o) {
        if (o == null) return "";
        StringBuilder bad = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            try {
                if (!DEFAULTS.containsKey(e.getKey())) throw new IllegalArgumentException();
                double d = e.getValue().getAsDouble();
                if (d != Math.rint(d) || d < 0 || d > 1_000_000) throw new IllegalArgumentException();
                values.put(e.getKey(), (int) d);
            } catch (RuntimeException ex) {
                bad.append(bad.length() == 0 ? "" : ", ").append(e.getKey());
            }
        }
        return bad.toString();
    }

    public BrainConfig set(String key, int v) {
        if (!DEFAULTS.containsKey(key)) throw new IllegalArgumentException("no brain setting " + key);
        values.put(key, v);
        return this;
    }

    public Map<String, Integer> all() { return Collections.unmodifiableMap(values); }
}
