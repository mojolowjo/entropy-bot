package io.github.mojolowjo.entropybot.threat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.24.4 fight model from measurement (pure, JUnit): per mob kind the observed damage per hit (after the bot's armour),
 * a decaying average (each new hit weighs {@link #ALPHA}) and the sample count. {@link FightOrFlee} uses it instead of
 * the hand table once a kind has {@link #MIN_SAMPLES}. Saved as {@code entropybot\mobdamage.json} by {@link HitWatch}.
 * Loader notes: none.
 */
public final class MobDamage {
    public static final MobDamage INSTANCE = new MobDamage();
    public static final double ALPHA = 0.3;
    public static final int MIN_SAMPLES = 3;

    private final Map<String, double[]> m = new LinkedHashMap<>();
    private boolean dirty;

    static String key(String kind) { return kind == null ? "" : kind.replaceFirst("^minecraft:", ""); }

    public synchronized void add(String kind, double amount) {
        if (!(amount > 0) || amount > 100) return;
        double[] s = m.get(key(kind));
        if (s == null) m.put(key(kind), new double[]{amount, 1});
        else {
            s[0] = s[1] < 1 ? amount : s[0] + ALPHA * (amount - s[0]);
            s[1]++;
        }
        dirty = true;
    }

    /** {average, samples} or null. */
    public synchronized double[] get(String kind) {
        double[] s = m.get(key(kind));
        return s == null ? null : s.clone();
    }

    /** The average when there are enough samples, else NaN. */
    public double observed(String kind) {
        double[] s = get(kind);
        return s != null && s[1] >= MIN_SAMPLES ? s[0] : Double.NaN;
    }

    public synchronized boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, double[]> e : m.entrySet()) {
            JsonObject x = new JsonObject();
            x.addProperty("avg", Math.round(e.getValue()[0] * 100) / 100.0);
            x.addProperty("n", (long) e.getValue()[1]);
            o.add(e.getKey(), x);
        }
        return o;
    }

    public synchronized void load(JsonObject o) {
        m.clear();
        if (o == null) return;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            try {
                JsonObject x = e.getValue().getAsJsonObject();
                m.put(key(e.getKey()), new double[]{x.get("avg").getAsDouble(), x.get("n").getAsDouble()});
            } catch (RuntimeException ignored) {
                // a broken entry is skipped
            }
        }
    }

    public synchronized void clear() { m.clear(); }
}
