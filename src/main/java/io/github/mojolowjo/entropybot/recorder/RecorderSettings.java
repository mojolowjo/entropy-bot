package io.github.mojolowjo.entropybot.recorder;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The recorder's settings (B7e E5, docs/B7E_PLAN.md 7a): a preset plus the values the owner changed, and a timed
 * boost that falls back by itself. Pure (no game classes); not thread-safe on its own, {@link FlightRecorder} guards it.
 * Stored as {@code entropybot/recorder/settings.json} in the same shape as {@link #toJson}.
 */
public final class RecorderSettings {
    public static final List<String> PRESETS = List.of("off", "light", "normal", "detailed", "max");
    public static final int RANGE_MAX = 32, TRAIL_MAX = 200, SNAP_MIN = 2, SNAP_MAX = 32, KEEP_MAX = 720;

    public String preset = "normal";
    public int range = 4, trailTicks = 20, snapshot = 8, keepHours = 48;
    public boolean states;
    /** The running boost (null when none): its preset, when it ends, and the settings it goes back to. */
    public Boost boost;

    public static final class Boost {
        public String preset;
        public long untilMs;
        public RecorderSettings then;
    }

    /** off records nothing (the read side still answers from what is on disk). */
    public boolean on() { return !"off".equals(preset); }

    /** Sets the preset's values (keep stays as it is). False for an unknown preset. */
    public boolean applyPreset(String p) {
        switch (p) {
            case "off" -> { range = 4; trailTicks = 20; snapshot = 8; states = false; }
            case "light" -> { range = 2; trailTicks = 100; snapshot = 6; states = false; }
            case "normal" -> { range = 4; trailTicks = 20; snapshot = 8; states = false; }
            case "detailed" -> { range = 6; trailTicks = 5; snapshot = 12; states = true; }
            case "max" -> { range = 8; trailTicks = 1; snapshot = 16; states = true; }
            default -> { return false; }
        }
        preset = p;
        return true;
    }

    public RecorderSettings copy() {
        RecorderSettings s = new RecorderSettings();
        s.preset = preset;
        s.range = range;
        s.trailTicks = trailTicks;
        s.snapshot = snapshot;
        s.keepHours = keepHours;
        s.states = states;
        if (boost != null) {
            s.boost = new Boost();
            s.boost.preset = boost.preset;
            s.boost.untilMs = boost.untilMs;
            s.boost.then = boost.then == null ? null : boost.then.copy();
        }
        return s;
    }

    private void setFrom(RecorderSettings o) {
        preset = o.preset;
        range = o.range;
        trailTicks = o.trailTicks;
        snapshot = o.snapshot;
        states = o.states;
        boost = null;
        // keep is not part of a boost: it stays as it is now
    }

    /** Ends a boost whose time has come. Returns the note to log ("boost ended: back to normal"), else null. */
    public String tick(long nowMs) {
        if (boost == null || nowMs < boost.untilMs) return null;
        String was = boost.preset;
        RecorderSettings then = boost.then;
        if (then != null) setFrom(then);
        else { applyPreset("normal"); boost = null; }
        return "boost " + was + " ended: back to " + preset;
    }

    /** The settings that differ from the preset, for the summary ("range 6, states on"), or "". */
    public String changedFromPreset() {
        RecorderSettings p = new RecorderSettings();
        if (!p.applyPreset(preset)) return "";
        StringBuilder b = new StringBuilder();
        if (p.range != range) b.append(b.length() > 0 ? ", " : "").append("range ").append(range);
        if (p.trailTicks != trailTicks) b.append(b.length() > 0 ? ", " : "").append("trail ").append(trailText(trailTicks));
        if (p.snapshot != snapshot) b.append(b.length() > 0 ? ", " : "").append("snapshot ").append(snapshot);
        if (p.states != states) b.append(b.length() > 0 ? ", " : "").append("states ").append(states ? "on" : "off");
        return b.toString();
    }

    public static String trailText(int ticks) {
        if (ticks % 20 == 0) return (ticks / 20) + "s";
        return ticks + " ticks";
    }

    /** One line: "normal (range 4 chunks, trail 1s, snapshot 8, states off, keep 48h)". */
    public String line() {
        return preset + " (range " + range + " chunks, trail " + trailText(trailTicks) + ", snapshot " + snapshot
                + ", states " + (states ? "on" : "off") + ", keep " + keepHours + "h)";
    }

    // ---- the verb's changes (RecorderCommand.Cmd) ----

    /**
     * Applies a settings command; the reply text ("ok: ..." or "error: ..."), or null when the command is not a settings
     * change (show, mark, snapshot now). nowMs for the boost's end.
     */
    public String apply(RecorderCommand.Cmd c, long nowMs) {
        switch (c.kind()) {
            case PRESET -> {
                if (boost != null) boost = null;
                applyPreset(c.word());
                return "ok: recorder " + line() + (preset.equals("off") ? " - recording nothing now" : "");
            }
            case BOOST -> {
                RecorderSettings then = boost != null && boost.then != null ? boost.then : copy();
                then.boost = null;
                applyPreset(c.word());
                boost = new Boost();
                boost.preset = c.word();
                boost.untilMs = nowMs + c.value() * 60_000L;
                boost.then = then;
                return "ok: recorder " + c.word() + " until " + hhmm(boost.untilMs) + ", then back to " + then.preset;
            }
            case RANGE -> {
                range = c.value();
                return "ok: recorder range " + range + " chunks";
            }
            case TRAIL -> {
                trailTicks = c.value();
                return "ok: recorder trail every " + trailText(trailTicks);
            }
            case SNAPSHOT -> {
                snapshot = c.value();
                return "ok: recorder snapshot box " + (2 * snapshot + 1) + " blocks wide (half-size " + snapshot + ")";
            }
            case STATES -> {
                states = c.value() != 0;
                return "ok: recorder states " + (states ? "on (furnaces, crops, redstone too)" : "off");
            }
            case KEEP -> {
                keepHours = c.value();
                return "ok: recorder keeps changes and the trail " + keepHours + "h (incidents longer)";
            }
            default -> { return null; }
        }
    }

    static String hhmm(long ms) {
        return DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    // ---- JSON ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("preset", preset);
        o.addProperty("range", range);
        o.addProperty("trailTicks", trailTicks);
        o.addProperty("snapshot", snapshot);
        o.addProperty("states", states);
        o.addProperty("keepHours", keepHours);
        if (boost == null) o.add("boost", JsonNull.INSTANCE);
        else {
            JsonObject b = new JsonObject();
            b.addProperty("preset", boost.preset);
            b.addProperty("untilMs", boost.untilMs);
            b.addProperty("then", boost.then == null ? "normal" : boost.then.preset);
            if (boost.then != null) b.add("thenSettings", boost.then.toJson());
            o.add("boost", b);
        }
        return o;
    }

    /** From settings.json; anything missing or bad keeps the default. Never throws. */
    public static RecorderSettings fromJson(String text) {
        RecorderSettings s = new RecorderSettings();
        if (text == null || text.isBlank()) return s;
        try {
            JsonElement e = JsonParser.parseString(text);
            if (e.isJsonObject()) s.read(e.getAsJsonObject());
        } catch (RuntimeException ignored) {}
        return s;
    }

    private void read(JsonObject o) {
        String p = str(o, "preset");
        if (p != null && PRESETS.contains(p)) applyPreset(p);
        range = clamp(intOf(o, "range", range), 1, RANGE_MAX);
        trailTicks = clamp(intOf(o, "trailTicks", trailTicks), 1, TRAIL_MAX);
        snapshot = clamp(intOf(o, "snapshot", snapshot), SNAP_MIN, SNAP_MAX);
        keepHours = clamp(intOf(o, "keepHours", keepHours), 1, KEEP_MAX);
        if (o.has("states") && o.get("states").isJsonPrimitive()) states = o.get("states").getAsBoolean();
        if (o.has("boost") && o.get("boost").isJsonObject()) {
            JsonObject b = o.getAsJsonObject("boost");
            String bp = str(b, "preset");
            if (bp != null && PRESETS.contains(bp)) {
                boost = new Boost();
                boost.preset = bp;
                boost.untilMs = b.has("untilMs") ? b.get("untilMs").getAsLong() : 0;
                RecorderSettings then = new RecorderSettings();
                if (b.has("thenSettings") && b.get("thenSettings").isJsonObject()) then.read(b.getAsJsonObject("thenSettings"));
                else then.applyPreset(PRESETS.contains(str(b, "then")) ? str(b, "then") : "normal");
                then.boost = null;
                boost.then = then;
            }
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static int intOf(JsonObject o, String k, int d) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
