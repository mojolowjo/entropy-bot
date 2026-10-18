package io.github.mojolowjo.entropybot.ai;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.35.3: a goal-met check the mod can see for simple requests ("cut 3 logs", "get 10 cobblestone", "mine 5 iron"):
 * the count asked for against the bag's gain since the goal started. Plain Java; the inventory is item id -> count
 * (ids without the namespace).
 */
public final class GoalMet {
    private GoalMet() {}

    /** What a goal asks for: n of the items whose id matches word (null: not a simple count request). */
    public record Want(int n, String word) {}

    private static final Pattern COUNT = Pattern.compile("(?i)\\b(?:cut|chop|gather|get|collect|mine|fetch|dig|bring|craft|smelt|cook|hunt|kill)\\s+(?:me\\s+)?(\\d{1,4})\\s+([a-z_]+)");

    public static Want parse(String goal) {
        if (goal == null) return null;
        Matcher m = COUNT.matcher(goal);
        if (!m.find()) return null;
        String w = m.group(2).toLowerCase(Locale.ROOT);
        if (w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes"))) w = w.substring(0, w.length() - 2);
        else if (w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);
        if (w.equals("wood")) w = "log";
        return new Want(Integer.parseInt(m.group(1)), w);
    }

    /**
     * 0.35.4 review: a kind word (log: every *_log / *_stem, not stripped ones; plank: *_planks; ore: *_ore; wool,
     * bed, sapling, leaves the same way) or else the exact id ("iron" alone matches nothing: say raw_iron or iron_ingot).
     */
    static boolean matches(String id, String word) {
        switch (word) {
            case "log" -> { return (id.endsWith("_log") || id.endsWith("_stem")) && !id.startsWith("stripped_"); }
            case "plank" -> { return id.endsWith("_planks"); }
            case "ore" -> { return id.endsWith("_ore"); }
            case "wool", "bed", "sapling", "leaves", "carpet" -> { return id.endsWith("_" + word); }
            default -> { return id.equals(word) || id.equals(word + "s"); }
        }
    }

    static int count(Map<String, Integer> inv, String word) {
        int n = 0;
        if (inv == null) return 0;
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            String id = e.getKey().toLowerCase(Locale.ROOT);
            int c = id.indexOf(':');
            if (c >= 0) id = id.substring(c + 1);
            if (matches(id, word)) n += e.getValue();
        }
        return n;
    }

    /** The hint for the model when the bag has gained what was asked, else null. */
    public static String hint(String goal, Map<String, Integer> start, Map<String, Integer> now) {
        Want w = parse(goal);
        if (w == null || now == null || start == null) return null;      // 0.35.4: no start, no hint
        int gained = count(now, w.word()) - count(start, w.word());
        if (gained < w.n()) return null;
        return "the request looks done (" + w.word() + " +" + gained + " of " + w.n() + "): reply finished unless something is left";
    }
}
