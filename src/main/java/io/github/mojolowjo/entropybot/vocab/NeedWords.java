package io.github.mojolowjo.entropybot.vocab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V1b (VOCABULARY 2, BRAIN_LOOP): standing needs ({@code need <item> <n>}, listed by {@code needs} with have/want),
 * goals from a fixed grammar ({@code goal camp | stone tools | iron tools | iron <n> | food <n> | wood <n> | light <area>},
 * listed by {@code goals}), the release word ({@code done}, also {@code free}) and {@code sleep auto on|off}. The brain
 * (B1) reads them; until then a goal says the chain that does it now. Pure.
 */
public final class NeedWords {
    private NeedWords() {}

    public static final int MAX_NEEDS = 32, MAX_GOALS = 10;

    /** "need <item> <n>": {item, n}; null when there is no count (the old "what does it take" answer). */
    public static String[] standing(String rest) {
        String[] w = (rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT)).split("\\s+");
        if (w.length != 2 || !w[1].matches("^\\d{1,6}$")) return null;
        return new String[]{w[0], w[1]};
    }

    /** The hint after the old answer (need <item> alone). */
    public static final String COST_HINT = " (need <item> <n> keeps a standing need; this answer becomes cost <item> in 0.24)";

    /** "needs": "torch 12/64, logs 3/32" (have/want), or the empty text. */
    public static String list(Map<String, Integer> want, Map<String, Integer> have) {
        if (want.isEmpty()) return "no standing needs - need <item> <n> (e.g. need torch 64)";
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            int h = have.getOrDefault(e.getKey(), 0);
            out.add(e.getKey().replaceFirst("^minecraft:", "") + " " + h + "/" + e.getValue() + (h >= e.getValue() ? " (met)" : ""));
        }
        return "needs: " + String.join(", ", out) + " - needs clear <item>|all";
    }

    /** A goal: its words and the chain that does it now (before the brain). */
    public record Goal(String text, String chain, String error) {}

    public static final String GOAL_USAGE = "usage: goal camp | goal stone tools | goal iron tools | goal iron <n> | goal food <n> | goal wood <n> | goal light <area>";

    public static Goal goal(String rest, java.util.function.Predicate<String> isArea) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (t.equals("camp")) return new Goal(t, "camp here", null);
        if (t.equals("stone tools")) return new Goal(t, "craft stone tools", null);
        if (t.equals("iron tools")) return new Goal(t, "craft iron tools", null);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(iron|food|wood) (\\d{1,4})$").matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(2));
            if (n < 1) return new Goal(null, null, GOAL_USAGE);
            String chain = switch (m.group(1)) {
                case "iron" -> "gather iron_ingot " + n;
                case "food" -> "gather food " + n;
                default -> "cut " + n + " logs";
            };
            return new Goal(t, chain, null);
        }
        m = java.util.regex.Pattern.compile("^light ([a-z0-9_-]{1,24})$").matcher(t);
        if (m.find()) {
            if (isArea != null && !isArea.test(m.group(1))) return new Goal(null, null, "error: I have no area called " + m.group(1) + " (area list)");
            return new Goal(t, "light " + m.group(1), null);
        }
        return new Goal(null, null, "I can't plan that yet - " + GOAL_USAGE);
    }

    /** "done"/"free" (the owner's release) is lifted by the owner's next direct order, come or escort. */
    public static boolean liftsRelease(String verb) {
        return !List.of("done", "free", "status", "inv", "help", "queue", "places", "have", "stock", "needs", "goals", "kinds", "check", "confirm", "sleep", "why", "brain", "idle").contains(verb);
    }

    /** "sleep auto on|off" -> the value, null when not that form. */
    /**
     * V1b-4: the name for a new camp's area: camp, else camp2 .. camp9 (the first one no area of any type has), or null
     * when all are taken. camp here never overwrites (and so never retypes) an existing area, a main one least of all.
     */
    public static String campName(java.util.function.Predicate<String> taken) {
        if (!taken.test("camp")) return "camp";
        for (int i = 2; i <= 9; i++) if (!taken.test("camp" + i)) return "camp" + i;
        return null;
    }

    public static Boolean sleepAuto(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (r.equals("auto on")) return true;
        if (r.equals("auto off")) return false;
        return null;
    }
}
