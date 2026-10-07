package io.github.mojolowjo.entropybot.vocab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * V1b (VOCABULARY 6): the kind-words, usable wherever an item or block id is taken (mine, get, cut, gather, need, fetch):
 * {@code logs} (any log or stem, not stripped), {@code wood} (any planks), {@code ores}, {@code stone}, {@code food},
 * {@code seeds}. Each kind may exclude ids ({@code kinds logs exclude cherry_log}), saved in commands.json "kinds" as
 * {kind: {"exclude": [...], "include": [...]}}. Pure: the game hands in the id list and the food test. Loader notes: none.
 */
public final class Kinds {
    private Kinds() {}

    public static final List<String> KINDS = List.of("logs", "wood", "ores", "stone", "food", "seeds");

    static final Set<String> STONE = Set.of("minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate", "minecraft:cobbled_deepslate",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:blackstone");

    public static boolean isKind(String word) {
        return word != null && KINDS.contains(word.trim().toLowerCase(Locale.ROOT));
    }

    /** "cherry_log" -> "minecraft:cherry_log"; ids with a namespace stay. */
    public static String norm(String id) {
        String t = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return t.indexOf(':') >= 0 ? t : "minecraft:" + t;
    }

    /** Is this id of the kind by its name (food needs the game's test)? */
    public static boolean byName(String kind, String id, Predicate<String> isFood) {
        String path = id.substring(id.indexOf(':') + 1);
        switch (kind) {
            case "logs": return (path.endsWith("_log") || path.endsWith("_stem")) && !path.startsWith("stripped_");
            case "wood": return path.endsWith("_planks");
            case "ores": return path.endsWith("_ore") || path.equals("ancient_debris");
            case "stone": return STONE.contains(id);
            case "food": return isFood != null && isFood.test(id);
            case "seeds": return path.endsWith("_seeds");
            default: return false;
        }
    }

    /** The settings of one kind: what the owner excluded, and what he added back by hand. */
    public record Rule(Set<String> exclude, Set<String> include) {
        public static Rule none() { return new Rule(new TreeSet<>(), new TreeSet<>()); }
    }

    /** The ids of a kind: every id of it, minus the excluded, plus the included. Sorted. */
    public static List<String> expand(String kind, Collection<String> ids, Predicate<String> isFood, Rule rule) {
        String k = kind.toLowerCase(Locale.ROOT);
        TreeSet<String> out = new TreeSet<>();
        for (String id : ids) if (byName(k, id, isFood)) out.add(id);
        if (rule != null) {
            out.addAll(rule.include());
            out.removeAll(rule.exclude());
        }
        return new ArrayList<>(out);
    }

    /**
     * "kinds &lt;kind&gt; exclude|include &lt;id&gt;": the new rule, or an error in err[0]. An exclusion that would leave the
     * kind empty is refused with the list (BRAIN_PLAN 3.10).
     */
    public static Rule change(String kind, String op, String id, Rule now, Collection<String> ids, Predicate<String> isFood, String[] err) {
        String k = kind.toLowerCase(Locale.ROOT), i = norm(id);
        if (!isKind(k)) { err[0] = "error: no kind " + kind + " - kinds: " + String.join(", ", KINDS); return null; }
        Rule r = new Rule(new TreeSet<>(now.exclude()), new TreeSet<>(now.include()));
        switch (op.toLowerCase(Locale.ROOT)) {
            case "exclude" -> {
                r.include().remove(i);
                r.exclude().add(i);
                if (expand(k, ids, isFood, r).isEmpty()) {
                    err[0] = "error: that leaves " + k + " empty (it is " + String.join(", ", expand(k, ids, isFood, now)) + ") - include one first";
                    return null;
                }
            }
            case "include" -> {
                r.exclude().remove(i);
                if (!byName(k, i, isFood)) r.include().add(i);
            }
            default -> { err[0] = "usage: kinds <kind> exclude|include <id>"; return null; }
        }
        return r;
    }

    /** "kinds": one line per kind with its count and exclusions. */
    public static String list(Map<String, Rule> rules, Collection<String> ids, Predicate<String> isFood) {
        List<String> out = new ArrayList<>();
        for (String k : KINDS) {
            Rule r = rules.getOrDefault(k, Rule.none());
            List<String> e = expand(k, ids, isFood, r);
            String ex = r.exclude().isEmpty() ? "" : " (not " + String.join(", ", shorts(r.exclude())) + ")";
            String in = r.include().isEmpty() ? "" : " (+ " + String.join(", ", shorts(r.include())) + ")";
            out.add(k + ": " + e.size() + " ids" + ex + in);
        }
        return "kinds: " + String.join(" | ", out) + " - kinds <kind> exclude|include <id>";
    }

    static List<String> shorts(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String s : ids) out.add(s.replaceFirst("^minecraft:", ""));
        return out;
    }

    /**
     * One id for verbs that take one (get, fetch, need, gather): the kind's id with the most in stock (ties: the first
     * in id order), else the first. Null when the kind is empty.
     */
    public static String pick(List<String> ids, Map<String, Integer> stock) {
        String best = null;
        int bestN = -1;
        for (String id : ids) {
            int n = stock == null ? 0 : stock.getOrDefault(id, 0);
            if (n > bestN) {
                best = id;
                bestN = n;
            }
        }
        return best;
    }
}
