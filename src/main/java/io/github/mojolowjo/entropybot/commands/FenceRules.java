package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The walking fence (the sim's guard run, B7e: pure, JUnit tests it): whether a walk's goal may be reached, the
 * refusals of come, follow and goto, and how far outside every area the bot stands (the position watch; the grace a
 * dig gets is {@link FenceGrace}).
 */
public final class FenceRules {
    private FenceRules() {}

    private static final Pattern GOTO3 = Pattern.compile("^goto (-?\\d+) (-?\\d+) (-?\\d+)$"), GOTO2 = Pattern.compile("^goto (-?\\d+) (-?\\d+)$");

    /**
     * Null when the bot may walk to the spot, else the reason. check: the guard's dry run for "go" there ("ok",
     * "would refuse: ...", "would refuse (log mode): ...", "error: ..."); fenceOn: strict mode with at least one area.
     * Next to a portal is refused in every mode (the floor); the rest only with the fence on.
     */
    public static String goalAllowed(String check, boolean fenceOn) {
        String r = check == null ? "error: no answer" : check;
        if (r.startsWith("would refuse: next to a ")) return r.replaceFirst("^would refuse: ", "");
        if (!fenceOn) return null;
        if (r.equals("ok") || r.startsWith("would refuse (log mode)")) return null;
        return r.replaceFirst("^would refuse: ", "").replaceFirst("^error: ", "guard error: ");
    }

    /**
     * V1a (0.22.0): as {@link #goalAllowed(String, boolean)}, but a walker AreaTypeRules lets out (the owner's own
     * goto/go, explore, find) may walk to a spot outside every area. The floor (a portal, the Nether/End) still holds.
     */
    public static String goalAllowed(String check, boolean fenceOn, io.github.mojolowjo.entropybot.guard.AreaTypeRules.Walker who) {
        String why = goalAllowed(check, fenceOn);
        if (why == null || !io.github.mojolowjo.entropybot.guard.AreaTypeRules.walk(null, who)) return why;
        return why.startsWith("outside every area") || why.startsWith("no areas set") ? null : why;
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

    /** A refused goto (or go, base...): the reason with the hint (no hint for "next to a portal"). */
    public static String gotoRefusal(String why) {
        return "error: " + withAreaHint(why);
    }

    /** The reason plus "area <name> <r>" (not for "next to a portal": no area helps there). */
    public static String withAreaHint(String reason) {
        return reason.startsWith("next to a ") ? reason : reason + " - " + PolicyCommands.AREA_HINT;
    }

    /** "come" to an owner the fence won't let the bot reach. */
    public static String comeRefusal(String why, int x, int z) {
        if (why.startsWith("next to a ")) return "you're " + why;
        return "you're outside my areas (" + x + " " + z + ") - area here 30 <name>";
    }

    /** "follow" a player standing outside the areas. */
    public static String followRefusal(String name, int x, int z) {
        return name + " is outside my areas (" + x + " " + z + ") - area here 30 <name>";
    }

    /** The whisper when a followed player leaves the areas (the follow ends, Baritone is cancelled). */
    public static String followLeft(String name, int[] at) {
        return "stopped: " + name + " left my areas at " + at[0] + " " + at[1] + " " + at[2];
    }

    /** The position watch looks at a job: the fence is on, a job runs, and it isn't a reflex's walk (a retreat may cross the edge). */
    public static boolean watches(boolean fenceOn, boolean jobRunning, boolean reflexJob) {
        return fenceOn && jobRunning && !reflexJob;
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
