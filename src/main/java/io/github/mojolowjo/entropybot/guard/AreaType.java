package io.github.mojolowjo.entropybot.guard;

import java.util.Locale;

/**
 * V1a (0.22.0): the type of an area (VOCABULARY.md 1). Plain Java. The colour is the one the dashboard map, the RTS
 * legend and the companion's box view use for it.
 */
public enum AreaType {
    NEUTRAL("white", 0xFFFFFF),
    DESTROY("red", 0xFF3030),
    MAIN("blue", 0x3070FF),
    SAFE("green", 0x30E040);

    public final String colour;
    public final int rgb;

    AreaType(String colour, int rgb) {
        this.colour = colour;
        this.rgb = rgb;
    }

    public String word() { return name().toLowerCase(Locale.ROOT); }

    /** The type for a word ("destroy"), or null. */
    public static AreaType of(String w) {
        if (w == null) return null;
        for (AreaType t : values()) if (t.word().equals(w.trim().toLowerCase(Locale.ROOT))) return t;
        return null;
    }

    /** The type in a JSON "type" value; absent or unknown = neutral. */
    public static AreaType orNeutral(String w) {
        AreaType t = of(w);
        return t == null ? NEUTRAL : t;
    }

    public static final String BAD = "error: type must be neutral, destroy, main or safe";
}
