package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

/**
 * What is left of the AA v0 ladder after round 4A N: the rungs, the gates ({@code openGate}, {@code needsOpenGround},
 * needs.surface/area/woodArea) and the ladder order are gone - the needs are {@link Need}s with option sets
 * ({@link AaDesires#needs}), the climb is a priced first step ({@link Ways#climb}). Kept: the shared names and pure
 * helpers below. Loader notes: none.
 */
public final class AaLadder {
    private AaLadder() {}

    public static final String SHELTER_FACT = "shelterBuilt";

    /** The from-nothing job (fells trees, table, wooden then stone tools, furnace, chest, charcoal, torches, base, camp). */
    public static final String BOOTSTRAP = "bootstrap";

    public static final String CLIMB = "climb";
    /** Under this much ground a need whose work wants open ground prices a climb into its row (a tree's leaves never count). */
    public static final int SURFACE_DEPTH = 3;

    private static final java.util.regex.Pattern ZERO_GAIN = java.util.regex.Pattern.compile("\\bgot 0 of [1-9]\\d*\\b");

    /** aa-prereq: a task's end that says it got none of what it wanted ("got 0 of 32"). */
    public static boolean zeroGain(String end) { return end != null && ZERO_GAIN.matcher(end).find(); }

    /** No table in the bag and planks for one (4): make the spare. */
    public static boolean spareTableWanted(boolean tableInBag, int planks) {
        return !tableInBag && planks >= 4;
    }

    /** Seen within this many blocks of the camp or base mark, a table/furnace counts as the camp's (latched). */
    public static final int CAMP_R = 24;

    /**
     * 0.36.a6: a table/furnace seen within {@link #CAMP_R} of the camp or base mark is latched and counts wherever the bot
     * is. Cleared only when neither a camp nor a base is known any more.
     *
     * @param distToCamp blocks to the nearer of the camp and base marks, -1 when neither is known
     */
    public static boolean campLatch(boolean latched, boolean seenNear, double distToCamp) {
        if (distToCamp < 0) return false;
        return latched || (seenNear && distToCamp <= CAMP_R);
    }

    /** AA_ROUND3 5: short of wool, the bed first hunts sheep (at the remembered herd when none is in view), then {@code goal bed}. */
    public static String bedChain(int wool, int[] herd) {
        if (wool >= BedOptions.WOOL) return "goal bed";
        return BedOptions.huntChain(wool, herd);
    }

    /** The shelter need's SUCCESS is remembered on the blackboard (the adapter persists it). */
    public static void noteShelter(Blackboard bb) { bb.fact(SHELTER_FACT, Boolean.TRUE); }
}
