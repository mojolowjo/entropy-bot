package io.github.mojolowjo.entropybot.recorder;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.function.Supplier;

/**
 * state.json {@code settings.recorder} (B7e contract), pure: SettingsBlock adds {@link #json()} (JsonNull while no
 * recorder is installed). Shape:
 * <pre>{"preset", "range", "trailTicks", "snapshot", "states", "keepHours", "boost": null | {"preset", "untilMs", "then"},
 *  "diskBytes", "incidents", "lastIncident": "12:41 stuck at 260 -48 852" | null}</pre>
 */
public final class RecorderStatus {
    private RecorderStatus() {}

    private static volatile Supplier<JsonObject> source;

    static void install(Supplier<JsonObject> s) { source = s; }

    /** The block for state.json's settings; JsonNull when the recorder is not installed or fails. Never throws. */
    public static JsonElement json() {
        Supplier<JsonObject> s = source;
        if (s == null) return JsonNull.INSTANCE;
        try {
            JsonObject o = s.get();
            return o == null ? JsonNull.INSTANCE : o;
        } catch (RuntimeException e) {
            return JsonNull.INSTANCE;
        }
    }

    public static JsonObject build(RecorderSettings s, long diskBytes, int incidents, String lastIncident) {
        JsonObject o = new JsonObject();
        o.addProperty("preset", s.preset);
        o.addProperty("range", s.range);
        o.addProperty("trailTicks", s.trailTicks);
        o.addProperty("snapshot", s.snapshot);
        o.addProperty("states", s.states);
        o.addProperty("keepHours", s.keepHours);
        if (s.boost == null) o.add("boost", JsonNull.INSTANCE);
        else {
            JsonObject b = new JsonObject();
            b.addProperty("preset", s.boost.preset);
            b.addProperty("untilMs", s.boost.untilMs);
            b.addProperty("then", s.boost.then == null ? "normal" : s.boost.then.preset);
            o.add("boost", b);
        }
        o.addProperty("diskBytes", Math.max(0, diskBytes));
        o.addProperty("incidents", incidents);
        if (lastIncident == null) o.add("lastIncident", JsonNull.INSTANCE);
        else o.addProperty("lastIncident", lastIncident);
        return o;
    }
}
