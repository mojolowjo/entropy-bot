package io.github.mojolowjo.entropybot.baritone;

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
    public static final long PROTECT_EVERY = 200;
    /** Breaking/placing with nobody owning it is turned off at this tick of every 200 (the bridge's 10 s net). */
    public static final long NET_EVERY = 200, NET_AT = 100;

    /**
     * What {@code restore()} puts back, in this order (the bridge's restoreSafeSettings): the booleans to false,
     * allowBreakAnyway to its default (empty), then the protected list and the FIXED_OFF settings.
     */
    public static final List<String> RESTORE_FALSE = List.of("allowBreak", "allowPlace", "allowInventory", "buildIgnoreExisting");
    public static final List<String> RESTORE_RESET = List.of("allowBreakAnyway");
    public static final List<String> RESTORE_FALSE_IF_PRESENT = List.of("backfill");

    /** A Baritone command that saves settings.txt ("set", "setting", "settings" and their forms). */
    private static final Pattern SAVES = Pattern.compile("^(set|setting|settings)\\b", Pattern.CASE_INSENSITIVE);

    public static boolean saves(String text) {
        return text != null && SAVES.matcher(text.trim()).find();
    }

    /** The protected list is due: not put yet, or its regular re-put. */
    public static boolean protectedDue(long tick, boolean putOnce) {
        return !putOnce || tick % PROTECT_EVERY == 0;
    }

    public static boolean netDue(long tick) { return tick % NET_EVERY == NET_AT; }

    /** Breaking or placing is on and no job of the mod owns it (Baritone's mine with breaking on, the builder). */
    public static boolean shouldTurnOff(boolean allowBreak, boolean allowPlace, boolean breakingOwned, boolean placingOwned) {
        return (allowBreak || allowPlace) && !breakingOwned && !placingOwned;
    }

    /** Baritone is also cancelled when it is busy with no job of the mod running (as the bridge: "no job and not idle"). */
    public static boolean cancelBaritone(boolean baritoneIdle, boolean jobRunning) {
        return !baritoneIdle && !jobRunning;
    }

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
