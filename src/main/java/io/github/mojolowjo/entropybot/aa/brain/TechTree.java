package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BRAIN_PLAN 2.8 (B2): the unlock graph from the game's recipes (crafting, smelting, any type the recipe manager holds).
 * An item's unlock value = the weighted count of not-yet-had results it is an ingredient of, plus half of those
 * results' own unlock values (depth {@link #DEPTH}); tools, armour and stations weigh {@link #KEY_WEIGHT}; the vanilla
 * anchors ({@link #ANCHORS}) multiply so the order feels like a player's. {@link #norm} scales to 0..1 against the best
 * item. {@link #ideas}: items not had whose every ingredient is had: the next things to make, best first. Pure; ids are
 * "namespace:path" (minecraft: may be left out).
 */
public final class TechTree {
    public static final int DEPTH = 3;
    public static final double KEY_WEIGHT = 3, DISCOUNT = 0.5;
    public static final Map<String, Double> ANCHORS = Map.of(
            "minecraft:iron_ingot", 2.0, "minecraft:diamond", 2.0, "minecraft:obsidian", 1.5, "minecraft:book", 1.5,
            "minecraft:blaze_rod", 1.5, "minecraft:redstone", 1.3);

    /** One recipe: the result and its ingredient slots (each slot = the items that fit it). */
    public record Recipe(String result, List<Set<String>> slots) {}

    private final List<Recipe> recipes = new ArrayList<>();
    private final Map<String, List<Recipe>> usedIn = new HashMap<>();

    public TechTree(List<Recipe> rs) {
        for (Recipe r : rs) {
            List<Set<String>> slots = new ArrayList<>();
            for (Set<String> s : r.slots()) {
                if (s.isEmpty()) continue;
                Set<String> n = new HashSet<>();
                for (String id : s) n.add(id(id));
                slots.add(n);
            }
            if (slots.isEmpty()) continue;
            Recipe q = new Recipe(id(r.result()), slots);
            recipes.add(q);
            Set<String> seen = new HashSet<>();
            for (Set<String> s : slots) for (String id : s) if (seen.add(id)) usedIn.computeIfAbsent(id, k -> new ArrayList<>()).add(q);
        }
    }

    public int size() { return recipes.size(); }

    /**
     * AA_ROUND2 4 (Worth): the weighted share (0..1) of the recipes not yet had whose result needs {@code needs} (a 3x3
     * grid for the table). Each result counts once, with its {@link #weight}.
     */
    public synchronized double share(java.util.function.Predicate<Recipe> needs, Set<String> have) {
        Set<String> h = norm(have);
        double all = 0, of = 0;
        Set<String> seen = new HashSet<>();
        for (Recipe r : recipes) {
            if (h.contains(r.result()) || !seen.add(r.result())) continue;
            double w = weight(r.result());
            all += w;
            if (needs.test(r)) of += w;
        }
        return all <= 0 ? 0 : of / all;
    }

    /** The blackboard fact (AaDesires.TECH_FACT). */
    public static final String FACT = "aa.techTree";

    /** Does any recipe make this item? */
    public boolean makes(String item) {
        String id = id(item);
        for (Recipe r : recipes) if (r.result().equals(id)) return true;
        return false;
    }

    private Set<String> bestFor;
    private double bestV;

    /** "iron_ingot" -> "minecraft:iron_ingot". */
    public static String id(String s) { return s.contains(":") ? s : "minecraft:" + s; }

    public static double weight(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        boolean key = p.endsWith("_pickaxe") || p.endsWith("_sword") || p.endsWith("_axe") || p.endsWith("_shovel") || p.endsWith("_hoe")
                || p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings") || p.endsWith("_boots")
                || p.equals("shield") || p.equals("bucket") || p.equals("furnace") || p.equals("blast_furnace") || p.equals("crafting_table")
                || p.equals("anvil") || p.equals("enchanting_table") || p.equals("bow") || p.equals("chest") || p.endsWith("_bed");
        return key ? KEY_WEIGHT : 1;
    }

    /** The unlock value of an item given what is had. */
    public double value(String item, Set<String> have) {
        Set<String> h = norm(have);
        return value(id(item), h, DEPTH, new HashSet<>()) * ANCHORS.getOrDefault(id(item), 1.0);
    }

    private double value(String item, Set<String> have, int depth, Set<String> path) {
        if (depth <= 0 || !path.add(item)) return 0;
        double v = 0;
        Set<String> counted = new HashSet<>();
        for (Recipe r : usedIn.getOrDefault(item, List.of())) {
            if (have.contains(r.result()) || !counted.add(r.result())) continue;
            v += weight(r.result()) + DISCOUNT * value(r.result(), have, depth - 1, path);
        }
        path.remove(item);
        return v;
    }

    /** 0..1 against the best item in the graph. */
    public synchronized double norm(String item, Set<String> have) {
        Set<String> h = norm(have);
        if (!h.equals(bestFor)) {          // the best is worked out once per bag
            double b = 0;
            for (String i : usedIn.keySet()) b = Math.max(b, value(i, h, DEPTH, new HashSet<>()) * ANCHORS.getOrDefault(i, 1.0));
            bestFor = h;
            bestV = b;
        }
        double best = bestV;
        return best <= 0 ? 0 : Math.min(1, value(item, have) / best);
    }

    /** An idea: an item to make next and its worth. */
    public record Idea(String item, double worth) {}

    /** Items not had whose every ingredient slot holds a had item, best first (worth = own weight + unlock value). */
    public synchronized List<Idea> ideas(Set<String> have, int n) {
        // review: scored every tick; worked out once per bag (the adapter refreshes the bag at most every 10 s)
        if (have.equals(ideasFor) && ideasN >= n) return ideasLast.size() > n ? ideasLast.subList(0, n) : ideasLast;
        List<Idea> r = ideas0(have, Math.max(n, 8));
        ideasFor = have;
        ideasN = Math.max(n, 8);
        ideasLast = List.copyOf(r);
        return ideasLast.size() > n ? ideasLast.subList(0, n) : ideasLast;
    }

    private Set<String> ideasFor;
    private int ideasN;
    private List<Idea> ideasLast = List.of();

    private List<Idea> ideas0(Set<String> have, int n) {
        Set<String> h = norm(have);
        Map<String, Double> best = new HashMap<>();
        for (Recipe r : recipes) {
            if (h.contains(r.result()) || best.containsKey(r.result())) continue;
            boolean ok = true;
            for (Set<String> s : r.slots()) {
                boolean any = false;
                for (String i : s) if (h.contains(i)) { any = true; break; }
                if (!any) { ok = false; break; }
            }
            if (ok) best.put(r.result(), weight(r.result()) + value(r.result(), h, DEPTH, new HashSet<>()) * ANCHORS.getOrDefault(r.result(), 1.0));
        }
        List<Idea> out = new ArrayList<>();
        for (var e : best.entrySet()) out.add(new Idea(e.getKey(), e.getValue()));
        out.sort((a, b) -> a.worth() != b.worth() ? Double.compare(b.worth(), a.worth()) : a.item().compareTo(b.item()));
        return out.size() > n ? out.subList(0, n) : out;
    }

    private static Set<String> norm(Set<String> have) {
        Set<String> h = new HashSet<>();
        for (String s : have) h.add(id(s));
        return h;
    }
}
