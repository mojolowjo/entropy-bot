package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;
import io.github.mojolowjo.entropybot.aa.brain.Sequencer;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * one-planner (owner 2026-10-21: "AA is the planner; given items it must be able to craft anything from the game's data"):
 * the one planner. A need, an owner's order, a step that wants an item: "I need N of X", resolved by recursion over the
 * game's own books and nothing of ours. Item ids and the game's tags only (no family words, no pseudo-items).
 * <ul>
 * <li>Have it: nothing to do.</li>
 * <li>Craft it: every crafting recipe of the item ({@link ItemWorth#recipes(String, String)} kind CRAFT); an ingredient
 *     given as a tag makes every member a candidate, each priced, the cheapest wins; ingredients recurse; a 3x3 recipe
 *     needs the crafting station (the recipes' own toast symbol, {@link ItemWorth#station}), itself an item.</li>
 * <li>Smelt it: every furnace recipe (kind SMELT), its input candidates priced the same way, the furnace (an item, placed
 *     near me by Baritone's builder), and the burn: every item of the game's fuel table ({@link ItemWorth#fuels}) is a
 *     candidate, priced, the cheapest wins; 10 s an item (TIME_MODEL).</li>
 * <li>Dig it: every known block that gives it (the loot data's blocks plus the item's own block, Baritone's scan first,
 *     the world's {@link World#seenAll}); the pickaxe tier the block asks for (the mining-tier data) pulls in the cheapest
 *     pickaxe of at least that tier (the game's pickaxes tag), each candidate priced.</li>
 * <li>Hunt it: a mob whose loot drops it ({@link LootBook} MOB sources) with an animal of that kind known
 *     ({@link World#mobs}): the walk and the kills (the mob's health by the bot's hit, game facts).</li>
 * <li>Else explore until a source is known: no price (the check before the chain, SCENARIOS); for an item a block
 *     generates with (the catalog's worldgen) one Baritone goal to that height band first ({@link Step.ToBand}).</li>
 * </ul>
 * An item already on the path is a loop: that way is dropped. A way that ends in an explore never competes with one that
 * does not. Every loser goes to the dropped list with its seconds or its reason. Rough prices (RULES 14): walk, break,
 * craft, place, smelt, kill; a requirement with many members prices the {@code needChain.candidates} likeliest in full.
 * Pure (JUnit: NeedChainTest, ScenariosTest). Loader notes: none (the facts come from {@code AaRuntime.needWorld}).
 */
public final class NeedChain {
    private NeedChain() {}

    /** A seen source: a cell to walk to, the straight-line {@code dist}, the block's id (its pickaxe tier comes from it), how many blocks there are (0: not counted, as many as needed). */
    public record Spot(int x, int y, int z, double dist, String block, int count) {
        public Spot(int x, int y, int z, double dist, String block) { this(x, y, z, dist, block, 0); }
        String key() { return x + "," + y + "," + z; }
    }

    /** An animal of a kind known nearby (the sightings): its entity id, where, how far. */
    public record Mob(String type, int x, int y, int z, double dist) {}

    /**
     * What the bot knows now. {@code bag}: item id -> count; {@code tableNear} / {@code furnaceNear}: one within reach;
     * {@code seenAll}: item id -> the known blocks that give it (nearest first not needed); {@code mobs}: entity id -> the
     * animals of that kind known; {@code loot}: the game's loot data (mob drops; null: none read); {@code band}: item id ->
     * {minY, maxY} where the blocks that drop it generate (the worldgen data; null: unknown); {@code horizonBlocks}: a way
     * dearer than this is priced out when another stands (0: the planning horizon). Lookups are memoised per World.
     */
    public record World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                        Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks) {
        public World {
            Map<String, Integer> b = new LinkedHashMap<>();
            if (bag != null) for (var e : bag.entrySet()) b.merge(id(e.getKey()), e.getValue(), Integer::sum);
            bag = Map.copyOf(b);
            Function<String, List<Spot>> sa = seenAll == null ? w -> List.of() : seenAll;
            Map<String, List<Spot>> memo = new ConcurrentHashMap<>();
            seenAll = w -> memo.computeIfAbsent(id(w), k -> { List<Spot> l = sa.apply(k); return l == null ? List.of() : List.copyOf(l); });
            Function<String, List<Mob>> mb = mobs == null ? w -> List.of() : mobs;
            Map<String, List<Mob>> mmemo = new ConcurrentHashMap<>();
            mobs = w -> mmemo.computeIfAbsent(id(w), k -> { List<Mob> l = mb.apply(k); return l == null ? List.of() : List.copyOf(l); });
            if (band == null) band = w -> null;
            if (horizonBlocks <= 0) horizonBlocks = Unit.blocks(Sequencer.defaultHorizonS());
        }

        /** A world of blocks only (tests and the simple callers): the nearest source per item. */
        public World(Map<String, Integer> bag, boolean tableNear, Function<String, Spot> seen) {
            this(bag, tableNear, false, w -> { Spot s = seen == null ? null : seen.apply(w); return s == null ? List.of() : List.of(s); }, null, null, null, 0);
        }

        public World(Map<String, Integer> bag, boolean tableNear, Function<String, Spot> seen, double horizonBlocks) {
            this(bag, tableNear, false, w -> { Spot s = seen == null ? null : seen.apply(w); return s == null ? List.of() : List.of(s); }, null, null, null, horizonBlocks);
        }

        public World withMobs(Function<String, List<Mob>> m, LootBook l) { return new World(bag, tableNear, furnaceNear, seenAll, m, l, band, horizonBlocks); }

        public World withBand(Function<String, int[]> b) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, b, horizonBlocks); }
    }

    /**
     * @param steps   the typed steps, in run order (empty: nothing to do)
     * @param cost    walk-blocks ({@link Unit}); {@code why}: the whole chain in one line, the dropped ways after it
     * @param explore the chain ends with the explore way (something needed is not known yet): it has no price in {@code why}
     * @param missing why there is no way at all (null: there is one)
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
    static int candidates() { return Math.max(1, Tuning.i("needChain.candidates")); }
    static int maxTrials() { return Tuning.i("needChain.maxTrials"); }

    /** Game fact: the player's bare hit is 1 damage (Attributes.ATTACK_DAMAGE base), 4 full-strength hits a second (ATTACK_SPEED base). */
    private static final double HAND_DAMAGE = 1; // game fact: the player base attack damage attribute is 1
    private static final double HIT_S = 0.25; // game fact: the player base attack speed attribute is 4 hits a second
    /** Game fact: eating one item takes 32 ticks (the food's use duration): the food comparison counts it. */
    private static final double EAT_S = 1.6; // game fact: eating takes 32 ticks = 1.6 s per item
    /** Game fact: the inventory's crafting grid is 2x2: more than 4 ingredients need the crafting station. */
    private static final int GRID_2X2 = 4; // game fact: the inventory crafting grid holds 4 ingredients

    /** TIME_MODEL: walk = straight line x 1.3 / walk speed, before Baritone has planned a path. */
    static double walkS(double dist) { return dist * StepTimes.pathFactor() / Unit.walkBps(); }

    static String s(double seconds) { return String.format(Locale.ROOT, "%.1f s", seconds); }

    static String id(String w) { return w == null ? null : w.indexOf(':') < 0 ? "minecraft:" + w : w; }

    /** The id as the lines and steps write it ("minecraft:torch" -> "torch"; a modded id stays whole). */
    static String sh(String id) { return id == null ? "?" : id.startsWith("minecraft:") ? id.substring(10) : id; }

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
        int trials;           // ways priced so far (the search's bound)

        Ctx(World w) {
            this.w = w;
            for (var e : w.bag().entrySet()) if (e.getValue() > 0) bag.merge(e.getKey(), e.getValue(), Integer::sum);
            tableReady = w.tableNear();
            furnaceReady = w.furnaceNear();
            for (var e : w.bag().entrySet()) {
                if (e.getValue() <= 0) continue;
                int t = MiningTiers.toolTier(e.getKey(), "pickaxe");
                if (t > pickTier) { pickTier = t; pickItem = e.getKey(); }
            }
        }

        boolean dead() { return stuck || fail != null; }

        int have(String item) { return bag.getOrDefault(item, 0); }

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

    /**
     * The chain that gets {@code n} of {@code what} into the bag from {@code w}. {@code what}: an item id, or a tag
     * ("#minecraft:planks", or an id the game has no item for but a tag of: "planks"): any member, the cheapest.
     */
    public static Chain resolve(String what, int n, World w) {
        Ctx c = new Ctx(w);
        String tag = tagOf(what);
        if (tag != null) {
            List<String> members = ItemWorth.withTag(tag);
            if (members.isEmpty()) return finish(c, what, what + ": the game has no item and no tag called " + what);
            Got g = need(c, members, n, sh(tag) + " (any)", null);
            return finish(c, sh(what.replaceFirst("^#", "")), g == null ? sh(what) + ": " + c.fail : sh(what.replaceFirst("^#", "")) + " <- " + g.line());
        }
        String item = id(what);
        String phrase = obtain(c, item, n);
        return finish(c, sh(item), phrase == null ? sh(item) + " is in the bag" : sh(item) + " <- " + phrase);
    }

    /** The chain for one "I need N of X" step: a group (the foods: by their energy), a tag or an item. */
    public static Chain of(Step.Obtain o, World w) {
        if (o.anyOf() != null) return resolveAny(o.what(), o.anyOf(), o.n(), w, ItemWorth::energy);
        return resolve(o.what(), o.n(), w);
    }

    /** "#minecraft:planks" or "planks" (a tag the game has, no item of that id) -> the tag id; an item -> null. */
    static String tagOf(String what) {
        if (what == null) return null;
        if (what.startsWith("#")) return id(what.substring(1));
        String i = id(what);
        if (!ItemWorth.recipes(i).isEmpty() || ItemWorth.facts(i) != ItemWorth.Facts.NONE) return null;
        return ItemWorth.withTag(i).isEmpty() ? null : i;
    }

    /**
     * Any one of {@code items} ({@code n} of it), the cheapest: the food need (the plain foods by their FOOD data). With
     * {@code energy} (the food's nutrition + saturation) the members compare by seconds per point, eating counted (32 ticks an
     * item, game fact), so 8 cooked beef beat 8 raw when a furnace is at hand.
     */
    public static Chain resolveAny(String label, List<String> items, int n, World w, ToDoubleFunction<String> energy) {
        Ctx c = new Ctx(w);
        List<String> ids = new ArrayList<>();
        for (String i : items) ids.add(id(i));
        if (ids.isEmpty()) return finish(c, label, label + ": the game's data names no member");
        Got g = need(c, ids, n, label, energy);
        if (g == null) return finish(c, label, label + ": " + c.fail);
        return finish(c, label, label + " <- " + g.line());
    }

    /** Blackboard fact: a {@code Supplier<World>} the adapter installs (what the chains start from); the needs and the owner's goals read it. */
    public static final String WORLD_FACT = "aa.needWorld";

    /**
     * A group of items as one job (one table, one trip to the stone; one simulated bag, so planks and sticks are counted once):
     * {@code n} of each member, made in the given order on the same context. A member that cannot be made at all is dropped
     * with its reason; a member that ends in an explore (something it needs is not known anywhere) is dropped too when another
     * member stands without one. When every member ends in an explore the chain is the first one's, ending in that explore.
     */
    public static Chain resolveAll(String group, List<String> items, int n, World w) {
        Ctx c = new Ctx(w);
        List<String> phrases = new ArrayList<>(), waiting = new ArrayList<>();
        Map<String, String> unknown = new LinkedHashMap<>();
        for (String raw : items) {
            String item = id(raw);
            if (ItemWorth.recipes(item, ItemWorth.CRAFT).isEmpty() && ItemWorth.recipes(item, ItemWorth.SMELT).isEmpty()) { drop(c, item, "no recipe"); continue; }
            Ctx.Snap before = c.snap();
            String p = obtain(c, item, n);
            if (c.fail != null) { String why = c.fail; c.restore(before); drop(c, item, why); continue; }
            if (c.stuck) { unknown.put(item, c.unknown); c.restore(before); continue; }
            phrases.add(p == null ? item + " is in the bag" : item + " <- " + p);
        }
        if (phrases.isEmpty() && !unknown.isEmpty()) {          // nothing stands without an explore: the first member's chain ends in it
            String first = unknown.keySet().iterator().next();
            phrases.add(first + " <- " + obtain(c, first, n));
            for (String other : unknown.keySet()) if (!other.equals(first)) waiting.add(other);
            for (String other : waiting) drop(c, other, "not priced: " + first + " ends in an explore");
        } else {
            for (var e : unknown.entrySet()) drop(c, e.getKey(), "no known source for " + sh(e.getValue()));
        }
        return finish(c, group, phrases.isEmpty() ? group + ": nothing to make" : group + " <- " + String.join("; ", phrases));
    }

    /** The cheapest pickaxe of at least {@code tier} (the game's pickaxes tag) as a chain of its own: what a dig of a block that asks for that tier pulls in. */
    public static Chain resolveTool(int tier, World w) {
        Ctx c = new Ctx(w);
        String phrase = tier > c.pickTier ? cheapestPickaxe(c, tier) : null;
        return finish(c, "pickaxe", phrase == null ? "a pickaxe of tier " + tier + " is in the bag" : "pickaxe <- " + phrase);
    }

    private static Chain finish(Ctx c, String name, String head) {
        double cost = Unit.blocks(c.seconds);
        // an explore has no price in the chain; the scorer still needs a number to weigh the whole row, so the row's cost carries the explore leg
        if (c.stuck) cost += exploreMinutes() * Options.MINUTE * (1 + exploreReturn());
        String drops = c.dropped.isEmpty() ? "" : " | dropped: " + String.join("; ", c.dropped);
        if (c.fail != null) return new Chain(List.copyOf(c.steps), cost, name + ": " + c.fail + drops, false, c.fail, List.copyOf(c.dropped));
        String why = head + (c.stuck ? "" : ": " + s(c.seconds)) + drops;
        return new Chain(List.copyOf(c.steps), cost, why, c.stuck, null, List.copyOf(c.dropped));
    }

    /** One way to get a requirement: what runs it on the simulated bag, its name for the dropped list, and what breaks a tie. */
    private static final class Alt {
        final String label;
        final Supplier<String> run;
        String id;
        int tier, rank;
        double work;
        /** A member of a tag, the fuel table or a group: when it would need an explore and another member stands, it is not listed. */
        boolean quiet;
        /** Seconds -> the number the ways compare by (null: the seconds). */
        java.util.function.DoubleUnaryOperator rate;
        Alt(String label, Supplier<String> run) { this.label = label; this.run = run; }
        double value(double seconds) { return rate == null ? seconds : rate.applyAsDouble(seconds); }
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
            c.trials++;
            String p = alts.get(i).run.get();
            trials.add(new Trial(alts.get(i), p, c.seconds - base, c.stuck, c.fail, c.loopOn, c.unknown, c.snap(), i));
            c.restore(s0);
        }
        double horizonS = Unit.seconds(c.w.horizonBlocks());
        Comparator<Trial> best = Comparator.<Trial>comparingInt(t -> t.stuck() ? 1 : 0)
                .thenComparingInt(t -> t.seconds() > horizonS ? 1 : 0)
                .thenComparingDouble(t -> t.stuck() ? t.alt().rank : t.alt().value(t.seconds()))
                .thenComparingDouble(t -> t.alt().work).thenComparingInt(t -> t.alt().tier).thenComparingInt(Trial::index);
        Trial win = null;
        for (Trial t : trials) if (t.fail() == null && (win == null || best.compare(t, win) < 0)) win = t;
        if (win == null) {
            // every way failed by a loop back to an item above this one: this requirement is part of that loop, the failure goes up
            // to the way that asked for it (it is listed there); a loop back to this very item, or any other failure, is listed here
            boolean up = self != null && trials.stream().allMatch(t -> t.loopOn() != null && !t.loopOn().equals(self));
            if (up) { c.fail = trials.get(0).fail(); c.loopOn = trials.get(0).loopOn(); }
            else for (Trial t : trials) drop(c, t.alt().label, t.fail());
            return null;
        }
        c.restore(win.after());
        for (Trial t : trials) {
            if (t == win) continue;
            if (t.fail() != null) drop(c, t.alt().label, t.fail());
            else if (t.stuck()) { if (!win.stuck() && !t.alt().quiet) drop(c, t.alt().label, "no known source for " + sh(t.unknown())); }
            else if (t.alt().rate != null) drop(c, t.alt().label, s(t.seconds()) + String.format(Locale.ROOT, ", %.2f s a point with the eating (dearer a point than %s, %.2f)", t.alt().value(t.seconds()), win.alt().label, win.alt().value(win.seconds())));
            else drop(c, t.alt().label, s(t.seconds()) + " (dearer than " + win.alt().label + (t.seconds() > horizonS ? ", past the horizon" : "") + ")");
        }
        return win;
    }

    /** The dropped list names each losing candidate once: the same requirement is asked again per batch, the label is listed the first time only. */
    private static void drop(Ctx c, String label, String why) {
        String head = label + ": ";
        for (String d : c.dropped) if (d.startsWith(head)) return;
        c.dropped.add(head + why);
    }

    /** What one requirement got: the member that won and its phrase (null: it was in the bag). */
    private record Got(String id, int n, String phrase) {
        String line() { return phrase == null ? n + " " + sh(id) + " (in the bag)" : n + " " + sh(id) + " <- " + phrase; }
        String part() { return phrase == null ? n + " " + sh(id) : n + " " + sh(id) + " <- " + phrase; }
    }

    /**
     * {@code n} of any one of {@code options} (a tag ingredient, the fuel table, a group): one member is the plain
     * {@link #obtain}; several are priced each ({@link #choose}), the likeliest {@code needChain.candidates} in full (in the
     * bag, a known source, a recipe from what is in the bag or known, then the rest; past {@code needChain.maxTrials} only
     * the first). {@code energy} (food): compare by seconds per point, eating counted. Null: none can be had (c.fail set).
     */
    private static Got need(Ctx c, List<String> options, int n, String label, ToDoubleFunction<String> energy) {
        if (options.size() == 1) {
            String only = options.get(0);
            String p = obtain(c, only, n);
            return c.fail != null ? null : new Got(only, n, p);
        }
        for (String o : options) if (c.have(o) >= n) return new Got(o, n, null);          // in the bag: nothing to price
        List<String> ranked = new ArrayList<>(options);
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (String o : ranked) rank.put(o, reach(c, o, n));
        ranked.sort(Comparator.comparingInt((String o) -> rank.get(o)).thenComparing(Comparator.naturalOrder()));
        int cap = c.trials > maxTrials() ? 1 : candidates();
        List<Alt> alts = new ArrayList<>();
        for (int i = 0; i < ranked.size() && i < cap; i++) {
            String o = ranked.get(i);
            Alt a = new Alt(sh(o), () -> obtain(c, o, n));
            a.id = o;
            a.rank = rank.get(o);
            a.quiet = true;
            if (energy != null) {
                double pts = Math.max(1e-9, n * energy.applyAsDouble(o));  // structural: epsilon
                a.rate = sec -> (sec + n * EAT_S) / pts;
            }
            alts.add(a);
        }
        if (ranked.size() > cap) drop(c, label + " (" + (ranked.size() - cap) + " more members)", "not priced: the " + cap + " likeliest were (needChain.candidates)");
        Trial win = choose(c, alts, null);
        if (win == null) {
            if (c.fail == null) c.fail = "no member of " + label + " can be had";
            return null;
        }
        return new Got(win.alt().id, n, win.phrase());
    }

    /** How likely a member is to be had cheaply, for the order and the cap: 0 in the bag, 1 a known source, 2 made from what is had or known, 3 else. */
    private static int reach(Ctx c, String item, int n) {
        if (c.have(item) > 0) return 0;
        if (!c.w.seenAll().apply(item).isEmpty() || mobKnown(c, item)) return 1;
        for (ItemWorth.Recipe r : recipesOf(item)) {
            boolean all = true;
            for (List<String> slot : r.options()) {
                boolean any = false;
                for (String o : slot) if (c.have(o) > 0 || !c.w.seenAll().apply(o).isEmpty() || mobKnown(c, o)) { any = true; break; }
                if (!any) { all = false; break; }
            }
            if (all) return 2;
        }
        return 3;
    }

    private static List<ItemWorth.Recipe> recipesOf(String item) {
        List<ItemWorth.Recipe> out = new ArrayList<>(ItemWorth.recipes(item, ItemWorth.CRAFT));
        out.addAll(ItemWorth.recipes(item, ItemWorth.SMELT));
        return out;
    }

    private static boolean mobKnown(Ctx c, String item) {
        if (c.w.loot() == null) return false;
        for (LootBook.Source s : c.w.loot().sources(item)) if (s.kind() == LootBook.Kind.MOB && !c.w.mobs().apply(s.from()).isEmpty()) return true;
        return false;
    }

    /**
     * Makes {@code n} of {@code item} true in the simulated bag; returns the phrase of how ("craft: 3 cobblestone <- dig cobblestone seen 12 m"),
     * null when it was there. The ways: each recipe, each furnace recipe, each known block, each known animal. None left: the explore leaf for it.
     */
    private static String obtain(Ctx c, String item, int n) {
        if (c.dead() || n <= 0) return null;
        int have = c.have(item);
        if (have >= n) return null;
        int lack = n - have;
        if (c.path.contains(item)) {
            c.fail = "loop: " + sh(item) + " needs itself (a recipe loop: " + String.join(" > ", c.path.stream().map(NeedChain::sh).toList()) + " > " + sh(item) + ")";
            c.loopOn = item;
            return null;
        }
        c.path.add(item);
        try {
            List<Alt> alts = new ArrayList<>();
            for (ItemWorth.Recipe r : ItemWorth.recipes(item, ItemWorth.CRAFT)) {
                if (packing(item, r)) continue;
                alts.add(new Alt(sh(item) + " from " + ingredients(r), () -> craft(c, item, lack, r)));
            }
            for (ItemWorth.Recipe r : ItemWorth.recipes(item, ItemWorth.SMELT)) alts.add(new Alt("smelting " + sh(item) + " from " + ingredients(r), () -> smelt(c, item, lack, r)));
            alts.addAll(sourceAlts(c, item, lack));
            alts.addAll(huntAlts(c, item, lack));
            Trial w = choose(c, alts, item);
            if (w != null) return w.phrase();
            if (c.fail != null) return null;
            return exploreLeaf(c, item, noSource(c, item));
        } finally {
            c.path.remove(c.path.size() - 1);
        }
    }

    /** A recipe that takes the item itself (a packing, a dye-over): not a way to make it. */
    private static boolean packing(String item, ItemWorth.Recipe r) {
        for (List<String> slot : r.options()) if (slot.contains(item)) return true;
        return false;
    }

    /** "oak_planks+stick", a tag slot as its first member and how many more ("oak_planks|+10"). */
    private static String ingredients(ItemWorth.Recipe r) {
        List<String> out = new ArrayList<>();
        for (List<String> slot : r.options()) {
            String t = slotLabel(slot);
            if (!out.contains(t)) out.add(t);
        }
        return String.join("+", out);
    }

    private static String slotLabel(List<String> slot) { return sh(slot.get(0)) + (slot.size() > 1 ? "|+" + (slot.size() - 1) : ""); }

    private static String noSource(Ctx c, String item) {
        return "no " + sh(item) + " source known";
    }

    /** True when nothing makes it and nothing known gives it (its explore ends the chain, so it is asked for last). */
    private static boolean rawUnseen(Ctx c, List<String> slot) {
        for (String o : slot) {
            if (c.have(o) > 0 || !recipesOf(o).isEmpty() || !c.w.seenAll().apply(o).isEmpty() || mobKnown(c, o)) return false;
        }
        return true;
    }

    private static String craft(Ctx c, String item, int lack, ItemWorth.Recipe rec) {
        int batches = (lack + rec.outCount() - 1) / rec.outCount();
        List<String> parts = new ArrayList<>();
        // the slots, the same choice counted once (4 planks of one tag); what can be made or fetched first, a raw source nothing knows last
        Map<List<String>, Integer> slots = new LinkedHashMap<>();
        for (List<String> slot : rec.options()) slots.merge(slot, 1, Integer::sum);
        List<Map.Entry<List<String>, Integer>> ins = new ArrayList<>(slots.entrySet());
        ins.sort(Comparator.comparingInt((Map.Entry<List<String>, Integer> e) -> rawUnseen(c, e.getKey()) ? 1 : 0));
        for (var e : ins) {
            int want = e.getValue() * batches;
            Got g = need(c, e.getKey(), want, slotLabel(e.getKey()), null);
            if (c.fail != null || g == null) { if (c.fail == null) c.fail = "no " + sh(e.getKey().get(0)) + " can be had"; return null; }
            parts.add(g.part());
            if (c.stuck) return "craft: " + String.join(" + ", parts);                 // something needed is not known: the chain ends at the explore
            c.bag.merge(g.id(), -want, Integer::sum);
        }
        if (ItemWorth.needsTable(item) || rec.inputs().size() > GRID_2X2) {
            String t = station(c, ItemWorth.CRAFT);
            if (c.fail != null) return null;
            if (t != null) parts.add("table <- " + t);
            if (c.stuck) return "craft: " + String.join(" + ", parts);
            walkBack(c);
        }
        c.steps.add(new Step.Craft(sh(item), batches * rec.outCount()));
        c.seconds += Price.craftS() * batches;
        c.bag.merge(item, batches * rec.outCount(), Integer::sum);
        return "craft: " + String.join(" + ", parts);
    }

    /**
     * A furnace turns the input into the item: the input (any member of the recipe's ingredient, priced), the furnace in
     * reach, the burn (any item of the game's fuel table, priced: its burn ticks / a smelt's ticks items per piece), 10 s an
     * item, the walk back to the furnace.
     */
    private static String smelt(Ctx c, String item, int lack, ItemWorth.Recipe rec) {
        int k = (lack + rec.outCount() - 1) / rec.outCount();
        List<String> parts = new ArrayList<>();
        Got in = need(c, rec.options().get(0), k, slotLabel(rec.options().get(0)), null);
        if (c.fail != null || in == null) { if (c.fail == null) c.fail = "no " + sh(rec.inputs().get(0)) + " can be had"; return null; }
        parts.add(in.part());
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        c.bag.merge(in.id(), -k, Integer::sum);
        String f = station(c, ItemWorth.SMELT);
        if (c.fail != null) return null;
        if (f != null) parts.add("furnace <- " + f);
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        String burn = burn(c, k);
        if (c.fail != null) return null;
        parts.add(burn);
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        walkBack(c);
        c.steps.add(new Step.Smelt(sh(item), k * rec.outCount(), true));
        c.seconds += k * smeltTicks() / GameTimes.TPS;
        c.bag.merge(item, k * rec.outCount(), Integer::sum);
        return "smelt " + String.join(" + ", parts);
    }

    static double smeltTicks() { return Tuning.get("craftJob.smeltTicks"); }

    /** The burn for {@code k} smelts: every item of the game's fuel table is a candidate (count = k x smelt ticks / its burn ticks), the cheapest wins. */
    private static String burn(Ctx c, int k) {
        Map<String, Integer> fuels = ItemWorth.fuels();
        if (fuels.isEmpty()) { c.fail = "the game's fuel table is empty (no data loaded)"; return null; }
        Map<String, Integer> count = new LinkedHashMap<>();
        for (var e : fuels.entrySet()) if (e.getValue() > 0) count.put(e.getKey(), (int) Math.ceil(k * smeltTicks() / e.getValue()));
        // in the bag, enough of one: burnt, no price
        for (var e : count.entrySet()) if (c.have(e.getKey()) >= e.getValue()) {
            c.bag.merge(e.getKey(), -e.getValue(), Integer::sum);
            return "burn " + e.getValue() + " " + sh(e.getKey()) + " (in the bag)";
        }
        List<String> ranked = new ArrayList<>(count.keySet());
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (String o : ranked) rank.put(o, reach(c, o, count.get(o)));
        ranked.sort(Comparator.comparingInt((String o) -> rank.get(o)).thenComparing(Comparator.naturalOrder()));
        int cap = c.trials > maxTrials() ? 1 : candidates();
        List<Alt> alts = new ArrayList<>();
        for (int i = 0; i < ranked.size() && i < cap; i++) {
            String o = ranked.get(i);
            int n = count.get(o);
            Alt a = new Alt("burn " + sh(o), () -> {
                String p = obtain(c, o, n);
                if (c.fail == null && !c.stuck) c.bag.merge(o, -n, Integer::sum);
                return "burn " + n + " " + sh(o) + (p == null ? "" : " <- " + p);
            });
            a.id = o;
            a.rank = rank.get(o);
            a.quiet = true;
            alts.add(a);
        }
        if (ranked.size() > cap) drop(c, "burn (" + (ranked.size() - cap) + " more of the fuel table)", "not priced: the " + cap + " likeliest were (needChain.candidates)");
        Trial win = choose(c, alts, null);
        if (win == null) {
            if (c.fail == null) c.fail = "nothing of the fuel table can be had";
            return null;
        }
        return win.phrase();
    }

    /**
     * The station a recipe kind needs (the crafting table, the furnace: the game's recipe data names it) is an item like any
     * other: placed and known -> used (0 s); in the bag or made by the same recursion -> "place it near me" (Baritone's
     * builder picks the cell; if it cannot, the step fails with its reason). Null when one already is in reach.
     */
    private static String station(Ctx c, String kind) {
        boolean table = ItemWorth.CRAFT.equals(kind);
        if (table ? c.tableReady : c.furnaceReady) return null;
        String item = ItemWorth.stationOf(kind);
        if (item == null) { c.fail = "the game's data names no station for " + kind + " recipes"; return null; }
        String made = obtain(c, item, 1);
        if (c.fail != null) return null;
        if (c.stuck) return made;
        c.steps.add(new Step.PlaceNear(sh(item)));
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

    // ---- the sources: a known block, a known animal ----

    /** The ways a known block gives the item: one per known block (nearest others fill what it lacks). */
    private static List<Alt> sourceAlts(Ctx c, String item, int lack) {
        List<Spot> spots = new ArrayList<>(c.w.seenAll().apply(item));
        spots.sort(Comparator.comparingDouble(Spot::dist));
        List<Alt> alts = new ArrayList<>();
        for (Spot first : spots) {
            List<Spot> order = new ArrayList<>();
            order.add(first);
            for (Spot o : spots) if (o != first) order.add(o);
            alts.add(new Alt((tree(item, first) ? "tree " : sh(item) + " ") + Math.round(first.dist()) + " m", () -> sourceFrom(c, item, lack, order)));
        }
        return alts;
    }

    /** A block the game tags as logs is a tree (Baritone's tree mining). */
    private static boolean tree(String item, Spot sp) {
        String b = sp.block() != null ? sp.block() : item;
        return ItemWorth.hasTag(b, "minecraft:logs");
    }

    /** How many of the item one block of {@code block} drops (the loot data's expectation; 1 when unknown). */
    private static double perBlock(Ctx c, String item, String block) {
        if (c.w.loot() == null || block == null) return 1;
        for (LootBook.Source s : c.w.loot().sources(item)) if (s.kind() == LootBook.Kind.BLOCK && s.from().equals(block) && s.per() > 0) return s.per();
        return 1;
    }

    /** Walk to the first spot (and on to the next while it has too few blocks) and break: after the pickaxe tier the block asks for. */
    private static String sourceFrom(Ctx c, String item, int lack, List<Spot> order) {
        Spot first = order.get(0);
        boolean log = tree(item, first);
        String pre = null;
        int tier = tierOf(item, first);
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null) return null;
            if (c.stuck) return "dig " + sh(item) + " seen " + Math.round(first.dist()) + " m (after " + pre + ")";      // the pickaxe's own source is still to find: the chain ends at that explore
        }
        int remaining = lack;
        for (Spot sp : order) {
            if (remaining <= 0) break;
            double per = perBlock(c, item, sp.block());
            int blocksWanted = (int) Math.ceil(remaining / per - 1e-9);  // structural: epsilon
            int avail = sp.count() <= 0 ? blocksWanted : sp.count() - c.taken.getOrDefault(sp.key(), 0);
            int take = Math.min(avail, blocksWanted);
            if (take <= 0) continue;
            int got = Math.min(remaining, (int) Math.floor(take * per + 1e-9));  // structural: epsilon
            if (got <= 0) got = Math.min(remaining, take);
            Integer at = c.leafAt.get(sp.key());
            if (at != null) {
                Step prev = c.steps.get(at);
                c.steps.set(at, prev instanceof Step.ChopTree t ? new Step.ChopTree(t.at(), t.logs() + got, t.log())
                        : new Step.Collect(((Step.Collect) prev).item(), ((Step.Collect) prev).n() + got));
            } else {
                c.leafAt.put(sp.key(), c.steps.size() + 1);
                c.steps.add(new Step.Walk(sp.x(), sp.y(), sp.z(), (int) Tuning.get("steps.walkNear")));
                c.steps.add(log ? new Step.ChopTree(new int[]{sp.x(), sp.y(), sp.z()}, got, null) : new Step.Collect(sh(item), got));
                c.seconds += walkS(sp.dist());
                c.away = sp.dist();
            }
            c.seconds += breakS(c, item, sp) * take;
            c.taken.merge(sp.key(), take, Integer::sum);
            c.bag.merge(item, got, Integer::sum);
            remaining -= got;
        }
        String how = (log ? "cut tree" : "dig " + sh(item)) + " seen " + Math.round(first.dist()) + " m" + (pre == null ? "" : " with " + pre);
        if (remaining > 0) return how + " + " + exploreLeaf(c, item, "not enough " + sh(item) + " known");
        return how;
    }

    /** The block a spot's break is priced on: its own id, else the item's own block. */
    private static String blockOf(String item, Spot sp) { return sp.block() != null ? sp.block() : item; }

    /** The pickaxe tier the block asks for (0: none; a tree: none). */
    private static int tierOf(String item, Spot sp) {
        if (tree(item, sp)) return 0;
        return Math.max(0, MiningTiers.blockTierOf(blockOf(item, sp)));
    }

    /**
     * Seconds to break one block by the game's formula ({@link GameTimes#breakSeconds}: hardness x 30 / tool speed ticks,
     * x 100 and no drop without the tool a block asks for). No hardness known: the tuned break of an unknown block.
     */
    private static double breakS(Ctx c, String item, Spot sp) {
        // the hardness of the item's own block when it is one (cobblestone, a log: SCENARIOS prices "break cobblestone"), else the spot's (an ore)
        double own = ItemWorth.facts(item).hardness();
        double hardness = own > 0 ? own : ItemWorth.facts(blockOf(item, sp)).hardness();
        if (hardness <= 0) return tree(item, sp) ? WoodOptions.cutLog() : Tuning.get("needChain.unknownBreakS");
        int tier = tierOf(item, sp);
        if (tier < 1) return GameTimes.breakSeconds(hardness, 1, true);
        double speed = c.pickItem == null ? 1 : Math.max(1, ItemWorth.facts(c.pickItem).digSpeed());
        return GameTimes.breakSeconds(hardness, speed, c.pickTier >= tier);
    }

    /** The ways an animal gives the item: per mob kind whose loot drops it (the game's loot data), the nearest one known. */
    private static List<Alt> huntAlts(Ctx c, String item, int lack) {
        List<Alt> alts = new ArrayList<>();
        if (c.w.loot() == null) return alts;
        for (LootBook.Source s : c.w.loot().sources(item)) {
            if (s.kind() != LootBook.Kind.MOB || s.per() <= 0) continue;
            List<Mob> known = new ArrayList<>(c.w.mobs().apply(s.from()));
            if (known.isEmpty()) continue;
            known.sort(Comparator.comparingDouble(Mob::dist));
            Mob m = known.get(0);
            alts.add(new Alt("hunt " + sh(s.from()) + " " + Math.round(m.dist()) + " m", () -> hunt(c, item, lack, s, m)));
        }
        return alts;
    }

    /** Walk to the animal, kill as many as the drop wants (its health by the bot's best hit, 4 hits a second; game facts). */
    private static String hunt(Ctx c, String item, int lack, LootBook.Source s, Mob m) {
        int kills = (int) Math.ceil(lack / s.per() - 1e-9);  // structural: epsilon
        ItemWorth.Mob kind = ItemWorth.mob(s.from());
        double hp = kind == null || Double.isNaN(kind.health()) ? Tuning.get("needChain.unknownMobHp") : kind.health();
        double dmg = HAND_DAMAGE;
        for (var e : c.bag.entrySet()) if (e.getValue() > 0) dmg = Math.max(dmg, ItemWorth.facts(e.getKey()).attack());
        double killS = Math.ceil(hp / dmg - 1e-9) * HIT_S;  // structural: epsilon
        c.steps.add(new Step.HuntAt(s.from(), kills, new int[]{m.x(), m.z()}, sh(item), lack));
        c.seconds += walkS(m.dist()) + kills * killS;
        c.away = m.dist();
        c.bag.merge(item, lack, Integer::sum);
        return "hunt " + kills + " " + sh(s.from()) + " seen " + Math.round(m.dist()) + " m";
    }

    /**
     * The leaf when no way stands: look for the item's source. It has no price. An item a block generates with (the worldgen
     * band of the blocks that drop it) first goes to that height (one Baritone goal), then explores until a source is known.
     */
    private static String exploreLeaf(Ctx c, String item, String reason) {
        int[] band = c.w.band().apply(item);
        if (band != null) c.steps.add(new Step.ToBand(band[0], band[1]));
        c.steps.add(new Step.Explore(exploreMinutes(), sh(item)));      // live-facts: it ends once a source of the item is known
        c.stuck = true;
        if (c.unknown == null) c.unknown = item;
        return "explore (" + reason + (band == null ? "" : "; it generates at Y " + band[0] + ".." + band[1] + ", going there first") + ")";
    }

    /**
     * A requirement several items meet (any pickaxe of at least {@code tier}, from the game's pickaxes tag): {@link #choose} prices
     * each in full (loops and unknown sources dropped as everywhere); ties: the less raw work the recipe tree still holds (a loop
     * holds infinite), then the lower tier. No candidate stands: c.fail. Returns the winner's phrase.
     */
    private static String cheapestPickaxe(Ctx c, int tier) {
        List<Alt> alts = new ArrayList<>();
        List<String> ids = new ArrayList<>(ItemWorth.withTag("pickaxes"));
        java.util.Collections.sort(ids);
        for (String pid : ids) {
            int t = MiningTiers.toolTier(pid, "pickaxe");
            if (t < tier || ItemWorth.recipes(pid, ItemWorth.CRAFT).isEmpty()) continue;
            Alt a = new Alt(sh(pid), () -> obtain(c, pid, 1));
            a.id = pid; a.tier = t; a.work = rawWork(pid, new ArrayList<>());
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

    /** The raw items an item's whole recipe tree takes (recipe data only, a slot's first member); a recipe loop is infinite work. Breaks the tie between chains that stop at the same explore. */
    private static double rawWork(String item, List<String> path) {
        if (path.contains(item)) return Double.POSITIVE_INFINITY;
        List<ItemWorth.Recipe> rs = ItemWorth.recipes(item, ItemWorth.CRAFT);
        ItemWorth.Recipe rec = null;
        for (ItemWorth.Recipe r : rs) if (!packing(item, r)) { rec = r; break; }
        if (rec == null) return 1;
        path.add(item);
        double sum = 0;
        for (List<String> slot : rec.options()) sum += rawWork(slot.get(0), path);
        path.remove(path.size() - 1);
        return sum / rec.outCount();
    }
}
