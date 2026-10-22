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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * <li>Craft it: every crafting recipe of the item ({@link ItemWorth#recipes(String, String)} kind CRAFT); ingredients
 *     recurse; a 3x3 recipe needs the crafting station (the recipes' own toast symbol, {@link ItemWorth#station}), itself an item.</li>
 * <li>Smelt it: every furnace recipe (kind SMELT), the furnace (an item, placed near me by Baritone's builder), and the
 *     burn: the game's fuel table ({@link ItemWorth#fuels}) by its burn classes (the tag rule below); 10 s an item (TIME_MODEL).</li>
 * <li>Dig it: every known block that gives it (the loot data's blocks plus the item's own block, Baritone's scan first,
 *     the world's {@link World#seenAll}); the pickaxe tier the block asks for (the mining-tier data) pulls in the cheapest
 *     pickaxe of at least that tier (the game's pickaxes tag), each candidate priced.</li>
 * <li>Hunt it: a mob whose loot drops it ({@link LootBook} MOB sources) with an animal of that kind known
 *     ({@link World#mobs}): the walk and the kills (the mob's health by the bot's hit, game facts).</li>
 * <li>Else explore until a source is known: no price (the check before the chain, SCENARIOS); for an item a block
 *     generates with (the catalog's worldgen) one Baritone goal to that height band first ({@link Step.ToBand}).</li>
 * </ul>
 * TAG_NEEDS (owner 2026-10-22): a need is {@code N of <item>} or {@code N of #<tag>} (a recipe slot as the recipe gives it,
 * {@link ItemWorth.Recipe#needs}). A tag need stays a tag until a step must name a block or an item in the world: it is met
 * by any members in the bag summed (the game's tag index, {@link ItemWorth#isIn}); it is made by its members' recipes grouped
 * by what they need (one price per group, never per member; one-ingredient recipes of the same shape merge into one need,
 * {@code #planks <- #logs}); it is dug by its known sources grouped by the member they give (a tree is any log); the explore
 * is for its whole source set. The member is whatever the leaf (the bag, the tree, the ore) yields.
 * An item already on the path is a loop: that way is dropped. A way that ends in an explore never competes with one that
 * does not. Every loser goes to the dropped list with its seconds or its reason. Rough prices (RULES 14): walk, break,
 * craft, place, smelt, kill. Pure (JUnit: NeedChainTest, ScenariosTest). Loader notes: none (the facts come from
 * {@code AaRuntime.needWorld}).
 */
public final class NeedChain {
    private NeedChain() {}

    /**
     * A seen source: a cell to walk to, the straight-line {@code dist}, the block's id (its pickaxe tier comes from it), how
     * many blocks there are (0: not counted, as many as needed; a tree: its connected logs, all of which the chop takes), and
     * {@code gives}: the item it yields when a tag need asked for it (the loot data; null: the item asked for, or the block).
     */
    public record Spot(int x, int y, int z, double dist, String block, int count, String gives) {
        public Spot(int x, int y, int z, double dist, String block, int count) { this(x, y, z, dist, block, count, null); }
        public Spot(int x, int y, int z, double dist, String block) { this(x, y, z, dist, block, 0, null); }
        /** The same spot, yielding {@code item}. */
        public Spot giving(String item) { return new Spot(x, y, z, dist, block, count, item); }
        String key() { return x + "," + y + "," + z; }
    }

    /** An animal of a kind known nearby (the sightings): its entity id, where, how far. */
    public record Mob(String type, int x, int y, int z, double dist) {}

    /**
     * What the bot knows now. {@code bag}: item id -> count; {@code tableNear} / {@code furnaceNear}: one within reach;
     * {@code seenAll}: a need word (an item id, a tag "#ns:path", or several joined by "|") -> the known blocks that give it
     * (for a tag: any member; nearest first not needed); {@code mobs}: entity id -> the animals of that kind known;
     * {@code loot}: the game's loot data (mob drops; null: none read); {@code band}: need word -> {minY, maxY} where the
     * blocks that drop it generate (the worldgen data; null: unknown); {@code horizonBlocks}: a way dearer than this is
     * priced out when another stands (0: the planning horizon). Lookups are memoised per World.
     */
    public record World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                        Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks) {
        public World {
            Map<String, Integer> b = new LinkedHashMap<>();
            if (bag != null) for (var e : bag.entrySet()) b.merge(id(e.getKey()), e.getValue(), Integer::sum);
            bag = Map.copyOf(b);
            Function<String, List<Spot>> sa = seenAll == null ? w -> List.of() : seenAll;
            Map<String, List<Spot>> memo = new ConcurrentHashMap<>();
            seenAll = w -> memo.computeIfAbsent(word(w), k -> { List<Spot> l = sa.apply(k); return l == null ? List.of() : List.copyOf(l); });
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

        /** chop: the same world less the sources {@code held} names (need word, spot): a failed source is out of the re-price. */
        public World without(java.util.function.BiPredicate<String, Spot> held) {
            Function<String, List<Spot>> sa = seenAll;
            return new World(bag, tableNear, furnaceNear, w -> sa.apply(w).stream().filter(sp -> !held.test(word(w), sp)).toList(), mobs, loot, band, horizonBlocks);
        }
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
    static int maxTrials() { return Tuning.i("needChain.maxTrials"); }

    /** Game fact: the player's bare hit is 1 damage (Attributes.ATTACK_DAMAGE base), 4 full-strength hits a second (ATTACK_SPEED base). */
    private static final double HAND_DAMAGE = 1; // game fact: the player base attack damage attribute is 1
    private static final double HIT_S = 0.25; // game fact: the player base attack speed attribute is 4 hits a second
    /** Game fact: eating one item takes 32 ticks (the food's use duration): the food comparison counts it. */
    private static final double EAT_S = 1.6; // game fact: eating takes 32 ticks = 1.6 s per item
    /** Game fact: the inventory's crafting grid is 2x2: more than 4 ingredients need the crafting station. */
    private static final int GRID_2X2 = 4; // game fact: the inventory crafting grid holds 4 ingredients
    /** The tag the game gives every log block: a block of it is a tree (Baritone's chop). */
    private static final String LOGS_TAG = "minecraft:logs";

    /** TIME_MODEL: walk = straight line x 1.3 / walk speed, before Baritone has planned a path. */
    static double walkS(double dist) { return dist * StepTimes.pathFactor() / Unit.walkBps(); }

    static String s(double seconds) { return String.format(Locale.ROOT, "%.1f s", seconds); }

    static String id(String w) { return w == null ? null : w.indexOf(':') < 0 ? "minecraft:" + w : w; }

    /** The id as the lines and steps write it ("minecraft:torch" -> "torch"; a modded id stays whole). */
    static String sh(String id) { return id == null ? "?" : id.startsWith("minecraft:") ? id.substring(10) : id; }

    // ---- TAG_NEEDS: a need word is an item id, a tag ("#ns:path") or several of them joined by "|" ----

    /** TAG_NEEDS: the item counts for the need word (an item, a tag member by the game's tag index, or one of several); the live lookups' test. */
    public static boolean gives(String word, String item) { return word != null && new Want(word).has(item); }

    /** TAG_NEEDS: the need word is one item (not a tag, not several). */
    public static boolean plain(String word) { return word != null && new Want(word).item(); }

    /** A need word in full: "#planks" -> "#minecraft:planks", "torch" -> "minecraft:torch"; several parts in one canonical order. */
    public static String word(String raw) {
        if (raw == null) return null;
        if (raw.indexOf('|') < 0) return raw.startsWith("#") ? "#" + id(raw.substring(1)) : id(raw);
        Set<String> parts = new TreeSet<>();
        for (String p : raw.split("\\|")) if (!p.isEmpty()) parts.add(word(p));
        return String.join("|", parts);
    }

    /** A need word as the lines write it: "#minecraft:planks" -> "#planks". */
    static String shw(String word) {
        if (word == null) return "?";
        if (word.indexOf('|') >= 0) {
            List<String> out = new ArrayList<>();
            for (String p : word.split("\\|")) out.add(shw(p));
            return String.join("|", out);
        }
        return word.startsWith("#") ? "#" + sh(word.substring(1)) : sh(word);
    }

    /** One requirement: an item, a tag (any member: the game's tag index), or several (any of them). */
    static final class Want {
        final String word;
        final Set<String> items = new LinkedHashSet<>();
        final List<String> tags = new ArrayList<>();

        Want(String raw) {
            word = word(raw);
            for (String p : word.split("\\|")) {
                if (p.startsWith("#")) tags.add(p.substring(1));
                else items.add(p);
            }
        }

        boolean item() { return tags.isEmpty() && items.size() == 1; }

        String id() { return items.iterator().next(); }

        /** The bag entry counts for this need: a member (the game's tag index), or the token a way of this need made. */
        boolean has(String id) {
            if (id == null) return false;
            if (id.startsWith("#") || id.indexOf('|') >= 0) return id.equals(word);
            if (items.contains(id)) return true;
            for (String t : tags) if (ItemWorth.isIn(id, t)) return true;
            return false;
        }

        /** The members whose recipe data the craft and smelt ways read (a set in id order: the groups' listing order only). */
        Set<String> members() {
            Set<String> out = new TreeSet<>(items);
            for (String t : tags) out.addAll(ItemWorth.itemsIn(t));
            return out;
        }
    }

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
        String unknown;       // the first need nothing is known to give (the explore leaf's)
        String loopOn;        // the need a loop failure came back to (set with fail)
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

    /** Rule 1 (have it): what the bag holds of the need, mixed members summed. */
    private static int have(Ctx c, Want w) {
        if (w.item()) return c.have(w.id());
        int n = 0;
        for (var e : c.bag.entrySet()) if (e.getValue() > 0 && w.has(e.getKey())) n += e.getValue();
        return n;
    }

    /**
     * Takes {@code n} of the need out of the simulated bag: {@code prefer} first (what the way just made), then the bag's
     * members as they came in. Returns what was taken, item -> count.
     */
    private static Map<String, Integer> take(Ctx c, Want w, int n, String prefer) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (n <= 0) return out;
        if (w.item()) {
            c.bag.merge(w.id(), -n, Integer::sum);
            out.put(w.id(), n);
            return out;
        }
        List<String> keys = new ArrayList<>();
        if (prefer != null && w.has(prefer)) keys.add(prefer);
        for (String k : c.bag.keySet()) if (!keys.contains(k) && w.has(k)) keys.add(k);
        int left = n;
        for (String k : keys) {
            int t = Math.min(left, c.have(k));
            if (t <= 0) continue;
            c.bag.merge(k, -t, Integer::sum);
            out.merge(k, t, Integer::sum);
            left -= t;
        }
        if (left > 0) { c.bag.merge(w.word, -left, Integer::sum); out.merge(w.word, left, Integer::sum); }   // the way's own token
        return out;
    }

    /** What a take of {@code n} from the bag would name: one kind when one kind covers it, else the need word. */
    private static String bagName(Ctx c, Want w, int n) {
        if (w.item()) return w.id();
        for (var e : c.bag.entrySet()) if (e.getValue() > 0 && w.has(e.getKey())) return e.getValue() >= n ? e.getKey() : w.word;
        return w.word;
    }

    /** "3 oak_log", "2 oak_log + 1 oak_planks". */
    private static String counted(Map<String, Integer> took) {
        List<String> out = new ArrayList<>();
        for (var e : took.entrySet()) out.add(e.getValue() + " " + shw(e.getKey()));
        return String.join(" + ", out);
    }

    /**
     * The chain that gets {@code n} of {@code what} into the bag from {@code w}. {@code what}: an item id, or a tag
     * ("#minecraft:planks", or an id the game has no item for but a tag of: "planks"): any member (TAG_NEEDS).
     */
    public static Chain resolve(String what, int n, World w) {
        Ctx c = new Ctx(w);
        String tag = tagOf(what);
        if (tag != null) {
            String name = sh(tag);
            if (ItemWorth.itemsIn(tag).isEmpty()) return finish(c, what, what + ": the game has no item and no tag called " + what);
            Got g = needTag(c, new Want("#" + tag), n);
            return finish(c, name, g == null ? name + ": " + c.fail : name + " <- " + g.line());
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
        return ItemWorth.itemsIn(i).isEmpty() ? null : i;
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
        Got g = needAny(c, ids, n, label, energy);
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
            for (var e : unknown.entrySet()) drop(c, e.getKey(), "no known source for " + shw(e.getValue()));
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
        Supplier<String> run;
        /** The item the way yields (a tag need's member; set by the way itself when only its run knows it), null: the need word. */
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
     * Past {@code needChain.maxTrials} ways in one chain (the search's bound) the first way that stands ends the pricing.
     * Null: no way stands and none ends in an explore (every failure is in the dropped list); the caller makes the explore leaf.
     */
    private static Trial choose(Ctx c, List<Alt> alts, String self) {
        if (alts.isEmpty()) return null;
        List<Trial> trials = new ArrayList<>();
        for (int i = 0; i < alts.size(); i++) {
            if (c.trials > maxTrials() && trials.stream().anyMatch(t -> t.fail() == null && !t.stuck())) break;
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
            else if (t.stuck()) { if (!win.stuck() && !t.alt().quiet) drop(c, t.alt().label, "no known source for " + shw(t.unknown())); }
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

    /** What one requirement got: the member that won (or the need word while no leaf named one) and its phrase (null: it was in the bag). */
    private record Got(String id, int n, String phrase) {
        String line() { return phrase == null ? n + " " + shw(id) + " (in the bag)" : n + " " + shw(id) + " <- " + phrase; }
        String part() { return phrase == null ? n + " " + shw(id) : n + " " + shw(id) + " <- " + phrase; }
    }

    /** {@code n} of a recipe slot's need word: an item ({@link #obtain}), else the tag rule ({@link #needTag}). Null: none can be had (c.fail set). */
    private static Got needWord(Ctx c, String word, int n) {
        Want w = new Want(word);
        if (w.item()) {
            String p = obtain(c, w.id(), n);
            return c.fail != null ? null : new Got(w.id(), n, p);
        }
        return needTag(c, w, n);
    }

    /**
     * {@code n} of any one of {@code options} (a group of items, not a tag: the plain foods, the pickaxes of a tier): one member
     * is the plain {@link #obtain}; several are each priced in full ({@link #choose}). {@code energy} (food): compare by seconds
     * per point, eating counted, a member in the bag at 0 s. Null: none can be had (c.fail set).
     */
    private static Got needAny(Ctx c, List<String> options, int n, String label, ToDoubleFunction<String> energy) {
        if (options.size() == 1) {
            String only = options.get(0);
            String p = obtain(c, only, n);
            return c.fail != null ? null : new Got(only, n, p);
        }
        if (energy == null) for (String o : options) if (c.have(o) >= n) return new Got(o, n, null);          // any one in the bag meets it: nothing to price
        List<Alt> alts = new ArrayList<>();
        for (String o : options) {
            Alt a = new Alt(sh(o), () -> obtain(c, o, n));
            a.id = o;
            a.quiet = true;
            if (energy != null) {
                double pts = Math.max(1e-9, n * energy.applyAsDouble(o));  // structural: epsilon
                a.rate = sec -> (sec + n * EAT_S) / pts;
            }
            alts.add(a);
        }
        Trial win = choose(c, alts, null);
        if (win == null) {
            if (c.fail == null) c.fail = "no member of " + label + " can be had";
            return null;
        }
        return new Got(win.alt().id, n, win.phrase());
    }

    /**
     * TAG_NEEDS: {@code n} of a tag need. Have it: any members in the bag, summed. Else its ways, each priced once: its members'
     * recipes grouped by what they need ({@link #groups}), its known sources grouped by the member they give (every tree one way
     * each: any log), the animals whose loot drops a member; none known: the explore for the whole source set. The member is
     * what the winning way yields; while none is named the need word stands in the line. Null: none can be had (c.fail set).
     */
    private static Got needTag(Ctx c, Want w, int n) {
        if (c.dead() || n <= 0) return c.fail != null ? null : new Got(w.word, n, null);
        int have = have(c, w);
        if (have >= n) return new Got(bagName(c, w, n), n, null);
        int lack = n - have;
        if (c.path.contains(w.word)) {
            c.fail = "loop: " + shw(w.word) + " needs itself (a recipe loop: " + String.join(" > ", c.path.stream().map(NeedChain::shw).toList()) + " > " + shw(w.word) + ")";
            c.loopOn = w.word;
            return null;
        }
        c.path.add(w.word);
        try {
            List<Alt> alts = new ArrayList<>();
            for (Group g : groups(w, ItemWorth.CRAFT)) alts.add(groupAlt(c, w, lack, g));
            for (Group g : groups(w, ItemWorth.SMELT)) alts.add(groupAlt(c, w, lack, g));
            alts.addAll(tagSourceAlts(c, w, lack));
            alts.addAll(tagHuntAlts(c, w, lack));
            for (Alt a : alts) a.quiet = true;
            Trial win = choose(c, alts, w.word);
            if (win != null) return new Got(win.alt().id != null ? win.alt().id : w.word, n, win.phrase());
            if (c.fail != null) return null;
            return new Got(w.word, n, exploreLeaf(c, w.word, noSource(c, w.word)));
        } finally {
            c.path.remove(c.path.size() - 1);
        }
    }

    /**
     * One way of a tag need: its members' recipes of one kind that need the same: {@code recs} (with {@code outs}, the member
     * each makes), {@code slots} (need word -> pieces a batch) and the batch's {@code outCount}.
     */
    private record Group(String kind, int outCount, List<ItemWorth.Recipe> recs, List<String> outs, Map<String, Integer> slots) {
        Set<String> made() { return new LinkedHashSet<>(outs); }
    }

    /** A recipe's slots by need word, in the recipe's order: word -> pieces a batch. */
    private static Map<String, Integer> slotWords(ItemWorth.Recipe r) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String sw : r.needs()) out.merge(word(sw), 1, Integer::sum);
        return out;
    }

    /**
     * TAG_NEEDS rule 2: a tag need's members' recipes of {@code kind}, grouped by what they need (a recipe that takes a member
     * of the tag itself, a dye-over, is no way to make it). One-ingredient recipes of the same shape are one group whose
     * need is any of their ingredients ({@link #cover}: {@code #planks <- #logs}); others group by their exact need words.
     */
    private static List<Group> groups(Want w, String kind) {
        Map<String, List<ItemWorth.Recipe>> recs = new LinkedHashMap<>();
        Map<String, List<String>> outs = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> slots = new LinkedHashMap<>();
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (String m : w.members()) {
            for (ItemWorth.Recipe r : ItemWorth.recipes(m, kind)) {
                if (takesItself(w, r)) continue;
                Map<String, Integer> sw = slotWords(r);
                boolean one = sw.size() == 1;
                String key = one ? r.outCount() + " x" + sw.values().iterator().next() : r.outCount() + " " + new TreeMap<>(sw);
                recs.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                outs.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
                if (one) merged.computeIfAbsent(key, k -> new TreeSet<>()).add(sw.keySet().iterator().next());
                else slots.putIfAbsent(key, sw);
            }
        }
        List<Group> out = new ArrayList<>();
        for (var e : recs.entrySet()) {
            String key = e.getKey();
            ItemWorth.Recipe first = e.getValue().get(0);
            Map<String, Integer> sw = merged.containsKey(key) ? Map.of(cover(merged.get(key)), first.needs().size()) : slots.get(key);
            out.add(new Group(kind, first.outCount(), e.getValue(), outs.get(key), sw));
        }
        return out;
    }

    /** A recipe that takes a member of the need itself (a dye-over, a packing): not a way to make it. */
    private static boolean takesItself(Want w, ItemWorth.Recipe r) {
        for (List<String> slot : r.options()) for (String o : slot) if (w.has(o)) return true;
        return false;
    }

    /**
     * The need "any of these words": one word as it is; several as the one game tag whose items are exactly theirs (the
     * planks' log tags together are {@code #logs}), else all of them joined.
     */
    static String cover(Set<String> words) {
        if (words.size() == 1) return words.iterator().next();
        Set<String> all = new TreeSet<>();
        for (String w : words) all.addAll(new Want(w).members());
        if (all.isEmpty()) return word(String.join("|", words));
        Set<String> hits = new TreeSet<>();
        for (String t : ItemWorth.itemTagsOf(all.iterator().next())) if (ItemWorth.itemsIn(t).equals(all)) hits.add(t);
        return hits.isEmpty() ? word(String.join("|", words)) : "#" + hits.iterator().next();
    }

    /** A group as one way: one member made: that member's own recipe (the item way); several: {@link #makeGroup}. */
    private static Alt groupAlt(Ctx c, Want w, int lack, Group g) {
        Set<String> made = g.made();
        boolean craft = ItemWorth.CRAFT.equals(g.kind());
        if (made.size() == 1) {
            String out = made.iterator().next();
            ItemWorth.Recipe r = g.recs().get(0);
            Alt a = new Alt(sh(out), () -> craft ? craft(c, out, lack, r) : smelt(c, out, lack, r));
            a.id = out;
            return a;
        }
        List<String> needs = new ArrayList<>();
        for (String sw : g.slots().keySet()) needs.add(shw(sw));
        Alt a = new Alt((craft ? "" : "smelting ") + shw(w.word) + " from " + String.join("+", needs), null);
        a.run = () -> makeGroup(c, w, lack, g, a);
        return a;
    }

    /**
     * Several members from one group's recipes: the need of each slot (tag rule), the station, the batches; the member is the
     * one whose recipe takes what went in (the bag decides), else the step names the tag and the craft fills it from the bag.
     */
    private static String makeGroup(Ctx c, Want w, int lack, Group g, Alt self) {
        boolean craft = ItemWorth.CRAFT.equals(g.kind());
        int batches = (lack + g.outCount() - 1) / g.outCount();
        List<String> parts = new ArrayList<>();
        Map<String, Integer> used = new LinkedHashMap<>();
        List<Map.Entry<String, Integer>> ins = new ArrayList<>(g.slots().entrySet());
        ins.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> rawUnseen(c, e.getKey()) ? 1 : 0));
        String verb = craft ? "craft: " : "smelt ";
        int pieces = 0;
        for (var e : ins) {
            int want = e.getValue() * batches;
            pieces += e.getValue();
            Got got = needWord(c, e.getKey(), want);
            if (c.fail != null || got == null) { if (c.fail == null) c.fail = "no " + shw(e.getKey()) + " can be had"; return null; }
            parts.add(got.part());
            if (c.stuck) return verb + String.join(" + ", parts);
            take(c, new Want(e.getKey()), want, got.id()).forEach((k, v) -> used.merge(k, v, Integer::sum));
        }
        if (craft) {
            boolean table = pieces > GRID_2X2;
            for (String m : g.made()) table |= ItemWorth.needsTable(m);
            if (table) {
                String t = station(c, ItemWorth.CRAFT);
                if (c.fail != null) return null;
                if (t != null) parts.add("table <- " + t);
                if (c.stuck) return verb + String.join(" + ", parts);
                walkBack(c);
            }
        } else {
            String f = station(c, ItemWorth.SMELT);
            if (c.fail != null) return null;
            if (f != null) parts.add("furnace <- " + f);
            if (c.stuck) return verb + String.join(" + ", parts);
            String burn = burn(c, batches);
            if (c.fail != null) return null;
            parts.add(burn);
            if (c.stuck) return verb + String.join(" + ", parts);
            walkBack(c);
        }
        String out = outputOf(g, used);
        self.id = out;
        String name = out != null ? out : w.word;
        int made = batches * g.outCount();
        if (craft) {
            c.steps.add(new Step.Craft(out != null ? sh(out) : shw(w.word), made));
            c.seconds += Price.craftS() * batches;
        } else {
            c.steps.add(new Step.Smelt(out != null ? sh(out) : shw(w.word), made, true));
            c.seconds += batches * smeltTicks() / GameTimes.TPS;
        }
        c.bag.merge(name, made, Integer::sum);
        return verb + String.join(" + ", parts);
    }

    /** The one member whose recipe takes everything that went in (each slot's own need word, the game's tag index); null: none or several. */
    private static String outputOf(Group g, Map<String, Integer> used) {
        Set<String> outs = new LinkedHashSet<>();
        for (int i = 0; i < g.recs().size(); i++) {
            ItemWorth.Recipe r = g.recs().get(i);
            boolean all = true;
            for (String k : used.keySet()) {
                boolean in = false;
                for (String sw : r.needs()) if (new Want(sw).has(k)) { in = true; break; }
                if (!in) { all = false; break; }
            }
            if (all) outs.add(g.outs().get(i));
        }
        return outs.size() == 1 ? outs.iterator().next() : null;
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
            c.fail = "loop: " + sh(item) + " needs itself (a recipe loop: " + String.join(" > ", c.path.stream().map(NeedChain::shw).toList()) + " > " + sh(item) + ")";
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
            alts.addAll(sourceAlts(c, item, item, lack, c.w.seenAll().apply(item)));
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

    /** "oak_planks+stick", a tag slot as its tag ("#coals+stick"). */
    private static String ingredients(ItemWorth.Recipe r) {
        List<String> out = new ArrayList<>();
        for (String sw : r.needs()) {
            String t = shw(word(sw));
            if (!out.contains(t)) out.add(t);
        }
        return String.join("+", out);
    }

    private static String noSource(Ctx c, String word) {
        return "no " + shw(word) + " source known";
    }

    /** True when nothing makes it and nothing known gives it (its explore ends the chain, so it is asked for last). */
    private static boolean rawUnseen(Ctx c, String word) {
        Want w = new Want(word);
        if (have(c, w) > 0 || !c.w.seenAll().apply(w.word).isEmpty() || mobKnown(c, w)) return false;
        if (w.item()) return recipesOf(w.id()).isEmpty();
        for (String m : w.members()) if (!recipesOf(m).isEmpty()) return false;
        return true;
    }

    private static List<ItemWorth.Recipe> recipesOf(String item) {
        List<ItemWorth.Recipe> out = new ArrayList<>(ItemWorth.recipes(item, ItemWorth.CRAFT));
        out.addAll(ItemWorth.recipes(item, ItemWorth.SMELT));
        return out;
    }

    private static boolean mobKnown(Ctx c, Want w) {
        if (c.w.loot() == null) return false;
        List<LootBook.Source> l = w.item() ? c.w.loot().sources(w.id()) : c.w.loot().sourcesMatching(w::has);
        for (LootBook.Source s : l) if (s.kind() == LootBook.Kind.MOB && !c.w.mobs().apply(s.from()).isEmpty()) return true;
        return false;
    }

    private static String craft(Ctx c, String item, int lack, ItemWorth.Recipe rec) {
        int batches = (lack + rec.outCount() - 1) / rec.outCount();
        List<String> parts = new ArrayList<>();
        // the slots by need word, the same word counted once (4 planks of one tag); what can be made or fetched first, a raw source nothing knows last
        List<Map.Entry<String, Integer>> ins = new ArrayList<>(slotWords(rec).entrySet());
        ins.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> rawUnseen(c, e.getKey()) ? 1 : 0));
        for (var e : ins) {
            int want = e.getValue() * batches;
            Got g = needWord(c, e.getKey(), want);
            if (c.fail != null || g == null) { if (c.fail == null) c.fail = "no " + shw(e.getKey()) + " can be had"; return null; }
            parts.add(g.part());
            if (c.stuck) return "craft: " + String.join(" + ", parts);                 // something needed is not known: the chain ends at the explore
            take(c, new Want(e.getKey()), want, g.id());
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
     * A furnace turns the input into the item: the input (the recipe's ingredient, a tag by the tag rule), the furnace in
     * reach, the burn ({@link #burn}), 10 s an item, the walk back to the furnace.
     */
    private static String smelt(Ctx c, String item, int lack, ItemWorth.Recipe rec) {
        int k = (lack + rec.outCount() - 1) / rec.outCount();
        List<String> parts = new ArrayList<>();
        String input = word(rec.needs().get(0));
        Got in = needWord(c, input, k);
        if (c.fail != null || in == null) { if (c.fail == null) c.fail = "no " + shw(input) + " can be had"; return null; }
        parts.add(in.part());
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        take(c, new Want(input), k, in.id());
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

    /**
     * The burn for {@code k} smelts by the tag rule (TAG_NEEDS 6): the fuel table's items in classes of the same burn ticks
     * (count = k x smelt ticks / its burn ticks), each class one need (any of its items, mixed in the bag). In the bag: the
     * class that burns longest a piece and is covered. Else each class priced once, the cheapest wins.
     */
    private static String burn(Ctx c, int k) {
        Map<String, Integer> fuels = ItemWorth.fuels();
        if (fuels.isEmpty()) { c.fail = "the game's fuel table is empty (no data loaded)"; return null; }
        TreeMap<Integer, Set<String>> classes = new TreeMap<>(Comparator.reverseOrder());
        for (var e : fuels.entrySet()) if (e.getValue() > 0) classes.computeIfAbsent(e.getValue(), t -> new TreeSet<>()).add(e.getKey());
        for (var e : classes.entrySet()) {
            int count = (int) Math.ceil(k * smeltTicks() / e.getKey());
            Want cw = new Want(classWord(e.getValue()));
            if (have(c, cw) >= count) return "burn " + counted(take(c, cw, count, null)) + " (in the bag)";
        }
        List<Alt> alts = new ArrayList<>();
        for (var e : classes.entrySet()) {
            int count = (int) Math.ceil(k * smeltTicks() / e.getKey());
            String cw = classWord(e.getValue());
            boolean plain = new Want(cw).item() || cw.indexOf('|') < 0;
            Alt a = new Alt("burn " + (plain ? shw(cw) : e.getValue().size() + " items of " + e.getKey() + " ticks"), null);
            a.run = () -> {
                Got g = needWord(c, cw, count);
                if (g == null) return null;
                a.id = g.id();
                if (c.fail == null && !c.stuck) take(c, new Want(cw), count, g.id());
                return "burn " + g.part();
            };
            a.quiet = true;
            alts.add(a);
        }
        Trial win = choose(c, alts, null);
        if (win == null) {
            if (c.fail == null) c.fail = "nothing of the fuel table can be had";
            return null;
        }
        return win.phrase();
    }

    /** A burn class as one need word: one item, or the tag its items are exactly ({@link #cover}), else all of them. */
    private static String classWord(Set<String> items) {
        if (items.size() == 1) return word(items.iterator().next());
        Set<String> words = new TreeSet<>();
        for (String i : items) words.add(word(i));
        return cover(words);
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

    /**
     * The ways known blocks give {@code gives} (asked for as {@code word}): one per known block (nearest others fill what it
     * lacks).
     */
    private static List<Alt> sourceAlts(Ctx c, String word, String gives, int lack, List<Spot> known) {
        List<Spot> spots = new ArrayList<>(known);
        spots.sort(Comparator.comparingDouble(Spot::dist));
        List<Alt> alts = new ArrayList<>();
        for (Spot first : spots) {
            List<Spot> order = new ArrayList<>();
            order.add(first);
            for (Spot o : spots) if (o != first) order.add(o);
            alts.add(new Alt((tree(word, first) ? "tree " : shw(gives) + " ") + Math.round(first.dist()) + " m", () -> sourceFrom(c, word, gives, lack, order)));
        }
        return alts;
    }

    /**
     * TAG_NEEDS: the known sources of a tag need (the world's lookup for the whole source set): every tree its own way (the
     * chop takes any log; the member is the tree's), the other blocks one way per member they give (an ore dig), its spots
     * priced inside it.
     */
    private static List<Alt> tagSourceAlts(Ctx c, Want w, int lack) {
        List<Spot> spots = new ArrayList<>(c.w.seenAll().apply(w.word));
        spots.sort(Comparator.comparingDouble(Spot::dist));
        List<Spot> trees = new ArrayList<>();
        Map<String, List<Spot>> dig = new LinkedHashMap<>();
        for (Spot sp : spots) {
            if (tree(w.word, sp)) trees.add(sp);
            else dig.computeIfAbsent(givesOf(sp, w), k -> new ArrayList<>()).add(sp);
        }
        List<Alt> alts = new ArrayList<>();
        for (Spot first : trees) {
            List<Spot> order = new ArrayList<>();
            order.add(first);
            for (Spot o : trees) if (o != first) order.add(o);
            String gives = givesOf(first, w);
            Alt a = new Alt("tree " + Math.round(first.dist()) + " m", () -> sourceFrom(c, w.word, gives, lack, order));
            a.id = gives;
            alts.add(a);
        }
        for (var e : dig.entrySet()) {
            String gives = e.getKey();
            List<Spot> sps = e.getValue();
            Alt a = new Alt(shw(gives), () -> {
                Trial t = choose(c, sourceAlts(c, w.word, gives, lack, sps), null);
                if (t != null) return t.phrase();
                if (c.fail == null) c.fail = "no known " + shw(gives) + " source can be dug";
                return null;
            });
            a.id = gives;
            alts.add(a);
        }
        return alts;
    }

    /** What a spot of a tag need yields: what the lookup says (the loot data), else its block when that is a member, else the need's token. */
    private static String givesOf(Spot sp, Want w) {
        if (sp.gives() != null) return word(sp.gives());
        if (sp.block() != null && w.has(sp.block())) return sp.block();
        return w.word;
    }

    /** TAG_NEEDS: the animals whose loot drops any member, each its own way (the member it drops). */
    private static List<Alt> tagHuntAlts(Ctx c, Want w, int lack) {
        List<Alt> alts = new ArrayList<>();
        if (c.w.loot() == null) return alts;
        for (LootBook.Source s : c.w.loot().sourcesMatching(w::has)) {
            if (s.kind() != LootBook.Kind.MOB || s.per() <= 0) continue;
            List<Mob> known = new ArrayList<>(c.w.mobs().apply(s.from()));
            if (known.isEmpty()) continue;
            known.sort(Comparator.comparingDouble(Mob::dist));
            Mob m = known.get(0);
            Alt a = new Alt("hunt " + sh(s.from()) + " " + Math.round(m.dist()) + " m", () -> hunt(c, s.item(), lack, s, m));
            a.id = s.item();
            alts.add(a);
        }
        return alts;
    }

    /**
     * chop: the Failures key of a block source, the same for the step that ran it and the spot it came from: a tree is its
     * position ("cut at x y z"), a collect its item ("collect coal": the collect hand mines every block that gives the item).
     * Null: not a block source.
     */
    public static String sourceKey(Step s) {
        if (s instanceof Step.ChopTree t && t.at() != null) return "cut at " + t.at()[0] + " " + t.at()[1] + " " + t.at()[2];
        if (s instanceof Step.Collect k) return "collect " + k.item();
        return null;
    }

    /** The key of a spot of {@code item} (a need word; a tag's spot by the item it gives) (see {@link #sourceKey(Step)}). */
    public static String sourceKey(String item, Spot sp) {
        return tree(item, sp) ? "cut at " + sp.x() + " " + sp.y() + " " + sp.z() : "collect " + shw(sp.gives() != null ? word(sp.gives()) : item);
    }

    /** A block the game tags as logs is a tree (Baritone's tree mining); a spot with no block known for a tag need: a tree when the need is logs only. */
    private static boolean tree(String item, Spot sp) {
        String b = sp.block() != null ? sp.block() : sp.gives() != null ? sp.gives() : item;
        if (b == null) return false;
        if (!b.startsWith("#") && b.indexOf('|') < 0) return ItemWorth.hasTag(b, LOGS_TAG);
        Set<String> m = new Want(b).members();
        if (m.isEmpty()) return false;
        for (String i : m) if (!ItemWorth.hasTag(i, LOGS_TAG)) return false;
        return true;
    }

    /** How many of the item one block of {@code block} drops (the loot data's expectation; 1 when unknown). */
    private static double perBlock(Ctx c, String item, String block) {
        if (c.w.loot() == null || block == null) return 1;
        for (LootBook.Source s : c.w.loot().sources(item)) if (s.kind() == LootBook.Kind.BLOCK && s.from().equals(block) && s.per() > 0) return s.per();
        return 1;
    }

    /**
     * Break at the first spot (and on at the next while it has too few blocks), the walk priced in but no walk step (chop B2):
     * after the pickaxe tier the block asks for. {@code word}: the need (its explore when the known blocks fall short);
     * {@code gives}: what goes into the bag (the item, a tag need's member, or its token).
     */
    private static String sourceFrom(Ctx c, String word, String gives, int lack, List<Spot> order) {
        Spot first = order.get(0);
        boolean log = tree(word, first);
        String pre = null;
        int tier = tierOf(word, first);
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null) return null;
            if (c.stuck) return "dig " + shw(gives) + " seen " + Math.round(first.dist()) + " m (after " + pre + ")";      // the pickaxe's own source is still to find: the chain ends at that explore
        }
        int remaining = lack;
        for (Spot sp : order) {
            if (remaining <= 0) break;
            double per = perBlock(c, gives, sp.block());
            int blocksWanted = (int) Math.ceil(remaining / per - 1e-9);  // structural: epsilon
            int avail = sp.count() <= 0 ? blocksWanted : sp.count() - c.taken.getOrDefault(sp.key(), 0);
            // S20 (owner 2026-10-22): the chop takes the whole tree (the fork's ChopTree), so a counted tree is priced and
            // bagged by all its logs, the surplus to stock; the next tree only while the need is not covered
            boolean whole = log && sp.count() > 0;
            int take = whole ? avail : Math.min(avail, blocksWanted);
            if (take <= 0) continue;
            int got = whole ? (int) Math.floor(take * per + 1e-9) : Math.min(remaining, (int) Math.floor(take * per + 1e-9));  // structural: epsilon
            if (got <= 0) got = Math.min(remaining, take);
            int wanted = Math.min(remaining, got);                       // the step's number: what the need asks of this source
            Integer at = c.leafAt.get(sp.key());
            if (at != null) {
                Step prev = c.steps.get(at);
                c.steps.set(at, prev instanceof Step.ChopTree t ? new Step.ChopTree(t.at(), t.logs() + wanted, t.log())
                        : new Step.Collect(((Step.Collect) prev).item(), ((Step.Collect) prev).n() + wanted));
            } else {
                // chop: no walk step before a block source (the cut/collect paths itself; the spot names the blocks and the price)
                c.leafAt.put(sp.key(), c.steps.size());
                c.steps.add(log ? new Step.ChopTree(new int[]{sp.x(), sp.y(), sp.z()}, wanted, null) : new Step.Collect(shw(gives), wanted));
                c.seconds += walkS(sp.dist());
                c.away = sp.dist();
            }
            c.seconds += breakS(c, gives, sp) * take;
            c.taken.merge(sp.key(), take, Integer::sum);
            c.bag.merge(gives, got, Integer::sum);
            remaining -= got;
        }
        String how = (log ? "cut tree" : "dig " + shw(gives)) + " seen " + Math.round(first.dist()) + " m" + (pre == null ? "" : " with " + pre);
        if (remaining > 0) return how + " + " + exploreLeaf(c, word, "not enough " + shw(word) + " known");
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
     * The leaf when no way stands: look for the need's source (a tag: any member's: the whole source set). It has no price.
     * A need blocks generate with (the worldgen band of the blocks that drop it) first goes to that height (one Baritone
     * goal), then explores until a source is known.
     */
    private static String exploreLeaf(Ctx c, String word, String reason) {
        int[] band = c.w.band().apply(word);
        if (band != null) c.steps.add(new Step.ToBand(band[0], band[1]));
        c.steps.add(new Step.Explore(exploreMinutes(), shw(word)));      // live-facts: it ends once a source of the need is known
        c.stuck = true;
        if (c.unknown == null) c.unknown = word;
        return "explore (" + reason + (band == null ? "" : "; it generates at Y " + band[0] + ".." + band[1] + ", going there first") + ")";
    }

    /**
     * A requirement several items meet (any pickaxe of at least {@code tier}, from the game's pickaxes tag): {@link #choose} prices
     * each in full (loops and unknown sources dropped as everywhere); ties: the less raw work the recipe tree still holds (a loop
     * holds infinite), then the lower tier. No candidate stands: c.fail. Returns the winner's phrase.
     */
    private static String cheapestPickaxe(Ctx c, int tier) {
        List<Alt> alts = new ArrayList<>();
        for (String pid : new TreeSet<>(ItemWorth.itemsIn("pickaxes"))) {
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

    /**
     * The raw items a need's whole recipe tree takes (recipe data only; a tag slot by its least-work group, TAG_NEEDS); a recipe
     * loop is infinite work. Breaks the tie between chains that stop at the same explore.
     */
    private static double rawWork(String word, List<String> path) {
        if (path.contains(word)) return Double.POSITIVE_INFINITY;
        Want w = new Want(word);
        if (!w.item()) {
            List<Group> gs = groups(w, ItemWorth.CRAFT);
            if (gs.isEmpty()) return 1;
            double best = Double.POSITIVE_INFINITY;
            path.add(w.word);
            for (Group g : gs) {
                double sum = 0;
                for (var e : g.slots().entrySet()) sum += e.getValue() * rawWork(e.getKey(), path);
                best = Math.min(best, sum / g.outCount());
            }
            path.remove(path.size() - 1);
            return best;
        }
        String item = w.id();
        List<ItemWorth.Recipe> rs = ItemWorth.recipes(item, ItemWorth.CRAFT);
        ItemWorth.Recipe rec = null;
        for (ItemWorth.Recipe r : rs) if (!packing(item, r)) { rec = r; break; }
        if (rec == null) return 1;
        path.add(item);
        double sum = 0;
        for (String sw : rec.needs()) sum += rawWork(word(sw), path);
        path.remove(path.size() - 1);
        return sum / rec.outCount();
    }
}
