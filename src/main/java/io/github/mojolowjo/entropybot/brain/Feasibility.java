package io.github.mojolowjo.entropybot.brain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 0.26.2 "feasibility before every pick" (docs/BRAIN_PLAN.md 4.5b): before a brain leaf starts a chain, each step's
 * machine-checkable precondition (plan.ActionTable's {@code pre} texts) is judged against one bounded snapshot: FEASIBLE,
 * INFEASIBLE (the step, what is missing, a {@link FailMemory.Reason} class, the item a sub-chain could provide) or UNKNOWN
 * (a verb this table cannot judge, or a fact the game side did not fill: then the chain starts as before).
 *
 * <p>Steps run in order, and what an earlier step gives (gather/get/craft/cut/smelt X n) counts for the later ones, so
 * "craft torch 4 then light here 8" is judged as a whole. Optimistic on purpose: an UNKNOWN step that names an item still
 * counts as giving it. Owner-ordered chains never come here (only {@link Brain} calls it, for its own picks).
 *
 * <p>Pure Java, no game classes: JUnit builds fake states. Loader notes: none (the facts are filled by
 * {@code commands.BrainRuntime} with vanilla client getters). Errors: never throws on odd text; a step it cannot parse is UNKNOWN.
 */
public final class Feasibility {
    private Feasibility() {}

    /** The shell blocks shelter needs at the least (ShelterPlan.FULL less the roof support the ground often gives). */
    public static final int SHELTER_MIN = io.github.mojolowjo.entropybot.camp.ShelterPlan.FULL - 1;

    public enum Kind { FEASIBLE, INFEASIBLE, UNKNOWN }

    /**
     * step: 0-based index of the deciding step (-1 for a feasible chain); stepText: that step; missing: the words for why and
     * the log ("no torches, nothing to craft them from"); item and n: what a sub-chain could provide (null: no sub-chain
     * helps, e.g. no mine marked); cls: the failure memory class (null: not remembered, e.g. sleep by day).
     */
    public record Verdict(Kind kind, int step, String stepText, String missing, String item, int n, FailMemory.Reason cls) {
        public boolean infeasible() { return kind == Kind.INFEASIBLE; }

        static Verdict ok() { return new Verdict(Kind.FEASIBLE, -1, null, null, null, 0, null); }

        static Verdict unknown(int i, String st, String why) { return new Verdict(Kind.UNKNOWN, i, st, why, null, 0, null); }
    }

    /**
     * The facts the game side adds to {@link BrainState} once per brain loop (bounded reads, no block scan in the loop: the
     * table/furnace/trees flags are refreshed at most once a minute). known false (the default, and every fake state that
     * does not set it) = none of these were read: checks that need them answer UNKNOWN.
     */
    public static final class Facts {
        public boolean known;
        /** Short ids (no "minecraft:") -> count; stock = the stock view less the bag. */
        public final Map<String, Integer> bag = new HashMap<>(), stock = new HashMap<>();
        /** The best pickaxe tier in the bag (0 none, 1 wood/gold, 2 stone/copper, 3 iron, 4 diamond+). */
        public int pickTier;
        /** Place names known (base, camp, mine, farm, ...) -> position (may be null). */
        public final Map<String, int[]> places = new HashMap<>();
        /** An area of this name exists; null = unknown (a go to a name that is no place is then UNKNOWN). */
        public Predicate<String> isArea;
        public boolean baseChests, table, furnace, trees, mineMarked, caveKnown;
        /** The fence: a spot (x y z) the bot may walk to (an area or the near-me zone); null = unknown. */
        public Predicate<int[]> inFence;
    }

    static final class Sim {
        final BrainState s;
        final Facts f;
        final Map<String, Integer> given = new HashMap<>();

        Sim(BrainState s) {
            this.s = s;
            this.f = s.feas == null ? new Facts() : s.feas;
        }

        int given(String id) { return given.getOrDefault(id, 0); }

        /** bag + storage + what earlier steps give (known facts only for bag/stock). */
        int have(String id) {
            int n = given(id);
            if (f.known) n += f.bag.getOrDefault(id, 0) + f.stock.getOrDefault(id, 0);
            return n;
        }

        int torches() { return s.torches + given("torch") + (f.known ? f.stock.getOrDefault("torch", 0) : 0); }

        int fuel() { return s.coal + given("coal") + given("charcoal") + (f.known ? f.stock.getOrDefault("coal", 0) + f.stock.getOrDefault("charcoal", 0) : 0); }

        /** Wood in planks: planks, logs as 4, sticks as half. */
        int planksEq() {
            int logs = Math.max(s.logs, 0) + given("log");
            int planks = Math.max(0, s.woodItems - s.logs) + given("planks");
            if (f.known) {
                logs = Math.max(logs, sumSuffix(f.bag, "_log") + sumSuffix(f.bag, "_stem") + given("log")) + sumSuffix(f.stock, "_log") + sumSuffix(f.stock, "_stem");
                planks = Math.max(planks, sumSuffix(f.bag, "_planks") + given("planks")) + sumSuffix(f.stock, "_planks");
            }
            return planks + logs * 4;
        }

        int sticks() { return given("stick") + (f.known ? have("stick") - given("stick") : 0); }

        int shelterBlocks() { return s.shelterBlocks + given("planks") + given("cobblestone") + given("dirt") + given("log") * 4; }

        int food() { return Math.max(s.foodItems, s.foodStock) + given("food"); }

        int pickTier() {
            int t = f.known ? f.pickTier : (s.pickaxes > 0 ? 1 : 0);
            for (String k : given.keySet()) if (k.endsWith("_pickaxe")) t = Math.max(t, tier(k.substring(0, k.length() - 8)));
            return t;
        }

        void give(String item, int n) {
            if (item == null) return;
            given.merge(item, Math.max(1, n), Integer::sum);
        }
    }

    static int sumSuffix(Map<String, Integer> m, String suffix) {
        int n = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) if (e.getKey().endsWith(suffix) && !e.getKey().startsWith("stripped_")) n += e.getValue();
        return n;
    }

    static int tier(String material) { return io.github.mojolowjo.entropybot.plan.PlanFacts.tier(material); }

    static String tierName(int t) { return switch (t) { case 1 -> "wooden"; case 2 -> "stone"; case 3 -> "iron"; default -> "diamond"; }; }

    /** The short id ("minecraft:oak_log" -> "oak_log"; "logs" -> "log"). */
    static String id(String w) {
        String p = w == null ? "" : w.toLowerCase(Locale.ROOT).trim();
        if (p.startsWith("minecraft:")) p = p.substring(10);
        if (p.equals("logs") || p.matches("^[a-z_]+_(log|stem)$")) return "log";
        if (p.endsWith("_planks")) return "planks";
        if (p.equals("torches")) return "torch";
        if (p.equals("sticks")) return "stick";
        return p;
    }

    static int num(String[] w, int i, int def) {
        try { return w.length > i ? Integer.parseInt(w[i]) : def; } catch (NumberFormatException e) { return def; }
    }

    /** Judges a whole chain ("a then b then c"). Never throws. */
    public static Verdict check(String chain, BrainState s) {
        if (chain == null || chain.isBlank() || s == null) return Verdict.unknown(-1, chain, "no chain");
        String[] steps = chain.trim().split("\\s+then\\s+");
        Sim sim = new Sim(s);
        Verdict firstUnknown = null;
        for (int i = 0; i < steps.length; i++) {
            Verdict v;
            try { v = step(i, steps[i].trim(), sim); } catch (RuntimeException e) { v = Verdict.unknown(i, steps[i], "could not judge (" + e + ")"); }
            if (v.kind() == Kind.INFEASIBLE) return v;
            if (v.kind() == Kind.UNKNOWN && firstUnknown == null) firstUnknown = v;
        }
        return firstUnknown != null ? firstUnknown : Verdict.ok();
    }

    private static Verdict no(int i, String st, String missing, String item, int n, FailMemory.Reason cls) {
        return new Verdict(Kind.INFEASIBLE, i, st, missing, item, n, cls);
    }

    private static final Verdict OK = Verdict.ok();

    static Verdict step(int i, String st, Sim sim) {
        String[] w = st.toLowerCase(Locale.ROOT).split("\\s+");
        BrainState s = sim.s;
        Facts f = sim.f;
        switch (w[0]) {
            case "light" -> {
                if (sim.torches() > 0) return OK;
                if (sim.fuel() > 0 && (sim.planksEq() > 0 || sim.sticks() > 0)) return OK;      // it crafts them (2x2: no table)
                return no(i, st, "no torches, nothing to craft them from", "torch", 16, FailMemory.Reason.NO_ITEM);
            }
            case "sleep" -> {
                if (!s.night) return no(i, st, "it is day", null, 0, null);
                if (s.bedNear || s.bedInBag || sim.given("bed") > 0) return OK;
                return no(i, st, "no bed within 24 or in my bag", "white_bed", 1, FailMemory.Reason.NO_BED);
            }
            case "spawn" -> {
                if (s.bedNear || sim.given("bed") > 0) return OK;
                return no(i, st, "no bed within 24", null, 0, FailMemory.Reason.NO_BED);
            }
            case "shelter" -> {
                if (w.length >= 2 && w[1].matches("status|leave")) return OK;
                int b = sim.shelterBlocks();
                if (b >= SHELTER_MIN) return OK;
                return no(i, st, b + " planks, cobblestone or dirt, I need " + SHELTER_MIN, "planks", 32, FailMemory.Reason.NO_ITEM);
            }
            case "eat" -> {
                if (sim.food() > 0) return OK;
                return no(i, st, "no food on me or in storage", "food", 8, FailMemory.Reason.NO_ITEM);
            }
            case "deposit", "unload" -> {
                if (!f.known) return Verdict.unknown(i, st, "base chests not read");
                if (!f.places.containsKey("base")) return no(i, st, "no base place", null, 0, FailMemory.Reason.NO_PLACE);
                if (!f.baseChests) return no(i, st, "the base chests are not scanned (scan base)", null, 0, FailMemory.Reason.NONE_IN_CHESTS);
                return OK;
            }
            case "get", "fetch" -> {
                if (w.length < 2) return Verdict.unknown(i, st, "no item");
                String it = id(w[1]);
                int n = num(w, 2, 1);
                if (it.equals("food")) {
                    if (Math.max(0, s.foodStock - s.foodItems) + sim.given("food") > 0) { sim.give("food", n); return OK; }
                    return no(i, st, "no food in my chests", null, 0, FailMemory.Reason.NONE_IN_CHESTS);
                }
                if (!f.known) { sim.give(it, n); return Verdict.unknown(i, st, "stock not read"); }
                int have = it.equals("log") ? sumSuffix(f.stock, "_log") + sumSuffix(f.stock, "_stem") + sim.given("log") : f.stock.getOrDefault(it, 0) + sim.given(it);
                if (have > 0) { sim.give(it, n); return OK; }
                return no(i, st, "no " + it + " in my chests", null, 0, FailMemory.Reason.NONE_IN_CHESTS);
            }
            case "craft" -> { return craft(i, st, w, sim); }
            case "gather" -> {
                if (w.length < 2) return Verdict.unknown(i, st, "no item");
                String it = id(w[1]);
                int n = num(w, 2, 1);
                Verdict v = null;
                if (it.equals("torch")) {
                    if (sim.torches() > 0 || (sim.fuel() > 0 && sim.planksEq() > 0)) v = OK;
                } else if (it.equals("log") || it.equals("planks") || it.equals("stick")) {
                    if (sim.planksEq() > 0 || (f.known && f.trees)) v = OK;
                } else if (f.known && sim.have(it) > 0) v = OK;
                sim.give(it, n);
                return v != null ? v : Verdict.unknown(i, st, "gather's sources (mine, farm, smelt, cut) are judged by gather itself");
            }
            case "cut", "chop" -> {
                sim.give("log", num(w, 1, 4));
                return f.known && f.trees ? OK : Verdict.unknown(i, st, "no trees seen near (there may be some in my areas)");
            }
            case "smelt", "cook" -> {
                if (w.length >= 2 && !w[1].equals("collect") && !w[1].equals("jobs")) sim.give(id(w[1]), num(w, 2, 1));
                return Verdict.unknown(i, st, "smelting is judged by the smelt planner");
            }
            case "mine" -> { return mine(i, st, w, sim); }
            case "go" -> {
                if (w.length < 2) return Verdict.unknown(i, st, "no place");
                if (!f.known) return Verdict.unknown(i, st, "places not read");
                String name = w[1].equals("poi") ? null : w[1];
                if (name == null) return Verdict.unknown(i, st, "a point of interest");
                if (!f.places.containsKey(name) && f.isArea == null) return Verdict.unknown(i, st, "areas not read");
                if (!f.places.containsKey(name) && !f.isArea.test(name)) return no(i, st, "I have no place " + name, null, 0, FailMemory.Reason.NO_PLACE);
                int[] p = f.places.get(name);
                if (p != null && f.inFence != null && !f.inFence.test(p)) return no(i, st, name + " is outside my areas", null, 0, FailMemory.Reason.OUTSIDE);
                return OK;
            }
            case "goto" -> {
                if (w.length < 4) return Verdict.unknown(i, st, "not x y z");
                if (!f.known || f.inFence == null) return Verdict.unknown(i, st, "the fence not read");
                int[] p;
                try { p = new int[]{Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3])}; } catch (NumberFormatException e) { return Verdict.unknown(i, st, "not x y z"); }
                return f.inFence.test(p) ? OK : no(i, st, p[0] + " " + p[1] + " " + p[2] + " is outside my areas", null, 0, FailMemory.Reason.OUTSIDE);
            }
            case "home" -> {
                if (!f.known) return Verdict.unknown(i, st, "places not read");
                return f.places.containsKey("base") ? OK : no(i, st, "no base place", null, 0, FailMemory.Reason.NO_PLACE);
            }
            default -> { return Verdict.unknown(i, st, w[0] + " is not judged here"); }
        }
    }

    /** One craft's inputs: {input id, per craft}..., the yield. Only the cheap wood/stone recipes; others are UNKNOWN. */
    record Recipe(int yield, Object[][] inputs) {}

    static final Map<String, Recipe> RECIPES = new HashMap<>();
    static {
        RECIPES.put("torch", new Recipe(4, new Object[][]{{"fuel", 1}, {"stick", 1}}));
        RECIPES.put("stick", new Recipe(4, new Object[][]{{"planks", 2}}));
        RECIPES.put("planks", new Recipe(4, new Object[][]{{"log", 1}}));
        RECIPES.put("crafting_table", new Recipe(1, new Object[][]{{"planks", 4}}));
        RECIPES.put("chest", new Recipe(1, new Object[][]{{"planks", 8}}));
        RECIPES.put("furnace", new Recipe(1, new Object[][]{{"cobblestone", 8}}));
        RECIPES.put("wooden_pickaxe", new Recipe(1, new Object[][]{{"planks", 3}, {"stick", 2}}));
        RECIPES.put("stone_pickaxe", new Recipe(1, new Object[][]{{"cobblestone", 3}, {"stick", 2}}));
        RECIPES.put("iron_pickaxe", new Recipe(1, new Object[][]{{"iron_ingot", 3}, {"stick", 2}}));
    }

    static Verdict craft(int i, String st, String[] w, Sim sim) {
        if (w.length < 2) return Verdict.unknown(i, st, "no item");
        String it = id(w[1]);
        int n = Math.max(1, num(w, 2, 1));
        Recipe r = RECIPES.get(it);
        if (r == null) {
            sim.give(it, n);
            return Verdict.unknown(i, st, "the craft planner judges " + it);
        }
        int crafts = (n + r.yield() - 1) / r.yield();
        boolean needTable = !it.equals("torch") && !it.equals("stick") && !it.equals("planks");
        for (Object[] in : r.inputs()) {
            String what = (String) in[0];
            int want = crafts * (Integer) in[1];
            int have = switch (what) {
                case "fuel" -> sim.fuel();
                case "planks" -> sim.planksEq();
                case "stick" -> sim.sticks() + sim.planksEq() * 2;       // 2 planks make 4 sticks
                case "log" -> sim.planksEq() / 4 + sim.given("log");
                default -> sim.f.known || sim.given(what) > 0 ? sim.have(what) : -1;      // the bag not read: UNKNOWN
            };
            if (have < 0) { sim.give(it, n); return Verdict.unknown(i, st, what + " not read"); }
            if (what.equals("planks") && have >= want) continue;
            if (have < want) {
                String word = what.equals("fuel") ? "coal or charcoal" : what.equals("log") ? "logs" : what;
                return no(i, st, "craft " + it + " " + n + ": " + have + " " + word + ", I need " + want, what.equals("fuel") ? "coal" : what, want, FailMemory.Reason.NO_ITEM);
            }
        }
        if (needTable && sim.f.known && !sim.f.table && sim.planksEq() < 4 + (it.equals("crafting_table") ? 4 : 0) && sim.given("crafting_table") == 0)
            return no(i, st, "craft " + it + ": no crafting table within 12 and no planks for one", "crafting_table", 1, FailMemory.Reason.NO_ITEM);
        sim.give(it, n);
        return OK;
    }

    /** The pickaxe tier an ore list needs at the least ("any" 1; iron/copper/lapis 2; gold/diamond/redstone/emerald 3). */
    static int oreTier(String ores) {
        int min = 4;
        for (String o : ores.split(",")) {
            String p = o.replace("deepslate_", "").replace("_ore", "").replace("minecraft:", "");
            int t = switch (p) {
                case "iron", "copper", "lapis" -> 2;
                case "gold", "diamond", "redstone", "emerald" -> 3;
                default -> 1;
            };
            min = Math.min(min, t);
        }
        return min;
    }

    static Verdict mine(int i, String st, String[] w, Sim sim) {
        Facts f = sim.f;
        if (w.length >= 2 && w[1].matches("status|reset|turn|ores")) return OK;
        boolean strip = w.length >= 2 && w[1].equals("strip"), cave = w.length >= 2 && w[1].equals("cave");
        String ores = strip || cave ? (w.length >= 3 && !w[2].matches("\\d+.*") ? w[2] : "any") : (w.length >= 2 ? w[1] : "any");
        if (!strip && !cave) sim.give(ores.replace("_ore", ""), num(w, 2, 1));
        if (!f.known) return Verdict.unknown(i, st, "mine facts not read");
        if (strip && !f.mineMarked && !st.contains(" at ")) return no(i, st, "no mine marked (place mine)", null, 0, FailMemory.Reason.NO_PLACE);
        int need = oreTier(ores);
        boolean stocked = false;
        for (int t = need; t <= 4; t++) if (f.stock.getOrDefault(tierName(t) + "_pickaxe", 0) > 0) stocked = true;
        if (sim.pickTier() < need && !stocked && !(need <= 2 && sim.s.pickMaterials))
            return no(i, st, "no " + tierName(need) + " pickaxe or better (" + ores + ")", tierName(need) + "_pickaxe", 1, FailMemory.Reason.NO_ITEM);
        if (cave && !f.caveKnown) return Verdict.unknown(i, st, "no known cave (one may be in view)");
        return OK;
    }
}
