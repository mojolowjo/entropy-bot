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
 * results' own unlock values (depth {@link #DEPTH}); tools, armour and stations weigh {@code tech.keyWeight}; no per-item anchors (the order is recipe depth alone). {@link #norm} scales to 0..1 against the best
 * item. {@link #ideas}: items not had whose every ingredient is had: the next things to make, best first. Pure; ids are
 * "namespace:path" (minecraft: may be left out).
 */
public final class TechTree {
    public static int depthV() { return Tuning.i("techTree.depth"); }
    public static double discount() { return Tuning.get("techTree.discount"); }
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
        long t0 = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.now();
        try {
            return share0(needs, have);
        } finally {
            if (t0 != 0) io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.tech("share", io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.now() - t0);
        }
    }

    private double share0(java.util.function.Predicate<Recipe> needs, Set<String> have) {
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

    /** A result weighs {@code tech.keyWeight} when the game says it is gear (damageable) or a workstation (opens a menu), else 1: roles, never names. */
    public static double weight(String id) {
        return Kit.toolValue(id) > 0 || ItemWorth.damageable(id) || ItemWorth.station(id) ? Tuning.get("tech.keyWeight") : 1;
    }

    /** The unlock value of an item given what is had. */
    public double value(String item, Set<String> have) {
        Set<String> h = norm(have);
        return value(id(item), h, depthV(), new HashSet<>());
    }

    private double value(String item, Set<String> have, int depth, Set<String> path) {
        if (depth <= 0 || !path.add(item)) return 0;
        double v = 0;
        Set<String> counted = new HashSet<>();
        for (Recipe r : usedIn.getOrDefault(item, List.of())) {
            if (have.contains(r.result()) || !counted.add(r.result())) continue;
            v += weight(r.result()) + discount() * value(r.result(), have, depth - 1, path);
        }
        path.remove(item);
        return v;
    }

    /** 0..1 against the best item in the graph. */
    public synchronized double norm(String item, Set<String> have) {
        var laps = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA;
        long t0 = laps.now();
        Set<String> h = norm(have);
        // job-tick3 (run JAX: "tree" 785 ms over 22 aa ticks): every item's value is worked out once per bag and knobs
        // (the best pass fills the memo, so the scorer's later norm calls are lookups); exact: value() with a fresh path
        // depends only on the bag, the depth, the discount and the weights
        Object knobs = java.util.List.of(depthV(), discount(), Tuning.get("tech.keyWeight"));
        if (!h.equals(bestFor) || !knobs.equals(bestKnobs)) {          // the best is worked out once per bag
            valueMemo.clear();
            double b = 0;
            for (String i : usedIn.keySet()) b = Math.max(b, memoValue(i, h));
            bestFor = h;
            bestKnobs = knobs;
            bestV = b;
            if (t0 != 0) laps.tech("best", laps.now() - t0);
        }
        double best = bestV;
        double out = best <= 0 ? 0 : Math.min(1, memoValue(id(item), h) / best);
        if (t0 != 0) laps.tech("norm", laps.now() - t0);
        return out;
    }

    private Object bestKnobs;
    /** job-tick3: value(item) for {@link #bestFor} (cleared with it). */
    private final Map<String, Double> valueMemo = new HashMap<>();

    private double memoValue(String item, Set<String> h) {
        Double v = valueMemo.get(item);
        if (v == null) { v = value(item, h, depthV(), new HashSet<>()); valueMemo.put(item, v); }
        return v;
    }

    /** An idea: an item to make next and its worth. */
    public record Idea(String item, double worth) {}

    /** Items not had whose every ingredient slot holds a had item, best first (worth = own weight + unlock value). */
    public synchronized List<Idea> ideas(Set<String> have, int n) {
        // review: scored every tick; worked out once per bag (the adapter refreshes the bag at most every 10 s)
        if (have.equals(ideasFor) && ideasN >= n) return ideasLast.size() > n ? ideasLast.subList(0, n) : ideasLast;
        long t0 = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.now();
        List<Idea> r = ideas0(have, Math.max(n, 8));
        if (t0 != 0) io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.tech("ideas", io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.now() - t0);
        ideasFor = have;
        ideasN = Math.max(n, 8);
        ideasLast = List.copyOf(r);
        return ideasLast.size() > n ? ideasLast.subList(0, n) : ideasLast;
    }

    private Set<String> ideasFor;
    private int ideasN;
    private List<Idea> ideasLast = List.of();

    /** All recipes (normalised ids), read-only: the mining-tier pass walks them. */
    public List<Recipe> recipes() { return java.util.Collections.unmodifiableList(recipes); }

    /**
     * The top {@code n} next things: not had, every ingredient slot had or a primitive (mined or dropped, no recipe makes
     * it), best first (what {@link MiningTiers} asks: which of these does the pickaxe tier hold back). Cached per bag.
     */
    public synchronized List<Idea> top(Set<String> have, int n) {
        if (have.equals(topFor) && topN >= n) return topLast.size() > n ? topLast.subList(0, n) : topLast;
        long t0 = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.now();
        List<Idea> r = ideas0(have, Math.max(n, 8), true);
        if (t0 != 0) io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.tech("top", io.github.mojolowjo.entropybot.aa.core.TickLaps.AA.now() - t0);
        topFor = have;
        topN = Math.max(n, 8);
        topLast = List.copyOf(r);
        return topLast.size() > n ? topLast.subList(0, n) : topLast;
    }

    private Set<String> topFor;
    private int topN;
    private List<Idea> topLast = List.of();
    private Set<String> madeCache;

    private Set<String> made() {
        if (madeCache == null) { Set<String> m = new HashSet<>(); for (Recipe r : recipes) m.add(r.result()); madeCache = m; }
        return madeCache;
    }

    private List<Idea> ideas0(Set<String> have, int n) { return ideas0(have, n, false); }

    /** {@code reach}: a slot also counts when it holds a primitive (mined or dropped, no recipe makes it): the next steps, not only the ready ones. */
    private List<Idea> ideas0(Set<String> have, int n, boolean reach) {
        Set<String> h = norm(have);
        Set<String> made = reach ? made() : Set.of();
        Map<String, Double> best = new HashMap<>();
        for (Recipe r : recipes) {
            if (h.contains(r.result()) || best.containsKey(r.result())) continue;
            boolean ok = true;
            for (Set<String> s : r.slots()) {
                boolean any = false;
                for (String i : s) if (h.contains(i) || (reach && !made.contains(i))) { any = true; break; }
                if (!any) { ok = false; break; }
            }
            if (ok) best.put(r.result(), weight(r.result()) + value(r.result(), h, depthV(), new HashSet<>()));
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


