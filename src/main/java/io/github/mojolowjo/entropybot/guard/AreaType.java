package io.github.mojolowjo.entropybot.guard;

import java.util.Locale;

/**
 * V1a (0.22.0), AREAS (0.39.0, AREAS_PLAN 2): the meaning of an area, a label on a place. Plain Java. The world is open
 * by default; a hard meaning binds the guard, a soft one is a fact AA prices. The colour is the one the dashboard map,
 * the RTS legend and the companion's box view use for it. Declared hardest first: {@link #rank()} is the stacking order
 * when boxes overlap (the hardest wins).
 *
 * <p>Old words (areas.json before 0.39.0 and the owner's habit) still parse: neutral = work, safe = no-break, main = base.
 */
public enum AreaType {
    KEEP_OUT("keep-out", "red", 0xFF3030, true),
    NO_BREAK("no-break", "green", 0x30E040, true),
    DESTROY("destroy", "dark red", 0xA01010, true),
    BASE("base", "blue", 0x3070FF, false),
    WORK("work", "white", 0xFFFFFF, false),
    STORAGE("storage", "yellow", 0xF0E040, false),
    DANGER("danger", "orange", 0xFF9020, false);

    private final String word;
    public final String colour;
    public final int rgb;
    /** The guard enforces it (keep-out, no-break, destroy's owner-only built blocks); soft = a fact for AA. */
    public final boolean hard;

    AreaType(String word, String colour, int rgb, boolean hard) {
        this.word = word;
        this.colour = colour;
        this.rgb = rgb;
        this.hard = hard;
    }

    public String word() { return word; }

    /** The stacking order: 0 = keep-out (hardest) ... 6 = danger. */
    public int rank() { return ordinal(); }

    /** The harder of two (null = no meaning, the softest of all). */
    public static AreaType harder(AreaType a, AreaType b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.rank() <= b.rank() ? a : b;
    }

    /** The old word (before 0.39.0) for a new meaning, or null when the word is the same. */
    public static String oldWord(String w) {
        if (w == null) return null;
        return switch (w.trim().toLowerCase(Locale.ROOT)) {
            case "neutral" -> "work";
            case "safe" -> "no-break";
            case "main" -> "base";
            default -> null;
        };
    }

    /** The meaning for a word ("destroy", "keep-out", "keepout", "no_break", or an old word), or null. */
    public static AreaType of(String w) {
        if (w == null) return null;
        String s = w.trim().toLowerCase(Locale.ROOT);
        String mapped = oldWord(s);
        if (mapped != null) s = mapped;
        for (AreaType t : values()) if (t.word.equals(s) || t.word.replace("-", "").equals(s) || t.word.replace("-", "_").equals(s)) return t;
        return null;
    }

    /** The meaning in a JSON "type" value; absent or unknown = work (the default meaning, AREAS_PLAN 10.3). */
    public static AreaType orWork(String w) {
        AreaType t = of(w);
        return t == null ? WORK : t;
    }

    /** Every word, for usage lines: "keep-out, no-break, base, storage, work, danger, destroy". */
    public static final String WORDS = "keep-out, no-break, base, storage, work, danger, destroy";

    public static final String BAD = "error: the meaning is one of " + WORDS + " (old words: neutral = work, safe = no-break, main = base)";
}
