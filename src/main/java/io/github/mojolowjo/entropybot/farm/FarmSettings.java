package io.github.mojolowjo.entropybot.farm;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * P5 (docs/SURVIVAL_PLAN.md): how a farm round harvests and grows, saved in commands.json {@code "farm": {"mode":
 * "auto|modded|vanilla", "grow": "auto|on|off"}} (absent = auto, auto). modded = right-click the ripe crop (Harvest with
 * Ease replants it); vanilla = break the ripe crop and replant a seed from the drops. grow on = crouch where Squat Grow
 * reaches the crops until they are ripe. Auto reads the loaded mod list (the game side passes {@code ModList.isLoaded}
 * of {@link #HWE} and {@link #SQUAT}). Pure Java: JUnit drives it.
 * Loader notes: the mod list is NeoForge's {@code ModList.get().isLoaded(id)} (Fabric: {@code FabricLoader.isModLoaded}).
 */
public record FarmSettings(String mode, String grow) {
    /** The mod ids auto looks for (as the game's log lists them: "Harvest with ease 9.4.0 (harvest_with_ease)"). */
    public static final String HWE = "harvest_with_ease", SQUAT = "squatgrow";
    public static final FarmSettings DEFAULT = new FarmSettings("auto", "auto");
    public static final String USAGE = "usage: farm mode modded|vanilla|auto | farm grow twerk on|off|auto | farm status";

    /** What a round does: right-click (modded) or break and replant; crouch to grow or not; and why, in words. */
    public record Effective(boolean modded, boolean twerk, String modeWhy, String growWhy) {
        public String modeWord() { return modded ? "modded (right-click, Harvest with Ease replants)" : "vanilla (break the ripe crop, replant a seed)"; }

        public String growWord() { return twerk ? "twerk on" : "twerk off"; }
    }

    public static FarmSettings fromJson(JsonObject o) {
        if (o == null) return DEFAULT;
        String m = o.has("mode") && o.get("mode").isJsonPrimitive() ? o.get("mode").getAsString() : "auto";
        String g = o.has("grow") && o.get("grow").isJsonPrimitive() ? o.get("grow").getAsString() : "auto";
        return new FarmSettings(norm(m, "modded", "vanilla"), norm(g, "on", "off"));
    }

    private static String norm(String v, String a, String b) {
        String t = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return t.equals(a) || t.equals(b) ? t : "auto";
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        o.addProperty("grow", grow);
        return o;
    }

    /** The settings in effect, given which of the two mods are loaded. */
    public Effective resolve(boolean hweLoaded, boolean squatLoaded) {
        boolean modded;
        String modeWhy;
        switch (mode) {
            case "modded" -> {
                modded = true;
                modeWhy = "set by you" + (hweLoaded ? "" : "; Harvest with Ease is not loaded, so a right-click won't harvest - farm mode vanilla or auto");
            }
            case "vanilla" -> {
                modded = false;
                modeWhy = "set by you";
            }
            default -> {
                modded = hweLoaded;
                modeWhy = "auto: Harvest with Ease is " + (hweLoaded ? "loaded" : "not loaded");
            }
        }
        boolean twerk;
        String growWhy;
        switch (grow) {
            case "on" -> {
                twerk = true;
                growWhy = "set by you" + (squatLoaded ? "" : "; Squat Grow is not loaded, so crouching does nothing");
            }
            case "off" -> {
                twerk = false;
                growWhy = "set by you";
            }
            default -> {
                twerk = squatLoaded && modded;
                growWhy = "auto: " + (!squatLoaded ? "Squat Grow is not loaded" : modded ? "Squat Grow is loaded" : "vanilla mode doesn't crouch");
            }
        }
        return new Effective(modded, twerk, modeWhy, growWhy);
    }

    /**
     * "mode modded|vanilla|auto" / "grow [twerk] on|off|auto": the new settings and the reply, or (null, the error).
     * Anything else: (null, null) - not a settings command.
     */
    public record Change(FarmSettings set, String reply) {}

    public Change command(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (t.equals("mode")) return new Change(null, "farm mode: " + mode + " - " + USAGE);
        if (t.startsWith("mode ")) {
            String v = t.substring(5).trim();
            if (!v.matches("modded|vanilla|auto")) return new Change(null, USAGE);
            return new Change(new FarmSettings(v, grow), "ok: farm mode is now " + v);
        }
        if (t.equals("grow") || t.equals("grow twerk")) return new Change(null, "farm grow: twerk " + grow + " - " + USAGE);
        if (t.startsWith("grow ")) {
            String v = t.substring(5).trim().replaceFirst("^twerk ", "");
            if (!v.matches("on|off|auto")) return new Change(null, USAGE);
            return new Change(new FarmSettings(mode, v), "ok: farm grow is now twerk " + v);
        }
        return new Change(null, null);
    }

    /** The "farm status" head: the mode and grow in effect and why. */
    public String statusText(Effective e) {
        return "farm: mode " + e.modeWord() + " [" + mode + ", " + e.modeWhy() + "]; grow " + e.growWord() + " [" + grow + ", " + e.growWhy() + "]";
    }
}
