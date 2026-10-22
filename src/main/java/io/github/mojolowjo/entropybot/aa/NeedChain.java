package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.plan.PlanFacts;
import io.github.mojolowjo.entropybot.plan.PlanRecipes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * rm-bootstrap: a need that wants an item, resolved by recursion over the game's recipe data ({@link PlanRecipes}, the
 * recipe manager's crafting recipes read through {@link ItemWorth}); no second planner, no fixed order, no name list.
 * <ol>
 * <li>Have it: nothing to do.</li>
 * <li>Craftable: need its ingredients (recurse) and, for a 3x3 recipe, a crafting table in reach (recurse: one in the
 *     bag is placed, else the table is itself a craftable item made from planks, from logs); then the craft (a GUI job,
 *     Baritone has no crafting).</li>
 * <li>Else a block that drops it, seen (a {@link Spot}: a tree for any log, stone for a stone-like item): the walk there and
 *     Baritone's MineProcess (the existing cut and collect steps), after the pickaxe tier the block asks for (recurse).</li>
 * <li>Else nothing seen: the explore way, priced for that need with its return leg. The chain stops there (what comes
 *     after cannot be placed before something is seen); AA asks again when the explore ends.</li>
 * </ol>
 * Nothing is ever dropped as infeasible: a missing ingredient is the next sub-need with its own price, and {@link Chain#why}
 * prints the whole chain for {@code aa why}. A need with no way at all (no recipe, no block, no cell for a table) is a
 * {@link Chain} with {@code missing} set, its stage and reason named. Pure (JUnit: NeedChainTest). Loader notes: none (the
 * facts come from {@code AaRuntime.toolsFacts}).
 */
public final class NeedChain {
    private NeedChain() {}

    /** A seen source: a cell to walk to, the straight-line {@code dist}, the block's id when known (its pickaxe tier comes from it). */
    public record Spot(int x, int y, int z, double dist, String block) {}

    /**
     * What the bot knows now. {@code bag}: item id -> count; {@code tableNear}: a crafting table within reach;
     * {@code tableCell}: a free cell to place one at (null none); {@code seen}: planner word ({@link PlanFacts#canon}) -> the
     * nearest seen source of it, null when none (a tree for "log", stone for a stone-like item).
     */
    public record World(Map<String, Integer> bag, boolean tableNear, int[] tableCell, java.util.function.Function<String, Spot> seen) {
        public World {
            Map<String, Integer> b = new LinkedHashMap<>();
            if (bag != null) for (var e : bag.entrySet()) b.merge(e.getKey().indexOf(':') < 0 ? "minecraft:" + e.getKey() : e.getKey(), e.getValue(), Integer::sum);
            bag = Map.copyOf(b);
            tableCell = tableCell == null ? null : tableCell.clone();
            seen = seen == null ? w -> null : seen;
        }
    }

    /**
     * @param steps   the typed steps, in run order (empty: nothing to do)
     * @param cost    walk-blocks ({@link Unit}); {@code why}: the whole chain in one line
     * @param explore the chain ends with the explore way (something needed is not seen yet)
     * @param missing why there is no way at all (null: there is one)
     */
    public record Chain(List<Step> steps, double cost, String why, boolean explore, String missing) {
        public boolean ok() { return missing == null; }
    }

    public static int exploreMinutes() { return io.github.mojolowjo.entropybot.aa.brain.Discovery.exploreMinutes(); }
    /** Share of the explore time the walk back costs again. */
    public static double exploreReturn() { return Tuning.get("needChain.exploreReturn"); }

    // ---- the recursion ----

    private static final class Ctx {
        final World w;
        final Map<String, Integer> bag = new LinkedHashMap<>();
        final List<Step> steps = new ArrayList<>();
        final Map<String, Integer> leafAt = new LinkedHashMap<>();
        final Set<String> stack = new HashSet<>();
        double seconds;       // seconds of work (craft, place); walks are priced in blocks
        double blocks;
        boolean tableReady, stuck;
        String missing;
        int pickTier;

        Ctx(World w) {
            this.w = w;
            for (var e : w.bag().entrySet()) if (e.getValue() > 0) bag.merge(PlanFacts.canon(e.getKey()), e.getValue(), Integer::sum);
            tableReady = w.tableNear();
            for (var e : w.bag().entrySet()) if (e.getValue() > 0) pickTier = Math.max(pickTier, PlanFacts.tier(PlanFacts.canon(e.getKey())));
        }

        boolean dead() { return stuck || missing != null; }
    }

    /** The chain that gets {@code n} of {@code item} into the bag from {@code w}. */
    public static Chain resolve(String item, int n, World w) {
        Ctx c = new Ctx(w);
        String word = PlanFacts.canon(item);
        String phrase = obtain(c, word, n);
        double cost = Unit.blocks(c.seconds) + c.blocks;
        if (c.missing != null) return new Chain(List.copyOf(c.steps), cost, item + ": " + c.missing, false, c.missing);
        String why = (phrase == null ? item + " is in the bag" : item + " <- " + phrase) + ": " + Math.round(Unit.seconds(cost)) + " s";
        return new Chain(List.copyOf(c.steps), cost, why, c.stuck, null);
    }

    /** Makes {@code n} of {@code word} true in the simulated bag; returns the phrase of how ("3 cobblestone <- dig stone seen 12 m"), null when it was there. */
    private static String obtain(Ctx c, String word, int n) {
        if (c.dead() || n <= 0) return null;
        int have = c.bag.getOrDefault(word, 0);
        if (have >= n) return null;
        int lack = n - have;
        if (!c.stack.add(word)) { c.missing = word + " needs itself (a recipe loop)"; return null; }
        try {
            PlanRecipes.Recipe rec = isLog(word) ? null : PlanRecipes.of(word);
            if (rec != null) return craft(c, word, lack, rec);
            return source(c, word, lack);
        } finally {
            c.stack.remove(word);
        }
    }

    private static boolean rawUnseen(Ctx c, String word) {
        return (isLog(word) || PlanRecipes.of(word) == null) && c.bag.getOrDefault(word, 0) <= 0 && c.w.seen().apply(word) == null;
    }

    private static boolean isLog(String word) { return word.equals("log"); }

    private static String craft(Ctx c, String word, int lack, PlanRecipes.Recipe rec) {
        int batches = (lack + rec.out() - 1) / rec.out();
        List<String> parts = new ArrayList<>();
        // what can be made or fetched first; a raw source nothing has seen yet last (its explore ends the chain, so it must not hide the rest)
        List<Map.Entry<String, Integer>> ins = new ArrayList<>(rec.in().entrySet());
        ins.sort(java.util.Comparator.comparingInt((Map.Entry<String, Integer> e) -> rawUnseen(c, e.getKey()) ? 1 : 0));
        for (var e : ins) {
            int want = e.getValue() * batches;
            String p = obtain(c, e.getKey(), want);
            if (c.missing != null) return null;
            parts.add(p == null ? want + " " + e.getKey() + " (in the bag)" : want + " " + e.getKey() + " <- " + p);
            if (c.stuck) return String.join(" + ", parts);                 // something needed is not seen: the chain ends at the explore
            c.bag.merge(e.getKey(), -want, Integer::sum);
        }
        if (rec.table()) {
            String t = table(c);
            if (c.missing != null) return null;
            if (t != null) parts.add("table <- " + t);
            if (c.stuck) return String.join(" + ", parts);
        }
        c.steps.add(new Step.Craft(concrete(c, word), batches * rec.out()));
        c.seconds += io.github.mojolowjo.entropybot.aa.brain.ToolWear.craftSV();
        c.bag.merge(word, batches * rec.out(), Integer::sum);
        return parts.isEmpty() ? word : String.join(" + ", parts);
    }

    /** A crafting table in reach: the phrase of how it gets there, null when one already is. */
    private static String table(Ctx c) {
        if (c.tableReady) return null;
        if (c.w.tableCell() == null) { c.missing = "no free cell to place a crafting table at"; return null; }
        String made = obtain(c, "crafting_table", 1);
        if (c.missing != null) return null;
        if (c.stuck) return made;
        int[] at = c.w.tableCell();
        c.steps.add(Step.Build.one("crafting_table", at[0], at[1], at[2]));
        c.seconds += Price.placeS();
        c.bag.merge("crafting_table", -1, Integer::sum);
        c.tableReady = true;
        return made == null ? "in the bag, placed" : made + ", placed";
    }

    /** A block that drops the word, seen: walk and mine; else explore; else no way. */
    private static String source(Ctx c, String word, int lack) {
        Spot s = c.w.seen().apply(word);
        boolean log = isLog(word);
        if (!log && !stoneLike(word) && s == null) {
            c.missing = "no recipe and no block I know drops " + word;
            return null;
        }
        if (s == null) return explore(c, word, log ? "no tree seen" : "no " + word + " source seen");
        // the pickaxe tier the block asks for, as a sub-need of its own
        String pre = null;
        int tier = s.block() == null ? 0 : Math.max(0, MiningTiers.blockTierOf(s.block()));
        if (tier > c.pickTier) {
            String pick = pickaxeFor(tier);
            if (pick == null) { c.missing = "no pickaxe item of tier " + tier + " to dig " + s.block(); return null; }
            pre = obtain(c, PlanFacts.canon(pick), 1);
            if (c.dead()) return null;
            c.pickTier = tier;
        }
        Integer at = c.leafAt.get(word);
        if (at != null) {
            Step prev = c.steps.get(at);
            c.steps.set(at, prev instanceof Step.ChopTree t ? new Step.ChopTree(t.at(), t.logs() + lack, t.log())
                    : new Step.Collect(((Step.Collect) prev).item(), ((Step.Collect) prev).n() + lack));
        } else {
            c.leafAt.put(word, c.steps.size() + 1);
            c.steps.add(new Step.Walk(s.x(), s.y(), s.z(), (int) Tuning.get("steps.walkNear")));
            c.steps.add(log ? new Step.ChopTree(new int[]{s.x(), s.y(), s.z()}, lack, null) : new Step.Collect(word, lack));
            c.blocks += 2 * s.dist();                    // there and back
        }
        c.blocks += (log ? WoodOptions.cutLog() : CoalOptions.mineOre()) * lack;
        c.bag.merge(word, lack, Integer::sum);
        String how = (log ? "tree" : "dig " + word) + " seen " + Math.round(s.dist()) + " m";
        return pre == null ? how : how + " (after " + pre + ")";
    }

    private static String explore(Ctx c, String word, String reason) {
        int minutes = exploreMinutes();
        c.steps.add(new Step.Explore(minutes));
        c.blocks += minutes * Options.MINUTE * (1 + exploreReturn());
        c.stuck = true;
        return "explore " + minutes + " min (" + reason + ")";
    }

    private static boolean stoneLike(String word) { return ItemWorth.stoneLike(word.indexOf(':') < 0 ? "minecraft:" + word : word); }

    /** The pickaxe item of the least tier that is at least {@code tier} and can be crafted, from the game's pickaxes tag; null none. */
    static String pickaxeFor(int tier) {
        String best = null;
        int bestTier = Integer.MAX_VALUE;
        for (String id : ItemWorth.withTag("pickaxes")) {
            int t = MiningTiers.toolTier(id, "pickaxe");
            if (t >= tier && t < bestTier && PlanRecipes.of(PlanFacts.canon(id)) != null) { best = id; bestTier = t; }
        }
        return best;
    }

    /** The item a craft step names: planks are the kind the bag's logs make, else the word itself. */
    private static String concrete(Ctx c, String word) {
        String bare = word.indexOf(':') < 0 ? word : word.substring(word.indexOf(':') + 1);
        if (!word.equals(PlanFacts.PLANKS)) return bare;
        List<String> planks = ItemWorth.withTag("planks");
        for (String id : planks) {
            for (ItemWorth.Recipe r : ItemWorth.recipes(id)) {
                for (String in : r.inputs()) if (c.w.bag().getOrDefault(in, 0) > 0) return id.substring(id.indexOf(':') + 1);
            }
        }
        return planks.isEmpty() ? bare : planks.get(0).substring(planks.get(0).indexOf(':') + 1);
    }
}
