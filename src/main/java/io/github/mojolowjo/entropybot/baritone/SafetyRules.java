package io.github.mojolowjo.entropybot.baritone;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * B7e (E1): the pure rules of {@link SafetyNet}, ported from the bridge's safety net, restoreSafeSettings and
 * baritoneExecute. No Minecraft or Baritone types: JUnit tests them.
 */
public final class SafetyRules {
    private SafetyRules() {}

    /** The protected list goes back into Baritone this often (and once as soon as the guard's floor is ready). */
    public static long protectEvery() { return Tuning.l("safetyRules.protectEvery"); }
    /** Breaking/placing with nobody owning it is turned off at this tick of every 200 (the bridge's 10 s net). */
    public static long netEvery() { return Tuning.l("safetyRules.netEvery"); }
    public static long netAt() { return Tuning.l("safetyRules.netAt"); }

    /**
     * What {@code restore()} puts back, in this order (the bridge's restoreSafeSettings): the booleans to false, the
     * resets to their defaults, then the protected list and the FIXED_OFF settings. 0.39.z8 (owner 2026-10-22, "remove
     * the old job habit"): allowBreak, allowPlace and allowBreakAnyway are not here: each fork process owns them for its
     * own run and puts them back at its end.
     */
    public static final List<String> RESTORE_FALSE = List.of("allowInventory", "buildIgnoreExisting");
    public static final List<String> RESTORE_RESET = List.of("lavaCellCost");     // 0.39.s5: the reflex lava wade
    public static final List<String> RESTORE_FALSE_IF_PRESENT = List.of("backfill", "allowBreakNextToWater", "allowBreakNextToLava", "pathUnderWater");   // 0.36.8: our fork's mine-job setting

    /** A Baritone command that saves settings.txt ("set", "setting", "settings" and their forms). */
    private static final Pattern SAVES = Pattern.compile("^(set|setting|settings)\\b", Pattern.CASE_INSENSITIVE);

    public static boolean saves(String text) {
        return text != null && SAVES.matcher(text.trim()).find();
    }

    /** The protected list is due: not put yet, or its regular re-put. */
    public static boolean protectedDue(long tick, boolean putOnce) {
        return !putOnce || tick % protectEvery() == 0;
    }

    public static boolean netDue(long tick) { return tick % netEvery() == netAt(); }

    /**
     * Runs a raw Baritone command; a command that saves settings.txt runs with the protected list reset to its
     * default (so the file never gets thousands of blocks) and the list put back afterwards, whatever happens.
     */
    public static <T> T executeGuarded(String text, Runnable resetProtected, Supplier<T> execute, Runnable putBack) {
        boolean saves = saves(text);
        if (saves) resetProtected.run();
        try {
            return execute.get();
        } finally {
            if (saves) putBack.run();
        }
    }

    /** The log line naming how Baritone holds the protected list: "hash" (Guava's set view) or "list" (a plain list). */
    public static String kindLine(String kind, int n) {
        return "protected blocks: " + kind + ", " + n;
    }
}
