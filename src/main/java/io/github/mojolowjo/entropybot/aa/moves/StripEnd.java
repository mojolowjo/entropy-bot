package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z78 strip (re-review of a22b7e0b): the fork's strip end reasons as IStripProcess documents them ("done: ...",
 * "stopped", "lava ahead at x y z", "water ahead at x y z", "no path", "no pickaxe left", "bag full", "lost control"), each
 * with what it holds. Only an end that names a world cause along the heading holds the heading at its Y; a bag end is a bag
 * fact (nothing held, the bag decides); a stop (the owner, a monitor, a reflex), a loss of control or a done holds nothing
 * and goes by its own rules. An end not in this list is unmapped: nothing held, said loud by the caller (rule 7). Matched
 * by the whole reason or its fixed prefix, never a substring. Pure.
 */
public enum StripEnd {
    DONE("done", Hold.NONE, true),
    STOPPED("stopped", Hold.NONE, false),
    LAVA_AHEAD("lava ahead at ", Hold.HEADING, true),
    WATER_AHEAD("water ahead at ", Hold.HEADING, true),
    NO_PATH("no path", Hold.HEADING, false),
    NO_PICKAXE("no pickaxe left", Hold.BAG, false),
    BAG_FULL("bag full", Hold.BAG, false),
    LOST_CONTROL("lost control", Hold.NONE, false);

    /** What an end holds: the heading at its Y, a bag fact, or nothing. */
    public enum Hold { HEADING, BAG, NONE }

    public final String reason;
    public final Hold hold;
    private final boolean prefix;

    StripEnd(String reason, Hold hold, boolean prefix) {
        this.reason = reason;
        this.hold = hold;
        this.prefix = prefix;
    }

    /** The fork's end {@code forkEnd} (IStripProcess#lastEnd) as one of the documented ends; null: not in the list (unmapped). */
    public static StripEnd of(String forkEnd) {
        if (forkEnd == null) return null;
        for (StripEnd e : values()) if (e.prefix ? forkEnd.startsWith(e.reason) : forkEnd.equals(e.reason)) return e;
        return null;
    }
}
