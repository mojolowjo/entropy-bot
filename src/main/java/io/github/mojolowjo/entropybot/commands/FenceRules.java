package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The walking fence (the sim's guard run, B7e: pure, JUnit tests it): whether a walk's goal may be reached and the
 * refusals of come, follow and goto. AREAS (0.39.0): the world is open; the only walk veto is a keep-out area (and
 * the floor: a portal), so the position watch and its grace are gone.
 */
public final class FenceRules {
    private FenceRules() {}

    private static final Pattern GOTO3 = Pattern.compile("^goto (-?\\d+) (-?\\d+) (-?\\d+)$"), GOTO2 = Pattern.compile("^goto (-?\\d+) (-?\\d+)$");

    /**
     * Null when the bot may walk to the spot, else the reason. check: the guard's dry run for "go" there ("ok",
     * "would refuse: ...", "would refuse (log mode): ...", "error: ..."); fenceOn: strict mode. Next to a portal and
     * a keep-out area are refused in every mode (the floor); the rest only with the fence on.
     */
    public static String goalAllowed(String check, boolean fenceOn) {
        String r = check == null ? "error: no answer" : check;
        if (r.startsWith("would refuse: next to a ")) return r.replaceFirst("^would refuse: ", "");
        if (r.startsWith("would refuse: in ") && r.contains("(keep-out)")) return r.replaceFirst("^would refuse: ", "");
        if (!fenceOn) return null;
        if (r.equals("ok") || r.startsWith("would refuse (log mode)")) return null;
        String why = r.replaceFirst("^would refuse: ", "").replaceFirst("^error: ", "guard error: ");
        // AREAS: an old guard answer about areas (none set, outside every area) is no refusal any more
        return why.startsWith("outside every area") || why.startsWith("no areas set") ? null : why;
    }

    /** As {@link #goalAllowed(String, boolean)}; the walker no longer matters (kept for the callers). */
    public static String goalAllowed(String check, boolean fenceOn, io.github.mojolowjo.entropybot.guard.AreaTypeRules.Walker who) {
        return goalAllowed(check, fenceOn);
    }

    /** Where a travel goal ends, for the fence: "goto x y z", "goto x z" (at the bot's level y); null for a follow. */
    public static int[] goalSpot(String goal, int botY) {
        String g = goal == null ? "" : goal;
        Matcher m3 = GOTO3.matcher(g);
        if (m3.find()) return new int[]{Integer.parseInt(m3.group(1)), Integer.parseInt(m3.group(2)), Integer.parseInt(m3.group(3))};
        Matcher m2 = GOTO2.matcher(g);
        if (m2.find()) return new int[]{Integer.parseInt(m2.group(1)), botY, Integer.parseInt(m2.group(2))};
        return null;
    }

    /** A refused goto (or go, base...): the reason. */
    public static String gotoRefusal(String why) {
        return "error: " + withAreaHint(why);
    }

    /** The reason as it is (AREAS: no "make an area" hint - the world is open; a keep-out box names itself). */
    public static String withAreaHint(String reason) {
        return reason;
    }

    /** "come" to an owner the fence won't let the bot reach (a keep-out area, a portal). */
    public static String comeRefusal(String why, int x, int z) {
        if (why.startsWith("next to a ")) return "you're " + why;
        return "you're " + why + " (" + x + " " + z + ") - I don't go in there";
    }

    /** "follow" a player standing where the bot may not go. */
    public static String followRefusal(String name, String why, int x, int z) {
        return name + " is " + why + " (" + x + " " + z + ") - I don't go in there";
    }

    /** The whisper when a followed player walks into a keep-out area (the follow ends, Baritone is cancelled). */
    public static String followLeft(String name, int[] at) {
        return "stopped: " + name + " went where I don't go, at " + at[0] + " " + at[1] + " " + at[2];
    }

    /** Cells between x y z and the nearest area of this dimension (0 = inside one); 999 with none. */
    public static int areaGap(JsonArray areas, int x, int y, int z, String dim) {
        int best = 999;
        if (areas == null) return best;
        for (JsonElement e : areas) {
            JsonObject a = e.getAsJsonObject();
            if (!PolicyCommands.dimOf(a).equals(dim)) continue;
            best = Math.min(best, PolicyCommands.boxGap(a, x, y, z));
        }
        return best;
    }
}
