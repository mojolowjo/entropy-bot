package io.github.mojolowjo.entropybot.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Mode;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code defend creepers [flee|melee|bow]} (B7e C), kept in commands.json as {@code "creepers"}. Pure: the store and the
 * reflexes come in as functions, so JUnit can drive it.
 */
public final class CreeperSetting {
    private CreeperSetting() {}

    public static final String KEY = "creepers";

    /** rest: what follows "defend creepers". Shows the setting, or sets it (saved at once). */
    public static String command(String rest, JsonObject brain, Runnable save, Supplier<Mode> current, Consumer<Mode> apply) {
        String a = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || a.equals("status")) return "creepers: " + current.get().word() + " - " + describe(current.get());
        if (!a.equals("flee") && !a.equals("melee") && !a.equals("bow")) return "error: defence creepers flee|melee|bow";
        Mode m = Mode.parse(a);
        brain.addProperty(KEY, m.word());
        save.run();
        apply.accept(m);
        return "ok: creepers: " + m.word() + " - " + describe(m);
    }

    /** At startup: the saved mode (melee when none is saved). */
    public static Mode load(JsonObject brain) {
        JsonElement e = brain == null ? null : brain.get(KEY);
        return Mode.parse(e != null && e.isJsonPrimitive() ? e.getAsString() : null);
    }

    public static String describe(Mode m) {
        return switch (m) {
            case FLEE -> "I run from creepers that come within 6";
            case MELEE -> "I kill a lone creeper with a sword or axe, hitting and backing off (else I run)";
            case BOW -> "as melee, and I shoot it from 8-15 blocks when I carry a bow and arrows";
        };
    }
}
