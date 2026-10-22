package io.github.mojolowjo.entropybot.farm;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * P5 (docs/SURVIVAL_PLAN.md): how a farm round harvests, saved in commands.json {@code "farm": {"mode": "auto|modded|vanilla"}}
 * (absent = auto). modded = right-click the ripe crop (Harvest with Ease replants it); vanilla = break the ripe crop and
 * replant a seed from the drops. Auto reads the loaded mod list (the game side passes {@code ModList.isLoaded} of
 * {@link #HWE}). Pure Java: JUnit drives it.
 * Loader notes: the mod list is NeoForge's {@code ModList.get().isLoaded(id)} (Fabric: {@code FabricLoader.isModLoaded}).
 */
public record FarmSettings(String mode) {
    /** The mod id auto looks for (as the game's log lists it: "Harvest with ease 9.4.0 (harvest_with_ease)"). */
    public static final String HWE = "harvest_with_ease";
    public static final FarmSettings DEFAULT = new FarmSettings("auto");
    public static final String USAGE = "usage: farm mode modded|vanilla|auto | farm status";

    /** What a round does: right-click (modded) or break and replant; and why, in words. */
    public record Effective(boolean modded, String modeWhy) {
        public String modeWord() { return modded ? "modded (right-click, Harvest with Ease replants)" : "vanilla (break the ripe crop, replant a seed)"; }
    }

    public static FarmSettings fromJson(JsonObject o) {
        if (o == null) return DEFAULT;
        String m = o.has("mode") && o.get("mode").isJsonPrimitive() ? o.get("mode").getAsString() : "auto";
        String t = m.trim().toLowerCase(Locale.ROOT);
        return new FarmSettings(t.equals("modded") || t.equals("vanilla") ? t : "auto");
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        return o;
    }

    /** The settings in effect, given whether Harvest with Ease is loaded. */
    public Effective resolve(boolean hweLoaded) {
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
        return new Effective(modded, modeWhy);
    }

    /**
     * "mode modded|vanilla|auto": the new settings and the reply, or (null, the error).
     * Anything else: (null, null) - not a settings command.
     */
    public record Change(FarmSettings set, String reply) {}

    public Change command(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (t.equals("mode")) return new Change(null, "farm mode: " + mode + " - " + USAGE);
        if (t.startsWith("mode ")) {
            String v = t.substring(5).trim();
            if (!v.matches("modded|vanilla|auto")) return new Change(null, USAGE);
            return new Change(new FarmSettings(v), "ok: farm mode is now " + v);
        }
        return new Change(null, null);
    }

    /** The "farm status" head: the mode in effect and why. */
    public String statusText(Effective e) {
        return "farm: mode " + e.modeWord() + " [" + mode + ", " + e.modeWhy() + "]";
    }
}