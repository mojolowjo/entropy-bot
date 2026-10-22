package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;
import io.github.mojolowjo.entropybot.aa.brain.Sequencer;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.plan.PlanFacts;
import io.github.mojolowjo.entropybot.plan.PlanRecipes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * rm-bootstrap: a need that wants an item, resolved by recursion over the game's recipe data ({@link PlanRecipes}, the
 * recipe manager's crafting recipes read through {@link ItemWorth}); no second planner, no fixed order, no name list.
 * <ol>
 * <li>Have it: nothing to do.</li>
 * <li>Craftable: need its ingredients (recurse) and, for a 3x3 recipe, a crafting table in reach (recurse: one in the
 *     bag is placed, else the table is itself a craftable item made from planks, from logs); then the craft (a GUI job,
 *     Baritone has no crafting).</li>
 * <li>Else a block that drops it, seen (a {@link Spot}: a tree for any log, stone for a stone-like item; the {@link Nearby}
 *     fact): the walk there and Baritone's MineProcess (the existing cut and collect steps), after the pickaxe tier the
 *     block asks for (recurse).</li>
 * <li>Else nothing seen: the explore way, priced for that need with its return leg. The chain stops there (what comes
 *     after cannot be placed before something is seen); AA asks again when the explore ends.</li>
 * </ol>
 * simple-tools (0.39.w1): one bad branch never kills the chain. Every item has two branches, the craft and the source. A branch
 * that fails (the item is already on the path: a recipe loop; a pickaxe tier nothing can make; no cell for a table) or costs
 * more than the horizon is priced out with a named reason and the other branch is taken (the cheaper, when both stand). When
 * no branch is left, the leaf is the explore way for that item's source. So there is never a "no way"; {@link Chain#why}
 * prints the whole chain with every dropped branch for {@code aa why}. Pure (JUnit: NeedChainTest). Loader notes: none (the
 * facts come from {@code AaRuntime.toolsFacts}).
 */
public final class NeedChain {
    private NeedChain() {}

    /** A seen source: a cell to walk to, the straight-line {@code dist}, the block's id when known (its pickaxe tier comes from it). */
    public record Spot(int x, int y, int z, double dist, String block) {}

    /**
     * What the bot knows now. {@code bag}: item id -> count; {@code tableNear}: a crafting table within reach;
     * {@code tableCell}: a free cell to place one at (null none); {@code seen}: planner word ({@link PlanFacts#canon}) -> the
     * nearest source of it, null when none (a tree for "log", stone for a stone-like item; always {@link Nearby}'s answer);
     * {@code horizonBlocks}: a branch dearer than this is priced out when another stands (0: the planning horizon).
     */
    public record World(Map<String, Integer> bag, boolean tableNear, int[] tableCell, java.util.function.Function<String, Spot> seen, double horizonBlocks) {
        public World {
            Map<String, Integer> b = new LinkedHashMap<>();
            if (bag != null) for (var e : bag.entrySet()) b.merge(e.getKey().indexOf(':') < 0 ? "minecraft:" + e.getKey() : e.getKey(), e.getValue(), Integer::sum);
            bag = Map.copyOf(b);
            tableCell = tableCell == null ? null : tableCell.clone();
            seen = seen == null ? w -> null : seen;
            if (horizonBlocks <= 0) horizonBlocks = Unit.blocks(Sequencer.defaultHorizonS());
        }

        public World(Map<String, Integer> bag, boolean tableNear, int[] tableCell, java.util.function.Function<String, Spot> seen) {
            this(bag, tableNear, tableCell, seen, 0);
        }
    }

    /**
     * @param steps   the typed steps, in run order (empty: nothing to do)
     * @param cost    walk-blocks ({@link Unit}); {@code why}: the whole chain in one line, the dropped branches after it
     * @param explore the chain ends with the explore way (something needed is not seen yet)
     * @param missing why there is no way at all (null: there is one; kept for callers, the recursion no longer sets it)
     * @param dropped every branch priced out on the way, each with its reason
     */
    public record Chain(List<Step> steps, double cost, String why, boolean explore, String missing, List<String> dropped) {
        public Chain(List<Step> steps, double cost, String why, boolean explore, String missing) { this(steps, cost, why, explore, missing, List.of()); }
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
        final List<String> path = new ArrayList<>();
        final List<String> dropped = new ArrayList<>();
        double seconds;       // seconds of work (craft, place); walks are priced in blocks
        double blocks;
        boolean tableReady, stuck;
        String fail;          // the branch being tried cannot be done: the reason
        int pickTier;

        Ctx(World w) {
            this.w = w;
            for (var e : w.bag().entrySet()) if (e.getValue() > 0) bag.merge(PlanFacts.canon(e.getKey()), e.getValue(), Integer::sum);
            tableReady = w.tableNear();
            for (var e : w.bag().entrySet()) if (e.getValue() > 0) pickTier = Math.max(pickTier, PlanFacts.tier(PlanFacts.canon(e.getKey())));
        }

        boolean dead() { return stuck || fail != null; }

        double total() { return Unit.blocks(seconds) + blocks; }

        /** Everything a branch changes, to put back when the branch is dropped (the dropped list stays). */
        record Snap(Map<String, Integer> bag, List<Step> steps, Map<String, Integer> leafAt, double seconds, double blocks, boolean tableReady, boolean stuck, int pickTier) {}

        Snap snap() { return new Snap(new LinkedHashMap<>(bag), new ArrayList<>(steps), new LinkedHashMap<>(leafAt), seconds, blocks, tableReady, stuck, pickTier); }

        void restore(Snap s) {
            bag.clear(); bag.putAll(s.bag());
            steps.clear(); steps.addAll(s.steps());
            leafAt.clear(); leafAt.putAll(s.leafAt());
            seconds = s.seconds(); blocks = s.blocks(); tableReady = s.tableReady(); stuck = s.stuck(); pickTier = s.pickTier();
            fail = null;
        }
    }

    /** The chain that gets {@code n} of {@code item} into the bag from {@code w}. */
    public static Chain resolve(String item, int n, World w) {
        Ctx c = new Ctx(w);
        String word = PlanFacts.canon(item);
        String phrase = obtain(c, word, n);
        double cost = c.total();
        String drops = c.dropped.isEmpty() ? "" : " | dropped: " + String.join("; ", c.dropped);
        if (c.fail != null) return new Chain(List.copyOf(c.steps), cost, item + ": " + c.fail + drops, false, c.fail, List.copyOf(c.dropped));
        String why = (phrase == null ? item + " is in the bag" : item + " <- " + phrase) + ": " + Math.round(Unit.seconds(cost)) + " s" + drops;
        return new Chain(List.copyOf(c.steps), cost, why, c.stuck, null, List.copyOf(c.dropped));
    }

    /**
     * Makes {@code n} of {@code word} true in the simulated bag; returns the phrase of how ("3 cobblestone <- dig stone seen 12 m"),
     * null when it was there. The craft branch first (when the game has a recipe and the item is not already on the path), then
     * the source branch; a branch that fails or is over the horizon is dropped with its reason, the cheaper standing one is kept,
     * and with none standing the leaf is the explore for the item's source.
     */
    private static String obtain(Ctx c, String word, int n) {
        if (c.dead() || n <= 0) return null;
        int have = c.bag.getOrDefault(word, 0);
        if (have >= n) return null;
        int lack = n - have;
        boolean onPath = c.path.contains(word);
        PlanRecipes.Recipe rec = isLog(word) ? null : PlanRecipes.of(word);
        if (onPath && rec != null) {
            c.dropped.add("recipe branch of " + word + ": " + word + " needs itself (a recipe loop: " + String.join(" > ", c.path) + " > " + word + ")");
            rec = null;
        }
        c.path.add(word);
        try {
            Ctx.Snap s0 = c.snap();
            double base = c.total();
            if (rec == null) return leaf(c, s0, word, lack);
            String craftPhrase = craft(c, word, lack, rec);
            String craftFail = c.fail;
            double craftCost = c.total() - base;
            boolean over = craftFail == null && craftCost > c.w.horizonBlocks();
            if (craftFail == null && !over && !c.stuck) return craftPhrase;
            // the recipe branch failed, is over the horizon or ends in an explore: price the source branch against it. A source
            // is a branch only when one is seen; the explore leaf is what is left when no branch stands
            if (c.w.seen().apply(word) == null) {
                if (craftFail == null) return craftPhrase;
                c.dropped.add("recipe branch of " + word + ": " + craftFail);
                c.restore(s0);
                return exploreLeaf(c, word, "no way to make " + word + " and none seen to dig or cut");
            }
            Ctx.Snap sCraft = c.snap();
            c.restore(s0);
            String srcPhrase = source(c, word, lack);
            String srcFail = c.fail;
            double srcCost = c.total() - base;
            boolean srcOver = srcFail == null && srcCost > c.w.horizonBlocks();
            boolean craftStands = craftFail == null, srcStands = srcFail == null;
            if (craftStands && (!srcStands || preferCraft(over, c.stuck, craftWasStuck(sCraft), craftCost, srcOver, srcCost))) {
                if (srcStands) c.dropped.add("source of " + word + ": " + (c.stuck ? "ends in an explore" : "costs " + Math.round(srcCost) + " blocks") + ", the recipe is " + (over ? "dear" : "cheaper"));
                else c.dropped.add("source of " + word + ": " + srcFail);
                c.restore(sCraft);
                return craftPhrase;
            }
            if (craftFail != null) c.dropped.add("recipe branch of " + word + ": " + craftFail);
            else c.dropped.add("recipe branch of " + word + ": " + (over ? "costs " + Math.round(craftCost) + " blocks, past the horizon (" + Math.round(c.w.horizonBlocks()) + ")" : "ends in an explore") + ", the source is " + (srcStands ? "cheaper" : "the only way"));
            if (srcFail != null) {
                c.dropped.add("source of " + word + ": " + srcFail);
                c.restore(s0);
                return exploreLeaf(c, word, "no way found to " + word + " but to look for it");
            }
            return srcPhrase;
        } finally {
            c.path.remove(c.path.size() - 1);
        }
    }

    private static boolean craftWasStuck(Ctx.Snap s) { return s.stuck(); }

    /** True when the craft branch is the one to keep over the source branch (both stand). */
    private static boolean preferCraft(boolean craftOver, boolean srcStuck, boolean craftStuck, double craftCost, boolean srcOver, double srcCost) {
        if (craftStuck != srcStuck) return !craftStuck;         // a branch that ends in an explore loses to one that does not
        if (craftOver != srcOver) return !craftOver;
        return craftCost <= srcCost;
    }

    /** No recipe (or it is dropped): the source branch alone; a failure there is the explore leaf. */
    private static String leaf(Ctx c, Ctx.Snap s0, String word, int lack) {
        String p = source(c, word, lack);
        if (c.fail == null) return p;
        String why = c.fail;
        c.dropped.add("source of " + word + ": " + why);
        c.restore(s0);
        return exploreLeaf(c, word, "no way found to " + word + " but to look for it");
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
            if (c.fail != null) return null;
            parts.add(p == null ? want + " " + e.getKey() + " (in the bag)" : want + " " + e.getKey() + " <- " + p);
            if (c.stuck) return String.join(" + ", parts);                 // something needed is not seen: the chain ends at the explore
            c.bag.merge(e.getKey(), -want, Integer::sum);
        }
        if (rec.table()) {
            String t = table(c);
            if (c.fail != null) return null;
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
        if (c.w.tableCell() == null) { c.fail = "no free cell to place a crafting table at"; return null; }
        String made = obtain(c, "crafting_table", 1);
        if (c.fail != null) return null;
        if (c.stuck) return made;
        int[] at = c.w.tableCell();
        c.steps.add(Step.Build.one("crafting_table", at[0], at[1], at[2]));
        c.seconds += Price.placeS();
        c.bag.merge("crafting_table", -1, Integer::sum);
        c.tableReady = true;
        return made == null ? "in the bag, placed" : made + ", placed";
    }

    /** A block that drops the word, seen (the Nearby fact): walk and mine; else the explore leaf. A failure (c.fail) is for the caller to price out. */
    private static String source(Ctx c, String word, int lack) {
        Spot s = c.w.seen().apply(word);
        boolean log = isLog(word);
        if (s == null) return exploreLeaf(c, word, log ? "no tree seen" : !stoneLike(word) && PlanRecipes.of(word) == null ? "no recipe and no block I know drops " + word : "no " + word + " source seen");
        // the pickaxe tier the block asks for, as a sub-need of its own
        String pre = null;
        int tier = s.block() == null ? 0 : Math.max(0, MiningTiers.blockTierOf(s.block()));
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null) return null;
            if (c.stuck) return "dig " + word + " seen " + Math.round(s.dist()) + " m (after " + pre + ")";      // the pickaxe's own source is still to find: the chain ends at that explore
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

    /** The leaf when no branch stands: look for the item's source, priced with the walk back. */
    private static String exploreLeaf(Ctx c, String word, String reason) {
        int minutes = exploreMinutes();
        c.steps.add(new Step.Explore(minutes));
        c.blocks += minutes * Options.MINUTE * (1 + exploreReturn());
        c.stuck = true;
        return "explore " + minutes + " min (" + reason + ")";
    }

    private static boolean stoneLike(String word) { return ItemWorth.stoneLike(word.indexOf(':') < 0 ? "minecraft:" + word : word); }

    /**
     * A requirement several items meet (any pickaxe of at least {@code tier}, from the game's pickaxes tag): every candidate is
     * priced in full, recursively (loops and dead branches priced out as everywhere), tried on a snapshot and put back; the
     * cheapest is then done for real and each loser goes to the dropped list with its price and reason. Ties (chains that end at the same explore cost the same): the less raw work the recipe tree still holds (a loop holds infinite), then the lower tier.
     * No candidate stands: c.fail. Returns the winner's phrase.
     */
    private static String cheapestPickaxe(Ctx c, int tier) {
        record Trial(String id, int tier, double cost, double work, boolean stuck, String fail) {}
        List<Trial> trials = new ArrayList<>();
        for (String id : ItemWorth.withTag("pickaxes")) {
            int t = MiningTiers.toolTier(id, "pickaxe");
            String cw = PlanFacts.canon(id);
            if (t < tier || PlanRecipes.of(cw) == null) continue;
            if (c.path.contains(cw)) { c.dropped.add(cw + ": on the path already, a loop"); continue; }
            Ctx.Snap s0 = c.snap();
            int drops = c.dropped.size();
            double base = c.total();
            obtain(c, cw, 1);
            trials.add(new Trial(cw, t, c.total() - base, rawWork(cw, new ArrayList<>()), c.stuck, c.fail));
            c.restore(s0);
            while (c.dropped.size() > drops) c.dropped.remove(c.dropped.size() - 1);
        }
        Trial win = null;
        for (Trial t : trials) {
            if (t.fail() != null) continue;
            if (win == null || t.cost() < win.cost() || (t.cost() == win.cost() && (t.work() < win.work() || (t.work() == win.work() && t.tier() < win.tier())))) win = t;
        }
        if (win == null) {
            for (Trial t : trials) c.dropped.add(t.id() + ": " + Math.round(t.cost()) + " blocks (" + t.fail() + ")");
            c.fail = "no pickaxe of tier " + tier + " can be had to dig" + (trials.isEmpty() ? ": none in the game data can be crafted" : "");
            return null;
        }
        for (Trial t : trials) {
            if (t == win) continue;
            c.dropped.add(t.id() + ": " + Math.round(t.cost()) + " blocks (" + (t.fail() != null ? t.fail() : (t.stuck() ? "ends in an explore, " : "") + "dearer than " + win.id()) + ")");
        }
        return obtain(c, win.id(), 1);
    }
    /** The raw items a word's whole recipe tree takes (recipe data only); a recipe loop is infinite work. Breaks the tie between chains that stop at the same explore. */
    private static double rawWork(String word, List<String> path) {
        if (path.contains(word)) return Double.POSITIVE_INFINITY;
        PlanRecipes.Recipe rec = isLog(word) ? null : PlanRecipes.of(word);
        if (rec == null) return 1;
        path.add(word);
        double sum = 0;
        for (var e : rec.in().entrySet()) sum += e.getValue() * rawWork(e.getKey(), path);
        path.remove(path.size() - 1);
        return sum / rec.out();
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
