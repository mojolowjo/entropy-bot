package io.github.mojolowjo.entropybot.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * STRUCTURE_BLOCKS.md (owner 2026-10-23, RULES 16): the words an area may carry beside its type (areas.json v2 "tags").
 * {@code break-structures}: the structure blocks inside may go; {@code use-stations}: its stations rank first. The types
 * keep-out / no-break stay types (absolute). Pure.
 */
public final class AreaTags {
    private AreaTags() {}

    public static final String BREAK_STRUCTURES = "break-structures";
    public static final String USE_STATIONS = "use-stations";
    /** Every tag the owner may set, in the order area list shows them. */
    public static final List<String> KNOWN = List.of(BREAK_STRUCTURES, USE_STATIONS);

    /** The tag as saved (lower case, trimmed), or null for a blank word. */
    public static String norm(String w) {
        if (w == null || w.isBlank()) return null;
        return w.trim().toLowerCase(Locale.ROOT);
    }

    /** Null when every word is a known tag, else the refusal naming the unknown ones and the known list. */
    public static String unknown(Collection<String> words) {
        List<String> bad = new ArrayList<>();
        if (words != null) for (String w : words) {
            String n = norm(w);
            if (n == null || !KNOWN.contains(n)) bad.add(w);
        }
        if (bad.isEmpty()) return null;
        return "error: unknown tag " + String.join(", ", bad) + " (known tags: " + String.join(", ", KNOWN) + ")";
    }
}
