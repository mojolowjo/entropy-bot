package io.github.mojolowjo.entropybot.sense;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 0.26.1 R1: the precondition class of a job's end text ({@link #classify}), read by AA's failure memory
 * ({@code aa.brain.Failures}) and the option sets; and the stock view's storage hash (the sense side's change count).
 * AA_CLEANUP 3: moved from the old brain (its per-chain record store went with it). Pure; loader notes: none.
 */
public final class FailMemory {
    private FailMemory() {}

    /** NO_PLACE (0.26.1 live check): a place, area or mine the chain names is not marked. */
    public enum Reason { NO_ITEM, OUTSIDE, NONE_IN_CHESTS, NO_PATH, NO_BED, GUARD_REFUSED, NO_PLACE }

    /** Checked in this order (a guard refusal names "outside my areas" too: OUTSIDE wins, the cause it clears on). */
    private static final Object[][] PATTERNS = {
            {Reason.NONE_IN_CHESTS, Pattern.compile("none of my chests has|no chest (has|holds)")},
            {Reason.OUTSIDE, Pattern.compile("outside (my|every|the) areas?|outside every work area")},
            {Reason.GUARD_REFUSED, Pattern.compile("would refuse|the guard refuse|refused by the guard|guard refused|fence refuse")},
            {Reason.NO_BED, Pattern.compile("no bed")},
            {Reason.NO_PLACE, Pattern.compile("i have no (place|area|mine)\\b|no places yet|i have no place, area|no (camp|base) place")},
            {Reason.NO_PATH, Pattern.compile("no path|couldn't get there|could not get there|can't get there|couldn't reach|can't reach|no trees in my areas|chopped nothing|couldn't get at its trunk")},
            {Reason.NO_ITEM, Pattern.compile("no torches|i have no |no \\S+ on me|missing \\d|not enough |none on me|i need \\d+ |i need .* there is none|no furnace recipe")},
    };
    /** A fight or the owner: never a precondition (checked first). */
    private static final Pattern NOT_A_CAUSE = Pattern.compile("attacked by|low health|fight|stopped: your|overridden|interrupted|stopped by");

    /** The precondition class of a job's end text, or null (not a precondition). */
    public static Reason classify(String end) {
        if (end == null) return null;
        String t = end.toLowerCase(Locale.ROOT);
        if (NOT_A_CAUSE.matcher(t).find()) return null;
        for (Object[] p : PATTERNS) if (((Pattern) p[1]).matcher(t).find()) return (Reason) p[0];
        return null;
    }

    /**
     * 0.26.2 fix: the hash of what storage holds (the stock totals less the bag), so a change counts on a scan or
     * deposit, never on a change of the bag alone. Keys compared without "minecraft:".
     */
    public static int storageHash(Map<String, Integer> totals, Map<String, Integer> bag) {
        Map<String, Integer> b = new java.util.HashMap<>();
        if (bag != null) bag.forEach((k, v) -> b.merge(k.substring(k.indexOf(':') + 1), v == null ? 0 : v, Integer::sum));
        java.util.TreeMap<String, Integer> out = new java.util.TreeMap<>();
        if (totals != null) totals.forEach((k, v) -> {
            String s = k.substring(k.indexOf(':') + 1);
            int n = (v == null ? 0 : v) - b.getOrDefault(s, 0);
            if (n > 0) out.merge(s, n, Integer::sum);
        });
        return out.hashCode();
    }
}
