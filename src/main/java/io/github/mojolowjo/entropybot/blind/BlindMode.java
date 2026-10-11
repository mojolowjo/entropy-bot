package io.github.mojolowjo.entropybot.blind;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Blind mode's state and texts (0.26.0, docs/BLIND_PLAN.md section 2). Pure Java, loader-neutral: no game classes, so
 * JUnit drives all of it. The game side is {@link BlindRuntime}.
 *
 * <p>Config {@code entropybot/blind.json} = {"enabled": false, "title": true, "sound": "pause"}; written atomically
 * (tmp + move); a broken file loads as the defaults and leaves {@link #configProblem()} set (a {@code check} finding).
 */
public final class BlindMode {
    public static final String FILE = "blind.json";
    public static final String USAGE = "blind on|off|status";
    /** Above this maxFps the status suggests a lower limit. */
    public static final int FPS_HINT_ABOVE = 20;
    /** Ticks in a row with noRender found false (while enabled) before a finding. */
    public static final int RESET_TICKS = 3;

    /** The saved settings. sound: "pause" (pause the sound engine while blind) or "off" (leave it). */
    public record Config(boolean enabled, boolean title, String sound) {
        public static final Config DEFAULT = new Config(false, true, "pause");

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("title", title);
            o.addProperty("sound", sound);
            return o;
        }

        public boolean pauseSound() { return "pause".equals(sound); }
    }

    private Config config = Config.DEFAULT;
    private boolean enabled;
    private long since;
    private long framesSkipped;
    private long lastFrameCounter = -1;
    private String lastTitle;
    private String configProblem;
    private int resetStreak;
    private boolean resetSeen;

    public Config config() { return config; }
    public boolean enabled() { return enabled; }
    /** When blind mode was switched on (epoch ms), 0 when off. */
    public long since() { return since; }
    public long framesSkipped() { return framesSkipped; }
    public String lastTitle() { return lastTitle; }
    public void lastTitle(String t) { lastTitle = t; }
    /** Why blind.json could not be read or written (null: fine). */
    public String configProblem() { return configProblem; }

    /** Switches on/off (no config write here); true when it changed. */
    public boolean set(boolean on, long nowMs) {
        if (on == enabled) return false;
        enabled = on;
        since = on ? nowMs : 0;
        lastFrameCounter = -1;
        resetStreak = 0;
        if (on) framesSkipped = 0;
        return true;
    }

    /**
     * A frame counter reading (cumulative, any start): while enabled, the delta since the last reading is added to the
     * skipped frames. The first reading after switching on only sets the base. A counter that went backwards resets the base.
     */
    public void frames(long counter) {
        if (!enabled) {
            lastFrameCounter = -1;
            return;
        }
        if (lastFrameCounter >= 0 && counter >= lastFrameCounter) framesSkipped += counter - lastFrameCounter;
        lastFrameCounter = counter;
    }

    /**
     * End of a tick: was {@code Minecraft.noRender} still set (after the re-assert)? Returns true once, when it was
     * found false {@link #RESET_TICKS} ticks in a row while enabled (the finding stays until {@link #clearResetSeen}).
     */
    public boolean noRenderFound(boolean value) {
        if (!enabled || value) {
            resetStreak = 0;
            return false;
        }
        if (++resetStreak == RESET_TICKS) {
            resetSeen = true;
            return true;
        }
        return false;
    }

    public boolean resetSeen() { return resetSeen; }
    public void clearResetSeen() { resetSeen = false; }

    // ---- config file ----

    /** Reads blind.json (missing = defaults, no problem; broken = defaults + problem). Sets enabled from it. */
    public Config load(Path dir, long nowMs) {
        Path f = dir.resolve(FILE);
        configProblem = null;
        Config c = Config.DEFAULT;
        if (Files.exists(f)) {
            try {
                c = parse(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                configProblem = FILE + " is broken (" + e.getMessage() + "): using the defaults (blind off)";
                c = Config.DEFAULT;
            }
        }
        config = c;
        set(c.enabled(), nowMs);
        return c;
    }

    /** Pure: the config from JSON text; throws IllegalArgumentException when it is not a usable object. */
    public static Config parse(String text) {
        JsonElement e;
        try {
            e = JsonParser.parseString(text == null ? "" : text);
        } catch (RuntimeException x) {
            throw new IllegalArgumentException("not JSON");
        }
        if (!e.isJsonObject()) throw new IllegalArgumentException("not a JSON object");
        JsonObject o = e.getAsJsonObject();
        boolean en = bool(o, "enabled", false), ti = bool(o, "title", true);
        String so = "pause";
        if (o.has("sound")) {
            if (!o.get("sound").isJsonPrimitive()) throw new IllegalArgumentException("sound must be \"pause\" or \"off\"");
            so = o.get("sound").getAsString().trim().toLowerCase();
            if (!so.equals("pause") && !so.equals("off")) throw new IllegalArgumentException("sound must be \"pause\" or \"off\"");
        }
        return new Config(en, ti, so);
    }

    private static boolean bool(JsonObject o, String k, boolean dflt) {
        if (!o.has(k)) return dflt;
        JsonElement v = o.get(k);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(k + " must be true or false");
        return v.getAsBoolean();
    }

    /** Saves the config with enabled = the current state; "" when fine, else " (couldn't save blind.json: ...)". */
    public String save(Path dir) {
        config = new Config(enabled, config.title(), config.sound());
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE + ".tmp");
            Files.writeString(tmp, config.toJson().toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException x) {
                Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
            }
            configProblem = null;
            return "";
        } catch (IOException | RuntimeException e) {
            configProblem = "couldn't save " + FILE + ": " + e;
            return " (" + configProblem + ")";
        }
    }

    // ---- texts ----

    /** Pure: the window title. job null/blank = "idle". */
    public static String title(String name, int x, int y, int z, String job, int tps, int fps) {
        String j = job == null || job.isBlank() ? "idle" : job.trim();
        return "Entropy Bot " + (name == null ? "?" : name) + " | blind | " + x + " " + y + " " + z + " | " + j + " | " + tps + " tps | " + fps + " fps";
    }

    /** Pure: the maxFps hint, or "" when it is 20 or lower (260 = unlimited in vanilla's slider). */
    public static String fpsHint(int maxFps) {
        return maxFps > FPS_HINT_ABOVE ? " (set maxFps 10-20 in options.txt for the lowest cost)" : "";
    }

    /** The status line ("blind status"). */
    public String status(int maxFps, long nowMs) {
        return "blind mode: " + (enabled ? "on since " + clock(since) + " (" + ((nowMs - since) / 1000) + " s)" : "off")
                + " | frames skipped " + framesSkipped
                + " | title " + (config.title() ? "on" : "off")
                + " | sound " + config.sound()
                + " | maxFps " + maxFps + fpsHint(maxFps)
                + (configProblem != null ? " | " + configProblem : "")
                + (resetSeen ? " | something keeps turning rendering back on (see check)" : "");
    }

    static String clock(long ms) {
        return LocalTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public static final String WATCH_REFUSED = "blind mode is on - watch needs rendering (blind off first)";
    public static final String WATCH_TURNED_OFF = "blind mode on: the watch view is off now (nothing is drawn while blind)";

    /**
     * Pure: what "watch &lt;sub&gt;" answers while blind (null: go ahead). Only "off", "stop" and "status" still work;
     * everything else needs rendering.
     */
    public static String watchRefusal(boolean blind, String sub) {
        if (!blind) return null;
        String t = sub == null ? "" : sub.trim().toLowerCase();
        if (t.equals("off") || t.equals("stop") || t.equals("status") || t.endsWith(" status") || t.endsWith(" off")) return null;
        return WATCH_REFUSED;
    }

    /** state.json's "blind": {on, since, framesSkipped}. */
    public JsonObject stateJson() {
        JsonObject o = new JsonObject();
        o.addProperty("on", enabled);
        o.addProperty("since", since);
        o.addProperty("framesSkipped", framesSkipped);
        return o;
    }
}
