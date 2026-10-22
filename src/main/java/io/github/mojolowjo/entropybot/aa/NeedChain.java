package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;
import io.github.mojolowjo.entropybot.aa.brain.Sequencer;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.plan.PlanFacts;
import io.github.mojolowjo.entropybot.plan.PlanRecipes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * rm-bootstrap: a need that wants an item, resolved by recursion over the game's recipe data ({@link PlanRecipes}, the
 * recipe manager's crafting recipes read through {@link ItemWorth}); no second planner, no fixed order, no name list.
 * SCENARIOS (0.39.x1): every price is in seconds (TIME_MODEL) and every requirement that has several ways prices each and takes
 * the cheapest; the others go to the dropped list with their seconds.
 * <ul>
 * <li>Have it: nothing to do.</li>
 * <li>A way is a recipe (each distinct crafting recipe of the item: its ingredients, recursively, then the craft; a 3x3 recipe
 *     needs a table in reach, itself an item), a furnace (the raw input, a furnace, fuel, 10 s an item), or a seen source (each
 *     seen tree or ore/stone block: the walk, {@link GameTimes#breakSeconds} per block, after the pickaxe tier the block asks
 *     for, itself the cheapest of the pickaxes tag).</li>
 * <li>An item that is already on the path is a loop: that way is dropped.</li>
 * <li>Explore is not a priced way. A way that ends where nothing is known to give the item ("no known source") never competes
 *     with one that does not; when no way is left the chain's step is the explore for that block, with no price.</li>
 * </ul>
 * Pure (JUnit: NeedChainTest, ScenariosTest). Loader notes: none (the facts come from {@code AaRuntime.toolsFacts}).
 */
public final class NeedChain {
    private NeedChain() {}

    /** A seen source: a cell to walk to, the straight-line {@code dist}, the block's id when known (its pickaxe tier comes from it), how many blocks there are (0: not counted, as many as needed). */
    public record Spot(int x, int y, int z, double dist, String block, int count) {
        public Spot(int x, int y, int z, double dist, String block) { this(x, y, z, dist, block, 0); }
        String key() { return x + "," + y + "," + z; }
    }

    /**
     * What the bot knows now. {@code bag}: item id -> count; {@code tableNear} / {@code furnaceNear}: one within reach;
     * {@code seen}: planner word ({@link PlanFacts#canon}) -> the
     * nearest source of it, null when none (a tree for "log", stone for a stone-like item; always {@link Nearby}'s answer);
     * {@code seenAll}: every source seen for the word (null: just {@code seen}'s one); {@code horizonBlocks}: a way dearer than
     * this is priced out when another stands (0: the planning horizon).
     */
    public record World(Map<String, Integer> bag, boolean tableNear, Function<String, Spot> seen, double horizonBlocks,
                        Function<String, List<Spot>> seenAll, boolean furnaceNear) {
        public World {
            Map<String, Integer> b = new LinkedHashMap<>();
            if (bag != null) for (var e : bag.entrySet()) b.merge(e.getKey().indexOf(':') < 0 ? "minecraft:" + e.getKey() : e.getKey(), e.getValue(), Integer::sum);
            bag = Map.copyOf(b);
            Function<String, Spot> one = seen == null ? w -> null : seen;
            seen = one;
            if (seenAll == null) seenAll = w -> { Spot s = one.apply(w); return s == null ? List.of() : List.of(s); };
            if (horizonBlocks <= 0) horizonBlocks = Unit.blocks(Sequencer.defaultHorizonS());
        }

        public World(Map<String, Integer> bag, boolean tableNear, Function<String, Spot> seen, double horizonBlocks) {
            this(bag, tableNear, seen, horizonBlocks, null, false);
        }

        public World(Map<String, Integer> bag, boolean tableNear, Function<String, Spot> seen) {
            this(bag, tableNear, seen, 0, null, false);
        }
    }

    /**
     * @param steps   the typed steps, in run order (empty: nothing to do)
     * @param cost    walk-blocks ({@link Unit}); {@code why}: the whole chain in one line, the dropped ways after it
     * @param explore the chain ends with the explore way (something needed is not seen yet): it has no price in {@code why}
     * @param missing why there is no way at all (null: there is one; kept for callers, the recursion no longer sets it)
     * @param dropped every way priced out on the way, each with its reason (seconds, or "no known source", or "loop")
     */
    public record Chain(List<Step> steps, double cost, String why, boolean explore, String missing, List<String> dropped) {
        public Chain(List<Step> steps, double cost, String why, boolean explore, String missing) { this(steps, cost, why, explore, missing, List.of()); }
        public boolean ok() { return missing == null; }
        /** The chain's price in seconds (the explore leg of an explore chain is in {@code cost}, not here). */
        public double seconds() { return Unit.seconds(cost); }
    }

    public static int exploreMinutes() { return io.github.mojolowjo.entropybot.aa.brain.Discovery.exploreMinutes(); }
    /** Share of the explore time the walk back costs again. */
    public static double exploreReturn() { return Tuning.get("needChain.exploreReturn"); }

    /** Game fact: one fuel item (coal, charcoal) burns 1600 ticks, a smelt takes 200: eight items. */
    private static final int ITEMS_PER_FUEL = 8; // game fact: coal burns 1600 ticks, a furnace takes 200 per item

    /** TIME_MODEL: walk = straight line x 1.3 / walk speed, before Baritone has planned a path. */
    static double walkS(double dist) { return dist * StepTimes.pathFactor() / Unit.walkBps(); }

    static String s(double seconds) { return String.format(Locale.ROOT, "%.1f s", seconds); }

    // ---- the recursion ----

    private static final class Ctx {
        final World w;
        final Map<String, Integer> bag = new LinkedHashMap<>();
        final List<Step> steps = new ArrayList<>();
        final Map<String, Integer> leafAt = new LinkedHashMap<>();   // spot key -> index of its collect step
        final Map<String, Integer> taken = new LinkedHashMap<>();    // spot key -> blocks taken from it
        final List<String> path = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        double seconds;       // everything priced so far, in seconds
        double away;          // straight metres from the last new source to where the stations are (0: at them)
        boolean tableReady, furnaceReady, stuck;
        String fail;          // the way being tried cannot be done: the reason
        String unknown;       // the first item nothing is known to give (the explore leaf's)
        String loopOn;        // the item a loop failure came back to (set with fail)
        String pickItem;      // the best pickaxe in hand or made
        int pickTier;

        Ctx(World w) {
            this.w = w;
            for (var e : w.bag().entrySet()) if (e.getValue() > 0) bag.merge(PlanFacts.canon(e.getKey()), e.getValue(), Integer::sum);
            tableReady = w.tableNear();
            furnaceReady = w.furnaceNear();
            for (var e : w.bag().entrySet()) {
                if (e.getValue() <= 0) continue;
                int t = PlanFacts.tier(PlanFacts.canon(e.getKey()));
                if (t > pickTier) { pickTier = t; pickItem = e.getKey(); }
            }
        }

        boolean dead() { return stuck || fail != null; }

        /** Everything a way changes, to put back when the way is dropped. */
        record Snap(Map<String, Integer> bag, List<Step> steps, Map<String, Integer> leafAt, Map<String, Integer> taken, List<String> dropped,
                    double seconds, double away, boolean tableReady, boolean furnaceReady, boolean stuck, String unknown, String pickItem, int pickTier) {}

        Snap snap() {
            return new Snap(new LinkedHashMap<>(bag), new ArrayList<>(steps), new LinkedHashMap<>(leafAt), new LinkedHashMap<>(taken), new ArrayList<>(dropped),
                    seconds, away, tableReady, furnaceReady, stuck, unknown, pickItem, pickTier);
        }

        void restore(Snap s) {
            bag.clear(); bag.putAll(s.bag());
            steps.clear(); steps.addAll(s.steps());
            leafAt.clear(); leafAt.putAll(s.leafAt());
            taken.clear(); taken.putAll(s.taken());
            dropped = new ArrayList<>(s.dropped());
            seconds = s.seconds(); away = s.away(); tableReady = s.tableReady(); furnaceReady = s.furnaceReady(); stuck = s.stuck();
            unknown = s.unknown(); pickItem = s.pickItem(); pickTier = s.pickTier();
            fail = null; loopOn = null;
        }
    }

    /** The chain that gets {@code n} of {@code item} into the bag from {@code w}. */
    public static Chain resolve(String item, int n, World w) {
        Ctx c = new Ctx(w);
        String word = PlanFacts.canon(item);
        String phrase = obtain(c, word, n);
        double cost = Unit.blocks(c.seconds);
        // an explore has no price in the chain; the scorer still needs a number to weigh the whole row, so the row's cost carries the explore leg
        if (c.stuck) cost += exploreMinutes() * Options.MINUTE * (1 + exploreReturn());
        String drops = c.dropped.isEmpty() ? "" : " | dropped: " + String.join("; ", c.dropped);
        if (c.fail != null) return new Chain(List.copyOf(c.steps), cost, item + ": " + c.fail + drops, false, c.fail, List.copyOf(c.dropped));
        String head = phrase == null ? item + " is in the bag" : item + " <- " + phrase;
        String why = head + (c.stuck ? "" : ": " + s(c.seconds)) + drops;
        return new Chain(List.copyOf(c.steps), cost, why, c.stuck, null, List.copyOf(c.dropped));
    }

    /** One way to get a requirement: what runs it on the simulated bag, its name for the dropped list, and what breaks a tie. */
    private static final class Alt {
        final String label;
        final Supplier<String> run;
        String id;
        int tier;
        double work;
        Alt(String label, Supplier<String> run) { this.label = label; this.run = run; }
    }

    private record Trial(Alt alt, String phrase, double seconds, boolean stuck, String fail, String loopOn, String unknown, Ctx.Snap after, int index) {}

    /**
     * Prices every way on a snapshot (each put back), takes the cheapest that stands (no failure, no explore in it; past the
     * horizon last), restores the winner's state and lists every other with its seconds, its loop or its "no known source".
     * Null: no way stands and none ends in an explore (every failure is in the dropped list); the caller makes the explore leaf.
     */
    private static Trial choose(Ctx c, List<Alt> alts, String self) {
        if (alts.isEmpty()) return null;
        List<Trial> trials = new ArrayList<>();
        for (int i = 0; i < alts.size(); i++) {
            Ctx.Snap s0 = c.snap();
            double base = c.seconds;
            String p = alts.get(i).run.get();
            trials.add(new Trial(alts.get(i), p, c.seconds - base, c.stuck, c.fail, c.loopOn, c.unknown, c.snap(), i));
            c.restore(s0);
        }
        double horizonS = Unit.seconds(c.w.horizonBlocks());
        Comparator<Trial> best = Comparator.<Trial>comparingInt(t -> t.stuck() ? 1 : 0)
                .thenComparingInt(t -> t.seconds() > horizonS ? 1 : 0)
                .thenComparingDouble(t -> t.stuck() ? 0 : t.seconds())
                .thenComparingDouble(t -> t.alt().work).thenComparingInt(t -> t.alt().tier).thenComparingInt(Trial::index);
        Trial win = null;
        for (Trial t : trials) if (t.fail() == null && (win == null || best.compare(t, win) < 0)) win = t;
        if (win == null) {
            // every way failed by a loop back to an item above this one: this requirement is part of that loop, the failure goes up
            // to the way that asked for it (it is listed there); a loop back to this very item, or any other failure, is listed here
            boolean up = self != null && trials.stream().allMatch(t -> t.loopOn() != null && !t.loopOn().equals(self));
            if (up) { c.fail = trials.get(0).fail(); c.loopOn = trials.get(0).loopOn(); }
            else for (Trial t : trials) c.dropped.add(t.alt().label + ": " + t.fail());
            return null;
        }
        c.restore(win.after());
        for (Trial t : trials) {
            if (t == win) continue;
            if (t.fail() != null) c.dropped.add(t.alt().label + ": " + t.fail());
            else if (t.stuck()) { if (!win.stuck()) c.dropped.add(t.alt().label + ": no known source for " + t.unknown()); }
            else c.dropped.add(t.alt().label + ": " + s(t.seconds()) + " (dearer than " + win.alt().label + (t.seconds() > horizonS ? ", past the horizon" : "") + ")");
        }
        return win;
    }

    /**
     * Makes {@code n} of {@code word} true in the simulated bag; returns the phrase of how ("craft 3 cobblestone <- dig cobblestone seen 12 m"),
     * null when it was there. The ways: each recipe, the furnace, each seen source. None left: the explore leaf for it.
     */
    private static String obtain(Ctx c, String word, int n) {
        if (c.dead() || n <= 0) return null;
        int have = c.bag.getOrDefault(word, 0);
        if (have >= n) return null;
        int lack = n - have;
        if (c.path.contains(word)) {
            c.fail = "loop: " + word + " needs itself (a recipe loop: " + String.join(" > ", c.path) + " > " + word + ")";
            c.loopOn = word;
            return null;
        }
        c.path.add(word);
        try {
            List<Alt> alts = new ArrayList<>();
            if (!isLog(word)) {
                for (PlanRecipes.Recipe r : PlanRecipes.allOf(word)) alts.add(new Alt(word + " from " + String.join("+", r.in().keySet()), () -> craft(c, word, lack, r)));
                String raw = PlanRecipes.smelted(word);
                if (raw != null) alts.add(new Alt("smelting " + word, () -> smelt(c, word, raw, lack)));
            }
            alts.addAll(sourceAlts(c, word, lack));
            Trial w = choose(c, alts, word);
            if (w != null) return w.phrase();
            if (c.fail != null) return null;
            return exploreLeaf(c, word, alts.isEmpty() ? noSource(word) : "no way found to " + word + " but to look for it");
        } finally {
            c.path.remove(c.path.size() - 1);
        }
    }

    private static String noSource(String word) {
        if (isLog(word)) return "no tree seen";
        return !stoneLike(word) && PlanRecipes.of(word) == null && PlanRecipes.smelted(word) == null ? "no recipe and no block I know drops " + word : "no " + word + " source seen";
    }

    private static boolean rawUnseen(Ctx c, String word) {
        return c.bag.getOrDefault(word, 0) <= 0 && (isLog(word) || (PlanRecipes.allOf(word).isEmpty() && PlanRecipes.smelted(word) == null)) && c.w.seenAll().apply(word).isEmpty();
    }

    private static boolean isLog(String word) { return word.equals("log"); }

    private static String craft(Ctx c, String word, int lack, PlanRecipes.Recipe rec) {
        int batches = (lack + rec.out() - 1) / rec.out();
        List<String> parts = new ArrayList<>();
        // what can be made or fetched first; a raw source nothing has seen yet last (its explore ends the chain, so it must not hide the rest)
        List<Map.Entry<String, Integer>> ins = new ArrayList<>(rec.in().entrySet());
        ins.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> rawUnseen(c, e.getKey()) ? 1 : 0));
        for (var e : ins) {
            int want = e.getValue() * batches;
            String p = obtain(c, e.getKey(), want);
            if (c.fail != null) return null;
            parts.add(p == null ? want + " " + e.getKey() : want + " " + e.getKey() + " <- " + p);
            if (c.stuck) return "craft: " + String.join(" + ", parts);                 // something needed is not known: the chain ends at the explore
            c.bag.merge(e.getKey(), -want, Integer::sum);
        }
        if (rec.table()) {
            String t = station(c, "crafting_table", true);
            if (c.fail != null) return null;
            if (t != null) parts.add("table <- " + t);
            if (c.stuck) return "craft: " + String.join(" + ", parts);
            walkBack(c);
        }
        c.steps.add(new Step.Craft(concrete(c, word), batches * rec.out()));
        c.seconds += Price.craftS() * batches;
        c.bag.merge(word, batches * rec.out(), Integer::sum);
        return "craft: " + String.join(" + ", parts);
    }

    /** A furnace turns the raw input into the word: the input, a furnace in reach, fuel, 10 s an item, the walk back to the furnace. */
    private static String smelt(Ctx c, String word, String raw, int lack) {
        List<String> parts = new ArrayList<>();
        String p = obtain(c, raw, lack);
        if (c.fail != null) return null;
        parts.add(p == null ? lack + " " + raw : lack + " " + raw + " <- " + p);
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        c.bag.merge(raw, -lack, Integer::sum);
        String f = station(c, "furnace", false);
        if (c.fail != null) return null;
        if (f != null) parts.add("furnace <- " + f);
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        int fuel = (lack + ITEMS_PER_FUEL - 1) / ITEMS_PER_FUEL;
        String fp = obtain(c, "fuel", fuel);
        if (c.fail != null) return null;
        if (fp != null) parts.add(fuel + " fuel <- " + fp);
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        c.bag.merge("fuel", -fuel, Integer::sum);
        walkBack(c);
        c.steps.add(new Step.Smelt(concrete(c, word), lack, true));
        c.seconds += lack * Tuning.get("craftJob.smeltTicks") / GameTimes.TPS;
        c.bag.merge(word, lack, Integer::sum);
        return "smelt " + String.join(" + ", parts);
    }

    /**
     * A crafting table or furnace in reach is an item like any other: placed and known -> used (0 s); in the bag or made by the same recursion -> 
     * "place it near me" (Baritone's builder picks the cell; if it cannot, the step fails with its reason). Null when one already is in reach.
     */
    private static String station(Ctx c, String item, boolean table) {
        if (table ? c.tableReady : c.furnaceReady) return null;
        String made = obtain(c, item, 1);
        if (c.fail != null) return null;
        if (c.stuck) return made;
        c.steps.add(new Step.PlaceNear(item));
        c.seconds += Price.placeS();
        c.bag.merge(item, -1, Integer::sum);
        if (table) c.tableReady = true; else c.furnaceReady = true;
        c.away = 0;                                       // placed near where the bot stands
        return made == null ? "in the bag, placed" : made + ", placed";
    }

    /** Back to the stations after a trip to a new source: the straight walk. */
    private static void walkBack(Ctx c) {
        if (c.away > 0) { c.seconds += walkS(c.away); c.away = 0; }
    }

    /** The ways a seen block gives the word: one per seen source (nearest others fill what it lacks). */
    private static List<Alt> sourceAlts(Ctx c, String word, int lack) {
        List<Spot> spots = new ArrayList<>(c.w.seenAll().apply(word));
        spots.sort(Comparator.comparingDouble(Spot::dist));
        List<Alt> alts = new ArrayList<>();
        boolean log = isLog(word);
        for (Spot first : spots) {
            List<Spot> order = new ArrayList<>();
            order.add(first);
            for (Spot o : spots) if (o != first) order.add(o);
            alts.add(new Alt((log ? "tree " : word + " ") + Math.round(first.dist()) + " m", () -> sourceFrom(c, word, lack, order)));
        }
        return alts;
    }

    /** Walk to the first spot (and on to the next while it has too few blocks) and break: after the pickaxe tier the block asks for. */
    private static String sourceFrom(Ctx c, String word, int lack, List<Spot> order) {
        boolean log = isLog(word);
        Spot first = order.get(0);
        String pre = null;
        int tier = tierOf(word, first, log);
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null) return null;
            if (c.stuck) return "dig " + word + " seen " + Math.round(first.dist()) + " m (after " + pre + ")";      // the pickaxe's own source is still to find: the chain ends at that explore
        }
        int remaining = lack;
        for (Spot sp : order) {
            if (remaining <= 0) break;
            int avail = sp.count() <= 0 ? remaining : sp.count() - c.taken.getOrDefault(sp.key(), 0);
            int take = Math.min(avail, remaining);
            if (take <= 0) continue;
            Integer at = c.leafAt.get(sp.key());
            if (at != null) {
                Step prev = c.steps.get(at);
                c.steps.set(at, prev instanceof Step.ChopTree t ? new Step.ChopTree(t.at(), t.logs() + take, t.log())
                        : new Step.Collect(((Step.Collect) prev).item(), ((Step.Collect) prev).n() + take));
            } else {
                c.leafAt.put(sp.key(), c.steps.size() + 1);
                c.steps.add(new Step.Walk(sp.x(), sp.y(), sp.z(), (int) Tuning.get("steps.walkNear")));
                c.steps.add(log ? new Step.ChopTree(new int[]{sp.x(), sp.y(), sp.z()}, take, null) : new Step.Collect(word, take));
                c.seconds += walkS(sp.dist());
                c.away = sp.dist();
            }
            c.seconds += breakS(c, word, sp, log) * take;
            c.taken.merge(sp.key(), take, Integer::sum);
            c.bag.merge(word, take, Integer::sum);
            remaining -= take;
        }
        String how = (log ? "cut tree" : "dig " + word) + " seen " + Math.round(first.dist()) + " m" + (pre == null ? "" : " with " + pre);
        if (remaining > 0) return how + " + " + exploreLeaf(c, word, "not enough " + word + " seen");
        return how;
    }

    /** The pickaxe tier the block asks for (0: none). */
    private static int tierOf(String word, Spot sp, boolean log) {
        if (log) return 0;
        String b = sp.block() != null ? sp.block() : breakBlock(word, sp, false);
        return b == null ? 0 : Math.max(0, MiningTiers.blockTierOf(b));
    }

    /** The block whose hardness prices the break: a log's own block, an ore item's ore, else the item's block. */
    private static String breakBlock(String word, Spot sp, boolean log) {
        if (log) return sp.block();
        String ore = ItemWorth.oreDropping(word.indexOf(':') < 0 ? "minecraft:" + word : word);
        return ore != null ? ore : (word.indexOf(':') < 0 ? "minecraft:" + word : word);
    }

    /**
     * Seconds to break one block of the word's source with what is held, by the game's formula ({@link GameTimes#breakSeconds}:
     * hardness x 30 / tool speed ticks, x 100 and no drop without the tool a block asks for). No hardness known: the tuned cut.
     */
    private static double breakS(Ctx c, String word, Spot sp, boolean log) {
        String block = breakBlock(word, sp, log);
        double hardness = block == null ? 0 : ItemWorth.facts(block).hardness();
        if (hardness <= 0) return log ? WoodOptions.cutLog() : CoalOptions.mineOre();
        int tier = tierOf(word, sp, log);
        if (tier < 1) return GameTimes.breakSeconds(hardness, 1, true);
        double speed = c.pickItem == null ? 1 : Math.max(1, ItemWorth.facts(c.pickItem).digSpeed());
        return GameTimes.breakSeconds(hardness, speed, c.pickTier >= tier);
    }

    /** The leaf when no way stands: look for the item's source. It has no price. */
    private static String exploreLeaf(Ctx c, String word, String reason) {
        c.steps.add(new Step.Explore(exploreMinutes()));
        c.stuck = true;
        if (c.unknown == null) c.unknown = word;
        return "explore (" + reason + ")";
    }

    private static boolean stoneLike(String word) { return ItemWorth.stoneLike(word.indexOf(':') < 0 ? "minecraft:" + word : word); }

    /**
     * A requirement several items meet (any pickaxe of at least {@code tier}, from the game's pickaxes tag): {@link #choose} prices
     * each in full (loops and unknown sources dropped as everywhere); ties: the less raw work the recipe tree still holds (a loop
     * holds infinite), then the lower tier. No candidate stands: c.fail. Returns the winner's phrase.
     */
    private static String cheapestPickaxe(Ctx c, int tier) {
        List<Alt> alts = new ArrayList<>();
        List<String> ids = new ArrayList<>(ItemWorth.withTag("pickaxes"));
        java.util.Collections.sort(ids);
        for (String id : ids) {
            int t = MiningTiers.toolTier(id, "pickaxe");
            String cw = PlanFacts.canon(id);
            if (t < tier || PlanRecipes.of(cw) == null) continue;
            Alt a = new Alt(cw, () -> obtain(c, cw, 1));
            a.id = id; a.tier = t; a.work = rawWork(cw, new ArrayList<>());
            alts.add(a);
        }
        Trial win = choose(c, alts, null);
        if (win == null) {
            c.fail = "no pickaxe of tier " + tier + " can be had to dig" + (alts.isEmpty() ? ": none in the game data can be crafted" : "");
            return null;
        }
        c.pickItem = win.alt().id;
        c.pickTier = Math.max(c.pickTier, win.alt().tier);
        return win.alt().label + " (<- " + win.phrase() + ")";
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
