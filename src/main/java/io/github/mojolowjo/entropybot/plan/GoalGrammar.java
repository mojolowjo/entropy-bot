package io.github.mojolowjo.entropybot.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B3: the fixed goal grammar of {@code goal <text>} and {@code plan <text>} (V1b's forms, extended):
 * {@code camp}, {@code light <area>} (ready chains), {@code stone tools}, {@code iron tools}, {@code iron <n>},
 * {@code food <n>}, {@code wood <n>}, {@code bed}, {@code furnace}, {@code table}, {@code shelter}, {@code <item> <n>}.
 * A goal has a short name (the routine is {@code goal_<name>}) and either a ready chain or the needs the planner
 * searches for. Unknown text -> the list of forms. Pure.
 */
public final class GoalGrammar {
    private GoalGrammar() {}

    public static final String FORMS = "goal camp | goal stone tools | goal iron tools | goal iron <n> | goal food <n> | goal wood <n> | goal light <area> "
            + "| goal bed | goal furnace | goal table | goal shelter | goal <item> <n>";
    public static final String USAGE = "usage: " + FORMS + " | goal show [name]";
    public static final int MAX_N = 2304;

    /** name: the routine's suffix; chain: a ready chain (no search) or null; needs: what the planner must reach. */
    public record Goal(String text, String name, String chain, List<Planner.Need> needs, String error) {
        public String routine() { return routineName(name); }
    }

    static Goal err(String e) { return new Goal(null, null, null, List.of(), e); }

    /** "goal_" + name, at most 20 characters (the routine name rule). */
    public static String routineName(String name) {
        String r = "goal_" + name.replaceAll("[^a-z0-9_-]", "_");
        return r.length() > 20 ? r.substring(0, 20) : r;
    }

    static final Pattern COUNT = Pattern.compile("^(iron|food|wood) (\\d{1,6})$");
    static final Pattern ITEM = Pattern.compile("^([a-z0-9_.-]+:)?([a-z0-9_./-]+) (\\d{1,6})$");
    static final Pattern LIGHT = Pattern.compile("^light ([a-z0-9_-]{1,24})$");

    public static Goal parse(String rest, Predicate<String> isArea) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (t.isEmpty()) return err(USAGE);
        switch (t) {
            case "camp" -> { return new Goal(t, "camp", "camp here", List.of(), null); }
            case "stone tools", "iron tools", "wooden tools", "diamond tools" -> {
                String m = t.split(" ")[0];
                List<Planner.Need> n = new ArrayList<>();
                for (String k : List.of("pickaxe", "axe", "shovel", "sword")) n.add(Planner.Need.item(m + "_" + k, 1));
                return new Goal(t, m + "_tools", null, n, null);
            }
            case "bed" -> { return new Goal(t, "bed", null, List.of(Planner.Need.item("white_bed", 1)), null); }
            case "furnace" -> { return new Goal(t, "furnace", null, List.of(Planner.Need.fact("furnace")), null); }
            case "table", "crafting table" -> { return new Goal(t, "table", null, List.of(Planner.Need.fact("table")), null); }
            case "shelter" -> { return new Goal(t, "shelter", null, List.of(Planner.Need.fact("shelter")), null); }
            default -> { }
        }
        Matcher m = LIGHT.matcher(t);
        if (m.find()) {
            if (isArea != null && !isArea.test(m.group(1))) return err("error: I have no area called " + m.group(1) + " (area list)");
            return new Goal(t, "light_" + m.group(1), "light " + m.group(1), List.of(), null);
        }
        m = COUNT.matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(2));
            if (n < 1 || n > MAX_N) return err("error: a goal count is 1 to " + MAX_N);
            String item = switch (m.group(1)) { case "iron" -> "iron_ingot"; case "food" -> "food"; default -> "log"; };
            return new Goal(t, m.group(1) + "_" + n, null, List.of(Planner.Need.item(item, n)), null);
        }
        m = ITEM.matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(3));
            if (n < 1 || n > MAX_N) return err("error: a goal count is 1 to " + MAX_N);
            String ns = m.group(1) == null ? "" : m.group(1);
            if (!ns.isEmpty() && !ns.equals("minecraft:")) return err("error: I plan vanilla items only (" + ns + m.group(2) + ": try gather " + ns + m.group(2) + " " + n + ")");
            String item = PlanFacts.canon(m.group(2));
            return new Goal(t, m.group(2) + "_" + n, null, List.of(Planner.Need.item(item, n)), null);
        }
        return err("I can't plan that - " + USAGE);
    }
}
