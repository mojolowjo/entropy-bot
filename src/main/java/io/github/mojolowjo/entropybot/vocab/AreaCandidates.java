package io.github.mojolowjo.entropybot.vocab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * V1b-4: "area candidates": the bases explore and find noted (commands.json "safeCandidates") listed, accepted as a safe
 * area or rejected. Pure: the game side runs {@link Plan#areaCommand} through the area verb and drops the entry.
 */
public final class AreaCandidates {
    private AreaCandidates() {}

    /** How far round a candidate the safe area reaches (the 16 blocks explore keeps off), and down/up. */
    public static final int R = 16, DOWN = 8, UP = 16;

    public static final String USAGE = "usage: area candidates | area candidates accept <n> [name] | area candidates reject <n>|all";

    /** What to do: text = the answer when nothing changes; areaCommand = the area line to run (accept); remove = the 0-based entry to drop (-1 none, -2 all). */
    public record Plan(String text, String areaCommand, int remove) {}

    /** "1. 120 64 -300 (overworld): 9 built, 2 block entities, named mob" lines, or the empty answer. */
    public static String list(JsonArray l) {
        if (l == null || l.isEmpty()) return "no safe-area candidates (explore and find note someone's base here when they meet one)";
        StringBuilder sb = new StringBuilder("safe-area candidates (area candidates accept <n> [name] | reject <n>): ");
        for (int i = 0; i < l.size(); i++) {
            JsonObject o = l.get(i).getAsJsonObject();
            if (i > 0) sb.append("; ");
            sb.append(i + 1).append(". ").append(i(o, "x")).append(' ').append(i(o, "y")).append(' ').append(i(o, "z"))
                    .append(" (").append(o.has("dim") ? o.get("dim").getAsString().replaceFirst("^minecraft:", "") : "overworld").append("): ")
                    .append(i(o, "built")).append(" built, ").append(i(o, "blockEntities")).append(" block entities")
                    .append(o.has("named") && o.get("named").getAsBoolean() ? ", a named mob" : "");
        }
        return sb.toString();
    }

    /** Parses the words after "candidates". */
    public static Plan plan(JsonArray l, List<String> w) {
        if (w.isEmpty() || w.get(0).equalsIgnoreCase("list")) return new Plan(list(l), null, -1);
        String sub = w.get(0).toLowerCase(Locale.ROOT);
        int size = l == null ? 0 : l.size();
        if (sub.equals("reject") && w.size() == 2 && w.get(1).equalsIgnoreCase("all"))
            return size == 0 ? new Plan(list(l), null, -1) : new Plan("ok: forgot all " + size + " candidates", null, -2);
        if ((!sub.equals("accept") && !sub.equals("reject")) || w.size() < 2 || !w.get(1).matches("^\\d{1,3}$")) return new Plan(USAGE, null, -1);
        int n = Integer.parseInt(w.get(1));
        if (n < 1 || n > size) return new Plan("error: no candidate " + n + " (" + (size == 0 ? "there are none" : "1-" + size) + ")", null, -1);
        if (sub.equals("reject")) {
            if (w.size() > 2) return new Plan(USAGE, null, -1);
            return new Plan("ok: forgot candidate " + n, null, n - 1);
        }
        if (w.size() > 3) return new Plan(USAGE, null, -1);
        JsonObject o = l.get(n - 1).getAsJsonObject();
        String name = w.size() == 3 ? w.get(2) : "found" + n;
        int x = i(o, "x"), y = i(o, "y"), z = i(o, "z");
        return new Plan(null, (x - R) + " " + (z - R) + " " + (x + R) + " " + (z + R) + " " + name + " safe " + (y - DOWN) + " " + (y + UP), n - 1);
    }

    private static int i(JsonObject o, String k) {
        try { return o.get(k).getAsInt(); } catch (RuntimeException e) { return 0; }
    }
}
