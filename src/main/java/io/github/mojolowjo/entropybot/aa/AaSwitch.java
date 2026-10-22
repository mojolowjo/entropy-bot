package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * live-facts (0.39.y3): AA's switch is the world's, not the bot's: {@code on}, {@code since}, {@code heldUntil},
 * {@code shelterBuilt} and the owner's {@code aa only} live under {@link #KEY} in commands.json's world part
 * ({@code worlds/<key>/commands-world.json}, WorldState.COMMANDS_WORLD_KEYS), so a new world starts with AA off and no
 * {@code only}. The bot-wide AA settings (fair, horizon, look, replan) stay in commands.json's "aa". Pure (JUnit: AaSwitchTest).
 * Loader notes: none (Gson only).
 */
public final class AaSwitch {
    private AaSwitch() {}

    /** The world key in commands.json (listed in WorldState.COMMANDS_WORLD_KEYS). */
    public static final String KEY = "aaSwitch";
    public static final String ON = "on", SINCE = "since", HELD = "heldUntil", ONLY = "only";
    /** Before 0.39.y3 these sat in the bot-wide "aa" object. */
    static final List<String> OLD_GLOBAL = List.of(ON, SINCE, HELD, AaLadder.SHELTER_FACT);

    /** The switch object of the current world in {@code root} (commands.json's data), made when missing. */
    public static JsonObject of(JsonObject root) {
        if (!root.has(KEY) || !root.get(KEY).isJsonObject()) root.add(KEY, new JsonObject());
        return root.getAsJsonObject(KEY);
    }

    public static boolean on(JsonObject sw) { return sw.has(ON) && sw.get(ON).getAsBoolean(); }

    public static void turnOn(JsonObject sw, long now) {
        sw.addProperty(ON, true);
        sw.addProperty(SINCE, now);
        sw.remove(HELD);
    }

    public static void turnOff(JsonObject sw) { sw.addProperty(ON, false); }

    public static long heldUntil(JsonObject sw) { return sw.has(HELD) ? sw.get(HELD).getAsLong() : 0; }

    public static void hold(JsonObject sw, long until) { sw.addProperty(HELD, until); }

    public static boolean shelterBuilt(JsonObject sw) { return sw.has(AaLadder.SHELTER_FACT) && sw.get(AaLadder.SHELTER_FACT).getAsBoolean(); }

    public static void shelterBuilt(JsonObject sw, boolean b) { sw.addProperty(AaLadder.SHELTER_FACT, b); }

    /** The watched need ({@code aa only}), null: off. */
    public static String only(JsonObject sw) { return sw.has(ONLY) && !sw.get(ONLY).isJsonNull() ? sw.get(ONLY).getAsString() : null; }

    public static void only(JsonObject sw, String need) {
        if (need == null) sw.remove(ONLY);
        else sw.addProperty(ONLY, need);
    }

    /** Removes the old bot-wide switch keys from the "aa" settings object; the names removed (for the log line). */
    public static List<String> dropOldGlobal(JsonObject aa) {
        List<String> out = new ArrayList<>();
        for (String k : OLD_GLOBAL) if (aa.remove(k) != null) out.add(k);
        return out;
    }
}
