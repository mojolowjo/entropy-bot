package io.github.mojolowjo.entropybot.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The watch camera's settings, kept across restarts (TLL 32, 2026-10-04) in {@code entropybot\watch.json}: written
 * atomically through {@code BotFiles} when a value changes (never every tick), read once at the first tick in a world.
 * A broken file (or a bad value in it) is ignored with a log line and the defaults are used; never a crash. Its own small
 * file rather than commands.json, so the camera code never touches the routines' file. {@link #parse} and
 * {@link #toJson} are pure (JUnit).
 */
public final class WatchSettings {
    public static final String FILE = "watch.json";
    public static final WatchSettings INSTANCE = new WatchSettings();

    /** The values; the defaults are distance 4, tunnel height 4, dollhouse on, steer on. */
    public record Values(float distance, double height, boolean dollhouse, boolean steer) {
        public static final Values DEFAULTS = new Values(4f, 4, true, true);
    }

    /** What a read gave: the values, and a note for the log/status (null when all was fine). */
    public record Parsed(Values values, String note) {}

    private volatile boolean loaded;
    private volatile String note = "not loaded yet";

    private WatchSettings() {}

    /** Pure: the file's text to values. null/blank = the defaults; broken JSON = the defaults with a note; a bad field = its default with a note. */
    public static Parsed parse(String text) {
        Values d = Values.DEFAULTS;
        if (text == null || text.isBlank()) return new Parsed(d, null);
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) return new Parsed(d, "not a JSON object, using the defaults");
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return new Parsed(d, "not valid JSON (" + e.getMessage() + "), using the defaults");
        }
        StringBuilder bad = new StringBuilder();
        float distance = d.distance();
        double height = d.height();
        boolean dollhouse = d.dollhouse(), steer = d.steer();
        try {
            if (o.has("distance")) {
                float v = o.get("distance").getAsFloat();
                if (v >= 1f && v <= 8f) distance = v;
                else bad.append(" distance ").append(v).append(" (1-8)");
            }
        } catch (RuntimeException e) {
            bad.append(" distance");
        }
        try {
            if (o.has("tunnelHeight")) {
                double v = o.get("tunnelHeight").getAsDouble();
                if (v >= 1 && v <= 40) height = v;
                else bad.append(" tunnelHeight ").append(v).append(" (1-40)");
            }
        } catch (RuntimeException e) {
            bad.append(" tunnelHeight");
        }
        try {
            if (o.has("dollhouse")) dollhouse = bool(o.get("dollhouse"));
        } catch (RuntimeException e) {
            bad.append(" dollhouse");
        }
        try {
            if (o.has("steer")) steer = bool(o.get("steer"));
        } catch (RuntimeException e) {
            bad.append(" steer");
        }
        Values v = new Values(distance, height, dollhouse, steer);
        return new Parsed(v, bad.length() == 0 ? null : "ignored bad values:" + bad + " (defaults used for them)");
    }

    private static boolean bool(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("not true/false");
        return e.getAsBoolean();
    }

    /** Pure: the values as the file's JSON. */
    public static String toJson(Values v) {
        JsonObject o = new JsonObject();
        o.addProperty("distance", v.distance());
        o.addProperty("tunnelHeight", v.height());
        o.addProperty("dollhouse", v.dollhouse());
        o.addProperty("steer", v.steer());
        return o.toString();
    }

    // ---- game side ------------------------------------------------------------------------------------------------

    /** Once the bot's folder is known (the first tick in a world): read the file and apply it. Never throws. */
    public void ensureLoaded() {
        if (loaded) return;
        try {
            io.github.mojolowjo.entropybot.io.BotFiles files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
            if (files == null) return;
            loaded = true;
            String text = files.readJson(FILE);
            Parsed p;
            if (text != null && text.startsWith("error: ")) p = new Parsed(Values.DEFAULTS, "couldn't read (" + text.substring(7) + "), using the defaults");
            else p = parse(text);
            apply(p.values());
            note = FILE + ": " + (text == null ? "none yet (defaults)" : p.note() == null ? "loaded" : p.note());
            if (p.note() != null) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch settings: {}: {}", FILE, p.note());
            else com.mojang.logging.LogUtils.getLogger().info("[entropybot] watch settings: {}", note);
        } catch (Throwable t) {
            note = FILE + ": couldn't load (" + t + "), using the defaults";
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch settings: {}", note);
        }
    }

    private static void apply(Values v) {
        WatchCamera.INSTANCE.setDistance(v.distance());
        io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.setHeight(v.height());
        io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.setDollhouse(v.dollhouse());
        WatchSteer.INSTANCE.setMaster(v.steer());
    }

    /** The values in use now. */
    public static Values current() {
        return new Values(WatchCamera.INSTANCE.distance(), io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.height(),
                io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.dollhouse(), WatchSteer.INSTANCE.master());
    }

    /** After a change: write the file (atomic, a few bytes, on the client thread). "" when saved, else " (not saved: ...)" for the answer. Never throws. */
    public String save() {
        try {
            io.github.mojolowjo.entropybot.io.BotFiles files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
            if (files == null) return " (not saved: not in a world yet)";
            loaded = true;          // a change made before the first load wins over the file (WatchCamera.command loads first anyway)
            String r = files.writeJson(FILE, toJson(current()));
            if (r.startsWith("ok")) {
                note = FILE + ": saved";
                return "";
            }
            note = FILE + ": couldn't write (" + r + ")";
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch settings: {}", note);
            return " (not saved: " + r + ")";
        } catch (Throwable t) {
            note = FILE + ": couldn't write (" + t + ")";
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch settings: {}", note);
            return " (not saved: " + t + ")";
        }
    }

    /** For watch status. */
    public String status() {
        Values v = current();
        return "settings: distance " + v.distance() + ", tunnel height " + v.height() + ", dollhouse " + (v.dollhouse() ? "on" : "off")
                + ", steer " + (v.steer() ? "on" : "off") + " (" + note + ")";
    }
}
