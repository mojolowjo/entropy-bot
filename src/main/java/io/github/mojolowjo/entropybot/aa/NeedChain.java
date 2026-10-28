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
 *     generates with (the catalog's worldgen) one Baritone goal to that height band first ({@link Step.ToBand}); 0.39.z69:
 *     that walk is priced (Baritone's path, its breaks by hand) against making the cheapest pickaxe first ({@code bandLeg}).</li>
 * </ul>
 * TAG_NEEDS (owner 2026-10-22): a need is {@code N of <item>} or {@code N of #<tag>} (a recipe slot as the recipe gives it,
 * {@link ItemWorth.Recipe#needs}). A tag need stays a tag until a step must name a block or an item in the world: it is met
 * by any members in the bag summed (the game's tag index, {@link ItemWorth#isIn}); it is made by its members' recipes grouped
 * by what they need (one price per group, never per member; one-ingredient recipes of the same shape merge into one need
 * only for the same word or words that are exactly one game tag, {@code #planks <- #logs}; different tags are different
 * ways, {@code #coals}: charcoal and coal); it is dug by its known sources grouped by the member they give (a tree is any log); the explore
 * is for its whole source set. The member is whatever the leaf (the bag, the tree, the ore) yields.
 * 0.39.z75 (resource accounting): the chain knows where each leg leaves the bot - after a strip leg to another level a walk
 * is not priced (the fork prices from the feet only, rule 15) and a need there is met there or "before going down" (its
 * steps put before that leg, carried down); a smelt's planned fuel is a counted need of its step, one kind (the furnace
 * job's rule); a recipe way runs with what the bag holds of its own need set aside (counted once).
 * An item already on the path is a loop: that way is dropped. A way that ends in an explore never competes with one that
 * does not. Every loser goes to the dropped list with its seconds or its reason. Rough prices (RULES 14): walk, break,
 * craft, place, smelt, kill. 0.39.z78 D6: every resolve is a tree of nodes that keep their sums ({@link PlanNodes}; the hooks:
 * {@code Facts}, {@code TrackedBag}, {@code node}, {@code way}, {@code root}). Pure (JUnit: NeedChainTest, ScenariosTest, D6NodesTest). Loader notes: none (the facts come from
 * {@code AaRuntime.needWorld}).
 */
public final class NeedChain {
    /** z53: fuel classes whose wood was logged "not known" (once each). */
    private static final java.util.Set<String> UNKNOWN_WOOD_SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private NeedChain() {}

    /**
     * A seen source: a cell to walk to, the straight-line {@code dist}, the block's id (its pickaxe tier comes from it), how
     * many blocks there are (0: not counted, as many as needed; a tree: its connected logs, all of which the chop takes), and
     * {@code gives}: the item it yields when a tag need asked for it (the loot data; null: the item asked for, or the block).
     */
    public record Spot(int x, int y, int z, double dist, String block, int count, String gives, double bonusS, boolean unloaded) {
        public Spot(int x, int y, int z, double dist, String block, int count, String gives, double bonusS) { this(x, y, z, dist, block, count, gives, bonusS, false); }
        /** 0.39.z30, rule 15: a remembered station whose chunk is not loaded: priced as remembered, the plan says "unverified". */
        public Spot notLoaded() { return new Spot(x, y, z, dist, block, count, gives, bonusS, true); }
        public Spot(int x, int y, int z, double dist, String block, int count, String gives) { this(x, y, z, dist, block, count, gives, 0); }
        public Spot(int x, int y, int z, double dist, String block, int count) { this(x, y, z, dist, block, count, null); }
        public Spot(int x, int y, int z, double dist, String block) { this(x, y, z, dist, block, 0, null); }
        /** The same spot, yielding {@code item}. */
        public Spot giving(String item) { return new Spot(x, y, z, dist, block, count, item, bonusS, unloaded); }
        /** STRUCTURE_BLOCKS: a station's price bonus in seconds (a use-stations area), taken off its walk in the option's cost. */
        public Spot withBonus(double s) { return new Spot(x, y, z, dist, block, count, gives, s, unloaded); }
        String key() { return x + "," + y + "," + z; }
    }

    /** An animal of a kind known nearby (the sightings): its entity id, where, how far. */
    public record Mob(String type, int x, int y, int z, double dist) {}

    /**
     * 0.39.z43: what the strip way "mine at the ore's level" is priced on, from the game's data (AaRuntime: the world-gen
     * placement read, {@link io.github.mojolowjo.entropybot.aa.brain.OreBand}): {@code ores} the ore block ids that give
     * the need (the strip's set), {@code minY..maxY} their band, {@code y} the strip's feet Y (the band's best layer at or
     * below the feet), {@code perSlab} the expected ore blocks per chunk in the slab at y, {@code x z feetY} where the bot
     * stands, {@code heading} where it faces, {@code host} the block a strip at y digs through (its break is priced).
     */
    public static final String STRIP_KEY = "strip ";     // 0.39.z77 world 2: public (WorldFacts: a strip key lifts by its cause only)

    /**
     * 0.39.z44 (CAVE_DETECTION option 1): what the way "mine in the cave" is priced on: the nearest known cave mouth (a
     * Sightings fact: a heightmap hole confirmed by sight, rule 17) at x y z, {@code dist} straight metres away, the ore block
     * {@code ore} that gives the need, and the cave yield Seen learns for its family ({@code perMin} ore a minute of caving,
     * the Tuning prior blended in; {@code measured}: minutes of caving were measured).
     */
    static final String CAVE_KEY = "cave mouth ";

    public record CaveWay(int x, int y, int z, double dist, String ore, String family, double perMin, boolean measured, boolean candidate) {
        public CaveWay(int x, int y, int z, double dist, String ore, String family, double perMin, boolean measured) {
            this(x, y, z, dist, ore, family, perMin, measured, false);
        }
        /** The source key of this way: a mouth with no path is dropped for the order. */
        public String key() { return CAVE_KEY + x + "," + y + "," + z; }
    }

    /**
     * 0.39.z62 item 1b: {@code headings} - the headings the strip may take, in the order tried (the first is {@code heading});
     * AaRuntime leaves out one whose start cells Baritone will not break (its blocksToAvoidBreaking, unbreakable blocks). A
     * strip that ends with no new block in its heading holds that heading for the order ({@link #headingKey}); the next one
     * in this list is the strip way then ({@link World#withoutSources}).
     */
    public record StripBand(List<String> ores, int minY, int maxY, int y, double perSlab, int x, int z, int feetY, String heading, String host,
                            List<String> headings, int freshDist, Map<Integer, Double> otherYs, List<String> shut) {
        public StripBand {
            ores = List.copyOf(ores);
            headings = headings == null || headings.isEmpty() ? List.of(heading) : List.copyOf(headings);
            otherYs = otherYs == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(otherYs));
            shut = shut == null ? List.of() : List.copyOf(shut);
        }
        public StripBand(List<String> ores, int minY, int maxY, int y, double perSlab, int x, int z, int feetY, String heading, String host,
                         List<String> headings, int freshDist) {
            this(ores, minY, maxY, y, perSlab, x, z, feetY, heading, host, headings, freshDist, Map.of(), List.of());
        }
        public StripBand(List<String> ores, int minY, int maxY, int y, double perSlab, int x, int z, int feetY, String heading, String host,
                         List<String> headings) {
            this(ores, minY, maxY, y, perSlab, x, z, feetY, heading, host, headings, 0);
        }
        public StripBand(List<String> ores, int minY, int maxY, int y, double perSlab, int x, int z, int feetY, String heading, String host) {
            this(ores, minY, maxY, y, perSlab, x, z, feetY, heading, host, null);
        }
        /**
         * The source key of this strip way, the same as its step's ({@link NeedChain#sourceKey(Step)}): a failed strip or no
         * path to its Y drops it for the order. 0.39.z64 item 4 / review 3: a strip from fresh ground carries its start's area square ("|from area ax,az"): held and resumed by (Y, heading, square), so a bot a few blocks on asks the same way, never a new one each plan.
         */
        public String key() { List<String> s = new ArrayList<>(ores); s.sort(null); return STRIP_KEY + String.join(",", s) + "|" + y + "|" + heading + (fresh() ? "|from " + from() : ""); }
        /** "area ax,az" of a fresh start's square, null for the strip from the feet's column. */
        public String from() { return fresh() ? "area " + io.github.mojolowjo.entropybot.aa.brain.StripAreas.square(x, z) : null; }
        /** A strip from fresh ground: {@code freshDist} blocks from the origin along its heading (0: from the feet's column). */
        public boolean fresh() { return freshDist > 0; }
        /** The same strip way in heading {@code h} (its list unchanged). */
        public StripBand toward(String h) { return new StripBand(ores, minY, maxY, y, perSlab, x, z, feetY, h, host, headings, freshDist, otherYs, List.of()); }
        /** 0.39.z64 item 4: the strip from fresh ground at (fx, fz), {@code dist} blocks away, heading {@code h}: a walk there first. */
        public StripBand freshAt(int fx, int fz, int dist, String h) { return new StripBand(ores, minY, maxY, y, perSlab, fx, fz, feetY, h, host, List.of(h), Math.max(1, dist), Map.of(), List.of()); }
        /**
         * 0.39.z78 dz (runs DZ 19:10:15-19:28:38, DY): every heading of this band at its start is held ({@code why}: each
         * "heading: reason") - the strip from the feet is no way, but the band is still there: {@link NeedChain#stripAlts}
         * offers strips from fresh ground (another start cell) and the band's other Y levels from this cell, each priced.
         */
        public StripBand shutWith(List<String> why) { return new StripBand(ores, minY, maxY, y, perSlab, x, z, feetY, heading, host, headings, freshDist, otherYs, why == null || why.isEmpty() ? List.of("every heading held") : why); }
        /** 0.39.z78 dz: every heading at the start is held (the strip from the feet is no way here). */
        public boolean isShut() { return !shut.isEmpty(); }
        /** 0.39.z78 dz: the same band at its other level {@code y2} ({@code ps}: its ore per slab), from the same cell, every heading tried again. */
        public StripBand atY(int y2, double ps) { return new StripBand(ores, minY, maxY, y2, ps, x, z, feetY, headings.get(0), host, headings, freshDist, Map.of(), List.of()); }
    }

    /**
     * 0.39.z62 item 1b (run S 04:57:30-35: "strip east feet y 16: 0 blocks ... no path" three times, once per ore set - the
     * new crafting table stood on the tunnel's first cell): a strip blocked in its heading is a fact about the HEADING at that
     * Y, whatever the ores; held for the order under this key, every strip way at {@code y} tries its next heading.
     */
    public static final String STRIP_HEADING = "strip heading ";

    public static String headingKey(int y, String heading) { return STRIP_HEADING + "y " + y + " " + heading; }

    /**
     * 0.39.z77 world 3 (review 1): a heading fact kept by the tunnel's start too ("strip heading y Y h|from area ax,az"):
     * the world fact another order learned (WorldFacts) skips that heading only for a strip from the same start.
     */
    public static String headingFrom(String headingKey, String start) { return headingKey + "|from " + start; }

    /**
     * 0.39.z78 strip (run DS 15:14:33, to-do/diag-strip-heading-hold.md; manager 2026-10-27): the ONE key every strip hold
     * has - its heading at its Y ({@link #headingKey}), scoped by a fresh start ({@link #headingFrom}) - from a strip's key
     * ("ores|Y|heading[|from area ax,az]", Step.Strip#key, with or without {@link #STRIP_KEY}). Was: water / lava ahead and
     * the other strip ends held the joined ore key, which the per-word strip ways never asked, so the re-plan ran the same
     * heading into the same water. Null: not a strip key.
     */
    public static String stripHold(String stripKey) {
        if (stripKey == null) return null;
        String k = stripKey.startsWith(STRIP_KEY) && !stripKey.startsWith(STRIP_HEADING) ? stripKey.substring(STRIP_KEY.length()) : stripKey;
        if (k.startsWith(STRIP_HEADING)) return k;
        String[] p = k.split("\\|");
        if (p.length < 3 || !p[1].matches("-?\\d{1,4}") || p[2].isBlank()) return null;     // structural: ores | y | heading
        String h = headingKey(Integer.parseInt(p[1]), p[2]);
        String from = p.length > 3 && p[3].startsWith("from ") ? p[3].substring("from ".length()) : null;     // structural: the 4th part
        return from == null ? h : headingFrom(h, from);
    }

    /** 0.39.z78 strip: the hold's reason of heading {@code hd} of band {@code r} at its Y (from the feet, or from its start), null: open. */
    static String headingHeld(Map<String, String> keyWhy, StripBand r, String hd) {
        String k = headingKey(r.y(), hd);
        if (!r.fresh() && keyWhy.containsKey(k)) return keyWhy.get(k);                          // a fresh start's tunnel is its own
        return keyWhy.get(headingFrom(k, stripStart(r)));                                       // 0.39.z77 world 3: from this start
    }

    /** 0.39.z78 strip: the same for band {@code r} at Y {@code y}, read from a World's held list ("key: why" lines, World#held), null: open. */
    static String headingHeld(List<String> held, StripBand r, int y, String hd) {
        String k = headingKey(y, hd), kf = headingFrom(k, stripStart(r));
        for (String h : held) {
            if (!r.fresh() && h.startsWith(k + ": ")) return h.substring(k.length() + 2);
            if (h.startsWith(kf + ": ")) return h.substring(kf.length() + 2);
        }
        return null;
    }

    /** 0.39.z77 world 3: a strip way's start - a fresh start's own area, else the square of the column it starts from. */
    public static String stripStart(StripBand b) {
        return b.fresh() ? b.from() : "area " + io.github.mojolowjo.entropybot.aa.brain.StripAreas.square(b.x(), b.z());
    }

    /**
     * 0.39.z67 item 1 (run Y 09:26:14 / 09:31:16: "place crafting_table near me: no cell within 3 blocks; none to clear either"
     * swimming at sea level, re-planned to the same "place near me" at the same spot, the order stopped): a place-near that
     * failed is a fact about WHERE it failed: held for the order under this key ("place at x y z", the feet then).
     */
    public static final String PLACE_AT = "place at ";

    public static String placeKey(int x, int y, int z) { return PLACE_AT + x + " " + y + " " + z; }

    /** 0.39.z67: the cell of a {@link #placeKey}, null for any other key. Pure. */
    static int[] placeCell(String key) {
        if (key == null || !key.startsWith(PLACE_AT)) return null;
        String[] c = key.substring(PLACE_AT.length()).trim().split("\\s+");
        if (c.length != 3) return null;     // structural: x y z
        for (String n : c) if (!n.matches("-?\\d{1,9}")) return null;
        return new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])};
    }

    /** 0.39.z67: straight-line blocks between two cells. */
    static double dist3(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 0.39.z67 item 1 (Tuning place.failedNearM). */
    public static double placeFailedNearM() { return Tuning.get("place.failedNearM"); }

    /** 0.39.z67 item 1 (Tuning place.roomNear). */
    static int roomNear() { return Tuning.i("place.roomNear"); }

    /**
     * 0.39.z67 item 1: where a station may be placed when placing near me failed here. {@code cells}: cells with room from
     * facts (solid ground the bot stood on, known stations), each with its straight distance from the bot now;
     * {@code hereFailed}: why placing near me here is out (the held failure's words), null: not out.
     */
    public record Rooms(List<Spot> cells, String hereFailed) {
        public static final Rooms NONE = new Rooms(List.of(), null);
        public Rooms { cells = cells == null ? List.of() : List.copyOf(cells); }
    }

    /**
     * 0.39.z68: the surface over the bot: {@code depth} blocks of ground over the feet (0: under open sky, RestoreRules'
     * one under-ground rule), and the cell to walk up to (the nearest open-sky standing cell, else the column's top);
     * {@code dist}: its straight metres from the feet (a hint; the price is Baritone's path, rule 18).
     */
    public record Surface(int depth, int x, int y, int z, double dist) {}

    /**
     * 0.39.z68 (owner 2026-10-25: "if it's wood - go to surface to explore, if it's ore, explore for caves"): the facts the
     * explore leaf reads: {@code of}: need word -> where its blocks generate ({@link io.github.mojolowjo.entropybot.aa.brain.GenWhere},
     * from the jars' world-gen data; null: no such fact given, the explore as before z68); {@code surface}: the surface over
     * the bot now (null: not known - the column not loaded, rule 15). 0.39.z69 item 1: {@code feet}: the bot's feet cell now
     * {x, y, z} (null: not known) - the band walk's cell (the feet's column at the band's nearest Y, as StepHands walks it)
     * is priced from it; not known: the ToBand step as before z69 (unpriced, said).
     */
    public record WhereFacts(Function<String, io.github.mojolowjo.entropybot.aa.brain.GenWhere.Fact> of, java.util.function.Supplier<Surface> surface,
                             java.util.function.Supplier<int[]> feet, java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.brain.GenWhere.Base> base) {
        public static final WhereFacts NONE = new WhereFacts(w -> null, () -> null);

        public WhereFacts(Function<String, io.github.mojolowjo.entropybot.aa.brain.GenWhere.Fact> of, java.util.function.Supplier<Surface> surface) {
            this(of, surface, null);
        }

        /** No base block known (before 0.39.z74). */
        public WhereFacts(Function<String, io.github.mojolowjo.entropybot.aa.brain.GenWhere.Fact> of, java.util.function.Supplier<Surface> surface,
                          java.util.function.Supplier<int[]> feet) {
            this(of, surface, feet, null);
        }

        /** 0.39.z74 item 4: {@code base} - this dimension's base block (GenWhere.Base, from its noise settings); null: not known. */
        public WhereFacts {
            if (of == null) of = w -> null;
            if (surface == null) surface = () -> null;
            if (feet == null) feet = () -> null;
            if (base == null) base = () -> null;
        }
    }

    /** 0.39.z68: the source key of the walk up to the surface ("surface at x y z"); any one held for the order: no walk up. */
    public static final String SURFACE_AT = "surface at ";

    public static String surfaceKey(int x, int y, int z) { return SURFACE_AT + x + " " + y + " " + z; }

    /**
     * 0.39.z77 stone (run CF 16:55:08-16:55:38, under ground at 11 16 -19: the one stone spot the lookup named, 6 16 -21,
     * came back UNREACHABLE, the order held that spot, and the re-plan said "at band: iron_ingot ends in an explore (no known
     * source for #stone_tool_materials)" with stone all round the bot; the order stopped): a source lookup that names the
     * nearest counted spot can be asked to pass over the spots {@code skip} refuses (need word, spot: the order's holds,
     * {@link World#without}) and name the next nearest instead. A lookup that answers the nearest one only (the live one)
     * would otherwise answer the held spot, the hold would filter it out after, and the need would have no source left while
     * the scan counts the tunnel's own walls. {@code skip} null: no hold. A plain function (the tests' lists of every known
     * spot) is filtered after, as before. Pure.
     */
    @FunctionalInterface
    public interface Lookup extends Function<String, List<Spot>> {
        List<Spot> apply(String word, java.util.function.BiPredicate<String, Spot> skip);

        @Override
        default List<Spot> apply(String word) { return apply(word, null); }
    }

    /**
     * What the bot knows now. {@code bag}: item id -> count; {@code tableNear} / {@code furnaceNear}: one within reach;
     * {@code seenAll}: a need word (an item id, a tag "#ns:path", or several joined by "|") -> the known blocks that give it
     * (for a tag: any member; nearest first not needed); {@code mobs}: entity id -> the animals of that kind known;
     * {@code loot}: the game's loot data (mob drops; null: none read); {@code band}: need word -> {minY, maxY} where the
     * blocks that drop it generate (the worldgen data; null: unknown); {@code horizonBlocks}: a way dearer than this is
     * priced out when another stands (0: the planning horizon). {@code stationAt}: station-memory (0.39.z14): recipe kind
     * (ItemWorth.CRAFT / SMELT) -> where the known station stands (the station memory's nearest, any distance; null: not
     * known or no position): the chain prices the walk to it and says "at the table 20 m". Lookups are memoised per World.
     */
    public record World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                        Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks, PathPrice path,
                        Function<String, Spot> stationAt, Function<String, Integer> usesLeft, List<String> held, Function<String, StripBand> strip,
                        Function<String, CaveWay> cave, Rooms rooms, WhereFacts where) {
        /** 0.39.z68: no "where" facts ({@link WhereFacts#NONE}: every explore as before z68). */
        public World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                     Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks, PathPrice path,
                     Function<String, Spot> stationAt, Function<String, Integer> usesLeft, List<String> held, Function<String, StripBand> strip,
                     Function<String, CaveWay> cave, Rooms rooms) {
            this(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, strip, cave, rooms, null);
        }

        /** 0.39.z67 item 1: no cells with room known and no place failure held ({@link Rooms#NONE}). */
        public World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                     Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks, PathPrice path,
                     Function<String, Spot> stationAt, Function<String, Integer> usesLeft, List<String> held, Function<String, StripBand> strip,
                     Function<String, CaveWay> cave) {
            this(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, strip, cave, null, null);
        }

        /** 0.39.z44: no cave-mouth facts (the cave way is not offered). */
        public World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                     Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks, PathPrice path,
                     Function<String, Spot> stationAt, Function<String, Integer> usesLeft, List<String> held, Function<String, StripBand> strip) {
            this(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, strip, null);
        }

        /** 0.39.z43: no strip facts (the strip way is not offered). */
        public World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                     Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks, PathPrice path,
                     Function<String, Spot> stationAt, Function<String, Integer> usesLeft, List<String> held) {
            this(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, null);
        }

        /** 0.39.z33: no tool-wear facts (every tool unlimited) and no source held. */
        public World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                     Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks, PathPrice path,
                     Function<String, Spot> stationAt) {
            this(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, null, null, null);
        }

        /** No path provider: no walk has a price (0.39.z41: "walk: path not known (no path provider)"); ways without a walk stand. */
        public World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                     Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks) {
            this(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, null, null);
        }

        public World(Map<String, Integer> bag, boolean tableNear, boolean furnaceNear, Function<String, List<Spot>> seenAll,
                     Function<String, List<Mob>> mobs, LootBook loot, Function<String, int[]> band, double horizonBlocks, PathPrice path) {
            this(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, null);
        }

        public World {
            if (stationAt == null) stationAt = k -> null;
            if (usesLeft == null) usesLeft = k -> io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED;
            held = held == null ? List.of() : List.copyOf(held);
            Map<String, Integer> b = new LinkedHashMap<>();
            if (bag != null) for (var e : bag.entrySet()) b.merge(id(e.getKey()), e.getValue(), Integer::sum);
            bag = Map.copyOf(b);
            Function<String, List<Spot>> sa = seenAll == null ? w -> List.of() : seenAll;
            Map<String, List<Spot>> memo = new ConcurrentHashMap<>();
            Function<String, List<Spot>> once = w -> memo.computeIfAbsent(word(w), k -> { List<Spot> l = sa.apply(k); return l == null ? List.of() : List.copyOf(l); });
            // 0.39.z77 stone: a lookup that can pass over held spots stays one through the memo (World#without asks it so)
            seenAll = sa instanceof Lookup lk ? (Lookup) (w, skip) -> {
                if (skip == null) return once.apply(w);
                List<Spot> l = lk.apply(word(w), skip);
                return l == null ? List.of() : List.copyOf(l);
            } : once;
            Function<String, List<Mob>> mb = mobs == null ? w -> List.of() : mobs;
            Map<String, List<Mob>> mmemo = new ConcurrentHashMap<>();
            mobs = w -> mmemo.computeIfAbsent(id(w), k -> { List<Mob> l = mb.apply(k); return l == null ? List.of() : List.copyOf(l); });
            if (band == null) band = w -> null;
            if (strip == null) strip = w -> null;
            if (cave == null) cave = w -> null;
            if (rooms == null) rooms = Rooms.NONE;
            if (where == null) where = WhereFacts.NONE;
            if (horizonBlocks <= 0) horizonBlocks = Unit.blocks(Sequencer.defaultHorizonS());
        }

        /** A world of blocks only (tests and the simple callers): the nearest source per item. */
        public World(Map<String, Integer> bag, boolean tableNear, Function<String, Spot> seen) {
            this(bag, tableNear, false, w -> { Spot s = seen == null ? null : seen.apply(w); return s == null ? List.of() : List.of(s); }, null, null, null, 0);
        }

        public World(Map<String, Integer> bag, boolean tableNear, Function<String, Spot> seen, double horizonBlocks) {
            this(bag, tableNear, false, w -> { Spot s = seen == null ? null : seen.apply(w); return s == null ? List.of() : List.of(s); }, null, null, null, horizonBlocks);
        }

        public World withMobs(Function<String, List<Mob>> m, LootBook l) { return new World(bag, tableNear, furnaceNear, seenAll, m, l, band, horizonBlocks, path, stationAt, usesLeft, held, strip, cave, rooms, where); }

        public World withBand(Function<String, int[]> b) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, b, horizonBlocks, path, stationAt, usesLeft, held, strip, cave, rooms, where); }

        /** path-cost: the walks priced by Baritone's complete path (null: no walk has a price). */
        public World withPath(PathPrice p) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, p, stationAt, usesLeft, held, strip, cave, rooms, where); }

        /** station-memory: where the known stations stand (recipe kind -> spot; null: no position known). */
        public World withStations(Function<String, Spot> at) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, at, usesLeft, held, strip, cave, rooms, where); }

        /** 0.39.z33: the uses left of the bag's best stack of a tool id ({@link io.github.mojolowjo.entropybot.aa.brain.ToolUses}). */
        /** 0.39.z43: the strip facts per need word ({@link StripBand}; null: no strip way for it). */
        public World withStrip(Function<String, StripBand> s) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, s, cave, rooms, where); }

        /** 0.39.z44: the cave-mouth facts per need word ({@link CaveWay}; null: no cave way for it). */
        public World withCave(Function<String, CaveWay> k) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, strip, k, rooms, where); }   // z68 review 1: keeps the where facts

        public World withUses(Function<String, Integer> u) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, u, held, strip, cave, rooms, where); }

        /** 0.39.z67 item 1: the cells with room known now (solid ground the bot stood on, known stations; dist from the bot). */
        public World withRooms(List<Spot> cells) {
            return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, strip, cave,
                    new Rooms(cells, rooms.hereFailed()), where);
        }

        /** 0.39.z76 send-back 2: the order's held sources ("key: why"), as the live world reads them from the order. */
        public World withHeld(List<String> h) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, h, strip, cave, rooms, where); }

        /** 0.39.z68: where explore targets generate and the surface over the bot ({@link WhereFacts}). */
        public World withWhere(WhereFacts w) { return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, strip, cave, rooms, w); }

        /** 0.39.z77 J2: the same world with another bag (a chain priced from the bag another left). */
        public World withBag(Map<String, Integer> b) { return new World(b, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, strip, cave, rooms, where); }

        /**
         * 0.39.z67 item 1 (run Y 09:26:14 / 09:31:16): the order's held place failures ({@code keyWhy}: its dropped sources;
         * the {@link #PLACE_AT} keys count, each the cell where a place-near failed) seen from {@code pos} (the feet now; null:
         * not known, then none applies here - the step says it again if it fails, rule 15). The bot within
         * {@code place.failedNearM} of one: placing near me here is out ({@link Rooms#hereFailed}, its reason); a cell with
         * room within {@code steps.placeNearR} of one (the cells that placeNear already looked at) is no cell with room. Nothing held: this world.
         */
        public World withPlaceFailures(Map<String, String> keyWhy, int[] pos) {
            if (keyWhy == null || keyWhy.isEmpty()) return this;
            List<int[]> failed = new ArrayList<>();
            String here = null;
            double near = placeFailedNearM();
            for (var e : keyWhy.entrySet()) {
                int[] at = placeCell(e.getKey());
                if (at == null) continue;
                failed.add(at);
                if (here == null && pos != null && dist3(pos, at) <= near) here = e.getValue();
            }
            if (failed.isEmpty()) return this;
            List<Spot> cells = new ArrayList<>();
            for (Spot s : rooms.cells()) {
                boolean close = false;
                for (int[] f : failed) close |= dist3(new int[]{s.x(), s.y(), s.z()}, f) <= Tuning.get("steps.placeNearR");   // the cells the failed placeNear looked at
                if (!close) cells.add(s);
            }
            return new World(bag, tableNear, furnaceNear, seenAll, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, held, strip, cave,
                    new Rooms(cells, here), where);
        }

        /**
         * chop: the same world less the sources {@code held} names (need word, spot): a failed source is out of the re-price.
         * 0.39.z77 stone: a {@link Lookup} is asked to pass over the held spots (the next nearest one stands, not none); its
         * answer is filtered too.
         */
        public World without(java.util.function.BiPredicate<String, Spot> held) {
            Function<String, List<Spot>> sa = seenAll;
            Function<String, List<Spot>> less = sa instanceof Lookup lk
                    ? (Lookup) (w, skip) -> lk.apply(w, skip == null ? held : held.or(skip)).stream().filter(sp -> !held.test(word(w), sp)).toList()
                    : w -> sa.apply(w).stream().filter(sp -> !held.test(word(w), sp)).toList();
            return new World(bag, tableNear, furnaceNear, less, mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, this.held, strip, cave, rooms, where);
        }

        /**
         * 0.39.z33: the same world less the sources an order dropped ({@code keyWhy}: source key ({@link #sourceKey(Step)}) ->
         * reason); each is listed in the chain's dropped ways with its reason.
         */
        public World withoutSources(Map<String, String> keyWhy) {
            if (keyWhy == null || keyWhy.isEmpty()) return this;
            List<String> h = new ArrayList<>(held);
            for (var e : keyWhy.entrySet()) h.add(e.getKey() + ": " + e.getValue());
            List<String[]> near = nearHolds(keyWhy.keySet());                                // z75 review 2: a short collect's position
            World less = without((item, sp) -> spotHeld(item, sp, keyWhy::containsKey) || nearHeld(near, item, sp));     // 0.39.z69 fix 1: an item key or one spot's
            // 0.39.z42: a dropped band (no path into it from here) is no band for the order: its explore goes overland
            Function<String, int[]> b = band == null ? null : word -> {
                int[] r = band.apply(word);
                return r != null && keyWhy.containsKey(io.github.mojolowjo.entropybot.aa.moves.FactChange.bandKey(r[0], r[1])) ? null : r;
            };
            Function<String, StripBand> st = strip;
            Function<String, StripBand> stripLess = word -> {           // 0.39.z43: the same dropped band drops its strip way
                StripBand r = st.apply(word);
                if (r == null || r.isShut()) return r;          // 0.39.z78 dz: shut already (every start cell blocked, AaRuntime)
                // 0.39.z62 item 1b: a heading blocked at this Y is skipped for the next open one; the strip itself dropped
                // (its key: lava, no path down to the band, a tunnel that ended short) drops the way as before
                // 0.39.z78 strip (run DS): every strip hold is its heading key (stripHold), so this is its one reader; the old
                // per-word way-key check is deleted - no hold of that form is written any more (it never matched the playbook's
                // joined strip, the run DS loop)
                List<String> why = new ArrayList<>();
                for (String hd : r.headings()) {
                    String held = headingHeld(keyWhy, r, hd);
                    if (held != null) { why.add(hd + ": " + held); continue; }
                    return r.toward(hd);
                }
                // 0.39.z78 dz (run DZ 19:10:15-19:28:38: water held all four headings at y 16 for a strip from the feet, and
                // the way was null from anywhere - its fresh starts and the band's other levels were never asked): every
                // heading held here is a fact about THIS start; the band stays, shut, so the strip way prices the next options
                return r.shutWith(why);
            };
            Function<String, CaveWay> cv = cave;
            Function<String, CaveWay> caveLess = word -> {             // 0.39.z44: a mouth with no path is dropped for the order
                CaveWay r = cv.apply(word);
                return r != null && keyWhy.containsKey(r.key()) ? null : r;
            };
            // 0.39.z65 review 1: a known station whose priced walk failed in this order ("station at x y z") is out for the
            // order: no position, and not "near" either (the chain then prices placing a new one here)
            Function<String, Spot> sa = stationAt;
            Function<String, Spot> stationLess = k -> {
                Spot s = sa.apply(k);
                return s != null && keyWhy.containsKey(stationKey(s.x(), s.y(), s.z())) ? null : s;
            };
            boolean tn = tableNear && !heldStation(sa, keyWhy, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.CRAFT);
            boolean fn = furnaceNear && !heldStation(sa, keyWhy, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.SMELT);
            // 0.39.z77 walk: a walk target the order holds (ended short twice at the same feet) answers unreachable
            PathPrice pathLess = heldWalks(path, keyWhy);
            return new World(bag, tn, fn, less.seenAll, mobs, loot, b, horizonBlocks, pathLess, stationLess, usesLeft, h, stripLess, caveLess, rooms, where);
        }

        /** 0.39.z65 review 1: the known station of the kind is one the order dropped. */
        private static boolean heldStation(Function<String, Spot> at, Map<String, String> keyWhy, String kind) {
            Spot s = at.apply(kind);
            return s != null && keyWhy.containsKey(stationKey(s.x(), s.y(), s.z()));
        }
    }

    /**
     * @param steps   the typed steps, in run order (empty: nothing to do)
     * @param cost    walk-blocks ({@link Unit}); {@code why}: the whole chain in one line, the dropped ways after it
     * @param explore the chain ends with the explore way (something needed is not known yet): it has no price in {@code why}
     * @param missing why there is no way at all (null: there is one)
     * @param dropped every way priced out on the way, each with its reason (seconds, or "no known source", or "loop")
     */
    public record Chain(List<Step> steps, double cost, String why, boolean explore, String missing, List<String> dropped, Map<String, String> unreachable,
                        List<Map<String, Integer>> uses, String spares, List<Double> stepSeconds) {
        /** 0.39.z65 item 2: {@code spares} - the spare-tool decisions of the ways kept ("" none), for the log's one line per chain. */
        public Chain(List<Step> steps, double cost, String why, boolean explore, String missing, List<String> dropped, Map<String, String> unreachable,
                     List<Map<String, Integer>> uses, String spares) { this(steps, cost, why, explore, missing, dropped, unreachable, uses, spares, List.of()); }
        public Chain(List<Step> steps, double cost, String why, boolean explore, String missing, List<String> dropped, Map<String, String> unreachable,
                     List<Map<String, Integer>> uses) { this(steps, cost, why, explore, missing, dropped, unreachable, uses, ""); }
        public Chain(List<Step> steps, double cost, String why, boolean explore, String missing, List<String> dropped, Map<String, String> unreachable) { this(steps, cost, why, explore, missing, dropped, unreachable, List.of()); }
        public Chain(List<Step> steps, double cost, String why, boolean explore, String missing, List<String> dropped) { this(steps, cost, why, explore, missing, dropped, Map.of()); }
        public Chain(List<Step> steps, double cost, String why, boolean explore, String missing) { this(steps, cost, why, explore, missing, List.of()); }
        /**
         * 0.39.z61 (D1 J2 3.1 item 1): {@code uses} - per step, parallel to {@code steps}, what the planner's simulated bag
         * took for that step (need word -> count; null: the step took nothing it counted, StepNeeds falls back to the step's
         * own data). Empty: not known (an older caller). A parallel list, not a field on the step records: the re-price
         * compares step lists by equals, and a bag-mix change is not "other steps".
         */
        /*
         * 0.39.z73 (design D2 3.4, Job D): {@code stepSeconds} - per step, parallel to {@code steps}, the chain's own rough
         * seconds split: what the planner priced after the step was added until the next step was (the seconds priced before
         * the first add go to the first step; an explore chain's explore leg to its last step), so they sum to
         * {@link #seconds()}. Empty: not known (an older caller). The repair prices a kept tail by them (rule 14: the same
         * rough numbers split, no finer term).
         */
        public Chain {
            stepSeconds = stepSeconds == null ? List.of() : List.copyOf(stepSeconds);
            spares = spares == null ? "" : spares;
            unreachable = unreachable == null ? Map.of() : Map.copyOf(unreachable);
            uses = uses == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(uses));
        }
        public boolean ok() { return missing == null; }
        /**
         * loot-book-bg: no way stands only because the loot book is still being read: no park, no explore; priced again when it
         * lands. 0.39.z41: or because a walk's path is not known yet / its chunks are not loaded ({@link PathPrice#NOT_KNOWN}).
         */
        public boolean waiting() { return answerPending(missing); }
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
    public static final String LOGS_TAG = "minecraft:logs";      // 0.39.z73 review 4: Serves / StepMonitor read the same tag

    static String s(double seconds) { return String.format(Locale.ROOT, "%.1f s", seconds); }

    static String id(String w) { return w == null ? null : w.indexOf(':') < 0 ? "minecraft:" + w : w; }

    /** The id as the lines and steps write it ("minecraft:torch" -> "torch"; a modded id stays whole). */
    static String sh(String id) { return id == null ? "?" : id.startsWith("minecraft:") ? id.substring(10) : id; }

    // ---- TAG_NEEDS: a need word is an item id, a tag ("#ns:path") or several of them joined by "|" ----

    /** TAG_NEEDS: the item counts for the need word (an item, a tag member by the game's tag index, or one of several); the live lookups' test. */
    public static boolean gives(String word, String item) { return word != null && new Want(word).has(item); }

    /** TAG_NEEDS: the need word is one item (not a tag, not several). */
    public static boolean plain(String word) { return word != null && new Want(word).item(); }

    /** 0.39.z37: the items a need word names (a tag's members, the items of an "a|b" list), in id order. */
    public static Set<String> membersOf(String word) { return new Want(word).members(); }

    /** A need word in full: "#planks" -> "#minecraft:planks", "torch" -> "minecraft:torch"; several parts in one canonical order. */
    public static String word(String raw) {
        if (raw == null) return null;
        if (raw.indexOf('|') < 0) return raw.startsWith("#") ? "#" + id(raw.substring(1)) : id(raw);
        Set<String> parts = new TreeSet<>();
        for (String p : raw.split("\\|")) if (!p.isEmpty()) parts.add(word(p));
        return String.join("|", parts);
    }

    /** A need word as the lines write it: "#minecraft:planks" -> "#planks". */
    public static String shw(String word) {
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

        /** 0.39.z78 D6: the key of a bag scan by {@link #has} (one predicate per need word). */
        String scanKey() { return "has " + word; }

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

    // ---- 0.39.z28 aa debug: the pricing recorded as a tree (read only: no price, way or pick reads it) ----

    /**
     * The planner's thinking while it prices one chain: every requirement's ways (each a node with what it priced inside it,
     * its walks with the estimate kind, its breaks, its sources, its stations), each way's verdict in the chain's own words
     * (the dropped reason the dropped list gets), and the explore "until" words with the ways that ended in them. Recorded
     * only inside {@link #traced}; outside it the chain records nothing.
     */
    public static final class Trace {
        /** One line of the tree and the lines under it. */
        public static final class Node {
            public final String text;
            public final List<Node> kids = new ArrayList<>();
            /** The way's verdict ("picked: 9.0 s", "dropped: loop ..."), null for a fact line. */
            public String verdict;
            final boolean way;
            Node(String text, boolean way) { this.text = text; this.way = way; }
        }

        public final Node root = new Node("chain", false);
        private final java.util.ArrayDeque<Node> open = new java.util.ArrayDeque<>();
        /** Explore until word (as the step writes it) -> the ways that ended in exploring for it, in order. */
        public final Map<String, Set<String>> untilFrom = new LinkedHashMap<>();

        Trace() { open.push(root); }

        void note(String s) { open.peek().kids.add(new Node(s, false)); }

        Node push(String s, boolean way) {
            Node n = new Node(s, way);
            open.peek().kids.add(n);
            open.push(n);
            return n;
        }

        void pop() { if (open.size() > 1) open.pop(); }

        /** The innermost way being priced ("the need" when none: the order's own requirement). */
        String way() {
            for (Node n : open) if (n.way) return n.text.substring("way ".length());
            return "the need itself";
        }

        /** The tree as indented lines, two spaces a level, from {@code depth}. */
        public List<String> lines(int depth) {
            List<String> out = new ArrayList<>();
            for (Node k : root.kids) lines(k, depth, out);
            return out;
        }

        private static void lines(Node n, int depth, List<String> out) {
            out.add("  ".repeat(depth) + n.text + (n.verdict == null ? "" : " -> " + n.verdict));
            for (Node k : n.kids) lines(k, depth + 1, out);
        }
    }

    private static final ThreadLocal<Trace> TRACE = new ThreadLocal<>();

    /** The chain {@code run} prices, with its thinking recorded into {@code t} (aa debug). The chain is the same as without. */
    public static Chain traced(Trace t, Supplier<Chain> run) {
        Trace was = TRACE.get();
        TRACE.set(t);
        try {
            return run.get();
        } finally {
            if (was == null) TRACE.remove(); else TRACE.set(was);
        }
    }

    /** A new, empty trace for {@link #traced}. */
    public static Trace trace() { return new Trace(); }

    /** 0.39.z77 trip: the fuels a trip burned before (need words), kept while {@link #keepingFuel} prices. */
    private static final ThreadLocal<Set<String>> KEPT_FUEL = new ThreadLocal<>();

    /**
     * 0.39.z77 trip (manager's ruling 2026-10-26 ~21:35, fix 2; run CU 21:02-21:05: the descent's fuel swapped coal / oak_slab /
     * coal_block between re-prices of the same trip): {@code run} priced with the fuels the trip burned before ({@code kept});
     * a smelt burns one of them from the bag while the bag (less what is set aside) covers it ({@link #burn}), else the
     * cheapest as before. Nothing is kept outside the call.
     */
    public static <T> T keepingFuel(Set<String> kept, Supplier<T> run) {
        Set<String> was = KEPT_FUEL.get();
        KEPT_FUEL.set(kept == null ? Set.of() : kept);
        try {
            return run.get();
        } finally {
            if (was == null) KEPT_FUEL.remove(); else KEPT_FUEL.set(was);
        }
    }

    /** 0.39.z77 trip: of the kept fuels ({@link #keepingFuel}), the one the {@code free} bag covers for {@code k} smelts; null: none. */
    private static io.github.mojolowjo.entropybot.craft.CraftPlanner.Fuel keptFuel(Map<String, Integer> free, int k) {
        Set<String> kept = KEPT_FUEL.get();
        if (kept == null || kept.isEmpty()) return null;
        Map<String, Integer> only = new LinkedHashMap<>();
        for (var e : free.entrySet()) if (kept.contains(word(e.getKey()))) only.put(e.getKey(), e.getValue());
        return only.isEmpty() ? null : io.github.mojolowjo.entropybot.craft.CraftPlanner.pickFuel(only, k);
    }

    // ---- the recursion ----

    private static final class Ctx {
        final Trace trace = TRACE.get();
        final Facts w;
        /** 0.39.z78 D6: this resolve's node run (the reads, the open node); null: the resolve as before D6 (PlanNodes.old, a trace). */
        final PlanNodes.Run run;
        final TrackedBag bag = new TrackedBag();
        /** 0.39.z73 (D2 3.4): each step object -> {@link #seconds} when it was first added (Chain#stepSeconds; never restored). */
        final Map<Step, Double> addedAt = new java.util.IdentityHashMap<>();
        /** 0.39.z78 D6: a step whose add mark was copied from the step it replaced ({@link Marked#set}) -> that step. */
        final Map<Step, Step> markFrom = new java.util.IdentityHashMap<>();
        final List<Step> steps = new Marked();

        /** The chain's steps, each marked with the seconds priced so far when it first came in (a restore re-adds the same objects). */
        private final class Marked extends ArrayList<Step> {
            @Override public boolean add(Step s) {
                addedAt.putIfAbsent(s, seconds);
                return super.add(s);
            }

            @Override public boolean addAll(java.util.Collection<? extends Step> all) {
                for (Step s : all) addedAt.putIfAbsent(s, seconds);
                return super.addAll(all);
            }

            @Override public Step set(int i, Step s) {
                Step old = super.set(i, s);
                Double m = old == null ? null : addedAt.get(old);     // a merged leaf keeps its place in the split
                if (m != null && addedAt.putIfAbsent(s, m) == null) markFrom.put(s, old);
                return old;
            }
        }

        boolean caching() { return run != null; }

        /** 0.39.z78 D6: the walk memo's entry {@code key} (read by the open node). */
        Walked walk(String key) {
            Walked v = walks.get(key);
            if (run != null && run.top != null) {
                PlanNodes.Frame f = run.top;
                if (!f.walkWrites.contains(key) && !f.walkReads.containsKey(key)) f.walkReads.put(key, PlanNodes.box(v));
            }
            return v;
        }

        /** 0.39.z78 D6: the walk memo's entry {@code key} set (written by the open node). */
        void walk(String key, Walked v) {
            if (run != null && run.top != null) {
                PlanNodes.Frame f = run.top;
                if (!f.walkWrites.contains(key) && !f.walkReads.containsKey(key)) f.walkReads.put(key, PlanNodes.box(walks.get(key)));
                f.walkWrites.add(key);
            }
            walks.put(key, v);
        }

        /** 0.39.z78 D6: an unreachable source fact (kept with the open node, put again when it is reused). */
        void unreach(String key, String why) {
            if (run != null && run.top != null) run.top.unreach.add(new Object[]{key, why});
            unreachable.put(key, why);
        }

        /** 0.39.z78 D6: the unreachable facts read whole - the open node's result depends on what came before it: not kept. */
        Map<String, String> unreachableRead() {
            notKept();
            return unreachable;
        }

        /** 0.39.z78 D6: the step's counted uses (kept with the open node). */
        void use(Step s, Map<String, Integer> u) {
            if (run != null && run.top != null) run.top.uses.put(s, u);
            uses.put(s, u);
        }

        /** 0.39.z78 D6: the open node reads something no node can be keyed by (it is resolved again each time). */
        void notKept() { if (run != null && run.top != null) run.top.keep = false; }

        /**
         * 0.39.z78 D6 (run DR round, option C: nodes keep their sums): {@code s} more seconds priced, on the {@link #grid}
         * (so every sum is exact in any order: a node's own seconds are the same whatever came before it).
         */
        void addS(double s) { seconds += grid(s); }

        /**
         * 0.39.z78 D6 (option C): the walk back {@link #away} read - the open node's fact while it has not set it itself (its
         * key then holds it); a node that never read it before setting it is keyed without it.
         */
        double away() {
            if (run != null && run.top != null && !run.top.awayWritten) run.top.awayRead = true;
            return away;
        }

        /** 0.39.z78 D6 (option C): the walk back set (the open node's own; a reuse sets it only when the node did). */
        void setAway(double v) {
            if (run != null && run.top != null) run.top.awayWritten = true;
            away = v;
        }

        /**
         * 0.39.z78 D6 (option C): any dropped line matches the read {@code dep} ({@link #dropTest}: "head ", "dearer " or
         * "exact " and its text). A read of the lines before the open node is its fact (kept as present or not), unless a line
         * of its own matches.
         */
        boolean droppedHas(String dep) {
            java.util.function.Predicate<String> test = dropTest(dep);
            boolean own = false, before = false;
            int at = run != null && run.top != null ? Math.min(run.top.droppedAt, dropped.size()) : 0;
            for (int i = 0; i < dropped.size(); i++) {
                if (!test.test(dropped.get(i))) continue;
                if (i < at) before = true; else own = true;
            }
            if (run != null && run.top != null && !own) run.top.dropReads.putIfAbsent(dep, before);
            return own || before;
        }

        /** 0.39.z78 D6 (option C): the steps before the open node were read (each open node's key then holds them). */
        void prefixRead() {
            if (run == null) return;
            for (PlanNodes.Frame f = run.top; f != null; f = f.up) f.prefixRead = true;
        }

        /** 0.39.z78 D6: needChain.maxTrials passed - the check counted in the open node (its trial bound, PlanNodes.Node#bound). */
        boolean overBound() {
            boolean over = trials > maxTrials();
            if (run != null && run.top != null) {
                PlanNodes.Frame f = run.top;
                f.maxOff = Math.max(f.maxOff, trials - f.entryTrials);
                if (over) f.bound = true;
            }
            return over;
        }

        /** 0.39.z78 D6: an effect (a write outside the planner's state): run now, or kept for the apply on the worker. */
        void effect(PlanNodes.Effect e) {
            if (run != null) run.effect(e);
            else e.run(w.raw().path());
        }
        final Map<String, Integer> leafAt = new LinkedHashMap<>();   // spot key -> index of its collect step
        final Map<String, Integer> taken = new LinkedHashMap<>();    // spot key -> blocks taken from it
        final List<String> path = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        double seconds;       // everything priced so far, in seconds
        double away;          // seconds of the walk from the last new source back to where the stations are (0: at them)
        final Map<String, Walked> walks = new LinkedHashMap<>();   // path-cost: spot key -> its walk, one pricing pass (never restored)
        final Map<String, String> unreachable = new LinkedHashMap<>();   // 0.39.z41: source key -> Baritone's unreachable reason (never restored)
        boolean tableReady, furnaceReady, stuck;
        boolean tableWalked, furnaceWalked;   // station-memory: the walk to the known station is priced (or the station was placed here)
        boolean atRoom;       // 0.39.z67 item 1: the chain walked to a cell with room (placing near me failed where the bot stood)
        String fail;          // the way being tried cannot be done: the reason
        int pathsWaiting;     // 0.39.z42: the ways of the last requirement with no way standing that wait on a path (choose)
        String unknown;       // the first need nothing is known to give (the explore leaf's)
        String loopOn;        // the need a loop failure came back to (set with fail)
        String pickItem;      // the best pickaxe in hand or made
        int pickTier;
        int pickWorn;         // 0.39.z33: blocks the bag's pickItem breaks in this chain so far (a made pickaxe: reset, it is new)
        boolean pickMade;     // 0.39.z33: pickItem was made in this chain (fresh: its uses are its max damage, not the bag's)
        boolean pickOut;      // 0.39.z77 spares 2: pickItem is not in the simulated bag (spares made and worn in a way: out of it)
        int trials;           // ways priced so far (the search's bound)
        boolean bandPick;     // 0.39.z69 item 1: a band walk's pickaxe-first is being priced (no second one inside it)
        /**
         * 0.39.z61 (D1 J2): a craft / smelt step (this very object) -> what the simulated bag took for it, by the recipe slot's
         * need word. Keyed by identity: a dropped way's steps simply never reach the chain (no restore needed).
         */
        final Map<Step, Map<String, Integer>> uses = new java.util.IdentityHashMap<>();
        /*
         * 0.39.z75 item 1 (run AE 17:29:44, AF 18:12:45: the table and the wood left on the surface): where the chain leaves the
         * bot. {@code down}: the leg that took it to another level (a strip leg from the feet to the band's Y), null: the
         * bot is where the walks are priced from (the feet now, the base). {@code downAt}: that leg's first step index (the
         * steps of a need met "before going down" go in before it). {@code tableDown} / {@code furnaceDown}: a station of the
         * kind was placed down there in this chain (the base flags tableReady / tableWalked stay the base's).
         */
        String down;
        int downAt = -1;
        boolean tableDown, furnaceDown;
        /**
         * 0.39.z77 legs (send-back 2; run BK 10:57:13: "iron_helmet 1 <- before going down: craft: 5 iron_ingot" - the armour
         * crafted up there from the ingots the strip down there makes, then "step 7 (craft iron_helmet 1) needs 5 of iron_ingot;
         * the bag will hold 0"): what the bag holds at the start of the leg {@code down} names (item -> count), kept current with
         * what the ways met before going down add and take. A need met before going down has only this ({@link #beforeDown}).
         * Null: not down.
         */
        Map<String, Integer> upBag;
        /** 0.39.z75 item 1: a need is being priced before going down ({@link #beforeDown}): no second leg to another level in it. */
        boolean hoisting;
        /** 0.39.z75 item 3 (run AE 8): bag items an outer need has counted as had - set aside while its ways are priced. */
        final Map<String, Integer> aside = new LinkedHashMap<>();
        /** 0.39.z75 item 2: the burn the last {@link #burn} planned (the smelt step's counted fuel); null: none named. */
        Burn burned;
        /**
         * 0.39.z77 pending (run BV 13:59:36): every way dropped with no final answer yet ("label: reason", {@link #waitsHere}),
         * in the order dropped - a losing way's too. Never restored: a playbook leg's no-way verdict reads what its gets dropped
         * waiting ({@link #legNoWay}).
         */
        final List<String> waited = new ArrayList<>();

        Ctx(World w) { this(w, null); }

        /** 0.39.z78 D6: {@code run} - the node run (its root node already open, so the bag and the flags read here are its facts). */
        Ctx(World w, PlanNodes.Run run) {
            this.run = run;
            this.w = new Facts(w, run);
            bag.rawSet(initialBag(w));
            bag.run = run;
            dropped.addAll(this.w.held());             // 0.39.z33: the sources the order dropped, with their reasons
            tableReady = this.w.tableNear();
            furnaceReady = this.w.furnaceNear();
            // 0.39.z78 D6: the bag's pickaxes by their scan (a word that is no pickaxe never raised the tier: the same pick)
            for (String k : bag.scan(PICKAXES, PICKAXES_SCAN)) {
                int t = MiningTiers.toolTier(k, "pickaxe");
                if (t > pickTier) { pickTier = t; pickItem = k; }
            }
        }

        boolean dead() { return stuck || fail != null; }

        /** aa debug: a fact line under the way being priced (nothing outside a trace). */
        void note(Supplier<String> s) { if (trace != null) trace.note(s.get()); }

        /** What the bag holds of the item for this need: less what an outer need already counted as had (0.39.z75 item 3). */
        int have(String item) { return Math.max(0, bag.getOrDefault(item, 0) - aside.getOrDefault(item, 0)); }

        /**
         * Everything a way changes, to put back when the way is dropped. 0.39.z78 D6: {@code track} - the open node's bag writes
         * then (which words it wrote and made), put back with the bag; not part of any node's key.
         */
        record Snap(Map<String, Integer> bag, List<Step> steps, Map<String, Integer> leafAt, Map<String, Integer> taken, List<String> dropped,
                    double seconds, double away, boolean tableReady, boolean furnaceReady, boolean stuck, String unknown, String pickItem, int pickTier,
                    boolean tableWalked, boolean furnaceWalked, int pickWorn, boolean pickMade, boolean pickOut, List<String> spares, boolean atRoom,
                    String down, int downAt, boolean tableDown, boolean furnaceDown, Map<String, Integer> upBag, Object track) {}

        /** 0.39.z78 D6: the open node's bag writes as a snapshot keeps them ({@link Snap#track}). */
        private record Track(PlanNodes.Frame frame, Set<String> written, LinkedHashSet<String> created, boolean awayWritten) {}

        private Object track() {
            if (run == null || run.top == null) return null;
            return new Track(run.top, new java.util.HashSet<>(run.top.written), new LinkedHashSet<>(run.top.created), run.top.awayWritten);
        }

        private void untrack(Object t) {
            if (run == null || run.top == null) return;
            if (t instanceof Track k && k.frame() == run.top) {
                run.top.written = new java.util.HashSet<>(k.written());
                run.top.created = new LinkedHashSet<>(k.created());
                run.top.awayWritten = k.awayWritten();      // 0.39.z78 D6 (option C): the walk back as it was set then
            } else if (run.top != null) { run.top.keep = false; run.top.sound = false; }   // another node's snapshot: never expected; nothing kept
        }

        /**
         * 0.39.z65 item 2 (run V 07:13:11: one stone pickaxe, no spare, and the reason only in aa debug): the spare-tool
         * decisions of the ways this chain kept ({@link #spareTools}), one line each; restored with a dropped way.
         */
        List<String> spares = new ArrayList<>();

        /** 0.39.z65: one spare decision (kept in the chain, and the aa debug note as before). */
        void spare(String line) {
            spares.add(line);
            note(() -> line);
        }

        Snap snap() {
            return new Snap(bag.rawCopy(), new ArrayList<>(steps), new LinkedHashMap<>(leafAt), new LinkedHashMap<>(taken), new ArrayList<>(dropped),
                    seconds, away, tableReady, furnaceReady, stuck, unknown, pickItem, pickTier, tableWalked, furnaceWalked, pickWorn, pickMade, pickOut, new ArrayList<>(spares), atRoom,
                    down, downAt, tableDown, furnaceDown, upBag == null ? null : new LinkedHashMap<>(upBag), track());
        }

        void restore(Snap s) {
            bag.rawSet(s.bag());
            untrack(s.track());
            steps.clear(); steps.addAll(s.steps());
            leafAt.clear(); leafAt.putAll(s.leafAt());
            taken.clear(); taken.putAll(s.taken());
            dropped = new ArrayList<>(s.dropped());
            seconds = s.seconds(); away = s.away(); tableReady = s.tableReady(); furnaceReady = s.furnaceReady(); stuck = s.stuck();
            unknown = s.unknown(); pickItem = s.pickItem(); pickTier = s.pickTier();
            tableWalked = s.tableWalked(); furnaceWalked = s.furnaceWalked();
            pickWorn = s.pickWorn(); pickMade = s.pickMade(); pickOut = s.pickOut();
            spares = new ArrayList<>(s.spares());
            atRoom = s.atRoom();
            down = s.down(); downAt = s.downAt(); tableDown = s.tableDown(); furnaceDown = s.furnaceDown();
            upBag = s.upBag() == null ? null : new LinkedHashMap<>(s.upBag());
            fail = null; loopOn = null;
        }
    }

    // ---- 0.39.z78 D6: the planner's nodes keep their sums (PlanNodes; docs/designs/D6-planner-incremental.md) ----

    /**
     * 0.39.z78 D6: the simulated bag of one resolve - every read and write told to the open node (a word read before the
     * node wrote it is its fact; a scan by a predicate keeps the matching words; any other walk over the bag is a read of
     * the whole bag). With no run (the old resolve) a plain map. The {@code raw} methods are the planner's own bookkeeping
     * (a snapshot, a restore), never a read.
     */
    static final class TrackedBag extends LinkedHashMap<String, Integer> {
        PlanNodes.Run run;

        private PlanNodes.Frame top() { return run == null ? null : run.top; }

        Integer raw(String k) { return super.get(k); }

        LinkedHashMap<String, Integer> rawCopy() {
            LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
            for (var e : super.entrySet()) m.put(e.getKey(), e.getValue());
            return m;
        }

        void rawSet(Map<String, Integer> m) {
            super.clear();
            for (var e : m.entrySet()) super.put(e.getKey(), e.getValue());
        }

        void rawPut(String k, Integer v) { super.put(k, v); }

        /** The words matching {@code p}, in the bag's order (nothing told). */
        List<String> rawScan(java.util.function.Predicate<String> p) {
            List<String> out = new ArrayList<>();
            for (String k : super.keySet()) if (p.test(k)) out.add(k);
            return out;
        }

        /** The words matching {@code p}, in the bag's order; the open node keeps the scan ({@code key}: one per predicate). */
        List<String> scan(java.util.function.Predicate<String> p, Object key) {
            List<String> out = rawScan(p);
            PlanNodes.Frame f = top();
            if (f != null && !f.scans.containsKey(key)) {
                List<String> w = new ArrayList<>();
                for (String k : out) if (!f.created.contains(k)) w.add(k);
                f.scans.put(key, new PlanNodes.Scan(p, w));
            }
            return out;
        }

        private void read(Object k) {
            PlanNodes.Frame f = top();
            if (f == null || !(k instanceof String s)) return;
            if (!f.written.contains(s) && !f.bagReads.containsKey(s)) f.bagReads.put(s, super.get(s));
        }

        private void wrote(String k, boolean made) {
            PlanNodes.Frame f = top();
            if (f == null) return;
            f.written.add(k);
            if (made) f.created.add(k);
        }

        private void whole() {
            PlanNodes.Frame f = top();
            if (f != null) f.whole = true;
        }

        @Override public Integer get(Object k) { read(k); return super.get(k); }

        @Override public Integer getOrDefault(Object k, Integer d) { read(k); return super.getOrDefault(k, d); }

        @Override public boolean containsKey(Object k) { read(k); return super.containsKey(k); }

        @Override public Integer merge(String k, Integer v, java.util.function.BiFunction<? super Integer, ? super Integer, ? extends Integer> fn) {
            read(k);
            boolean had = super.containsKey(k);
            Integer r = super.merge(k, v, fn);
            wrote(k, !had);
            return r;
        }

        @Override public Integer put(String k, Integer v) {
            read(k);
            boolean had = super.containsKey(k);
            Integer r = super.put(k, v);
            wrote(k, !had);
            return r;
        }

        @Override public Set<Map.Entry<String, Integer>> entrySet() { whole(); return super.entrySet(); }

        @Override public Set<String> keySet() { whole(); return super.keySet(); }

        @Override public java.util.Collection<Integer> values() { whole(); return super.values(); }

        @Override public void forEach(java.util.function.BiConsumer<? super String, ? super Integer> a) { whole(); super.forEach(a); }

        /** Never used by the planner: the open node is not kept (its bag change is not traced word by word). */
        @Override public Integer remove(Object k) { untraced(); return super.remove(k); }

        @Override public void putAll(Map<? extends String, ? extends Integer> m) { untraced(); super.putAll(m); }

        @Override public void clear() { untraced(); super.clear(); }      // (rawSet is the planner's restore)

        @Override public Integer putIfAbsent(String k, Integer v) { untraced(); return super.putIfAbsent(k, v); }

        private void untraced() {
            PlanNodes.Frame f = top();
            if (f != null) { f.keep = false; f.whole = true; }
        }
    }

    /**
     * 0.39.z78 D6: the bag a resolve starts from - the World's words held (a count above 0), in its order (as Ctx always
     * copied it). On the worker: the snapshot's copy of the live World's, in the live order ({@link #offThread}; a World
     * made again orders its bag anew - Map.copyOf - so the snapshot carries the order the live World has).
     */
    static LinkedHashMap<String, Integer> initialBag(World w) {
        LinkedHashMap<String, Integer> o = BAG.get();
        if (o != null) return new LinkedHashMap<>(o);
        return liveBag(w);
    }

    /** 0.39.z78 D6: {@link #initialBag} of {@code w} itself (the client tick's: what a snapshot copies and a result is checked against). */
    public static LinkedHashMap<String, Integer> liveBag(World w) {
        LinkedHashMap<String, Integer> b = new LinkedHashMap<>();
        for (var e : w.bag().entrySet()) if (e.getValue() > 0) b.merge(e.getKey(), e.getValue(), Integer::sum);
        return b;
    }

    /** 0.39.z78 D6: the bag scans (one predicate each): the pickaxes, the fuels, the hits stronger than the bare hand. */
    static final java.util.function.Predicate<String> PICKAXES = k -> MiningTiers.toolTier(k, "pickaxe") > 0;
    static final String PICKAXES_SCAN = "pickaxes";
    static final String FUELS_SCAN = "fuels";
    static final java.util.function.Predicate<String> HITS = k -> !(ItemWorth.facts(k).attack() <= HAND_DAMAGE);
    static final String HITS_SCAN = "hits";

    /** 0.39.z78 D6: the item burns in a furnace (the fuel table, with or without its namespace: CraftPlanner's own test). */
    static boolean burns(String id) { return io.github.mojolowjo.entropybot.craft.CraftPlanner.burnTicks(ItemWorth.fuels(), id) > 0; }

    /**
     * 0.39.z78 D6: the two facts the planner read outside the World before D6 - the strip areas' faces and ores
     * (StripAreas) and the owner's main stations (Nearby / MainStations). Live: those classes; the worker's snapshot: copies.
     */
    public interface Extras {
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield along(int x, int y, int z, String heading, java.util.Collection<String> ores);

        int[] freshStart(int x, int y, int z, String heading);

        boolean isMain(Spot sp);

        Extras LIVE = new Extras() {
            @Override public io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield along(int x, int y, int z, String heading, java.util.Collection<String> ores) {
                return io.github.mojolowjo.entropybot.aa.brain.StripAreas.along(x, y, z, heading, ores);
            }

            @Override public int[] freshStart(int x, int y, int z, String heading) { return io.github.mojolowjo.entropybot.aa.brain.StripAreas.freshStart(x, y, z, heading); }

            @Override public boolean isMain(Spot sp) { return Nearby.isMain(null, sp, null); }
        };
    }

    private static final ThreadLocal<Extras> EXTRAS = new ThreadLocal<>();
    private static final ThreadLocal<LinkedHashMap<String, Integer>> BAG = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DEFER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static Extras extras() { Extras x = EXTRAS.get(); return x == null ? Extras.LIVE : x; }

    /**
     * 0.39.z78 D6 (the planner worker, off the render thread): {@code run} with {@code extras} as the facts read outside the
     * World and the effects kept, not run - {@link #deferred} hands them over for the client tick's apply.
     */
    public static <T> T offThread(Extras extras, LinkedHashMap<String, Integer> bag, Supplier<T> run) {
        Extras was = EXTRAS.get();
        LinkedHashMap<String, Integer> wasBag = BAG.get();
        boolean d = DEFER.get();
        EXTRAS.set(extras);
        BAG.set(bag);
        DEFER.set(Boolean.TRUE);
        try {
            return run.get();
        } finally {
            if (was == null) EXTRAS.remove(); else EXTRAS.set(was);
            if (wasBag == null) BAG.remove(); else BAG.set(wasBag);
            DEFER.set(d);
        }
    }

    /** 0.39.z78 D6: a fact's answer on {@code w} now (the snapshot's copy, the check of a result; {@code x} null: the live extras). */
    public static Object ask(World w, Extras x, PlanNodes.Fact f) { return Facts.ask(w, x == null ? Extras.LIVE : x, f); }

    /** 0.39.z78 D6: the start of a playbook's reason while the planner thinks off the render thread (a stand: rule 22). */
    public static final String THINKING = "the planner";

    /** 0.39.z78 D6: the trip while its resolve runs on the worker - not priced yet, its stand says so ("thinking: N nodes dirty"). */
    public static Legs thinking(String words) {
        String why = NOT_PRICED + THINKING + WAITS_ON + "its answer (" + words + ")";
        return new Legs(new Chain(List.of(), 0, PLAYBOOK + ": " + why, false, why), Map.of(), -1);
    }

    /** 0.39.z78 D6: the chain is the planner's thinking stand ({@link #thinking}). */
    public static boolean thinking(Chain c) { return c != null && c.missing() != null && c.missing().startsWith(NOT_PRICED + THINKING + WAITS_ON); }

    /** 0.39.z78 D6: one resolve's record - every fact it read, its bag words, its kept effects, its counts. */
    public record Read(Map<PlanNodes.Fact, Object> facts, Map<String, Integer> bag, Map<Object, PlanNodes.Scan> scans, Map<String, Integer> wholeBag,
                       List<PlanNodes.Effect> effects, PlanNodes.Stats stats, PlanNodes.FactNodes nodes) {
        public static final Read NONE = new Read(Map.of(), Map.of(), Map.of(), null, List.of(), PlanNodes.Stats.NONE, PlanNodes.FactNodes.NONE);

        /** The nodes of the resolve's tree a fact that now answers otherwise ({@code live}: the answers asked now) makes dirty; the bag: its root. */
        public int dirty(Map<PlanNodes.Fact, Object> live, World w) {
            Set<PlanNodes.Fact> changed = new java.util.HashSet<>();
            for (var e : facts.entrySet()) if (live.containsKey(e.getKey()) && !Deep.equal(PlanNodes.unboxed(e.getValue()), live.get(e.getKey()))) changed.add(e.getKey());
            int n = nodes.dirty(changed);
            return n == 0 && bagChanged(w) != null ? 1 : n;
        }

        /** The first bag word that differs on {@code w}'s bag now ("bag minecraft:coal"), null: none. */
        public String bagChanged(World w) { return NeedChain.bagChanged(liveBag(w), bag, scans, wholeBag); }

        /** Runs the kept effects (the strip yield facts, the ranked-out walks) on the client tick, {@code path} the live provider. */
        public void runEffects(PathPrice path) { for (PlanNodes.Effect e : effects) e.run(path); }

        /** Every fact and bag word it read has the same answer on {@code w} now (asked once each). */
        public boolean same(World w, Extras x) { return changed(w, x) == null; }

        /** The first fact that changed on {@code w} ("seen minecraft:iron_ore"), null: none. */
        public String changed(World w, Extras x) {
            Extras xx = x == null ? Extras.LIVE : x;
            for (var e : facts.entrySet()) {
                Object now = Facts.ask(w, xx, e.getKey());
                if (!Deep.equal(PlanNodes.unbox(e.getValue()), now)) return e.getKey().field() + " " + e.getKey().key();
            }
            return NeedChain.bagChanged(liveBag(w), bag, scans, wholeBag);
        }
    }

    private static final ThreadLocal<Read> LAST = ThreadLocal.withInitial(() -> Read.NONE);

    /** 0.39.z78 D6: the calling thread's last resolve's record ({@link Read}). */
    public static Read lastRead() { return LAST.get(); }

    /** The first bag word that differs on {@code bag} from what a node read ("bag minecraft:coal"), null: none. */
    private static String bagChanged(Map<String, Integer> bag, Map<String, Integer> reads, Map<Object, PlanNodes.Scan> scans, Map<String, Integer> whole) {
        for (var e : reads.entrySet()) if (!java.util.Objects.equals(bag.get(e.getKey()), e.getValue())) return "bag " + e.getKey();
        for (var e : scans.entrySet()) {
            List<String> now = new ArrayList<>();
            for (String k : bag.keySet()) if (e.getValue().test().test(k)) now.add(k);
            if (!now.equals(e.getValue().words())) return "bag " + e.getKey();
        }
        if (whole != null && !Deep.equal(new ArrayList<>(bag.entrySet()), new ArrayList<>(whole.entrySet()))) return "bag (all of it)";
        return null;
    }

    /** 0.39.z78 D6: a walk's ask as a fact's key (x y z, the straight metres the chain gave, the feet in the cell or by it). */
    public record PathKey(int x, int y, int z, double dist, boolean into) {}

    /**
     * 0.39.z78 D6: the World one resolve reads, each answer told to the open node ({@link PlanNodes.Run#read}) - the
     * snapshot's contract: whatever the planner reads goes through here (the World's functions, its plain facts, and the two
     * facts it read outside the World, {@link Extras}). With no run: the World's own functions, as before D6.
     */
    static final class Facts {
        private final World w;
        private final PlanNodes.Run run;
        private final Extras extras;
        private final Function<String, List<Spot>> seenAll;
        private final Function<String, List<Mob>> mobs;
        private final Function<String, int[]> band;
        private final Function<String, Spot> stationAt;
        private final Function<String, Integer> usesLeft;
        private final Function<String, StripBand> strip;
        private final Function<String, CaveWay> cave;
        private final PathPrice path;
        private final WhereFacts where;

        Facts(World w, PlanNodes.Run run) {
            this.w = w;
            this.run = run;
            this.extras = extras();
            if (run == null) {
                seenAll = w.seenAll(); mobs = w.mobs(); band = w.band(); stationAt = w.stationAt(); usesLeft = w.usesLeft();
                strip = w.strip(); cave = w.cave(); path = w.path(); where = w.where();
                return;
            }
            seenAll = k -> rec("seen", k, w.seenAll().apply(k));
            mobs = k -> rec("mobs", k, w.mobs().apply(k));
            band = k -> rec("band", k, w.band().apply(k));
            stationAt = k -> rec("station", k, w.stationAt().apply(k));
            usesLeft = k -> rec("uses", k, w.usesLeft().apply(k));
            strip = k -> rec("strip", k, w.strip().apply(k));
            cave = k -> rec("cave", k, w.cave().apply(k));
            path = w.path() == null ? null : new Recorded(w.path());
            WhereFacts wf = w.where();
            where = new WhereFacts(k -> rec("where", k, wf.of().apply(k)), () -> rec("surface", null, wf.surface().get()),
                    () -> rec("feet", null, wf.feet().get()), () -> rec("base", null, wf.base().get()));
        }

        private <T> T rec(String field, Object key, T v) {
            if (run != null) run.read(new PlanNodes.Fact(field, key), v);
            return v;
        }

        /** The World itself (nothing told): an effect's own provider. */
        World raw() { return w; }

        Function<String, List<Spot>> seenAll() { return seenAll; }
        Function<String, List<Mob>> mobs() { return mobs; }
        Function<String, int[]> band() { return band; }
        Function<String, Spot> stationAt() { return stationAt; }
        Function<String, Integer> usesLeft() { return usesLeft; }
        Function<String, StripBand> strip() { return strip; }
        Function<String, CaveWay> cave() { return cave; }
        WhereFacts where() { return where; }

        /** The loot data: read-only data, the cache's epoch (a new or newly read book forgets every node). */
        LootBook loot() { return w.loot(); }

        PathPrice path() { return rec("path provider", null, w.path() != null) ? path : null; }
        double horizonBlocks() { return rec("horizon", null, w.horizonBlocks()); }
        List<String> held() { return rec("held", null, w.held()); }
        Rooms rooms() { return rec("rooms", null, w.rooms()); }
        boolean tableNear() { return rec("tableNear", null, w.tableNear()); }
        boolean furnaceNear() { return rec("furnaceNear", null, w.furnaceNear()); }

        io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield along(int x, int y, int z, String heading, java.util.Collection<String> ores) {
            return rec("areas along", List.of(x, y, z, heading, List.copyOf(ores)), extras.along(x, y, z, heading, ores));
        }

        int[] freshStart(int x, int y, int z, String heading) { return rec("fresh start", List.of(x, y, z, heading), extras.freshStart(x, y, z, heading)); }

        boolean isMain(Spot sp) { return rec("main", sp, sp != null && extras.isMain(sp)); }

        /** A fact's answer on {@code w} now (the check of a kept node; nothing told). */
        @SuppressWarnings("unchecked")
        static Object ask(World w, Extras x, PlanNodes.Fact f) {
            Object k = f.key();
            PathPrice p = w.path();
            return switch (f.field()) {
                case "seen" -> w.seenAll().apply((String) k);
                case "mobs" -> w.mobs().apply((String) k);
                case "band" -> w.band().apply((String) k);
                case "station" -> w.stationAt().apply((String) k);
                case "uses" -> w.usesLeft().apply((String) k);
                case "strip" -> w.strip().apply((String) k);
                case "cave" -> w.cave().apply((String) k);
                case "where" -> w.where().of().apply((String) k);
                case "surface" -> w.where().surface().get();
                case "feet" -> w.where().feet().get();
                case "base" -> w.where().base().get();
                case "path provider" -> p != null;
                case "horizon" -> w.horizonBlocks();
                case "held" -> w.held();
                case "rooms" -> w.rooms();
                case "tableNear" -> w.tableNear();
                case "furnaceNear" -> w.furnaceNear();
                case "price" -> {
                    PathKey q = (PathKey) k;
                    yield p == null ? null : q.into() ? p.priceInto(q.x(), q.y(), q.z(), q.dist()) : p.price(q.x(), q.y(), q.z(), q.dist());
                }
                case "estimate" -> { List<Integer> q = (List<Integer>) k; yield p == null ? null : p.estimate(q.get(0), q.get(1), q.get(2)); }
                case "toBlock" -> { List<Integer> q = (List<Integer>) k; yield p == null ? null : p.toBlock(q.get(0), q.get(1), q.get(2)); }
                case "pending" -> p != null && p.pending();
                case "landed" -> p == null ? 0L : p.landed();
                case "first answers pending" -> p == null ? -1 : p.firstAnswersPending();
                case "areas along" -> {
                    List<Object> q = (List<Object>) k;
                    yield x.along((Integer) q.get(0), (Integer) q.get(1), (Integer) q.get(2), (String) q.get(3), (List<String>) q.get(4));
                }
                case "fresh start" -> { List<Object> q = (List<Object>) k; yield x.freshStart((Integer) q.get(0), (Integer) q.get(1), (Integer) q.get(2), (String) q.get(3)); }
                case "main" -> k != null && x.isMain((Spot) k);
                default -> throw new IllegalStateException("D6: no such planner fact: " + f.field());
            };
        }

        /** The World's path provider, each answer told to the open node (an effect: the ranked-out walks). */
        private final class Recorded implements PathPrice {
            private final PathPrice p;

            Recorded(PathPrice p) { this.p = p; }

            @Override public Answer price(int x, int y, int z, double dist) { return rec("price", new PathKey(x, y, z, dist, false), p.price(x, y, z, dist)); }

            @Override public Answer priceInto(int x, int y, int z, double dist) { return rec("price", new PathKey(x, y, z, dist, true), p.priceInto(x, y, z, dist)); }

            @Override public double estimate(int x, int y, int z) { return rec("estimate", List.of(x, y, z), p.estimate(x, y, z)); }

            @Override public Walk toBlock(int x, int y, int z) { return rec("toBlock", List.of(x, y, z), p.toBlock(x, y, z)); }

            @Override public void rankedOut(int x, int y, int z) { p.rankedOut(x, y, z); }

            @Override public boolean pending() { return rec("pending", null, p.pending()); }

            @Override public long landed() { return rec("landed", null, p.landed()); }

            @Override public int firstAnswersPending() { return rec("first answers pending", null, p.firstAnswersPending()); }
        }
    }

    // the effects (writes outside the planner's state)

    /** 0.39.z78 D6: StripYield.priced as an effect. */
    private record YieldPriced(String key, String family, double perSlab, double cellS) implements PlanNodes.Effect {
        @Override public void run(PathPrice p) { io.github.mojolowjo.entropybot.aa.brain.StripYield.priced(key, family, perSlab, cellS); }
    }

    /** 0.39.z78 D6: StripYield.other as an effect. */
    private record YieldOther(String key, String family, double seconds, String words, boolean strip) implements PlanNodes.Effect {
        @Override public void run(PathPrice p) { io.github.mojolowjo.entropybot.aa.brain.StripYield.other(key, family, seconds, words, strip); }
    }

    /** 0.39.z78 D6: StripOrder.predicted as an effect. */
    private record Predicted(String key, String family, double perCell) implements PlanNodes.Effect {
        @Override public void run(PathPrice p) { io.github.mojolowjo.entropybot.aa.brain.StripOrder.predicted(key, family, perCell); }
    }

    /** 0.39.z78 D6: a walk ranked out of the priced ways (PathPrice#rankedOut) as an effect. */
    private record RankedOut(int x, int y, int z) implements PlanNodes.Effect {
        @Override public void run(PathPrice p) { if (p != null) p.rankedOut(x, y, z); }
    }

    /** 0.39.z78 D6 (review): the game data's epoch now on {@code w} - a worker's result priced in another one is never applied. */
    public static Object dataEpoch(World w) { return w == null ? null : epoch(w); }

    /** 0.39.z78 D6: the game data a resolve is priced from (another epoch forgets every kept node). */
    private static Object epoch(World w) {
        LootBook b = w.loot();
        return List.of(ItemWorth.version(), new Same(MiningTiers.source()), MiningTiers.ready(), io.github.mojolowjo.entropybot.aa.brain.Tuning.version(),
                new Same(b), b != null && b.ready());
    }

    /** An object compared by identity (the data sources have no value of their own). */
    private static final class Same {
        final Object o;
        Same(Object o) { this.o = o; }
        @Override public boolean equals(Object x) { return x instanceof Same s && s.o == o; }
        @Override public int hashCode() { return System.identityHashCode(o); }
    }

    /** structural: the seconds' grid, 2^20 a second (about a microsecond; sums on it are exact below 2^33 s) */
    private static final double GRID = 1048576.0;

    /**
     * 0.39.z78 D6 (run DR round, option C: nodes keep their sums): {@code s} on the seconds' grid - every add to the chain's
     * seconds goes on it, so a node's own seconds (after minus entry) do not depend on what came before it; not finite: as is.
     */
    static double grid(double s) { return Double.isFinite(s) ? Math.rint(s * GRID) / GRID : s; }

    /** 0.39.z78 D6 (option C): the reads of the dropped lines ({@link Ctx#droppedHas}): a line starting with, one that lost on seconds, the line itself. */
    private static final String DROP_HEAD = "head ", DROP_DEARER = "dearer ", DROP_EXACT = "exact ";

    private static java.util.function.Predicate<String> dropTest(String dep) {
        if (dep.startsWith(DROP_HEAD)) { String h = dep.substring(DROP_HEAD.length()); return d -> d.startsWith(h); }
        if (dep.startsWith(DROP_DEARER)) { String h = dep.substring(DROP_DEARER.length()); return d -> d.startsWith(h) && d.contains("dearer than"); }
        if (dep.startsWith(DROP_EXACT)) { String x = dep.substring(DROP_EXACT.length()); return d -> d.equals(x); }
        throw new IllegalStateException("D6: no such dropped read: " + dep);
    }

    /** 0.39.z78 D6 (option C): a node's dropped reads hold on {@code lines} (the lines before it now). */
    private static boolean dropsHold(Map<String, Boolean> reads, List<String> lines) {
        for (var e : reads.entrySet()) {
            var t = dropTest(e.getKey());
            boolean has = false;
            for (String d : lines) if (t.test(d)) { has = true; break; }
            if (has != e.getValue()) return false;
        }
        return true;
    }

    /** 0.39.z78 D6 (option C): the place-near test reads the steps before it ({@link Ctx#prefixRead}); always true. */
    private static boolean prefixRead(Ctx c) {
        c.prefixRead();
        return true;
    }

    /** 0.39.z78 D6: a node's key state - everything of the Ctx a node's code reads but the bag and the walk memo (those by word). */
    /*
     * 0.39.z78 D6 (run DR round, option C - manager 2026-10-27: "nodes keep their sums"; run DQ: one walk re-priced re-resolved
     * 78 of 79 nodes): the key holds no seconds, no dropped lines and no spare lines from before the node (its result keeps its
     * own: Out), the steps before it only as their number - unless it read or changed them ({@code steps} then the list:
     * Node#fullSteps) - and the walk back ({@code away}) only when it read it before setting it (else null).
     */
    private record Entry(Object steps, Map<String, Integer> leafAt, Map<String, Integer> taken, Double away,
                         boolean tableReady, boolean furnaceReady, boolean stuck, String unknown, String pickItem, int pickTier, boolean tableWalked,
                         boolean furnaceWalked, int pickWorn, boolean pickMade, boolean pickOut, boolean atRoom, String down, int downAt,
                         boolean tableDown, boolean furnaceDown, Map<String, Integer> upBag, List<String> path, int pathsWaiting, Map<String, Integer> aside,
                         boolean hoisting, boolean bandPick, Burn burned, String fail, String loopOn, Set<String> keptFuel) {}

    /** A cheap hash of an entry state (its sizes and scalars; equal entries hash equal, a clash is settled by Deep.equal). */
    private static int hashOf(Entry e) {
        int steps = e.steps() instanceof List<?> l ? l.size() : (Integer) e.steps();
        int h = java.util.Objects.hash(steps, e.pickItem(), e.pickTier(), e.pickWorn(),
                e.down(), e.downAt(), e.path(), e.pathsWaiting(), e.fail(), e.loopOn(), e.unknown(), e.leafAt().size(), e.taken().size());
        int flags = (e.tableReady() ? 1 : 0) | (e.furnaceReady() ? 2 : 0) | (e.stuck() ? 4 : 0) | (e.tableWalked() ? 8 : 0) | (e.furnaceWalked() ? 16 : 0)    // structural: bits
                | (e.pickMade() ? 32 : 0) | (e.pickOut() ? 64 : 0) | (e.atRoom() ? 128 : 0) | (e.tableDown() ? 256 : 0) | (e.furnaceDown() ? 512 : 0)       // structural: bits
                | (e.hoisting() ? 1024 : 0) | (e.bandPick() ? 2048 : 0);                                                                                    // structural: bits
        return 31 * h + flags;                                                                                                                              // structural: a hash's multiplier
    }

    /**
     * 0.39.z78 D6 (option C): one entry state (the snapshot {@code s} and the Ctx's other key fields, read when the node is
     * entered) in the four forms a node can be keyed by ({@code fullSteps}: the steps' list, else their number; {@code
     * awayRead}: the walk back), each built once.
     */
    private static final class Entries {
        private final Ctx.Snap s;
        private final List<String> path;
        private final int pathsWaiting;
        private final Map<String, Integer> aside;
        private final boolean hoisting, bandPick;
        private final Burn burned;
        private final String fail, loopOn;
        private final Set<String> kept;
        private final Entry[] e = new Entry[4];          // structural: two flags, four forms

        Entries(Ctx c, Ctx.Snap s) {
            this.s = s;
            path = List.copyOf(c.path);
            pathsWaiting = c.pathsWaiting;
            aside = new LinkedHashMap<>(c.aside);
            hoisting = c.hoisting;
            bandPick = c.bandPick;
            burned = c.burned;
            fail = c.fail;
            loopOn = c.loopOn;
            Set<String> k = KEPT_FUEL.get();
            kept = k == null ? Set.of() : new TreeSet<>(k);
        }

        Entry of(boolean fullSteps, boolean awayRead) {
            int i = (fullSteps ? 2 : 0) + (awayRead ? 1 : 0);
            if (e[i] == null) e[i] = new Entry(fullSteps ? s.steps() : (Object) s.steps().size(), s.leafAt(), s.taken(), awayRead ? s.away() : null,
                    s.tableReady(), s.furnaceReady(), s.stuck(), s.unknown(), s.pickItem(), s.pickTier(), s.tableWalked(), s.furnaceWalked(), s.pickWorn(),
                    s.pickMade(), s.pickOut(), s.atRoom(), s.down(), s.downAt(), s.tableDown(), s.furnaceDown(), s.upBag(), path, pathsWaiting, aside,
                    hoisting, bandPick, burned, fail, loopOn, kept);
            return e[i];
        }

        Entry of(PlanNodes.Node n) { return of(n.fullSteps, n.awayRead); }
    }

    /** 0.39.z78 D6 (option C): {@code n} stands at this entry - its form's key, and its dropped reads hold on the lines now. */
    private static boolean sameEntry(Ctx c, PlanNodes.Node n, Entries es) {
        Entry e = es.of(n);
        return n.entryHash == hashOf(e) && Deep.equal(n.entry, e) && dropsHold(n.dropReads, c.dropped);
    }

    /** 0.39.z78 D6: what a need had read when its ways were made (a way's closure was built from it): facts, bag, scans, walk memo. */
    private record Prefix(List<Map.Entry<PlanNodes.Fact, Object>> facts, List<Map.Entry<String, Integer>> bag, List<Map.Entry<Object, List<String>>> scans,
                          List<Map.Entry<String, Object>> walks, List<Object> made) {}

    private static Prefix prefixOf(PlanNodes.Frame f) {
        List<Map.Entry<PlanNodes.Fact, Object>> fs = new ArrayList<>(f.reads.size());
        for (var e : f.reads.entrySet()) fs.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        List<Map.Entry<String, Integer>> bs = new ArrayList<>(f.bagReads.size());
        for (var e : f.bagReads.entrySet()) bs.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        List<Map.Entry<Object, List<String>>> ss = new ArrayList<>(f.scans.size());
        for (var e : f.scans.entrySet()) ss.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), List.copyOf(e.getValue().words())));
        List<Map.Entry<String, Object>> ws = new ArrayList<>(f.walkReads.size());
        for (var e : f.walkReads.entrySet()) ws.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        List<Object> made = new ArrayList<>();                 // what its children so far gave (a way's closure may hold it)
        for (var e : f.children.entrySet()) for (PlanNodes.Node n : e.getValue()) made.add(List.of(e.getKey(), n.out instanceof Out o ? PlanNodes.box(o.value()) : PlanNodes.NULL));
        return new Prefix(fs, bs, ss, ws, made);
    }

    /**
     * 0.39.z78 D6: what a node left. {@code after}: the Ctx's state then (its bag and steps are not used: {@code bagOut}
     * holds the words it wrote, the old ones first then the new ones in the bag's order from {@code made}; {@code stepMap}
     * says per step after it: its entry index (>= 0) or -(k+1), the k-th of {@code newSteps}); the walk memo, the
     * unreachable facts, the waits, the uses and add marks it wrote; the trials it priced; a way's own fields after it.
     */
    private record Out(Object value, Ctx.Snap after, String fail, String loopOn, int pathsWaiting, Burn burned, Map<String, Integer> aside,
                       boolean hoisting, boolean bandPick, List<Map.Entry<String, Integer>> bagOut, int made, Map<String, Walked> walkOut,
                       List<Object[]> unreach, List<String> waited, int trialsOff, int[] stepMap, List<Step> newSteps,
                       Map<Integer, Map<String, Integer>> entryUses, Map<Integer, Map<String, Integer>> newUses, Map<Integer, Object> newMarks,
                       String altId, Burn altBurned, int rankDelta, double ownS, List<String> droppedAdd, List<String> sparesAdd, boolean awayWritten) {}

    /*
     * 0.39.z78 D6 (option C): {@code ownS} - its own seconds (after minus entry, on the grid); {@code droppedAdd} /
     * {@code sparesAdd} - the dropped and spare lines it added (the ones before it are not its); {@code awayWritten} - it set
     * the walk back ({@code after}'s then counts); {@code newMarks}' seconds are its own too (from its entry).
     */
    private static Out out(Ctx c, PlanNodes.Frame f, Ctx.Snap entry, Object value, Alt alt, int rankAt) {
        Ctx.Snap after = new Ctx.Snap(null, null, new LinkedHashMap<>(c.leafAt), new LinkedHashMap<>(c.taken), List.of(), c.seconds, c.away,
                c.tableReady, c.furnaceReady, c.stuck, c.unknown, c.pickItem, c.pickTier, c.tableWalked, c.furnaceWalked, c.pickWorn, c.pickMade, c.pickOut,
                List.of(), c.atRoom, c.down, c.downAt, c.tableDown, c.furnaceDown, c.upBag == null ? null : new LinkedHashMap<>(c.upBag), null);
        Map<Step, Integer> at = new java.util.IdentityHashMap<>();
        for (int j = 0; j < entry.steps().size(); j++) at.putIfAbsent(entry.steps().get(j), j);
        int[] map = new int[c.steps.size()];
        List<Step> news = new ArrayList<>();
        Map<Step, Integer> newAt = new java.util.IdentityHashMap<>();
        for (int i = 0; i < c.steps.size(); i++) {
            Step s = c.steps.get(i);
            Integer j = at.get(s);
            if (j != null) { map[i] = j; continue; }
            Integer k = newAt.get(s);
            if (k == null) { k = news.size(); news.add(s); newAt.put(s, k); }
            map[i] = -(k + 1);
        }
        Map<Integer, Map<String, Integer>> entryUses = new LinkedHashMap<>(), newUses = new LinkedHashMap<>();
        for (Object o : f.uses.keySet()) {
            Step s = (Step) o;
            Integer j = at.get(s);
            if (j != null) entryUses.put(j, c.uses.get(s));
            else { Integer k = newAt.get(s); if (k != null) newUses.put(k, c.uses.get(s)); }
        }
        Map<Integer, Object> marks = new LinkedHashMap<>();
        for (int k = 0; k < news.size(); k++) {
            Step y = news.get(k);
            Integer src = null;
            while ((y = c.markFrom.get(y)) != null) { Integer j = at.get(y); if (j != null) { src = j; break; } }
            Double m = c.addedAt.get(news.get(k));
            marks.put(k, src != null ? (Object) src : m == null ? null : (Object) (m - entry.seconds()));     // option C: its own seconds
        }
        List<Map.Entry<String, Integer>> bagOut = new ArrayList<>();
        for (String k : f.written) if (!f.created.contains(k)) bagOut.add(new java.util.AbstractMap.SimpleImmutableEntry<>(k, c.bag.raw(k)));
        int made = bagOut.size();
        for (String k : c.bag.rawScan(f.created::contains)) bagOut.add(new java.util.AbstractMap.SimpleImmutableEntry<>(k, c.bag.raw(k)));
        Map<String, Walked> walkOut = new LinkedHashMap<>();
        for (String k : f.walkWrites) walkOut.put(k, c.walks.get(k));
        return new Out(value, after, c.fail, c.loopOn, c.pathsWaiting, c.burned, new LinkedHashMap<>(c.aside), c.hoisting, c.bandPick, bagOut, made, walkOut,
                List.copyOf(f.unreach), List.copyOf(c.waited.subList(f.waitedAt, c.waited.size())), c.trials - f.entryTrials, map, news, entryUses, newUses, marks,
                alt == null ? null : alt.id, alt == null ? null : alt.burned, alt == null || alt.rk == null ? 0 : alt.rk.asked - rankAt,
                c.seconds - entry.seconds(), List.copyOf(c.dropped.subList(Math.min(entry.dropped().size(), c.dropped.size()), c.dropped.size())),
                List.copyOf(c.spares.subList(Math.min(entry.spares().size(), c.spares.size()), c.spares.size())), f.awayWritten);
    }

    /** 0.39.z78 D6 (option C): {@code now} still starts with {@code before} (a node only adds lines after the ones it entered with). */
    private static boolean startsWith(List<String> now, List<String> before) {
        return now.size() >= before.size() && now.subList(0, before.size()).equals(before);
    }

    /** 0.39.z78 D6 (option C): the step map keeps the {@code n} entry steps in order and adds only its own after them. */
    private static boolean keepsPrefix(int[] map, int n) {
        if (map.length < n) return false;
        for (int i = 0; i < map.length; i++) if (i < n ? map[i] != i : map[i] >= 0) return false;
        return true;
    }

    /** 0.39.z78 D6 (option C): a line of {@code lines[from, to)} matches the dropped read {@code dep}. */
    private static boolean ownHas(List<String> lines, int from, int to, String dep) {
        var t = dropTest(dep);
        for (int i = Math.max(0, from); i < Math.min(to, lines.size()); i++) if (t.test(lines.get(i))) return true;
        return false;
    }

    /**
     * 0.39.z78 D6 (option C): a node's reads that are its parent's too - the walk back read before the parent set it, the
     * dropped lines read before the parent's own lines up to the node ({@code at}); its walk-back write is the parent's.
     */
    private static void sumsUp(Ctx c, PlanNodes.Frame p, boolean awayRead, boolean awayWritten, boolean prefixRead, Map<String, Boolean> dropReads, int at) {
        if (awayRead && !p.awayWritten) p.awayRead = true;
        if (awayWritten) p.awayWritten = true;
        if (prefixRead) p.prefixRead = true;
        for (var d : dropReads.entrySet()) if (!ownHas(c.dropped, p.droppedAt, at, d.getKey())) p.dropReads.putIfAbsent(d.getKey(), d.getValue());
    }

    /** 0.39.z78 D6: a kept node's facts hold here: the trial bound, the walk memo, the bag words, the World's answers. */
    private static boolean valid(Ctx c, PlanNodes.Node n) {
        if (n.maxOff >= 0 && c.trials != n.entryTrials && (n.bound || c.trials + n.maxOff > maxTrials())) return false;
        for (var e : n.walkReads.entrySet()) if (!Deep.equal(c.walks.get(e.getKey()), PlanNodes.unbox(e.getValue()))) return false;
        for (var e : n.bagReads.entrySet()) if (!java.util.Objects.equals(c.bag.raw(e.getKey()), e.getValue())) return false;
        for (var s : n.scans.values()) if (!c.bag.rawScan(s.test()).equals(s.words())) return false;
        if (n.wholeBag != null && !Deep.equal(c.bag.rawCopy(), n.wholeBag)) return false;
        return c.run.hold(n);
    }

    /** 0.39.z78 D6: a need ({@code obtain}, {@code needTag}) as a node: reused when its facts hold, else resolved by {@code body}. */
    private static Object node(Ctx c, String kind, Object key, Supplier<Object> body) {
        PlanNodes.Frame up = c.run.top;
        Ctx.Snap s = c.snap();
        Entries es = new Entries(c, s);
        Object ck = List.of(kind, key);
        PlanNodes.Node old = null;
        for (PlanNodes.Node n : up.candidates(ck)) {
            if (!sameEntry(c, n, es)) continue;
            if (valid(c, n)) return reuse(c, n, ck, s, null, body, null);
            old = n;
            c.run.dirty++;
            break;
        }
        return compute(c, kind, key, ck, es, s, old, body, null, null).out().value();
    }

    /** 0.39.z78 D6: way {@code i} of a choose ({@code ordinal}: its number in the open node) as a node, priced from {@code s0}. */
    private static String way(Ctx c, Alt a, int i, int ordinal, Ctx.Snap s0, Prefix prefix) {
        PlanNodes.Frame up = c.run.top;
        Entries es = new Entries(c, s0);
        Object ck = List.of(ordinal, i, a.label);
        Integer rank = a.rk == null ? null : a.rk.asked;
        PlanNodes.Node old = null;
        for (PlanNodes.Node n : up.candidates(ck)) {
            if (!java.util.Objects.equals(n.extra, rank) || !sameEntry(c, n, es) || !Deep.equal(n.prefix, prefix)) continue;
            if (valid(c, n)) return (String) reuse(c, n, ck, s0, a, a.run::get, prefix);
            old = n;
            c.run.dirty++;
            break;
        }
        return (String) compute(c, "way", ck, ck, es, s0, old, a.run::get, a, prefix).out().value();
    }

    private record Made(PlanNodes.Node node, Out out) {}

    /** Resolves a node by its code in a frame of its own; kept as the open node's child {@code ck} (when its reads can key it). */
    private static Made compute(Ctx c, String kind, Object key, Object ck, Entries es, Ctx.Snap s, PlanNodes.Node old, Supplier<Object> body, Alt alt, Prefix prefix) {
        PlanNodes.Node n = new PlanNodes.Node(kind, key, null, 0);
        n.resolves = old == null ? 1 : old.resolves + 1;
        n.reused = old == null ? 0 : old.reused;
        n.prefix = prefix;
        n.extra = alt == null || alt.rk == null ? null : alt.rk.asked;
        int rankAt = alt == null || alt.rk == null ? 0 : alt.rk.asked;
        PlanNodes.Frame up = c.run.top;
        PlanNodes.Frame f = c.run.open(n);
        f.old = old;
        f.entryTrials = c.trials;
        f.entryBag = s.bag();
        f.waitedAt = c.waited.size();
        f.droppedAt = c.dropped.size();
        f.sparesAt = c.spares.size();
        f.upWroteAway = up.awayWritten;
        Object v;
        try {
            v = body.get();
        } catch (RuntimeException | Error x) {
            c.run.close();
            up.keep = false;
            throw x;
        }
        Out o = out(c, f, s, v, alt, rankAt);
        c.run.close();
        // 0.39.z78 D6 (option C): its key's form, and its reads of the walk back and the dropped lines are its parent's too
        n.prefixRead = f.prefixRead;
        n.fullSteps = f.prefixRead || !keepsPrefix(o.stepMap(), s.steps().size());
        n.awayRead = f.awayRead;
        n.dropReads = f.dropReads.isEmpty() ? Map.of() : new LinkedHashMap<>(f.dropReads);
        n.entry = es.of(n);
        n.entryHash = hashOf((Entry) n.entry);
        sumsUp(c, up, f.awayRead && !f.upWroteAway, f.awayWritten, f.prefixRead, f.dropReads, f.droppedAt);
        // its own sums are its result only when its seconds are finite and it only added lines after the ones it entered with
        if (!Double.isFinite(s.seconds()) || !Double.isFinite(c.seconds) || !startsWith(c.dropped, s.dropped()) || !startsWith(c.spares, s.spares())) {
            f.keep = false;
            up.keep = false;
        }
        n.out = o;
        n.reads = new LinkedHashMap<>(f.reads);
        n.bagReads = new LinkedHashMap<>(f.bagReads);
        n.scans = new LinkedHashMap<>(f.scans);
        n.wholeBag = f.whole ? f.entryBag : null;
        n.walkReads = new LinkedHashMap<>(f.walkReads);
        n.entryTrials = f.entryTrials;
        n.maxOff = f.maxOff;
        n.bound = f.bound;
        n.effects = List.copyOf(f.effects);
        n.children = f.children;
        n.size = f.size;
        c.run.computed++;
        if (f.keep) up.child(ck, n);
        else {
            // not kept (it read what no key holds): its parent is not kept either, but its facts become the parent's own,
            // so the request's check (the root is kept) still covers every fact of its tree
            up.size += f.size;
            for (var r : PlanNodes.allReads(n).entrySet()) up.reads.putIfAbsent(r.getKey(), r.getValue());
        }
        return new Made(n, o);
    }

    /**
     * Applies a kept node: the state it left (its steps fresh copies, their uses and add marks re-keyed; the bag words,
     * walk memo, unreachable facts and waits it wrote; the trials it priced; a way's own fields), its effects run again,
     * its reads and writes the open node's. Verify mode (tests): computed again as well, and the two compared.
     */
    private static Object reuse(Ctx c, PlanNodes.Node n, Object ck, Ctx.Snap s, Alt alt, Supplier<Object> body, Prefix prefix) {
        if (PlanNodes.verifying()) return verifyReuse(c, n, ck, s, alt, body, prefix);
        Object v = apply(c, n, alt);
        n.reused++;
        c.run.reusedN++;
        c.run.top.child(ck, n);
        return v;
    }

    private static Object apply(Ctx c, PlanNodes.Node n, Alt alt) {
        Out o = (Out) n.out;
        PlanNodes.Frame f = c.run.top;
        int trials = c.trials;
        double s0 = c.seconds;                                // option C: its own seconds and marks go on from here
        int dropAt = c.dropped.size();
        List<Step> cur = new ArrayList<>(c.steps);
        Step[] fresh = new Step[o.newSteps().size()];
        for (int k = 0; k < fresh.length; k++) {
            fresh[k] = Deep.fresh(o.newSteps().get(k));
            Object m = o.newMarks().get(k);
            if (m instanceof Integer j) {
                Double mark = c.addedAt.get(cur.get(j));
                if (mark != null) c.addedAt.put(fresh[k], mark);
                c.markFrom.put(fresh[k], cur.get(j));
            } else if (m instanceof Double d) c.addedAt.put(fresh[k], s0 + d);
        }
        List<Step> fin = new ArrayList<>(o.stepMap().length);
        for (int v : o.stepMap()) fin.add(v >= 0 ? cur.get(v) : fresh[-v - 1]);
        Ctx.Snap a = o.after();
        c.seconds = s0 + o.ownS();
        c.steps.clear();
        c.steps.addAll(fin);
        c.leafAt.clear(); c.leafAt.putAll(a.leafAt());
        c.taken.clear(); c.taken.putAll(a.taken());
        c.dropped.addAll(o.droppedAdd());
        if (o.awayWritten()) c.away = a.away();
        c.tableReady = a.tableReady(); c.furnaceReady = a.furnaceReady(); c.stuck = a.stuck();
        c.unknown = a.unknown(); c.pickItem = a.pickItem(); c.pickTier = a.pickTier();
        c.tableWalked = a.tableWalked(); c.furnaceWalked = a.furnaceWalked();
        c.pickWorn = a.pickWorn(); c.pickMade = a.pickMade(); c.pickOut = a.pickOut();
        c.spares.addAll(o.sparesAdd());
        sumsUp(c, f, n.awayRead, o.awayWritten(), n.prefixRead, n.dropReads, dropAt);
        c.atRoom = a.atRoom();
        c.down = a.down(); c.downAt = a.downAt(); c.tableDown = a.tableDown(); c.furnaceDown = a.furnaceDown();
        c.upBag = a.upBag() == null ? null : new LinkedHashMap<>(a.upBag());
        c.fail = o.fail(); c.loopOn = o.loopOn(); c.pathsWaiting = o.pathsWaiting(); c.burned = o.burned();
        c.aside.clear(); c.aside.putAll(o.aside());
        c.hoisting = o.hoisting(); c.bandPick = o.bandPick();
        List<String> written = new ArrayList<>(), made = new ArrayList<>();
        for (int i = 0; i < o.bagOut().size(); i++) {
            var e = o.bagOut().get(i);
            c.bag.rawPut(e.getKey(), e.getValue());
            written.add(e.getKey());
            if (i >= o.made()) made.add(e.getKey());
        }
        for (var e : o.walkOut().entrySet()) c.walks.put(e.getKey(), e.getValue());
        for (Object[] u : o.unreach()) { c.unreachable.put((String) u[0], (String) u[1]); f.unreach.add(u); }
        c.waited.addAll(o.waited());
        c.trials = trials + o.trialsOff();
        for (var e : o.entryUses().entrySet()) { Step st = cur.get(e.getKey()); c.uses.put(st, e.getValue()); f.uses.put(st, e.getValue()); }
        for (var e : o.newUses().entrySet()) { Step st = fresh[e.getKey()]; c.uses.put(st, e.getValue()); f.uses.put(st, e.getValue()); }
        if (alt != null) {
            alt.id = o.altId();
            alt.burned = o.altBurned();
            if (alt.rk != null) alt.rk.asked += o.rankDelta();
        }
        PlanNodes.reusedInto(n, f, trials, written, made, o.walkOut().keySet());
        for (PlanNodes.Effect e : n.effects) c.run.effect(e);
        return o.value();
    }

    /** 0.39.z78 D6: a snapshot as a node keeps it (no open node's bag writes in it). */
    private static Ctx.Snap untracked(Ctx.Snap s) {
        return new Ctx.Snap(s.bag(), s.steps(), s.leafAt(), s.taken(), s.dropped(), s.seconds(), s.away(), s.tableReady(), s.furnaceReady(), s.stuck(),
                s.unknown(), s.pickItem(), s.pickTier(), s.tableWalked(), s.furnaceWalked(), s.pickWorn(), s.pickMade(), s.pickOut(), s.spares(), s.atRoom(), s.down(),
                s.downAt(), s.tableDown(), s.furnaceDown(), s.upBag(), null);
    }

    /** 0.39.z78 D6, tests: everything a node can change, by value (steps by value, their uses and marks by position). */
    private record Full(List<Step> steps, Map<String, Integer> bag, Ctx.Snap snap, List<Map<String, Integer>> uses, List<Double> marks,
                        Map<String, Walked> walks, Map<String, String> unreachable, List<String> waited, int trials, String fail, String loopOn,
                        int pathsWaiting, Burn burned, Map<String, Integer> aside, boolean hoisting, boolean bandPick, String altId, Burn altBurned,
                        Integer rank) {}

    private static Full full(Ctx c, Alt alt) {
        List<Map<String, Integer>> u = new ArrayList<>();
        List<Double> m = new ArrayList<>();
        for (Step s : c.steps) { u.add(c.uses.get(s)); m.add(c.addedAt.get(s)); }
        Ctx.Snap noTrack = untracked(c.snap());
        return new Full(new ArrayList<>(c.steps), c.bag.rawCopy(), noTrack, u, m, new LinkedHashMap<>(c.walks), new LinkedHashMap<>(c.unreachable),
                new ArrayList<>(c.waited), c.trials, c.fail, c.loopOn, c.pathsWaiting, c.burned, new LinkedHashMap<>(c.aside), c.hoisting, c.bandPick,
                alt == null ? null : alt.id, alt == null ? null : alt.burned, alt == null || alt.rk == null ? null : alt.rk.asked);
    }

    /** Tests (PlanNodes.verify): the kept node applied, the state put back, the node computed again; the two must be equal. */
    private static Object verifyReuse(Ctx c, PlanNodes.Node n, Object ck, Ctx.Snap s, Alt alt, Supplier<Object> body, Prefix prefix) {
        PlanNodes.Frame f = c.run.top;
        List<Step> steps0 = new ArrayList<>(c.steps);
        Map<Step, Double> marks0 = new java.util.IdentityHashMap<>(c.addedAt);
        Map<Step, Map<String, Integer>> uses0 = new java.util.IdentityHashMap<>(c.uses);
        Map<String, Walked> walks0 = new LinkedHashMap<>(c.walks);
        Map<String, String> unreach0 = new LinkedHashMap<>(c.unreachable);
        List<String> waited0 = new ArrayList<>(c.waited);
        Ctx.Snap snap0 = c.snap();
        int trials0 = c.trials, pw0 = c.pathsWaiting;
        String fail0 = c.fail, loop0 = c.loopOn;
        Burn burned0 = c.burned;
        Map<String, Integer> aside0 = new LinkedHashMap<>(c.aside);
        boolean hoist0 = c.hoisting, band0 = c.bandPick;
        String id0 = alt == null ? null : alt.id;
        Burn ab0 = alt == null ? null : alt.burned;
        int rank0 = alt == null || alt.rk == null ? 0 : alt.rk.asked;
        Set<String> walkW0 = new LinkedHashSet<>(f.walkWrites);
        int unreachN = f.unreach.size(), effN = f.effects.size();
        Map<Object, Object> fuses0 = new java.util.IdentityHashMap<>(f.uses);
        Object kept = apply(c, n, alt);
        Full a = full(c, alt);
        // put everything back
        c.restore(snap0);
        c.steps.clear(); c.steps.addAll(steps0);
        c.addedAt.clear(); c.addedAt.putAll(marks0);
        c.uses.clear(); c.uses.putAll(uses0);
        c.walks.clear(); c.walks.putAll(walks0);
        c.unreachable.clear(); c.unreachable.putAll(unreach0);
        c.waited.clear(); c.waited.addAll(waited0);
        c.trials = trials0; c.pathsWaiting = pw0; c.fail = fail0; c.loopOn = loop0; c.burned = burned0;
        c.aside.clear(); c.aside.putAll(aside0);
        c.hoisting = hoist0; c.bandPick = band0;
        if (alt != null) { alt.id = id0; alt.burned = ab0; if (alt.rk != null) alt.rk.asked = rank0; }
        f.walkWrites.clear(); f.walkWrites.addAll(walkW0);
        while (f.unreach.size() > unreachN) f.unreach.remove(f.unreach.size() - 1);
        while (f.effects.size() > effN) f.effects.remove(f.effects.size() - 1);
        f.uses.clear(); f.uses.putAll(fuses0);
        Made m = compute(c, n.kind, n.key, ck, new Entries(c, s), s, n, body, alt, prefix);
        m.node().resolves = n.resolves;                     // a check, not a resolve: the counts are the kept node's
        m.node().reused = n.reused + 1;
        c.run.computed--;
        c.run.reusedN++;
        Full b = full(c, alt);
        if (!Deep.equal(kept, m.out().value()) || !Deep.equal(a, b))
            throw new AssertionError("D6 verify: the kept node " + n.kind + " " + n.key + " differs from its resolve again:\n kept    " + kept + " / " + a
                    + "\n resolve " + m.out().value() + " / " + b);
        return m.out().value();
    }

    /**
     * 0.39.z78 D6: a request (a resolve entry point) as the root node: kept per request ({@code kind}, {@code args}, the trip's
     * kept fuels); reused when every fact it read (the World's answers, the bag words, the stations near, the holds) holds on
     * {@code w} - the stored result handed out as fresh copies ({@code fresh}) - else resolved by {@code body}, its kept
     * children reused where their facts hold. {@link PlanNodes#old}, a trace (aa debug), or no World: {@code body} as before D6.
     */
    @SuppressWarnings("unchecked")
    private static <T> T root(String kind, Object args, World w, Function<Ctx, T> body, java.util.function.UnaryOperator<T> fresh) {
        if (PlanNodes.isOld() || TRACE.get() != null || w == null) return body.apply(new Ctx(w));
        PlanNodes p = PlanNodes.mine();
        p.epoch(epoch(w));
        Extras x = extras();
        PlanNodes.Run run = p.new Run(f -> Facts.ask(w, x, f), w.path(), DEFER.get());
        Set<String> kept = KEPT_FUEL.get();
        Object rk = java.util.Arrays.asList(kind, args, kept == null ? Set.of() : new TreeSet<>(kept));
        PlanNodes.Node r = p.root(rk);
        LinkedHashMap<String, Integer> bag0 = initialBag(w);
        if (r != null && bagChanged(bag0, r.bagReads, r.scans, r.wholeBag) == null && run.hold(r)) {
            T out = fresh.apply((T) r.out);
            for (PlanNodes.Effect e : r.effects) run.effect(e);
            r.reused++;
            if (PlanNodes.verifying()) {
                T again = PlanNodes.old(() -> body.apply(new Ctx(w)));
                if (!Deep.equal(out, again)) throw new AssertionError("D6 verify: the kept request " + rk + " differs from its resolve again:\n kept    " + out + "\n resolve " + again);
            }
            PlanNodes.Stats st = new PlanNodes.Stats(r.size, 0, r.size, 0);
            p.last(st);
            LAST.set(new Read(PlanNodes.allReads(r), r.bagReads, r.scans, r.wholeBag, r.effects, st, run.defer ? PlanNodes.factNodes(r) : PlanNodes.FactNodes.NONE));
            return out;
        }
        PlanNodes.Node n = new PlanNodes.Node("root", rk, List.of(), 0);
        n.resolves = r == null ? 1 : r.resolves + 1;
        n.reused = r == null ? 0 : r.reused;
        PlanNodes.Frame f = run.open(n);
        f.old = r;
        f.entryBag = bag0;
        T out;
        try {
            out = body.apply(new Ctx(w, run));
        } finally {
            run.top = null;
        }
        n.out = out;
        n.reads = new LinkedHashMap<>(f.reads);
        n.bagReads = new LinkedHashMap<>(f.bagReads);
        n.scans = new LinkedHashMap<>(f.scans);
        n.wholeBag = f.whole ? bag0 : null;
        n.effects = List.copyOf(f.effects);
        n.children = f.children;
        n.size = f.size;
        if (f.sound) p.root(rk, n);                         // the root: every fact it read came from the World (its children may not be kept)
        run.computed++;
        PlanNodes.Stats st = new PlanNodes.Stats(f.size, run.computed, run.reusedN, run.dirty + (r == null ? 0 : 1));
        p.last(st);
        LAST.set(new Read(PlanNodes.allReads(n), n.bagReads, n.scans, n.wholeBag, run.defer ? List.copyOf(run.deferred) : List.of(), st,
                run.defer ? PlanNodes.factNodes(n) : PlanNodes.FactNodes.NONE));
        return out;
    }

    /** 0.39.z78 D6: a chain with fresh step objects (every resolve hands out its own, as before D6). */
    static Chain fresh(Chain ch) { return fresh(ch, new java.util.IdentityHashMap<>()); }

    private static Chain fresh(Chain ch, Map<Step, Step> map) {
        if (ch == null) return null;
        List<Step> st = new ArrayList<>(ch.steps().size());
        for (Step s : ch.steps()) st.add(map.computeIfAbsent(s, Deep::fresh));
        return new Chain(st, ch.cost(), ch.why(), ch.explore(), ch.missing(), ch.dropped(), ch.unreachable(), ch.uses(), ch.spares(), ch.stepSeconds());
    }

    /** 0.39.z78 D6: a playbook's legs with fresh step objects (the chain, the whole trip and the leg starts share them, by identity). */
    public static Legs fresh(Legs l) {
        if (l == null) return null;
        Map<Step, Step> map = new java.util.IdentityHashMap<>();
        Chain ch = fresh(l.chain(), map);
        Chain whole = l.whole() == l.chain() ? ch : fresh(l.whole(), map);
        Map<Step, String> starts = new java.util.IdentityHashMap<>();
        for (var e : l.starts().entrySet()) starts.put(map.computeIfAbsent(e.getKey(), Deep::fresh), e.getValue());
        return new Legs(ch, starts, l.failedLeg(), whole);
    }

    /** Rule 1 (have it): what the bag holds of the need, mixed members summed. */
    private static int have(Ctx c, Want w) {
        if (w.item()) return c.have(w.id());
        int n = 0;
        for (String k : c.bag.scan(w::has, w.scanKey())) {           // 0.39.z78 D6: the need's members, scanned (the same sum)
            Integer v = c.bag.get(k);
            if (v != null && v > 0) n += c.have(k);                  // z75 item 3: less what is set aside
        }
        return n;
    }

    /**
     * 0.39.z75 item 3 (run AE 17:31:10 / 17:34:53: "iron_helmet <- craft: 5 iron_ingot <- craft: 18 iron_nugget <- craft: 2
     * iron_ingot" with 3 ingots in the bag - the 2 the nuggets took were "had" from the same 3 the helmet already counted): a
     * need that counts some of the bag as had sets those aside while its ways are priced, so an inner need never counts them
     * again; the round trip then meets the loop guard. Returns what was set aside (item -> count) for {@link #putBack}.
     */
    private static Map<String, Integer> setAside(Ctx c, Want w) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : c.bag.scan(w::has, w.scanKey())) {        // 0.39.z78 D6: scanned, in the bag's order
            Integer v = c.bag.get(key);
            if (v == null || v <= 0) continue;
            int k = c.have(key);
            if (k > 0) out.put(key, k);
        }
        for (var e : out.entrySet()) c.aside.merge(e.getKey(), e.getValue(), Integer::sum);
        return out;
    }

    /** 0.39.z75 item 3: the set-aside items count for this need again (its ways are priced). */
    private static void putBack(Ctx c, Map<String, Integer> set) {
        for (var e : set.entrySet()) {
            int left = c.aside.getOrDefault(e.getKey(), 0) - e.getValue();
            if (left > 0) c.aside.put(e.getKey(), left); else c.aside.remove(e.getKey());
        }
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
        for (String k : c.bag.scan(w::has, w.scanKey())) if (!keys.contains(k)) keys.add(k);     // 0.39.z78 D6: scanned
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

    /**
     * 0.39.z77 bagat (run BY 15:27:03: "table <- craft: 4 acacia_planks ... + burn 2 oak_planks (in the bag, the cheapest burn by
     * worth)", then "step 15 (smelt iron_ingot 3 here) needs 2 of oak_planks; the bag will hold 0 after step 14", the same chain
     * again, the order parked): a recipe slot of a step that takes any member (a tag, an "a|b" word: the step's use is the slot
     * word, StepNeeds.withUses) comes off the chain's bag as the step's own check takes it - the need's token first, then the
     * bag's members as they came in (DryRun.take, the dry run every step runs before it starts) - never "what the way just
     * made" first. So the bag a later pick reads (the burn, a tool, an "in the bag" source) is the bag as it will be at its
     * step: the table's 4 #planks took the bag's 2 oak + 2 of the made acacia, and the burn after it sees 2 acacia_planks, not
     * the oak. A recipe group's own take ({@link #makeGroup}) still prefers what its way made: there the inputs name the member.
     */
    private static Map<String, Integer> takeAsStep(Ctx c, Want w, int n) { return take(c, w, n, w.word); }

    /** What a take of {@code n} from the bag would name: one kind when one kind covers it, else the need word. */
    private static String bagName(Ctx c, Want w, int n) {
        if (w.item()) return w.id();
        for (String k : c.bag.scan(w::has, w.scanKey())) if (c.have(k) > 0) return c.have(k) >= n ? k : w.word;   // 0.39.z78 D6: scanned
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
        return root("resolve", java.util.Arrays.asList(what, n), w, c -> resolveIn(c, what, n), NeedChain::fresh);    // 0.39.z78 D6: a request node
    }

    private static Chain resolveIn(Ctx c, String what, int n) {
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
        // 0.39.z78 D6: a request node (the energy is the game's data by the caller's function, its class the key)
        return root("any", java.util.Arrays.asList(label, items == null ? null : List.copyOf(items), n, energy == null ? null : energy.getClass().getName()), w,
                c -> resolveAnyIn(c, label, items, n, energy), NeedChain::fresh);
    }

    private static Chain resolveAnyIn(Ctx c, String label, List<String> items, int n, ToDoubleFunction<String> energy) {
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
        return root("all", java.util.Arrays.asList(group, items == null ? null : List.copyOf(items), n), w, c -> resolveAllIn(c, group, items, n), NeedChain::fresh);   // 0.39.z78 D6
    }

    private static Chain resolveAllIn(Ctx c, String group, List<String> items, int n) {
        List<String> phrases = new ArrayList<>(), waiting = new ArrayList<>();
        Map<String, String> unknown = new LinkedHashMap<>();
        String wait = null;          // loot-book-bg: a member that waits on the loot book
        for (String raw : items) {
            String item = id(raw);
            if (ItemWorth.recipes(item, ItemWorth.CRAFT).isEmpty() && ItemWorth.recipes(item, ItemWorth.SMELT).isEmpty()) { drop(c, item, "no recipe"); continue; }
            Ctx.Snap before = c.snap();
            String p = obtain(c, item, n);
            if (c.fail != null) {
                String why = c.fail;
                if (why.startsWith(LootBook.WAITING) && wait == null) wait = why;
                c.restore(before);
                drop(c, item, why);
                continue;
            }
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
        if (phrases.isEmpty() && wait != null) c.fail = wait;      // loot-book-bg: nothing stands yet because the book is read: the group waits
        return finish(c, group, phrases.isEmpty() ? group + ": nothing to make" : group + " <- " + String.join("; ", phrases));
    }

    /** The cheapest pickaxe of at least {@code tier} (the game's pickaxes tag) as a chain of its own: what a dig of a block that asks for that tier pulls in. */
    public static Chain resolveTool(int tier, World w) {
        return root("tool", List.of(tier), w, c -> resolveToolIn(c, tier), NeedChain::fresh);        // 0.39.z78 D6: a request node
    }

    private static Chain resolveToolIn(Ctx c, int tier) {
        String phrase = tier > c.pickTier ? cheapestPickaxe(c, tier) : null;
        return finish(c, "pickaxe", phrase == null ? "a pickaxe of tier " + tier + " is in the bag" : "pickaxe <- " + phrase);
    }

    // ---- 0.39.z77 J2: the playbook way (D5 J2; SHAPE CHANGE 2026-10-26: every plain-item order's one trip, BatchWay) ----

    /** 0.39.z77 J2: the name of a playbook chain (its why line starts with it). */
    public static final String PLAYBOOK = "playbook";

    /** 0.39.z77 price review 2: the start of the reason a playbook has a leg with no price yet (SHAPE CHANGE: that leg is priced when the bot gets there). */
    public static final String NOT_PRICED = "not priced yet: ";

    /** 0.39.z77 price review 2: a second unknown after the surface explore - the legs after it have no price, so neither has the playbook. */
    private static String secondUnknown(Ctx c) {
        return NOT_PRICED + "a second need with no known source after the surface explore (" + shw(c.unknown) + "): the legs after it are not priced (rule 15)";
    }

    /**
     * 0.39.z77 J2: the chain of a whole {@link Playbook} on ONE simulated bag and context: the SURFACE leg's gets (the gate's
     * pickaxe, the all-surface orders) with no leg to another level inside it; each DESCENT as ONE strip of every band
     * family it sums ({@link #playbookStrip}); each AT_BAND leg's gets from the bag the strip filled (the smelt for every
     * order at once, then the orders), what they need up there met before going down ({@link #beforeDown}, the z75
     * take-along). Every member must stand (rule 7): a member that fails, or explores past the surface leg, fails the
     * playbook with its reason; the surface leg ending in an explore is the row's explore (its word a surface word, E1).
     */
    public static Chain resolvePlaybook(Playbook pb, World w) { return resolvePlaybookLegs(pb, w).chain(); }

    /**
     * 0.39.z77 J2 (owner via the manager: the watcher sees each leg as it starts): the playbook's chain and, by identity, the
     * first step of each leg in the chain's order -> "playbook leg 2/3: descent to y 16 for raw_iron 27, coal 4" (said when
     * that step starts, Goals' replanner). Empty when the chain does not stand.
     * <p>0.39.z77 carry (runs CB 15:58:02, CE 16:19:53-16:20:18: the same legs re-priced said "carries ..." on one line and
     * nothing on the next): {@code whole} - the trip as priced, every leg's steps and their uses, before the run's cut at a
     * surface explore (the run ends at the explore, the price is all legs); the trip line's carry list is read from it, so a
     * re-price of the same legs with its explore carries the same. {@code chain} is what runs. No explore: the same chain.
     */
    public record Legs(Chain chain, Map<Step, String> starts, int failedLeg, Chain whole) {
        public Legs(Chain chain, Map<Step, String> starts, int failedLeg) { this(chain, starts, failedLeg, chain); }
        public Legs(Chain chain, Map<Step, String> starts) { this(chain, starts, -1); }

        /** 0.39.z77 noway: the leg (of {@code pb.legs()}) whose no-way failed the chain; null: none (it stands, or no leg's). */
        public Playbook.Leg failed(Playbook pb) {
            return chain.ok() || pb == null || failedLeg < 0 || failedLeg >= pb.legs().size() ? null : pb.legs().get(failedLeg);
        }
    }

    public static Legs resolvePlaybookLegs(Playbook pb, World w) {
        return root("playbook", java.util.Arrays.asList(pb), w, c -> playbookLegsIn(c, pb), NeedChain::fresh);      // 0.39.z78 D6: a request node
    }

    private static Legs playbookLegsIn(Ctx c, Playbook pb) {
        List<java.util.Set<Step>> added = new ArrayList<>();
        int[] failedLeg = {-1};
        List<Step> priced = new ArrayList<>();                  // 0.39.z77 carry: every leg's steps, before the explore's cut
        Chain ch = pb == null || !pb.ok() ? playbookFails(c, pb == null ? "no playbook" : pb.fail()) : playbookChain(c, pb, added, failedLeg, priced);
        if (!ch.ok() && waitsHere(ch.missing())) failedLeg[0] = -1;     // 0.39.z77 pending: a wait is no leg's no-way
        Chain whole = ch;
        if (ch.ok() && priced.size() > ch.steps().size()) {
            List<Map<String, Integer>> uses = new ArrayList<>();
            for (Step st : priced) uses.add(c.uses.get(st));
            whole = new Chain(List.copyOf(priced), ch.cost(), ch.why(), ch.explore(), null, ch.dropped(), ch.unreachable(), uses, ch.spares());
        }
        Map<Step, String> starts = new java.util.IdentityHashMap<>();
        if (ch.ok() && pb != null) {                              // SHAPE CHANGE: an explore trip says its legs too
            int prev = -1, n = Math.min(added.size(), pb.legs().size());
            for (int i = 0; i < n; i++) {
                int at = -1;
                for (int j = prev + 1; j < whole.steps().size(); j++) if (added.get(i).contains(whole.steps().get(j))) { at = j; break; }
                if (at < 0) continue;
                starts.put(whole.steps().get(at), "playbook leg " + (i + 1) + "/" + pb.legs().size() + ": " + legWords(pb.legs().get(i)));
                prev = at;
            }
        }
        return new Legs(ch, starts, ch.ok() ? -1 : failedLeg[0], whole);
    }

    /** 0.39.z77 J2: a leg as the watcher reads it ("descent to y 16 for raw_iron 27, coal 4"). */
    static String legWords(Playbook.Leg leg) {
        List<String> g = new ArrayList<>();
        for (Playbook.Target t : leg.gets()) g.add(t.text());
        return switch (leg.kind()) {
            case SURFACE -> (leg.climb() ? leg.whyY() + " (up to the surface): " : "on the surface: ") + String.join(", ", g);
            case HERE -> "here at y " + leg.y() + ": " + String.join(", ", g);
            case DESCENT -> (leg.on() ? "strip on at y " + leg.y() + " for " + String.join(", ", g) + " (with the new pickaxe)"
                    : leg.here() ? "strip here at y " + leg.y() + " for " + String.join(", ", g) + " (one strip)"
                    : "descent to y " + leg.y() + " for " + String.join(", ", g) + " (one strip)");
            case AT_BAND -> "at the band: " + String.join(", ", g);
        };
    }

    private static java.util.Set<Step> ids(List<Step> steps) {
        java.util.Set<Step> s = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        s.addAll(steps);
        return s;
    }

    private static java.util.Set<Step> addedSince(Ctx c, java.util.Set<Step> before) {
        java.util.Set<Step> s = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Step x : c.steps) if (!before.contains(x)) s.add(x);
        return s;
    }

    private static Chain playbookChain(Ctx c, Playbook pb, List<java.util.Set<Step>> added, int[] failedLeg, List<Step> priced) {
        List<String> phrases = new ArrayList<>();
        int levelY = Playbook.NO_Y;
        // 0.39.z77 freeze review (manager's ruling on run BE 08:08:15, "playbook 450.0 s (ends in an explore)" while its legs
        // listed a descent and strips: the pricing stopped at the surface leg's explore): the playbook's price is the SUM of its
        // legs - a surface get that ends in an explore is priced as that explore and taken as had after it, the later legs
        // priced on; the chain's steps end at the explore (the re-price where it finds its source picks the rest)
        int exploreEnd = -1;
        String exploreFor = null;
        // 0.39.z77 SHAPE CHANGE (owner 2026-10-26 10:30: playbook first, pricing only inside legs): the trip runs as derived;
        // a leg whose price cannot be had from here yet - a walk not known (rule 15/18), or a second unknown after the surface
        // explore - is priced when the bot gets there: the run's steps end before it ({@code later}) and the trip is priced
        // again from the bag at that boundary (never a whole-order "which planner")
        String later = null;
        // 0.39.z77 carry (runs CB 15:58:00-02, CE 16:19:53-16:20:18: a band leg's ways still computing cut the run before THAT
        // leg - the descent ran without the take-along its legs below need, priced up here and put in before it; the bot went
        // down with no furnace, table or fuel): what a descent carries is what the legs below it use, so a leg below the
        // descent that took the chain down (a strip on, a band leg) priced when the bot gets there cuts the run before that
        // descent ({@code downBefore}: the steps before it). Nothing before it: the trip stands until the answer lands (rule 22).
        java.util.Set<Step> downBefore = null;
        int legNo = 0;
        legs:
        for (Playbook.Leg leg : pb.legs()) {
            legNo++;
            failedLeg[0] = legNo - 1;            // 0.39.z77 noway: a fail returned in this leg is this leg's no-way
            java.util.Set<Step> before = ids(c.steps);
            String legName = "leg " + legNo + "/" + pb.legs().size() + " (" + leg.text() + ")";
            boolean below = downBefore != null && (leg.kind() == Playbook.Kind.DESCENT || leg.kind() == Playbook.Kind.AT_BAND);
            java.util.Set<Step> cut = below ? downBefore : before;
            if (leg.kind() == Playbook.Kind.DESCENT) {
                int w0 = c.waited.size();
                String p = playbookStrip(c, leg, levelY, exploreEnd >= 0);
                if (c.fail != null && legNo > 1 && waitsHere(c.fail)) {
                    if (!cut.isEmpty()) {
                        later = "descent to y " + leg.y() + ": " + c.fail;
                        cutTo(c, cut);
                        break;
                    }
                    if (below) return waitsBelow(c, legName, "descent to y " + leg.y() + ": " + c.fail);
                }
                if (c.fail != null) return legNoWay(c, w0, legName, "descent to y " + leg.y() + ": " + c.fail);
                if (c.stuck && exploreEnd >= 0) {                  // price review 2 / SHAPE CHANGE: priced on its find
                    later = secondUnknown(c);
                    if (below) cutTo(c, cut);
                    break;
                }
                if (c.stuck) return legNoWay(c, w0, legName, "descent to y " + leg.y() + ": its pickaxe ends in an explore (no known source for " + shw(c.unknown) + ")");
                if (p != null) phrases.add(p);
                levelY = leg.y();
                added.add(addedSince(c, before));
                if (downBefore == null && c.down != null) downBefore = before;     // this descent takes the chain down
                continue;
            }
            // 0.39.z77 legs 2: a leg HERE (under ground, the gets had where the bot stands) is priced as the surface leg is - no
            // leg to another level inside it, from the feet
            boolean surface = leg.kind() == Playbook.Kind.SURFACE || leg.kind() == Playbook.Kind.HERE;
            String name = leg.kind() == Playbook.Kind.HERE ? "here at y " + leg.y() : leg.climb() ? leg.whyY() : surface ? "surface" : "at band";
            List<String> got = new ArrayList<>();
            boolean wasHoisting = c.hoisting;
            if (surface) c.hoisting = true;                      // no leg to another level in the surface leg
            try {
                for (Playbook.Target t : leg.gets()) {
                    String item = id(t.item());
                    boolean gate = leg.gates().contains(item);
                    int w0 = c.waited.size();
                    // a gate: the pickaxe the derivation chose for its tier (its leaves on this leg's side), made unless one digs it
                    String p = gate ? (leg.tier() > c.pickTier ? obtain(c, item, c.have(item) + 1) : null) : obtain(c, item, t.n());
                    if (c.fail == null && !c.stuck) madePick(c, item);
                    if (c.fail != null && legNo > 1 && waitsHere(c.fail)) {
                        if (!cut.isEmpty()) {
                            later = name + ": " + sh(item) + ": " + c.fail;
                            cutTo(c, cut);
                            break legs;
                        }
                        if (below) return waitsBelow(c, legName, name + ": " + sh(item) + ": " + c.fail);
                    }
                    if (c.fail != null) return legNoWay(c, w0, legName, name + ": " + sh(item) + ": " + c.fail);
                    if (c.stuck) {
                        if (exploreEnd >= 0) {                       // price review 2 / SHAPE CHANGE: priced on its find
                            later = secondUnknown(c);
                            if (below) cutTo(c, cut);
                            break legs;
                        }
                        if (!surface) return legNoWay(c, w0, legName, name + ": " + sh(item) + " ends in an explore (no known source for " + shw(c.unknown) + ")");
                        got.add(sh(item) + " " + t.n() + " <- " + p + " (then priced as had)");
                        exploreEnd = c.steps.size();
                        exploreFor = c.unknown;
                        c.unknown = null;                               // a second unknown names itself (price review 2)
                        c.stuck = false;
                        int lack = (gate ? c.have(item) + 1 : t.n()) - c.have(item);
                        if (lack > 0) c.bag.merge(item, lack, Integer::sum);
                        madePick(c, item);
                        continue;
                    }
                    got.add(sh(item) + " " + t.n() + (p == null ? " (in the bag)" : " <- " + p));
                }
            } finally {
                c.hoisting = wasHoisting;
            }
            phrases.add(name + ": " + String.join("; ", got));
            added.add(addedSince(c, before));
        }
        failedLeg[0] = -1;                       // past the legs: a fail now is no one leg's
        priced.addAll(c.steps);                  // 0.39.z77 carry: the trip as priced (its line's carry lists read it)
        if (exploreEnd >= 0) {
            c.stuck = true;
            c.unknown = exploreFor;
            c.steps.subList(exploreEnd, c.steps.size()).clear();       // the run's steps end at the explore; the price is all legs
        }
        String tail = later != null ? PRICED_THERE + later + ")"
                : exploreEnd >= 0 ? ": " + s(c.seconds) + " priced with the legs after the explore" : "";
        return finish(c, PLAYBOOK, PLAYBOOK + " <- " + String.join(" > ", phrases) + tail);
    }

    /**
     * 0.39.z77 SHAPE CHANGE: a leg's failure that is a wait from here (a walk not known, chunks, the loot book or tiers not read).
     * 0.39.z77 pending: or a leg not priced yet ({@link #legNoWay}).
     */
    public static boolean waitsHere(String fail) {
        return fail != null && (fail.contains(PathPrice.NOT_KNOWN) || fail.startsWith(LootBook.WAITING) || fail.endsWith(SOURCE_NOT_LOADED) || fail.startsWith(MINING_WAIT)
                || fail.startsWith(NOT_PRICED));
    }

    /**
     * 0.39.z77 choose: a way's failure that is no answer yet - the states a chain waits in ({@link Chain#waiting}): a walk not
     * known (computing, queued, chunks not loaded), the loot book being read, a source's structure fact or the mining data not
     * loaded (rule 15). Null or any other failure: a final answer.
     */
    static boolean answerPending(String fail) {
        return fail != null && (fail.startsWith(LootBook.WAITING) || fail.startsWith(PathPrice.NOT_KNOWN) || fail.endsWith(SOURCE_NOT_LOADED) || fail.startsWith(MINING_WAIT));
    }

    /** 0.39.z77 pending: between a leg's name and what it waits on, in a not-priced-yet reason ({@link #legNoWay}). */
    public static final String WAITS_ON = " waits on ";

    /**
     * 0.39.z77 pending (run BV 13:59:36: "leg 4/4 (at band [...]) has no way" while its cheaper fuel and stick ways were "walk:
     * path not known yet (computing; queued ...)" - the fuel fell to lava_bucket, an explore, no way under ground; rule 15:
     * an unknown read as a verdict): a playbook leg's no-way, final only when every way its get or strip tried has a final
     * answer (priced, UNREACHABLE, held, dropped with a reason). A way dropped with no answer yet since {@code w0} (computing,
     * queued, chunks or data not loaded: {@link #waitsHere}) makes the leg "not priced yet" - a wait: the trip stands
     * (BatchWay.shape, rule 22), says what it waits for, parks nobody, and is priced again when an answer lands. A verdict
     * that is itself a wait stays as it is. Every leg's no-way goes through here (Z77NoWayVerdictScanTest).
     */
    private static Chain legNoWay(Ctx c, int w0, String leg, String verdict) {
        if (waitsHere(c.fail) || c.waited.size() <= w0) return playbookFails(c, verdict);
        Set<String> w = new LinkedHashSet<>(c.waited.subList(w0, c.waited.size()));
        String first = w.iterator().next();
        return playbookFails(c, NOT_PRICED + leg + WAITS_ON + w.size() + " of its ways with no answer yet (" + first
                + (w.size() > 1 ? "; " + (w.size() - 1) + " more" : "") + "); without them: " + verdict + "; priced again when an answer lands (rule 15)");
    }

    /** 0.39.z77 carry: the tail of a playbook's why whose run ends before a leg priced when the bot gets there (the trip line marks those legs). */
    public static final String PRICED_THERE = "; the legs from there are priced when the bot gets there (";

    /**
     * 0.39.z77 carry: a leg below the descent waits on an answer and nothing runs before that descent - not priced yet, the
     * trip stands (rule 22, BatchWay.shape), said; the descent goes down only with the carry list its legs below need. A wait,
     * never a no-way verdict (those go through {@link #legNoWay}).
     */
    private static Chain waitsBelow(Ctx c, String leg, String fail) {
        return playbookFails(c, NOT_PRICED + leg + WAITS_ON + "an answer before going down (" + fail + "): the descent carries what the legs below use, so it"
                + " waits for every one of them; priced again when an answer lands (rule 15)");
    }

    /** 0.39.z77 SHAPE CHANGE: the plan back to the steps it held at a leg's start (the leg priced when the bot gets there). */
    private static void cutTo(Ctx c, java.util.Set<Step> before) {
        c.steps.removeIf(s -> !before.contains(s));
        c.fail = null;
        c.stuck = false;
    }

    /** 0.39.z77 J2: a pickaxe the playbook just made that digs better than the one in hand is the one the next strip digs with (fresh). */
    private static void madePick(Ctx c, String item) {
        int t = MiningTiers.toolTier(item, "pickaxe");
        if (t > c.pickTier && c.have(item) > 0) {
            c.pickItem = item;
            c.pickTier = t;
            c.pickMade = true;
            c.pickOut = false;
            c.pickWorn = 0;
        }
    }

    private static Chain playbookFails(Ctx c, String why) {
        c.fail = why;
        return finish(c, PLAYBOOK, PLAYBOOK);
    }

    /**
     * 0.39.z77 J2: a playbook DESCENT as ONE strip (the fork's StripProcess takes any ore set: its BlockOptionalMetaLookup,
     * IStripProcess.strip; StripOrder.endRule ends it when every amount is met): every band leaf's ores in one Step.Strip at
     * the leg's Y, one amount per family. The price (rule 14, rough; the strip way's own formula): the pickaxe the ores ask
     * (made first, the cheapest), the walk down - Baritone's path for the first descent (rule 18), a later one from where the
     * last leg left the bot by the break formula (the Y difference x the tunnel height x the host's break: rule 18 amended, no
     * walk priced from a future cell) - then per family its ore blocks x blocks per ore (the leg's ore per slab; 0.39.z77
     * spares: x the areas along the first descent's heading, {@link #stripDig}, the seconds and the wear alike) x the tunnel
     * height x the host's break plus the ores' own breaks: one tunnel for all of them (0.39.z77 spares 2: its cells the most
     * any family needs, the others come along; the ores' own breaks summed). The strip leaves the bot down there ({@code c.down}): later needs are met there or before going down.
     */
    private static String playbookStrip(Ctx c, Playbook.Leg leg, int levelY, boolean afterExplore) {
        MiningTiers.Source src = MiningTiers.source();
        if (src == null) { c.fail = "the pickaxe tiers are not known (no mining data loaded yet)"; return null; }
        List<String> ores = new ArrayList<>();
        Map<String, Integer> lacks = new LinkedHashMap<>();
        Map<String, StripBand> bands = new LinkedHashMap<>();
        for (Playbook.Target t : leg.gets()) {
            String w = word(t.item());
            int lack = t.n() - have(c, new Want(w));
            if (lack <= 0) continue;
            StripBand sb = c.w.strip().apply(w);
            if (sb != null && sb.isShut()) {
                // 0.39.z78 dz: every heading at the batch's start held - the batch's one strip is no way from here; the
                // order's own chain prices the other starts and levels (shutAlts)
                c.fail = "no strip at y " + sb.y() + " for " + shw(w) + " from here: every heading is held (" + String.join("; ", sb.shut()) + ")";
                return null;
            }
            if (sb == null || sb.ores().isEmpty()) {
                // 0.39.z78 strip: every heading of the band held (its ways leave it out, World#withoutSources): said with the holds
                List<String> holds = new ArrayList<>();
                String at = headingKey(leg.y(), "");
                for (String h : c.w.held()) if (h.startsWith(at)) holds.add(h.substring(STRIP_HEADING.length()));
                c.fail = !holds.isEmpty() ? "no strip at y " + leg.y() + " for " + shw(w) + ": every heading is held (" + String.join("; ", holds) + ")"
                        : "no strip facts for " + shw(w) + " here (no world-gen band at or below the feet, or none read)";
                return null;
            }
            lacks.put(w, lack);
            bands.put(w, sb);
            for (String o : sb.ores()) if (!ores.contains(o)) ores.add(o);
        }
        if (lacks.isEmpty()) return "descent to y " + leg.y() + ": its ore is in the bag already";
        int tier = 0;
        for (String o : ores) {
            int t = src.blockTier(o);
            if (t == MiningTiers.UNKNOWN) { c.fail = "the pickaxe tier of " + shw(o) + " is not known"; return null; }
            tier = Math.max(tier, t);
        }
        StripBand first = bands.values().iterator().next();
        // 0.39.z78 strip (run DS 15:14:33-36: water ahead west at y 16 held, the re-plan struck west again and parked): the
        // joined strip's heading is the band's first one (StripOrder's order: a started tunnel, the facing, clockwise) whose
        // heading key at the leg's Y is not held (stripHold: every strip hold has that key); each held one is said in the
        // dropped list with the next; every heading held: no strip here, with the reasons (rule 7)
        String heading = null;
        List<String> heldHeadings = new ArrayList<>();
        for (String h : first.headings()) {
            String why = headingHeld(c.w.held(), first, leg.y(), h);
            if (why == null) { heading = h; break; }
            heldHeadings.add(h);
            heldHeadings.add(why);
        }
        if (heading == null) {
            List<String> said = new ArrayList<>();
            for (int i = 0; i < heldHeadings.size(); i += 2) said.add(heldHeadings.get(i) + ": " + heldHeadings.get(i + 1));
            c.fail = "no strip at y " + leg.y() + ": every heading is held (" + (said.isEmpty() ? "none open here" : String.join("; ", said)) + ")";
            return null;
        }
        for (int i = 0; i < heldHeadings.size(); i += 2)
            drop(c, "strip " + heldHeadings.get(i) + " at y " + leg.y() + " held", heldHeadings.get(i + 1) + "; " + heading + " next");
        String pre = null;
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null || c.stuck) return null;
        }
        int y = leg.y();
        String host = first.host() != null ? first.host() : ores.get(0);
        double hostS = blockBreakS(c, host);
        double walkS;
        int walkBreaks;
        String walkNote;
        Walked wk = null;
        boolean firstLeg = c.down == null;
        // 0.39.z77 price review 1 (rule 18): after the surface explore the bot is not at the feet any more - the descent is the
        // break formula from the surface Y (no walk priced from a cell the bot reaches later), as a later descent is
        if (firstLeg && !afterExplore) {
            wk = walk(c, first.x(), y, first.z(), Math.abs(first.feetY() - y));
            if (c.fail != null) return null;
            walkS = wk.seconds();
            walkBreaks = wk.pickBreaks();
            walkNote = "";
        } else {
            int dy = Math.abs((levelY == Playbook.NO_Y ? first.feetY() : levelY) - y);
            walkBreaks = dy * baritone.api.process.IStripProcess.TUNNEL_HEIGHT;
            walkS = walkBreaks * hostS;
            walkNote = " (down " + dy + (levelY == Playbook.NO_Y ? " from the surface after the explore: " : " from the last descent: ") + walkBreaks + " breaks x " + s(hostS) + ", rule 18 amended)";
        }
        double digS = 0;
        int tunnelCells = 0, oreTotal = 0;
        Map<String, Integer> amounts = new LinkedHashMap<>();
        Map<String, Double> perCell = new LinkedHashMap<>();      // 0.39.z77 spares 3: the price's ore a cell per family
        Map<String, Double> slabs = new LinkedHashMap<>();        // 0.39.z77 strip: the band per family, for the yield check
        Map<String, String> familyOf = new LinkedHashMap<>();     // 0.39.z77 strip: need word -> family (count key)
        Map<String, Integer> gains = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        for (var e : lacks.entrySet()) {
            StripBand sb = bands.get(e.getKey());
            String ore = sb.ores().get(0);
            String gives = memberOf(c, e.getKey(), ore);
            double per = perBlock(c, gives, ore);
            if (!(per > 0)) { c.fail = "the yield of " + shw(gives) + " from " + shw(ore) + " is not known (no loot entry; rule 15)"; return null; }
            int oreBlocks = (int) Math.ceil(e.getValue() / per - 1e-9);   // structural: epsilon
            Double ps = leg.perSlab().get(e.getKey());
            double slab = ps != null && ps > 0 ? ps : sb.perSlab();
            // 0.39.z77 spares: the strip way's own dig estimate (stripDig: the band x the areas along the heading); the
            // tunnel's start is known for the first descent only (a later one starts where the last leg left the bot: x 1)
            StripDig sd = stripDig(c.w, slab, oreBlocks, firstLeg ? new int[]{first.x(), first.z()} : null, y, heading, sb.ores());
            double bpo = sd.bpo();
            int dug = sd.cells();
            double oreS = blockBreakS(c, ore);
            // 0.39.z77 spares 2 (manager's call 2): ONE tunnel at the band serves every family in it - its cells are the
            // most any family needs (the rarest decides), the others come along; was: the families' cells summed (run CA
            // 15:35:44: coal 3's 672 cells on top of iron 24's 1325, 4021 blocks of wear)
            tunnelCells = Math.max(tunnelCells, dug);
            oreTotal += oreBlocks;
            digS += oreBlocks * oreS;
            amounts.merge(io.github.mojolowjo.entropybot.aa.brain.StripOrder.countKey(ore, io.github.mojolowjo.entropybot.cave.OreSpec::family), oreBlocks, Integer::sum);
            perCell.put(io.github.mojolowjo.entropybot.aa.brain.StripOrder.countKey(ore, io.github.mojolowjo.entropybot.cave.OreSpec::family), 1 / bpo);
            slabs.put(io.github.mojolowjo.entropybot.aa.brain.StripOrder.countKey(ore, io.github.mojolowjo.entropybot.cave.OreSpec::family), slab);
            familyOf.put(e.getKey(), io.github.mojolowjo.entropybot.aa.brain.StripOrder.countKey(ore, io.github.mojolowjo.entropybot.cave.OreSpec::family));
            gains.merge(gives, Math.max(e.getValue(), (int) Math.floor(oreBlocks * per + 1e-9)), Integer::sum);     // structural: epsilon
            parts.add(shw(gives) + " " + e.getValue() + " (" + oreBlocks + " ore x ~" + Math.round(bpo) + " blocks = " + dug + " cells)");
        }
        // 0.39.z77 strip (runs CK 16:50:31-17:00:31, CF 17:11:31): the yield check's facts per family - the band, a cell's
        // seconds and the family priced with no strip (the playbook's own descent has no rival way in a choice); the first
        // descent only - the strip that runs next (a later one at the same Y shares its key and is priced again from there)
        if (firstLeg) {
            String key = new Step.Strip(ores, amounts, y, heading, 0, null).key();
            for (var e : slabs.entrySet()) c.effect(new YieldPriced(key, e.getKey(), e.getValue(), baritone.api.process.IStripProcess.TUNNEL_HEIGHT * hostS));
            otherWays(c, key, lacks, familyOf);
        }
        int tunnelBreaks = baritone.api.process.IStripProcess.TUNNEL_HEIGHT * tunnelCells;
        digS += tunnelBreaks * hostS;
        final String tunnel = lacks.size() > 1 ? "; one tunnel of " + tunnelCells + " cells (the most of them; the others come along)" : "";
        int wear = walkBreaks + tunnelBreaks + oreTotal;
        String spare = spareTools(c, wear, tier);
        if (c.pickItem != null && spare == null) c.pickWorn += wear;
        c.addS(walkS + digS);
        Step.Strip whole = new Step.Strip(ores, amounts, y, heading, 0, null);
        for (var e : perCell.entrySet()) c.effect(new Predicted(whole.key(), e.getKey(), e.getValue()));
        int legAt = c.steps.size();
        if (firstLeg && first.feetY() != y) c.steps.add(new Step.ToStrip(first.x(), y, first.z(), whole.key()));
        List<Step> legs = stripLegs(whole, digS);
        c.steps.addAll(legs);
        if (firstLeg && first.feetY() != y) { c.down = "the playbook's descent to y " + y; c.downAt = legAt; c.setAway(0); c.upBag = new LinkedHashMap<>(c.bag); }
        else if (!firstLeg) {
            c.down = "the playbook's descent to y " + y;
            // 0.39.z77 legs (send-back 2; runs BJ 10:38:28 "take the crafting_table 83 m: walk: path not known yet", BK): the strip
            // leaves the bot at its tunnel's end, its dug cells away from the table and furnace placed down there before - the
            // walk back is from a cell the bot reaches later (rule 18 amended: not priced), so the next leg's crafts and smelts
            // place their own, carried down (beforeDown) or made from what is carried
            c.tableDown = false;
            c.furnaceDown = false;
        }
        else c.setAway(walkS);
        for (var e : gains.entrySet()) c.bag.merge(e.getKey(), e.getValue(), Integer::sum);
        // 0.39.z77 answers (2): the tunnel's own blocks come with the bot (the host's drop by the loot data, one a break): the
        // descent carries the blocks the climb back up and a furnace down there take (Deposit handles a full bag)
        String hostDrop = c.w.loot() == null ? host : null;
        if (c.w.loot() != null) for (LootBook.Source d : c.w.loot().blockDrops(host)) if (d.per() > 0) { hostDrop = d.item(); break; }
        int carried = tunnelBreaks + walkBreaks;
        if (hostDrop != null && carried > 0) c.bag.merge(id(hostDrop), carried, Integer::sum);
        int n = legs.size() / io.github.mojolowjo.entropybot.aa.brain.Goals.stripLoop(whole).size();
        final String line = "descent to y " + y + " (" + leg.whyY() + "): one strip of " + String.join(", ", ores.stream().map(NeedChain::shw).toList()) + " for "
                + String.join(" + ", parts) + tunnel + ": " + s(digS) + (n > 1 ? " in " + n + " legs (steps.stepMaxS " + Math.round(Tuning.get("steps.stepMaxS")) + " s each)" : "")
                + ", walk " + s(walkS) + walkNote + " -> " + s(walkS + digS)
                + (hostDrop != null && carried > 0 ? "; carries " + carried + " " + sh(id(hostDrop)) + " (the tunnel's: the climb back's blocks)" : "");
        c.note(() -> line);
        String out = line + (pre == null ? "" : " with " + pre) + (spare == null ? "" : " + " + spare);
        return wk == null ? out : noted(out, wk);
    }

    /**
     * 0.39.z77 strip (manager's ruling 2026-10-26 ~17:40, run CK: the 1 coal of a met strip never found at y 16, 600 s): a
     * playbook descent's families each priced with no strip way ({@code c.hoisting}: no leg to another level, so no strip),
     * on a snapshot put back after (the chain, the bag, the wait list, the trials, the unreachable sources: as before it): a
     * fuel the descent strips for the smelts by the planner's own burn ways for the same smelts (the charcoal for the coal), any
     * other family by its ways (a seen vein, a known cave). A way that stands with a final price is the family's other way
     * ({@link io.github.mojolowjo.entropybot.aa.brain.StripYield#other}); none (an explore, a wait, a fail): not priced, NaN.
     */
    private static void otherWays(Ctx c, String key, Map<String, Integer> lacks, Map<String, String> familyOf) {
        for (var e : lacks.entrySet()) {
            String fam = familyOf.get(e.getKey());
            if (fam == null || e.getKey().startsWith("#") || e.getKey().indexOf('|') >= 0) continue;    // the playbook's leaves are items
            String id0 = id(e.getKey());
            if (ItemWorth.fuels().getOrDefault(id0, 0) <= 0 && c.w.seenAll().apply(id0).isEmpty() && (c.w.cave() == null || c.w.cave().apply(id0) == null)) {
                // nothing seen and no cave known: its ways end in an explore (no price) - not priced, no walk asked for it
                c.effect(new YieldOther(key, fam, Double.NaN, "no source of " + shw(id0) + " seen and no cave known (an explore: no price)", false));
                continue;
            }
            Ctx.Snap s0 = c.snap();
            double base = c.seconds;
            int waited = c.waited.size(), trials = c.trials;
            java.util.Set<String> unreach = new java.util.HashSet<>(c.unreachable.keySet());
            boolean hoist = c.hoisting;
            String fail = c.fail, loop = c.loopOn;
            Burn burned = c.burned;
            String item = id(e.getKey());
            int burnT = ItemWorth.fuels().getOrDefault(item, 0);
            String words;
            double secs = Double.NaN;
            if (c.trace != null) c.trace.push("the other way for " + shw(item) + " " + e.getValue() + " (no strip; the strip's yield check, not taken)", false);
            try {
                c.hoisting = true;
                if (burnT > 0) {
                    int k = Math.max(1, (int) Math.floor(e.getValue() * (double) burnT / smeltTicks() + 1e-9));   // structural: epsilon
                    String p = burn(c, k);
                    words = "the fuel for " + k + " smelts: " + (p == null ? c.fail : p);
                } else {
                    String p = obtain(c, item, c.have(item) + e.getValue());
                    words = p == null ? String.valueOf(c.fail) : p;
                }
                if (c.fail == null && !c.stuck) secs = c.seconds - base;
                else if (c.stuck) words = "ends in an explore (no price): " + words;
            } finally {
                c.restore(s0);
                c.hoisting = hoist;
                c.fail = fail;
                c.loopOn = loop;
                c.burned = burned;
                c.trials = trials;
                while (c.waited.size() > waited) c.waited.remove(c.waited.size() - 1);
                c.unreachable.keySet().retainAll(unreach);
                if (c.trace != null) c.trace.pop();
            }
            final String w = words;
            final double sv = secs;
            c.note(() -> "the other way for " + fam + ": " + (Double.isNaN(sv) ? "not priced (" + w + ")" : s(sv) + " (" + w + ")"));
            c.effect(new YieldOther(key, fam, sv, w, false));
        }
    }

    /**
     * 0.39.z77 J2 (manager's R2; run BC 06:23:36: a 3-iron strip timed out at steps.stepMaxS 600 s): a strip whose priced
     * dig ({@code digS}) is past steps.stepMaxS is split into legs of the same strip - each its own Step.Strip (the same key:
     * a leg done by its amounts clears the saved tunnel, the next starts where the bot stands) with its share of every
     * amount, so each fits the step watch and has its own monitor; the count from the price, no knob. Each leg with its
     * deposit and walk back ({@link io.github.mojolowjo.entropybot.aa.brain.Goals#stripLoop}). One leg: as before.
     */
    static List<Step> stripLegs(Step.Strip whole, double digS) {
        double max = Tuning.get("steps.stepMaxS");
        int most = 0;
        for (int v : whole.amounts().values()) most = Math.max(most, v);
        int n = max > 0 && digS > max ? (int) Math.ceil(digS / max - 1e-9) : 1;     // structural: epsilon
        n = Math.max(1, Math.min(n, most));
        if (n == 1) return io.github.mojolowjo.entropybot.aa.brain.Goals.stripLoop(whole);
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> a = new LinkedHashMap<>();
            for (var e : whole.amounts().entrySet()) {
                int part = e.getValue() / n + (i < e.getValue() % n ? 1 : 0);
                if (part > 0) a.put(e.getKey(), part);
            }
            if (a.isEmpty()) continue;
            out.addAll(io.github.mojolowjo.entropybot.aa.brain.Goals.stripLoop(new Step.Strip(whole.ores(), a, whole.y(), whole.heading(), i + 1, whole.from())));
        }
        return out;
    }

    /** 0.39.z77 J2: a chain and the simulated bag after it (the next chain starts from it). */
    public record Priced(Chain chain, Map<String, Integer> bagAfter) {}

    /** 0.39.z77 J2: {@link #resolve} of an item with the simulated bag it leaves (item id -> count; a way's own tokens left out). */
    public static Priced resolveWithBag(String what, int n, World w) {
        return root("with bag", java.util.Arrays.asList(what, n), w, c -> withBagIn(c, what, n), pr -> new Priced(fresh(pr.chain()), pr.bagAfter()));   // 0.39.z78 D6
    }

    private static Priced withBagIn(Ctx c, String what, int n) {
        String item = id(what);
        String phrase = obtain(c, item, n);
        Chain ch = finish(c, sh(item), phrase == null ? sh(item) + " is in the bag" : sh(item) + " <- " + phrase);
        Map<String, Integer> after = new LinkedHashMap<>();
        for (var e : c.bag.entrySet()) if (e.getValue() > 0 && !e.getKey().startsWith("#") && e.getKey().indexOf('|') < 0) after.put(e.getKey(), e.getValue());
        return new Priced(ch, after);
    }

    /**
     * 0.39.z77 J2: the explore leg's price in seconds - the explore's own limit (discovery.exploreMinutes) and the share of
     * it the walk back costs again (needChain.exploreReturn). Manager 2026-10-26: the row's old explore leg was 5 x 60 x 1.5
     * = 450 walk-BLOCKS (104 s) for a step that runs up to 7.5 minutes - a unit slip; now its seconds, converted once.
     */
    public static double exploreS() { return exploreMinutes() * Options.MINUTE * (1 + exploreReturn()); }

    private static Chain finish(Ctx c, String name, String head) {
        // 0.39.z75 metric fix A (project manager; run AH 19:46:59 "strip ... for coal 0"): a need of 0 is "have it" before any
        // way is priced (obtain / needTag), and no way of count 0 ever leaves the planner - a step that would get or make
        // nothing is a planner fault, said, never a way the run or the repair takes
        if (c.fail == null) {
            String zero = zeroStep(c.steps);
            if (zero != null) c.fail = "a way of count 0 (" + zero + "): no way (it would get nothing)";
        }
        double cost = Unit.blocks(c.seconds);
        // an explore has no price in the chain; the scorer still needs a number to weigh the whole row, so the row's cost carries the explore leg
        // 0.39.z77 J2 (manager: a unit slip): its seconds as walk-blocks (was 450 blocks = 104 s for up to 7.5 min)
        if (c.stuck) cost += Unit.blocks(exploreS());
        String drops = c.dropped.isEmpty() ? "" : " | dropped: " + String.join("; ", c.dropped);
        List<Map<String, Integer>> uses = new ArrayList<>();
        for (Step st : c.steps) uses.add(c.uses.get(st));
        String spares = String.join("; ", c.spares);         // 0.39.z65 item 2
        if (c.fail != null) return new Chain(List.copyOf(c.steps), cost, name + ": " + c.fail + drops, false, c.fail, List.copyOf(c.dropped), c.unreachable, uses, spares, List.of());
        String why = head + (c.stuck ? "" : ": " + s(c.seconds)) + drops;
        return new Chain(List.copyOf(c.steps), cost, why, c.stuck, null, List.copyOf(c.dropped), c.unreachable, uses, spares, stepSeconds(c, Unit.seconds(cost)));
    }

    /** 0.39.z75 metric fix A: the first step that gets or makes 0 (a collect, a craft, a smelt, a cut, a mine, a strip with no ore), null: none. Pure. */
    static String zeroStep(List<Step> steps) {
        for (Step s : steps) {
            boolean zero = switch (s) {
                case Step.Collect k -> k.n() <= 0;
                case Step.Craft k -> k.n() <= 0;
                case Step.Smelt k -> k.n() <= 0;
                case Step.ChopTree t -> t.logs() <= 0;
                case Step.Mine m -> m.n() <= 0;
                case Step.Strip st -> st.amounts().values().stream().mapToInt(Integer::intValue).sum() <= 0;
                default -> false;
            };
            if (zero) return s.text();
        }
        return null;
    }

    /**
     * 0.39.z73 (D2 3.4): the chain's seconds split per step from the add marks - step i gets what was priced from its add to
     * the next step's (the first also what came before it, the last up to {@code total}: an explore chain's leg too), so the
     * list sums to {@code total}. A step with no mark (never seen added) gets 0. Empty for no steps.
     */
    static List<Double> stepSeconds(Ctx c, double total) {
        int n = c.steps.size();
        List<Double> out = new ArrayList<>();
        if (n == 0) return out;
        double[] mark = new double[n];
        for (int i = 0; i < n; i++) {
            Double m = c.addedAt.get(c.steps.get(i));
            mark[i] = m == null ? (i == 0 ? 0 : mark[i - 1]) : m;
            if (i > 0 && mark[i] < mark[i - 1]) mark[i] = mark[i - 1];     // a merged / re-added step out of order: no negative share
        }
        for (int i = 0; i < n; i++) {
            double from = i == 0 ? 0 : mark[i], to = i + 1 < n ? mark[i + 1] : total;
            out.add(Math.max(0, to - from));
        }
        double sum = 0;
        for (double d : out) sum += d;
        out.set(n - 1, out.get(n - 1) + (total - sum));         // what clamping moved, back on the last: the sum is the total
        return out;
    }

    /** 0.39.z61: the step just added used {@code used} (need word -> count) from the simulated bag. */
    private static void used(Ctx c, Map<String, Integer> used) {
        if (!c.steps.isEmpty() && !used.isEmpty()) c.use(c.steps.get(c.steps.size() - 1), java.util.Collections.unmodifiableMap(new LinkedHashMap<>(used)));
    }

    /** One way to get a requirement: what runs it on the simulated bag, its name for the dropped list, and what breaks a tie. */
    private static final class Alt {
        String label;
        /** The label when another way of the same requirement has the same short one ("coal from coal_block"); null: none. */
        String longLabel;
        Supplier<String> run;
        /** The item the way yields (a tag need's member; set by the way itself when only its run knows it), null: the need word. */
        String id;
        int tier, rank;
        double work;
        /** Seconds -> the number the ways compare by (null: the seconds). */
        java.util.function.DoubleUnaryOperator rate;
        /** 0.39.z75 item 2: a burn way's fuel (set by its run). */
        Burn burned;
        /** 0.39.z77 strip: a strip way ({@link #stripAlts}): its rivals' best price is kept for its yield check ({@link #stripRivals}). */
        boolean strip;
        /** 0.39.z78 D6: the rank budget the way runs under ({@link Ranks}; null: none) - part of its node's key. */
        Ranks rk;
        Alt(String label, Supplier<String> run) { this.label = label; this.run = run; }
        double value(double seconds) { return rate == null ? seconds : rate.applyAsDouble(seconds); }
    }

    private record Trial(Alt alt, String phrase, double seconds, boolean stuck, String fail, String loopOn, String unknown, Ctx.Snap after, int index) {}

    /**
     * Prices every way on a snapshot (each put back), takes the cheapest that stands (no failure, no explore in it; past the
     * horizon last), restores the winner's state and lists every other with its seconds, its loop or its "no known source".
     * Past {@code needChain.maxTrials} ways in one chain (the search's bound) the first way that stands ends the pricing.
     * Null: no way stands and none ends in an explore (every failure is in the dropped list); the caller makes the explore leaf.
     * 0.39.z77 choose: an explore wins only against final answers - while another way has no answer yet the way that stands
     * on its walks is taken, else null with that wait in {@code c.fail} (the caller stands, {@link #pathsWait}).
     * <p>The tie rule (0.39.z78 D6, manager 2026-10-27): ways compare by, in order, no explore in it, within the horizon,
     * the way's value of its own seconds (its rate, else the seconds), then its {@code work}, its {@code tier} and its index
     * in {@code alts}. The seconds are exact sums on the 2^-20 s grid ({@link #grid}, Ctx#addS), so two ways of the same
     * price are a true tie, never one decided by float noise - and a tie is decided by that order (work, tier, way index).
     */
    private static Trial choose(Ctx c, List<Alt> alts, String self) {
        if (alts.isEmpty()) return null;
        distinctLabels(alts);
        List<Trial> trials = new ArrayList<>();
        List<Trace.Node> nodes = new ArrayList<>();          // aa debug: each trial's node (empty outside a trace)
        int stepsAt = c.steps.size();
        int skipped = -1;
        // 0.39.z78 D6: each way a node of the open one (its number of choose there, what that had read when the ways were made)
        int ordinal = c.caching() ? c.run.top.chooses++ : 0;
        Prefix prefix = c.caching() ? prefixOf(c.run.top) : null;
        Trace.Node waysNode = c.trace == null ? null : c.trace.push("ways for " + (self == null ? "this requirement" : shw(self)) + " (" + alts.size() + ")", false);
        try {
            for (int i = 0; i < alts.size(); i++) {
                if (c.overBound() && trials.stream().anyMatch(t -> t.fail() == null && !t.stuck())) { skipped = i; break; }
                Ctx.Snap s0 = c.snap();
                double base = c.seconds;
                c.trials++;
                if (c.trace != null) nodes.add(c.trace.push("way " + alts.get(i).label, true));
                String p;
                try {
                    p = c.caching() ? way(c, alts.get(i), i, ordinal, s0, prefix) : alts.get(i).run.get();
                } finally {
                    if (c.trace != null) c.trace.pop();
                }
                trials.add(new Trial(alts.get(i), p, c.seconds - base, c.stuck, c.fail, c.loopOn, c.unknown, c.snap(), i));
                if (c.trace != null) {
                    Trial t = trials.get(trials.size() - 1);
                    Trace.Node n = nodes.get(nodes.size() - 1);
                    n.kids.add(new Trace.Node(t.fail() != null ? "fails: " + t.fail() : t.stuck() ? "ends in an explore (no price): " + p : "priced " + s(t.seconds()) + ": " + p, false));
                }
                c.restore(s0);
            }
        } finally {
            if (c.trace != null) c.trace.pop();
        }
        stripRivals(c, trials, stepsAt, self);                // 0.39.z77 strip: before the pick, whoever wins
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
            // (a loop back to an item still on the path above: one recipe circle through a member made in a tag's way is
            // listed at that tag, its item no longer on the path)
            boolean up = self != null && trials.stream().allMatch(t -> t.loopOn() != null && !t.loopOn().equals(self) && c.path.contains(t.loopOn()));
            if (up) {
                c.fail = trials.get(0).fail(); c.loopOn = trials.get(0).loopOn();
                for (Trace.Node n : nodes) n.verdict = "fails up to the way that asked (a loop through " + shw(c.loopOn) + ")";
            }
            else {
                for (Trial t : trials) { drop(c, t.alt().label, t.fail()); verdict(nodes, t, "dropped: " + t.fail()); }
                // 0.39.z41: no way stands while a walk's path is not known (computing, chunks not loaded): the requirement waits
                // on it (priced again when the answer lands), it does not turn into an explore
                for (Trial t : trials) if (t.fail() != null && t.fail().startsWith(PathPrice.NOT_KNOWN)) { if (c.fail == null || !c.fail.startsWith(PathPrice.NOT_KNOWN)) c.fail = t.fail(); }
                // 0.39.z42: how many ways wait on a path, for the requirement's standing wait (pathsWait; z76: no explore meanwhile, rule 22)
                c.pathsWaiting = (int) trials.stream().filter(t -> t.fail() != null && t.fail().startsWith(PathPrice.NOT_KNOWN)).count();
            }
            return null;
        }
        // 0.39.z77 choose (manager 2026-10-26 ~14:40 on run BW; cycle 5's open line; rule 15: an unknown is never a verdict): an
        // explore wins only against ways with a final answer (priced, UNREACHABLE, held, dropped with a reason). While another
        // way of this requirement has no answer yet - its walk computing or queued, chunks, the loot book or the mining data not
        // loaded (answerPending), or it stands on such a walk itself - the pick is not priced yet: a way that stands is taken,
        // else no way stands and the caller stands (pathsWait, rule 22) or waits with the reason; priced again when the answer
        // lands. A priced way still wins at once (the winner is not stuck then). The two-stands bound (stoodHere: "stood at")
        // turns the walks still not known into final drops, so this never waits for ever.
        String heldBack = null;
        if (win.stuck() && !stands(win, stepsAt)) {
            List<Trial> open = new ArrayList<>();
            for (Trial t : trials) if (t != win && noAnswerYet(t, stepsAt)) open.add(t);
            if (!open.isEmpty()) {
                heldBack = "): not picked while " + open.size() + " way" + (open.size() == 1 ? " has" : "s have") + " no answer yet ("
                        + open.get(0).alt().label + (open.size() > 1 ? "; " + (open.size() - 1) + " more" : "") + "); priced again when an answer lands (rule 15)";
                Trial stand = null;
                for (Trial t : open) if (t.fail() == null && (stand == null || best.compare(t, stand) < 0)) stand = t;
                if (stand == null) {
                    for (Trial t : trials) {
                        String why = t.fail() != null ? t.fail() : EXPLORE_HELD + shw(t.unknown()) + heldBack;
                        drop(c, t.alt().label, why);
                        verdict(nodes, t, "dropped: " + why);
                    }
                    for (Trial t : open) if (t.fail().startsWith(PathPrice.NOT_KNOWN)) { c.fail = t.fail(); break; }
                    if (c.fail == null) c.fail = open.get(0).fail();
                    c.pathsWaiting = (int) open.stream().filter(t -> t.fail().startsWith(PathPrice.NOT_KNOWN)).count();
                    return null;
                }
                win = stand;
            }
        }
        c.restore(win.after());
        verdict(nodes, win, "picked" + (win.stuck() ? " (ends in an explore)" : ": " + s(win.seconds())));
        // dropped-audit (0.39.z16): every way not chosen is listed, a way ending in an explore too (it was left out when the
        // winner explored as well, or when it was a tag's or a group's member: the owner's charcoal vanished that way)
        for (Trial t : trials) {
            if (t == win) continue;
            String why;
            if (t.fail() != null) why = t.fail();
            else if (heldBack != null && t.stuck() && !stands(t, stepsAt)) why = EXPLORE_HELD + shw(t.unknown()) + heldBack;
            else if (t.stuck()) why = "no known source for " + shw(t.unknown()) + (win.stuck() ? " (the explore ends when one is known too)" : "");
            else if (t.alt().rate != null) why = s(t.seconds()) + String.format(Locale.ROOT, ", %.2f s a point with the eating (dearer a point than %s, %.2f)", t.alt().value(t.seconds()), win.alt().label, win.alt().value(win.seconds()));
            else why = s(t.seconds()) + " (dearer than " + win.alt().label + (t.seconds() > horizonS ? ", past the horizon" : "") + ")";
            drop(c, t.alt().label, why);
            verdict(nodes, t, "dropped: " + why);
        }
        if (skipped >= 0) for (int i = skipped; i < alts.size(); i++) {
            String why = "not priced (the search's bound: needChain.maxTrials, " + maxTrials() + " ways a chain)";
            drop(c, alts.get(i).label, why);
            if (waysNode != null) {
                Trace.Node n = new Trace.Node("way " + alts.get(i).label, true);
                n.verdict = "dropped: " + why;
                waysNode.kids.add(n);
            }
        }
        if (win.stuck()) widenExplore(c, stepsAt, trials, win);
        return win;
    }

    /**
     * 0.39.z77 strip (manager's ruling 2026-10-26 ~17:40; runs CF 17:11:31, CJ 17:25:25: iron strips of 100+ cells with no
     * cluster ran on): each strip way priced in this choice keeps the best price of its rivals for the same need - a seen vein,
     * a cave, a strip from fresh ground in another heading, the charcoal - for the strip's yield check while it runs
     * ({@link io.github.mojolowjo.entropybot.aa.brain.StripYield#other}); a rival that ends in an explore or fails has no price
     * (rule 15). A re-price of the order on the facts of then (a landed path, the strip's own sightings) refreshes it. A
     * need whose strip way is not offered because a source of it is seen now (itemAlts: the strip only with nothing seen)
     * keeps its ways' best price for the strip from the feet ({@code self}'s strip facts: the tunnel the bot is in resumes
     * under that key), so a vein the strip passed is a price the strip is checked against (run CJ 17:25:25: "dig raw_iron
     * seen 14 m" after 102 dry cells).
     */
    private static void stripRivals(Ctx c, List<Trial> trials, int base, String self) {
        boolean any = false;
        for (Trial t : trials) {
            if (!t.alt().strip) continue;
            any = true;
            if (t.fail() != null) continue;
            Step.Strip st = null;
            List<Step> after = t.after().steps();
            for (int i = Math.max(0, base); i < after.size() && st == null; i++) if (after.get(i) instanceof Step.Strip s) st = s;
            if (st == null || st.amounts().size() != 1) continue;
            rival(c, st.key(), st.amounts().keySet().iterator().next(), trials, t);
        }
        if (any || self == null || c.hoisting || c.down != null || trials.isEmpty() || c.w.seenAll().apply(self).isEmpty()) return;
        StripBand sb = c.w.strip().apply(self);
        if (sb == null || sb.ores().isEmpty() || sb.isShut()) return;
        String key = new Step.Strip(sb.ores(), Map.of(), sb.y(), sb.heading(), 0, sb.from()).key();
        rival(c, key, io.github.mojolowjo.entropybot.aa.brain.StripOrder.countKey(sb.ores().get(0), io.github.mojolowjo.entropybot.cave.OreSpec::family), trials, null);
    }

    /** The best priced trial but {@code not} kept as the other way of strip {@code key}'s {@code family} (NaN: none priced). */
    private static void rival(Ctx c, String key, String family, List<Trial> trials, Trial not) {
        Trial best = null;
        for (Trial o : trials) if (o != not && o.fail() == null && !o.stuck() && (best == null || o.seconds() < best.seconds())) best = o;
        c.effect(new YieldOther(key, family, best == null ? Double.NaN : best.seconds(),
                best == null ? "no other way priced" : best.alt().label, best != null && best.alt().strip));
    }

    /** 0.39.z77 choose: the start of the dropped reason of an explore held back while another way has no answer yet. */
    public static final String EXPLORE_HELD = "ends in an explore (no known source for ";

    /** 0.39.z77 choose: the trial stands on walks still computing (its steps since {@code base} hold the standing step). */
    private static boolean stands(Trial t, int base) {
        List<Step> st = t.after().steps();
        for (int i = Math.max(0, base); i < st.size(); i++) if (st.get(i) instanceof Step.AwaitPaths) return true;
        return false;
    }

    /** 0.39.z77 choose: the way has no final answer yet - it failed waiting ({@link #answerPending}) or it stands ({@link #stands}). */
    private static boolean noAnswerYet(Trial t, int base) {
        return t.fail() != null ? answerPending(t.fail()) : t.stuck() && stands(t, base);
    }

    /** aa debug: the trial's verdict on its node (nothing outside a trace). */
    private static void verdict(List<Trace.Node> nodes, Trial t, String v) {
        if (t.index() < nodes.size()) nodes.get(t.index()).verdict = v;
    }

    /**
     * explore-until (0.39.z16): the winner ends in an explore and so did other ways of the same requirement: its explore
     * ends when a source of ANY of them is known (their until words joined, the winner's first), so the chain is priced
     * again the moment a tree for charcoal or a coal ore turns up. The live check: {@code ExploreRules.untilFound} per word.
     */
    private static void widenExplore(Ctx c, int base, List<Trial> trials, Trial win) {
        Set<String> words = new LinkedHashSet<>();
        untilWords(c.steps, base, words);
        int own = words.size();
        if (c.trace != null) for (String w : words) c.trace.untilFrom.computeIfAbsent(w, k -> new LinkedHashSet<>()).add(win.alt().label);
        for (Trial t : trials) if (t.stuck() && t.fail() == null) {
            untilWords(t.after().steps(), base, words);
            if (c.trace != null && t != win) {
                Set<String> its = new LinkedHashSet<>();
                untilWords(t.after().steps(), base, its);
                for (String w : its) c.trace.untilFrom.computeIfAbsent(w, k -> new LinkedHashSet<>()).add(t.alt().label);
            }
        }
        if (words.size() == own) return;
        String until = String.join("|", words);
        for (int i = base; i < c.steps.size(); i++) if (c.steps.get(i) instanceof Step.Explore e && e.until() != null) c.steps.set(i, new Step.Explore(e.minutes(), until));
    }

    private static void untilWords(List<Step> steps, int from, Set<String> into) {
        for (int i = Math.max(0, from); i < steps.size(); i++)
            if (steps.get(i) instanceof Step.Explore e && e.until() != null) for (String p : e.until().split("\\|")) if (!p.isEmpty()) into.add(p);
    }

    /**
     * dropped-audit: two ways of one requirement with the same label would be listed once (the dropped list's dedupe), so the
     * second vanished: a way with a long label takes it when its short one is shared ("coal from coal_block" beside a "coal" dig).
     */
    private static void distinctLabels(List<Alt> alts) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (Alt a : alts) n.merge(a.label, 1, Integer::sum);
        for (Alt a : alts) if (n.get(a.label) > 1 && a.longLabel != null) a.label = a.longLabel;
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Alt a : alts) {
            int k = seen.merge(a.label, 1, Integer::sum);
            if (k > 1) a.label = a.label + " (" + k + ")";       // still the same: numbered, never merged in the list
        }
    }

    /** The dropped list names each losing candidate once:the same requirement is asked again per batch, the label is listed the first time only. */
    private static void drop(Ctx c, String label, String why) {
        String head = label + ": ";
        if (waitsHere(why)) c.waited.add(head + why);     // 0.39.z77 pending: counted before the list's dedupe
        if (c.droppedHas(DROP_HEAD + head)) return;      // 0.39.z78 D6 (option C): the dedupe's read of the lines before is a fact
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
        // 0.39.z78 D6: the need is a node (kept with what it read; reused while that holds)
        if (c.caching()) return (Got) node(c, "tag", List.of(w.word, n), () -> tagWays(c, w, n));
        return tagWays(c, w, n);
    }

    /** {@link #needTag} past the bag check: the ways of the tag need, priced. */
    private static Got tagWays(Ctx c, Want w, int n) {
        int have = have(c, w);
        int lack = n - have;
        if (c.path.contains(w.word)) {
            c.fail = "loop: " + shw(w.word) + " needs itself (a recipe loop: " + String.join(" > ", c.path.stream().map(NeedChain::shw).toList()) + " > " + shw(w.word) + ")";
            c.loopOn = w.word;
            return null;
        }
        c.path.add(w.word);
        try {
            int stepsAt = c.steps.size();
            List<Alt> alts = tagAlts(c, w, lack);
            Alt up = beforeDown(c, shw(w.word), w.word, () -> tagAlts(c, w, lack));
            if (up != null) alts.add(up);
            Trial win = choose(c, alts, w.word);
            if (win != null) {
                if (win.stuck()) untilOwn(c, stepsAt, w);
                return new Got(win.alt().id != null ? win.alt().id : w.word, n, win.phrase());
            }
            if (waitsOnPaths(c)) return new Got(w.word, n, pathsWait(c, w.word));
            if (c.fail != null) return null;
            String leaf = exploreLeaf(c, w.word, noSource(c, w.word));
            return c.fail != null ? null : new Got(w.word, n, leaf);
        } finally {
            c.path.remove(c.path.size() - 1);
        }
    }

    /** The ways of a tag need: its members' recipe groups, its known sources, the animals, the strip, the cave, the base block. */
    private static List<Alt> tagAlts(Ctx c, Want w, int lack) {
        List<Alt> alts = new ArrayList<>();
        for (Group g : groups(w, ItemWorth.CRAFT)) alts.add(asideAlt(c, w, groupAlt(c, w, lack, g)));
        for (Group g : groups(w, ItemWorth.SMELT)) alts.add(asideAlt(c, w, groupAlt(c, w, lack, g)));
        alts.addAll(tagSourceAlts(c, w, lack));
        alts.addAll(tagHuntAlts(c, w, lack));
        if (c.w.seenAll().apply(w.word).isEmpty()) alts.addAll(stripAlts(c, w.word, w.word, lack));   // 0.39.z43
        alts.addAll(caveAlts(c, w.word, w.word, lack));    // 0.39.z44; 0.39.z77 cave: beside any seen source too
        alts.addAll(baseAlts(c, w.word, lack));                                                      // 0.39.z74 item 4
        return alts;
    }

    /**
     * 0.39.z75 item 2c (run AF 18:22-18:42: "explore 5 until lava_bucket|#logs|bamboo|...|scaffolding", every burnable but
     * coal and charcoal, under ground with coal ore round): a tag need whose winning way ends in an explore for ONE member's
     * leaf (#coals: charcoal's logs) explored only for that leaf - the tag's own sources (the blocks whose loot drops a member:
     * coal ore) were never asked, because the tag's own explore runs only when no way stands. Its explore now ends on a source
     * of the tag too: the tag's word joins the until words of the explore steps the winner added - only when the loot data
     * names a block or a mob that drops a member (no book, or not read yet: nothing added, rule 15; no source: an explore
     * could never end on it).
     */
    private static void untilOwn(Ctx c, int from, Want w) {
        LootBook book = c.w.loot();
        if (book == null || !book.ready()) return;
        boolean any = false;
        for (LootBook.Source s : book.sourcesMatching(w::has)) any |= s.kind() == LootBook.Kind.BLOCK || s.kind() == LootBook.Kind.MOB;
        if (!any) return;
        for (int i = Math.max(0, from); i < c.steps.size(); i++) {
            if (!(c.steps.get(i) instanceof Step.Explore e) || e.until() == null) continue;
            String own = shw(w.word);
            Set<String> words = new LinkedHashSet<>(List.of(e.until().split("\\|")));
            if (!words.add(own)) continue;
            c.steps.set(i, new Step.Explore(e.minutes(), String.join("|", words)));
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
    static Map<String, Integer> slotWords(ItemWorth.Recipe r) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String sw : r.needs()) out.merge(word(sw), 1, Integer::sum);
        return out;
    }

    /**
     * TAG_NEEDS rule 2: a tag need's members' recipes of {@code kind}, grouped by what they need (a recipe that takes a member
     * of the tag itself, a dye-over, is no way to make it). One-ingredient recipes of the same shape are one group only when
     * they take the same word, or words that together are exactly one game tag ({@link #covers}: {@code #planks <- #logs});
     * different tags are different ways ({@code #coals}: charcoal from #logs_that_burn, coal from #coal_ores); others group
     * by their exact need words.
     */
    private static List<Group> groups(Want w, String kind) {
        // pass 1: the one-ingredient recipes' words by shape (out count, pieces)
        Map<String, Set<String>> shapeWords = new LinkedHashMap<>();
        for (String m : w.members()) {
            for (ItemWorth.Recipe r : ItemWorth.recipes(m, kind)) {
                if (takesItself(w, r)) continue;
                Map<String, Integer> sw = slotWords(r);
                if (sw.size() == 1) shapeWords.computeIfAbsent(r.outCount() + " x" + sw.values().iterator().next(), k -> new TreeSet<>()).add(sw.keySet().iterator().next());
            }
        }
        // grouping-rule (0.39.z16, owner-watched: charcoal <- #logs_that_burn was joined with coal <- #coal_ores and vanished):
        // one-ingredient recipes of one shape are one group only for the same word, or for words that together are exactly
        // one game tag (the planks' logs: #logs); any other word is its own group, its own way, priced and shown
        Map<String, Map<String, String>> coverOf = new LinkedHashMap<>();
        for (var e : shapeWords.entrySet()) coverOf.put(e.getKey(), covers(e.getValue()));
        Map<String, List<ItemWorth.Recipe>> recs = new LinkedHashMap<>();
        Map<String, List<String>> outs = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> slots = new LinkedHashMap<>();
        for (String m : w.members()) {
            for (ItemWorth.Recipe r : ItemWorth.recipes(m, kind)) {
                if (takesItself(w, r)) continue;
                Map<String, Integer> sw = slotWords(r);
                String key;
                if (sw.size() == 1) {
                    String shape = r.outCount() + " x" + sw.values().iterator().next();
                    String need = coverOf.get(shape).get(sw.keySet().iterator().next());
                    key = shape + " " + need;
                    slots.putIfAbsent(key, Map.of(need, r.needs().size()));
                } else {
                    key = r.outCount() + " " + new TreeMap<>(sw);
                    slots.putIfAbsent(key, sw);
                }
                recs.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                outs.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
        }
        List<Group> out = new ArrayList<>();
        for (var e : recs.entrySet()) out.add(new Group(kind, e.getValue().get(0).outCount(), e.getValue(), outs.get(e.getKey()), slots.get(e.getKey())));
        return out;
    }

    /** A recipe that takes a member of the need itself (a dye-over, a packing): not a way to make it. */
    private static boolean takesItself(Want w, ItemWorth.Recipe r) {
        for (List<String> slot : r.options()) for (String o : slot) if (w.has(o)) return true;
        return false;
    }

    /**
     * The one need word for "any of these words": one word as it is; several as the one game tag whose items are exactly
     * theirs (the planks' log tags together are {@code #logs}). Null: no one tag is exactly theirs (never joined with "|":
     * unrelated words are separate needs).
     */
    static String cover(Set<String> words) {
        if (words.size() == 1) return words.iterator().next();
        Set<String> all = new TreeSet<>();
        for (String w : words) all.addAll(new Want(w).members());
        if (all.isEmpty()) return null;
        for (String t : new TreeSet<>(ItemWorth.itemTagsOf(all.iterator().next()))) if (ItemWorth.itemsIn(t).equals(all)) return "#" + t;
        return null;
    }

    /**
     * grouping-rule: each word -> the need it is grouped under. Words whose members together are exactly one game tag
     * (the largest such set first, ties by the tag's id) share that tag; every other word stands alone.
     */
    static Map<String, String> covers(Set<String> words) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> left = new ArrayList<>(words);
        while (left.size() >= 2) {
            String all = cover(new TreeSet<>(left));
            if (all != null) { for (String w : left) out.put(w, all); left.clear(); break; }
            Set<String> cands = new TreeSet<>();
            for (String w : left) for (String m : new Want(w).members()) cands.addAll(ItemWorth.itemTagsOf(m));
            String bestTag = null;
            List<String> best = List.of();
            for (String t : cands) {
                Set<String> tm = ItemWorth.itemsIn(t);
                List<String> in = new ArrayList<>();
                Set<String> union = new TreeSet<>();
                for (String w : left) {
                    Set<String> ms = new Want(w).members();
                    if (!ms.isEmpty() && tm.containsAll(ms)) { in.add(w); union.addAll(ms); }
                }
                if (in.size() >= 2 && union.equals(tm) && in.size() > best.size()) { bestTag = t; best = in; }
            }
            if (bestTag == null) break;
            for (String w : best) out.put(w, "#" + bestTag);
            left.removeAll(best);
        }
        for (String w : left) out.put(w, w);
        return out;
    }

    /** A group as one way: one member made: that member's own recipe (the item way); several: {@link #makeGroup}. */
    private static Alt groupAlt(Ctx c, Want w, int lack, Group g) {
        Set<String> made = g.made();
        boolean craft = ItemWorth.CRAFT.equals(g.kind());
        if (made.size() == 1) {
            String out = made.iterator().next();
            ItemWorth.Recipe r = g.recs().get(0);
            // loop-guard (0.39.z16, owner-watched "coal <- coal_block <- coal"): the member made is on the path while its
            // recipe is priced, as obtain puts it, so a recipe circle through it is a loop on both sides
            Alt a = new Alt(sh(out), () -> asItem(c, out, () -> craft ? craft(c, out, lack, r) : smelt(c, out, lack, r)));
            a.id = out;
            a.longLabel = (craft ? "" : "smelting ") + sh(out) + " from " + ingredients(r);
            return a;
        }
        List<String> needs = new ArrayList<>();
        for (String sw : g.slots().keySet()) needs.add(shw(sw));
        Alt a = new Alt((craft ? "" : "smelting ") + shw(w.word) + " from " + String.join("+", needs), null);
        a.run = () -> makeGroup(c, w, lack, g, a);
        return a;
    }

    /** Runs {@code way} with {@code item} on the path (the loop guard of {@link #obtain}); the item already on it: the loop failure. */
    private static String asItem(Ctx c, String item, Supplier<String> way) {
        if (c.path.contains(item)) {
            c.fail = "loop: " + sh(item) + " needs itself (a recipe loop: " + String.join(" > ", c.path.stream().map(NeedChain::shw).toList()) + " > " + sh(item) + ")";
            c.loopOn = item;
            return null;
        }
        c.path.add(item);
        try {
            return way.get();
        } finally {
            c.path.remove(c.path.size() - 1);
        }
    }

    /**
     * Several members from one group's recipes: the need of each slot (tag rule), the station, the batches; the member is the
     * one whose recipe takes what went in (the bag decides), else the step names the tag and the craft fills it from the bag.
     */
    private static String makeGroup(Ctx c, Want w, int lack, Group g, Alt self) {
        boolean craft = ItemWorth.CRAFT.equals(g.kind());
        int batches = (lack + g.outCount() - 1) / g.outCount();
        List<String> parts = new ArrayList<>();
        Map<String, Integer> used = new LinkedHashMap<>(), slotUses = new LinkedHashMap<>();
        List<Map.Entry<String, Integer>> ins = new ArrayList<>(g.slots().entrySet());
        ins.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> rawUnseen(c, e.getKey()) ? 1 : 0));
        String verb = craft ? "craft: " : "smelt ";
        Burn fuel = null;
        int pieces = 0;
        for (var e : ins) {
            int want = e.getValue() * batches;
            pieces += e.getValue();
            Got got = needWord(c, e.getKey(), want);
            if (c.fail != null || got == null) { if (c.fail == null) c.fail = "no " + shw(e.getKey()) + " can be had"; return null; }
            parts.add(got.part());
            if (c.stuck) return verb + String.join(" + ", parts);
            take(c, new Want(e.getKey()), want, got.id()).forEach((k, v) -> used.merge(k, v, Integer::sum));
            slotUses.merge(word(e.getKey()), want, Integer::sum);      // 0.39.z61: the step's count by its slot word
        }
        if (craft) {
            boolean table = pieces > GRID_2X2;
            for (String m : g.made()) table |= ItemWorth.needsTable(m);
            if (table) {
                String t = station(c, ItemWorth.CRAFT);
                if (c.fail != null) return null;
                if (t != null) parts.add(t);
                if (c.stuck) return verb + String.join(" + ", parts);
                walkBack(c);
            }
        } else {
            String f = station(c, ItemWorth.SMELT);
            if (c.fail != null) return null;
            if (f != null) parts.add(f);
            if (c.stuck) return verb + String.join(" + ", parts);
            String burn = burn(c, batches);
            if (c.fail != null) return null;
            parts.add(burn);
            if (c.stuck) return verb + String.join(" + ", parts);
            fuel = c.burned;
            walkBack(c);
        }
        String out = outputOf(g, used);
        // 0.39.z76 J3-mod item 1 (run AM 21:39:44: "craft #planks 8" with 1 birch + 1 oak log: no one member makes 8, the craft
        // refused twice and the order parked): inputs that name several members are one craft per member they make
        Map<String, Integer> split = craft && out == null ? membersMade(g, used, batches, w.word) : null;
        if (split != null) {
            self.id = null;
            String slot = word(g.slots().keySet().iterator().next());
            for (var e : split.entrySet()) {
                int n = e.getValue() * g.outCount();
                c.steps.add(new Step.Craft(e.getKey().equals(w.word) ? shw(w.word) : sh(e.getKey()), n));
                used(c, Map.of(slot, e.getValue() * pieces));     // 0.39.z61: each craft's own count of the slot word
                c.bag.merge(e.getKey(), n, Integer::sum);
            }
            c.addS(Price.craftS() * batches);
            return verb + String.join(" + ", parts);
        }
        self.id = out;
        String name = out != null ? out : w.word;
        int made = batches * g.outCount();
        if (craft) {
            c.steps.add(new Step.Craft(out != null ? sh(out) : shw(w.word), made));
            c.addS(Price.craftS() * batches);
        } else {
            c.steps.add(new Step.Smelt(out != null ? sh(out) : shw(w.word), made, true));
            c.addS(batches * smeltTicks() / GameTimes.TPS);
        }
        if (fuel != null) slotUses.merge(FUEL_USE + fuel.word(), fuel.n(), Integer::sum);     // 0.39.z75 item 2
        used(c, slotUses);
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
        // 0.39.z78 D6: the need is a node (kept with what it read; reused while that holds)
        if (c.caching()) return (String) node(c, "obtain", List.of(item, n), () -> obtainWays(c, item, n));
        return obtainWays(c, item, n);
    }

    /** {@link #obtain} past the bag check: the ways of the item, priced. */
    private static String obtainWays(Ctx c, String item, int n) {
        int have = c.have(item);
        int lack = n - have;
        if (c.path.contains(item)) {
            c.fail = "loop: " + sh(item) + " needs itself (a recipe loop: " + String.join(" > ", c.path.stream().map(NeedChain::shw).toList()) + " > " + sh(item) + ")";
            c.loopOn = item;
            return null;
        }
        c.path.add(item);
        try {
            List<Alt> alts = itemAlts(c, item, lack);
            Alt up = beforeDown(c, sh(item), item, () -> itemAlts(c, item, lack));
            if (up != null) alts.add(up);
            Trial w = choose(c, alts, item);
            if (w != null) return w.phrase();
            if (waitsOnPaths(c)) return pathsWait(c, item);
            if (c.fail != null) return null;
            return exploreLeaf(c, item, noSource(c, item));
        } finally {
            c.path.remove(c.path.size() - 1);
        }
    }

    /**
     * 0.39.z75 item 3: a recipe way of a need runs with what the bag holds of the need set aside - its inner needs never count
     * those as had (the recipe makes the lack, it does not recount). A dig way recounts after its pickaxe (z37, S39: the
     * pickaxe may take the bag's own cobblestone, the dig breaks that many more) and runs without.
     */
    private static Supplier<String> aside(Ctx c, Want w, Supplier<String> way) {
        return () -> {
            Map<String, Integer> set = setAside(c, w);
            try {
                return way.get();
            } finally {
                putBack(c, set);
            }
        };
    }

    /** 0.39.z75 item 3: a tag's recipe group way, run with the tag's members in the bag set aside ({@link #aside}). */
    private static Alt asideAlt(Ctx c, Want w, Alt a) {
        a.run = aside(c, w, a.run);
        return a;
    }

    /** The ways of an item need (the recipes, the furnace, the known blocks, the animals, the strip, the cave, the base block). */
    private static List<Alt> itemAlts(Ctx c, String item, int lack) {
        List<Alt> alts = new ArrayList<>();
        Want self = new Want(item);
        for (ItemWorth.Recipe r : ItemWorth.recipes(item, ItemWorth.CRAFT)) {
            if (packing(item, r)) continue;
            alts.add(new Alt(sh(item) + " from " + ingredients(r), aside(c, self, () -> craft(c, item, lack, r))));
        }
        for (ItemWorth.Recipe r : ItemWorth.recipes(item, ItemWorth.SMELT)) alts.add(new Alt("smelting " + sh(item) + " from " + ingredients(r), aside(c, self, () -> smelt(c, item, lack, r))));
        alts.addAll(sourceAlts(c, item, item, lack, c.w.seenAll().apply(item)));
        alts.addAll(huntAlts(c, item, lack));
        if (c.w.seenAll().apply(item).isEmpty()) alts.addAll(stripAlts(c, item, item, lack));   // 0.39.z43: mine at the ore's level
        alts.addAll(caveAlts(c, item, item, lack));    // 0.39.z44: mine in the cave; 0.39.z77 cave: beside any seen ore too
        alts.addAll(baseAlts(c, item, lack));                                                  // 0.39.z74 item 4: dig down to the base block
        return alts;
    }

    /** 0.39.z75 item 1: the label start of a way met before the chain's leg to another level. */
    public static final String BEFORE_DOWN = "before going down";

    /**
     * 0.39.z75 item 1 (rule 1: an option with its price, no scripted "always carry"; run AE 17:29:44: the iron pickaxe's table
     * left 47 blocks up, AF 18:12:45: the sticks' logs on the surface): while the chain is down there ({@code c.down}), a
     * need is met where the bot is (its walks not known from there, {@link #walk}) or - this way - before the leg that took
     * it down: the same ways priced where the walks are priced from, their steps put in before that leg, the items carried
     * down in the bag ("take the crafting_table" along, the logs cut first). Null: the chain is not down, or a way is
     * being priced before going down already (one level of it).
     */
    private static Alt beforeDown(Ctx c, String label, String self, Supplier<List<Alt>> ways) {
        if (c.down == null) return null;
        Alt a = new Alt(label + " " + BEFORE_DOWN, null);
        a.run = () -> {
            String leg = c.down;
            int at = c.downAt, s0 = c.steps.size();
            double away0 = c.away();
            c.down = null;                                   // priced where the walks are priced from: the base, before the leg
            c.setAway(0);
            c.hoisting = true;
            int moved = 0;
            // 0.39.z77 legs (send-back 2; run BK 10:57:13): up there, before the leg, the bag holds only what it held at the
            // leg's start - what the leg and the steps after it gain (the strip's ore, the smelt's ingots) is set aside while
            // these ways are priced (have() counts it out), so no step moved before the leg uses a later leg's output; what the
            // way met up there adds or takes is the leg start's bag from now on
            Map<String, Integer> late = new LinkedHashMap<>();
            if (c.upBag != null) for (var e : c.bag.entrySet()) {
                int k = c.have(e.getKey()) - c.upBag.getOrDefault(e.getKey(), 0);
                if (k > 0) late.put(e.getKey(), k);
            }
            for (var e : late.entrySet()) c.aside.merge(e.getKey(), e.getValue(), Integer::sum);
            Map<String, Integer> bag0 = new LinkedHashMap<>(c.bag);
            try {
                Trial t = choose(c, ways.get(), self);
                if (t == null) {
                    if (c.fail == null) c.fail = "no way stands before going down either" + (late.isEmpty() ? "" : " (up there the bag has none of what the legs below make: " + counted(late) + ")");
                    return null;
                }
                if (c.upBag != null) {
                    java.util.Set<String> keys = new LinkedHashSet<>(bag0.keySet());
                    keys.addAll(c.bag.keySet());
                    for (String k : keys) {
                        int d = c.bag.getOrDefault(k, 0) - bag0.getOrDefault(k, 0);
                        if (d != 0) c.upBag.merge(k, d, Integer::sum);
                    }
                }
                if (t.alt().id != null) a.id = t.alt().id;
                walkBack(c);                                 // back to where the leg starts, before it
                moved = c.steps.size() - s0;
                if (moved > 0) {
                    List<Step> mv = new ArrayList<>(c.steps.subList(s0, c.steps.size()));
                    c.steps.subList(s0, c.steps.size()).clear();
                    c.steps.addAll(at, mv);
                    Double mark = at + moved < c.steps.size() ? c.addedAt.get(c.steps.get(at + moved)) : null;
                    if (mark != null) for (Step s : mv) {
                        c.addedAt.put(s, mark);                  // their seconds go with the leg's (stepSeconds)
                        c.markFrom.put(s, c.steps.get(at + moved));   // 0.39.z78 D6: a mark copied from that step (re-keyed on a reuse)
                    }
                    for (var e : c.leafAt.entrySet()) {
                        int i = e.getValue();
                        if (i >= s0) e.setValue(at + (i - s0));
                        else if (i >= at) e.setValue(i + moved);
                    }
                }
                return BEFORE_DOWN + " (" + leg + "): " + t.phrase();
            } finally {
                putBack(c, late);
                c.down = leg;
                c.downAt = at + moved;
                c.setAway(away0);
                c.hoisting = false;
            }
        };
        return a;
    }

    /** A recipe that takes the item itself (a packing, a dye-over): not a way to make it. */
    static boolean packing(String item, ItemWorth.Recipe r) {
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
        Map<String, Integer> slotUses = new LinkedHashMap<>();
        for (var e : ins) {
            int want = e.getValue() * batches;
            Got g = needWord(c, e.getKey(), want);
            if (c.fail != null || g == null) { if (c.fail == null) c.fail = "no " + shw(e.getKey()) + " can be had"; return null; }
            parts.add(g.part());
            if (c.stuck) return "craft: " + String.join(" + ", parts);                 // something needed is not known: the chain ends at the explore
            takeAsStep(c, new Want(e.getKey()), want);                 // 0.39.z77 bagat: as the step's own check takes it
            slotUses.merge(word(e.getKey()), want, Integer::sum);      // 0.39.z61: the step's count by its slot word
        }
        if (ItemWorth.needsTable(item) || rec.inputs().size() > GRID_2X2) {
            String t = station(c, ItemWorth.CRAFT);
            if (c.fail != null) return null;
            if (t != null) parts.add(t);
            if (c.stuck) return "craft: " + String.join(" + ", parts);
            walkBack(c);
        }
        c.steps.add(new Step.Craft(sh(item), batches * rec.outCount()));
        used(c, slotUses);
        c.addS(Price.craftS() * batches);
        c.note(() -> "craft: " + sh(item) + " x" + batches * rec.outCount() + " (" + batches + " crafts): " + s(Price.craftS() * batches));
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
        takeAsStep(c, new Want(input), k);                         // 0.39.z77 bagat: as the step's own check takes it
        String f = station(c, ItemWorth.SMELT);
        if (c.fail != null) return null;
        if (f != null) parts.add(f);
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        String burn = burn(c, k);
        if (c.fail != null) return null;
        parts.add(burn);
        if (c.stuck) return "smelt " + String.join(" + ", parts);
        Burn fuel = c.burned;
        walkBack(c);
        c.steps.add(new Step.Smelt(sh(item), k * rec.outCount(), true));
        used(c, smeltUses(input, k, fuel));                        // 0.39.z61: the input by its word; z75 item 2: the planned fuel, counted
        c.addS(k * smeltTicks() / GameTimes.TPS);
        c.note(() -> "smelt " + sh(item) + " x" + k * rec.outCount() + ": " + s(k * smeltTicks() / GameTimes.TPS));
        c.bag.merge(item, k * rec.outCount(), Integer::sum);
        return "smelt " + String.join(" + ", parts);
    }

    static double smeltTicks() { return Tuning.get("craftJob.smeltTicks"); }

    /** 0.39.z75 item 2: the prefix of a smelt step's planned fuel in its uses ("fuel minecraft:coal" -> 1); StepNeeds.withUses reads it. */
    public static final String FUEL_USE = "fuel ";

    /**
     * 0.39.z75 item 2 (run AF 18:12:51 / 18:21:46: the smelt's fuel was only "any burn ticks" in its needs): a smelt step's
     * uses - its input by its word and the fuel the chain planned for it ({@code fuel}; null: none named), so the fuel is a
     * counted need of its step like any consume (StepNeeds.withUses, Reservations, DryRun).
     */
    static Map<String, Integer> smeltUses(String input, int k, Burn fuel) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put(input, k);
        if (fuel != null && fuel.word() != null && fuel.n() > 0) out.merge(FUEL_USE + fuel.word(), fuel.n(), Integer::sum);
        return out;
    }

    /** 0.39.z75 item 2: a planned burn - the fuel's need word and its count. */
    record Burn(String word, int n) {}

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
        // 0.39.z47 (S61): in the bag, the fuel the smelt step burns (CraftPlanner.pickFuel: any item with a burn time, the
        // cheapest by worth), so the chain says the fuel the furnace gets
        c.burned = null;
        Map<String, Integer> free = new LinkedHashMap<>();         // z75 item 3: less what an outer need set aside
        // 0.39.z78 D6: the bag's fuels, scanned - pickFuel and the kept fuels skip every item with no burn ticks, so the pick is the same
        for (String id : c.bag.scan(NeedChain::burns, FUELS_SCAN)) if (c.have(id) > 0) free.put(id, c.have(id));
        // 0.39.z77 trip: the fuel the trip burned before, while the bag covers it (keepingFuel)
        var kept = keptFuel(free, k);
        var inBag = kept != null ? kept : io.github.mojolowjo.entropybot.craft.CraftPlanner.pickFuel(free, k);
        if (inBag != null && have(c, new Want(inBag.id())) >= inBag.count()) {
            c.burned = new Burn(word(inBag.id()), inBag.count());
            return "burn " + counted(take(c, new Want(inBag.id()), inBag.count(), inBag.id())) + (kept != null ? " (in the bag, the fuel this trip burned before)" : " (in the bag, the cheapest burn by worth)");
        }
        List<Alt> alts = new ArrayList<>(), woodAlts = new ArrayList<>();
        Map<Alt, Double> wood = new java.util.HashMap<>();
        for (var cls : classes.entrySet()) {
            // 0.39.z77 fuel: the class's members of more wood a piece than its least-wood member are out of its word
            var e = Map.entry(cls.getKey(), leastWood(c, cls.getKey(), cls.getValue()));
            int count = (int) Math.ceil(k * smeltTicks() / e.getKey());
            String cw = classWord(e.getValue());
            boolean plain = new Want(cw).item() || cw.indexOf('|') < 0;
            Alt a = new Alt("burn " + (plain ? shw(cw) : e.getValue().size() + " items of " + e.getKey() + " ticks"), null);
            a.run = () -> {
                // 0.39.z75 item 2 (run AF 18:21:43-46: "burn 2 acacia_fence|...|oak_planks|..." counted in the bag, then the
                // smelt "can't smelt 3 iron_ingot - no fuel ... nothing left in the bag burns"): the furnace job burns ONE kind
                // that covers the smelt and never a tool (CraftPlanner.pickFuel); a class held mixed in the bag is no burn - what
                // the bag holds that no single kind can join is not counted, the rest is made or fetched as one kind
                // z75 review 2: a kind the bag holds enough of is burned (no way priced); else the way makes the count anew
                // (what the bag holds of the class not counted) and one kind must cover it after, or the way fails, said
                String one = oneKind(c, e.getValue(), count);
                int mixed = have(c, new Want(cw));
                int ask = one != null ? count : mixed + count;
                Got g = needWord(c, cw, ask);
                if (g == null) return null;
                a.id = g.id();
                if (c.fail != null || c.stuck) return "burn " + g.part();
                String kind = one != null ? one : oneKind(c, e.getValue(), count);
                if (kind == null && c.have(word(cw)) >= count) kind = word(cw);    // the way's own token: one way made it (one source)
                if (kind == null) {
                    c.fail = "burn: no one kind of " + count + " in the bag after " + (g.phrase() == null ? "the bag" : g.phrase()) + " (the furnace burns one kind: CraftPlanner.pickFuel)";
                    return null;
                }
                take(c, new Want(kind), count, kind);
                a.id = kind;
                a.burned = new Burn(word(kind), count);
                return "burn " + (one != null ? count + " " + shw(kind) + " (in the bag)"
                        : count + " " + shw(kind) + (mixed > 0 ? " (one kind: the bag's " + mixed + " of the class not counted)" : "")
                        + (g.phrase() == null ? "" : " <- " + g.phrase()));
            };
            // 0.39.z51 item 4 (run L: a boat crafted for 3 smelts): a class made of wood is priced by the wood it consumes
            // for this burn (FuelWood: the recipe data and the burn ticks; its least-wood member); a class with a member made
            // of no wood (coal in #coals) is had without wood and stays a plain way
            double wd = Double.POSITIVE_INFINITY;
            for (String m : e.getValue()) {
                double pm = io.github.mojolowjo.entropybot.craft.FuelWood.planks(m);
                if (Double.isInfinite(pm)) {                     // z53 review 1 (rule 15): unknown wood is "not known", never free
                    if (UNKNOWN_WOOD_SAID.add(cw))
                        com.mojang.logging.LogUtils.getLogger().info("[aa] fuel {}: wood not known ({}: recipe cycle or depth cut); stands as a plain way", cw, m);
                    wd = Double.POSITIVE_INFINITY; break;
                }
                if (pm <= 0) { wd = Double.POSITIVE_INFINITY; break; }
                wd = Math.min(wd, count * pm);
            }
            if (Double.isInfinite(wd)) alts.add(a);
            else { wood.put(a, wd); woodAlts.add(a); }
        }
        // the least-wood classes stand with the non-wood ones; a class consuming more wood is dropped by that fact once a
        // lesser one stood (priced: it won, or lost on seconds to another way); one that failed lets the next by wood in
        java.util.TreeMap<Double, List<Alt>> levels = new java.util.TreeMap<>();
        for (Alt a : woodAlts) levels.computeIfAbsent(wood.get(a), x -> new ArrayList<>()).add(a);
        Trial win = null;
        if (levels.isEmpty()) win = choose(c, alts, null);
        Ctx.Snap before = c.snap();
        boolean first = true;
        for (var lv : levels.entrySet()) {
            if (!first) {                                       // the failed level's reasons stay listed
                List<String> said = new ArrayList<>(c.dropped);
                int had = before.dropped().size();               // 0.39.z78 D6: the lines up to then are the restored ones (only appends)
                c.restore(before);
                for (String d : said.subList(Math.min(had, said.size()), said.size())) if (!c.droppedHas(DROP_EXACT + d)) c.dropped.add(d);
            }
            first = false;
            List<Alt> tryAlts = new ArrayList<>(alts);
            tryAlts.addAll(lv.getValue());
            win = choose(c, tryAlts, null);
            Alt lead = null;
            for (Alt a : lv.getValue()) if ((win != null && win.alt() == a) || stoodPriced(c, a.label)) { lead = a; break; }
            if (lead == null) continue;
            for (var more : levels.tailMap(lv.getKey(), false).values())
                for (Alt m : more)
                    drop(c, m.label, String.format(java.util.Locale.ROOT, "uses %.1f planks of wood vs %.1f (%s)", wood.get(m), lv.getKey(), lead.label));
            break;
        }
        if (win == null) {
            if (c.fail == null) c.fail = "nothing of the fuel table can be had";
            return null;
        }
        c.burned = win.alt().burned;
        return win.phrase();
    }

    /**
     * 0.39.z77 fuel (run DH 04:38:11: "craft dark_oak_slab 114 > craft composter 16" for 24 smelts, where 16 planks burn the
     * same): the members of one burn class burn the same ticks a piece, so the wood a piece ({@link
     * io.github.mojolowjo.entropybot.craft.FuelWood#planks}, from the recipe data) is the wood per burn tick. A member made of
     * known wood that consumes more a piece than the class's least-wood member is left out of the class's word (the way then
     * makes the least-wood member; which member a tag way makes is chosen by seconds alone, so it must not be offered), said
     * in the dropped list. Members of no wood or of unknown wood stay (nothing to compare: rule 15). No wood member: as is.
     */
    private static Set<String> leastWood(Ctx c, int ticks, Set<String> items) {
        Map<String, Double> wood = new java.util.HashMap<>();
        double least = Double.POSITIVE_INFINITY;
        for (String m : items) {
            double p = io.github.mojolowjo.entropybot.craft.FuelWood.planks(m);
            wood.put(m, p);
            if (p > 0 && !Double.isInfinite(p)) least = Math.min(least, p);
        }
        if (Double.isInfinite(least)) return items;
        Set<String> kept = new TreeSet<>();
        Map<String, Double> out = new TreeMap<>();
        for (String m : items) {
            double p = wood.get(m);
            if (p > least + 1e-9 && !Double.isInfinite(p)) out.put(m, p);   // structural: float epsilon
            else kept.add(m);
        }
        if (out.isEmpty()) return items;
        List<String> said = new ArrayList<>();
        for (var o : out.entrySet()) {
            if (said.size() == 6) { said.add("+" + (out.size() - 6) + " more"); break; }   // structural: the line's length
            said.add(String.format(java.util.Locale.ROOT, "%s %.1f", sh(o.getKey()), o.getValue()));
        }
        drop(c, "burn " + ticks + " ticks", String.format(java.util.Locale.ROOT,
                "%d items left out by the planks of wood a piece vs %.1f (the least of the class): %s", out.size(), least, String.join(", ", said)));
        return kept;
    }

    /**
     * 0.39.z75 item 2: the member of {@code members} the bag holds at least {@code count} of for a burn, the most held -
     * one kind (the furnace job loads one), never a damageable item (a tool burns away its worth: CraftPlanner.pickFuel
     * refuses it), less what is set aside. Null: none.
     */
    private static String oneKind(Ctx c, Set<String> members, int count) {
        String best = null;
        for (String m : members) {
            String id = id(m);
            if (ItemWorth.damageable(id) || c.have(id) < count) continue;
            if (best == null || c.have(id) > c.have(best)) best = id;
        }
        return best;
    }

    /** 0.39.z51: the way {@code label} was priced and lost on seconds (its dropped line says "dearer than"). */
    private static boolean stoodPriced(Ctx c, String label) {
        return c.droppedHas(DROP_DEARER + label + ": ");    // 0.39.z78 D6 (option C): a read of the dropped lines (a fact before the node)
    }

    /**
     * A burn class as one need word: one item, or the tag its items are exactly ({@link #cover}), else the class's items
     * themselves ("any of these items": the fuel table's own set of the same burn ticks, items only, never tags joined).
     */
    private static String classWord(Set<String> items) {
        if (items.size() == 1) return word(items.iterator().next());
        Set<String> words = new TreeSet<>();
        for (String i : items) words.add(word(i));
        String tag = cover(words);
        return tag != null ? tag : word(String.join("|", words));
    }

    /**
     * The station a recipe kind needs (the crafting table, the furnace: the game's recipe data names it) is an item like any
     * other: placed and known -> used (0 s); in the bag or made by the same recursion -> "place it near me" (Baritone's
     * builder picks the cell; if it cannot, the step fails with its reason). Returns the phrase part ("table <- in the bag,
     * placed", "at the table 20 m"), null when the station is in reach with nothing to say.
     * station-memory (0.39.z14): a known station with a position ({@link World#stationAt}) is used wherever it is: its walk
     * is priced once (the walk from the last source or from here, the longer of the two) and the craft / smelt step's own
     * use walks it (InteractProcess); a new one is made only when none is known.
     */
    /** 0.39.z30: the mark on a step that uses a remembered station whose chunk is not loaded (aa why, aa debug). */
    public static final String UNVERIFIED = "unverified: station not loaded";

    private static String station(Ctx c, String kind) {
        boolean table = ItemWorth.CRAFT.equals(kind);
        String label = table ? "table" : "furnace";
        if (c.down != null) return downStation(c, kind, table, label);
        if (table ? c.tableReady : c.furnaceReady) {
            boolean walkedBefore = table ? c.tableWalked : c.furnaceWalked;
            Spot far = walkedBefore ? null : c.w.stationAt().apply(kind);
            if (far != null && far.dist() > stationNearM()) return farStation(c, kind, table, label, far);
            String k = knownWalk(c, kind, table, label);
            if (c.fail != null) return null;                // 0.39.z41: its walk is not a price (path not known, unreachable)
            c.note(() -> "station " + label + ": " + (k != null ? (k.contains(UNVERIFIED) ? "not loaded, remembered, " : "known, ") + k : walkedBefore ? "known, walked to before in this chain" : "in reach"));
            return k;
        }
        c.note(() -> "station " + label + ": none known: to make or take from the bag and place near me");
        return placeNew(c, kind, table, label);
    }

    /**
     * 0.39.z75 item 1 (run AE 17:29:44: "crafting table at -754 63 -236 unusable: ... no path ... from -755 16 -236", the
     * craft priced 0 s because the table was walked to before the strip): the station a step needs after the leg to another
     * level. One placed down there in this chain: in reach. The base's (known, placed or in reach there): the climb back to
     * it is not priced ({@link #fromBelow}, rule 15) - listed as dropped; the way that stands is one placed down there, its
     * item from the bag, made from what the bag carries, or met before going down (the base's taken along, made up there:
     * {@link #beforeDown}) - each priced, the cheapest wins (rule 1).
     */
    private static String downStation(Ctx c, String kind, boolean table, String label) {
        if (table ? c.tableDown : c.furnaceDown) {
            c.note(() -> "station " + label + ": placed down there before in this chain");
            return null;
        }
        boolean up = (table ? c.tableReady : c.furnaceReady) || c.w.stationAt().apply(kind) != null;
        if (up) drop(c, "the " + label + " up there", fromBelow(c.down));
        c.note(() -> "station " + label + ": " + (up ? "the one up there is not priced from down here; " : "") + "one placed near me down there");
        return placeNew(c, kind, table, label);
    }

    /** 0.39.z33: a known station farther than this many blocks (straight line) is priced against placing a new one near me. */
    public static double stationNearM() { return Tuning.get("station.nearM"); }

    /**
     * 0.39.z33 (owner 2026-10-23, S37, rule 1): a known station beyond {@link #stationNearM} is one way; making (or taking from
     * the bag) and placing a new one near me is the other. Both are priced on the same simulated bag; the cheaper stands, the
     * other is listed dropped with its seconds. Rule 15 unchanged: a station not loaded is priced as remembered, unverified.
     */
    private static String farStation(Ctx c, String kind, boolean table, String label, Spot far) {
        Ctx.Snap s0 = c.snap();
        double t0 = c.seconds;
        String walk = knownWalk(c, kind, table, label);
        String walkFail = c.fail;                           // 0.39.z41: the walk is not a price (path not known, unreachable)
        double walkS = c.seconds - t0;
        Ctx.Snap walked = c.snap();
        c.restore(s0);
        String place = placeNew(c, kind, table, label);
        double placeS = c.seconds - t0;
        boolean placeStands = c.fail == null && !c.stuck;
        String pw = "place a new " + label + " near me", kw = "walk to the known " + label + " " + Math.round(far.dist()) + " m" + (far.unloaded() ? " (" + UNVERIFIED + ")" : "")
                + (c.w.isMain(far) ? " (" + Nearby.MAIN + " -" + Math.round(Math.max(0, far.bonusS())) + " s)" : "");
        if (placeStands && walkFail != null) {
            drop(c, kw, walkFail);
            c.note(() -> "station " + label + ": " + kw + " " + walkFail + " vs " + pw + " " + s(placeS) + " -> placed");
            return place;
        }
        if (walkFail != null) {
            String pf = c.fail != null ? c.fail : "its materials end in an explore";
            c.restore(walked);
            drop(c, pw, pf);
            c.fail = walkFail;
            return null;
        }
        if (placeStands && placeS < walkS) {
            drop(c, kw, s(walkS));
            c.note(() -> "station " + label + ": " + kw + " " + s(walkS) + " vs " + pw + " " + s(placeS) + " -> placed");
            return place;
        }
        String why = placeStands ? s(placeS) : c.fail != null ? c.fail : "its materials end in an explore";
        c.restore(walked);
        drop(c, pw, why);
        c.note(() -> "station " + label + ": " + kw + " " + s(walkS) + " vs " + pw + " " + why + " -> walked");
        return walk;
    }

    /** The station made (or taken from the bag) and placed near me (Baritone's builder picks the cell). */
    private static String placeNew(Ctx c, String kind, boolean table, String label) {
        String item = ItemWorth.stationOf(kind);
        if (item == null) { c.fail = "the game's data names no station for " + kind + " recipes"; return null; }
        String made = obtain(c, item, 1);
        if (c.fail != null) return null;
        if (c.stuck) return made == null ? null : label + " <- " + made;
        // 0.39.z67 item 1 (run Y 09:26:14 / 09:31:16): placing near me failed where the bot stands (a held fact for the
        // order): the station goes near a cell with room from facts, reached by its priced walk (rule 18); Baritone still
        // picks the cell there (rule 2)
        String room = null;
        if (c.w.rooms().hereFailed() != null && !c.atRoom && prefixRead(c) && placesHere(c.steps)) {
            room = roomWalk(c, item);
            if (room == null) return null;
        }
        c.steps.add(new Step.PlaceNear(sh(item)));
        c.addS(Price.placeS());
        c.note(() -> "place " + sh(item) + " near me: " + s(Price.placeS()));
        c.bag.merge(item, -1, Integer::sum);
        if (c.down != null) { if (table) c.tableDown = true; else c.furnaceDown = true; }        // 0.39.z75: down there, not at the base
        else if (table) { c.tableReady = true; c.tableWalked = true; } else { c.furnaceReady = true; c.furnaceWalked = true; }
        c.setAway(0);                                       // placed near where the bot stands
        return label + " <- " + (made == null ? "in the bag, placed" : made + ", placed") + (room == null ? "" : " " + room);
    }

    /**
     * 0.39.z67 item 1 (rule 1, 18): placing near me failed here: every cell with room the world knows ({@link World#rooms}:
     * solid ground stood on, known stations; none within place.failedNearM of a held failure) priced by Baritone's complete
     * path; the cheapest stands - its walk ("goto x y z near place.roomNear") goes before the place-near, the losers are
     * noted. Null with {@code c.fail}: a walk not known yet (the chain waits on it, as any walk), or no cell with room known
     * or reachable (the readable fact). Returns the phrase part ("near the cell with room at x y z, walk 12.0 s (path)").
     */
    /**
     * 0.39.z67 item 1: no step before the place moves the bot (only crafts, smelts, cooks, places, waits, eats, drops), so
     * the place-near would run where the bot stands now - where the held failure applies. Pure.
     */
    static boolean placesHere(List<Step> before) {
        for (Step s : before)
            if (!(s instanceof Step.Craft || s instanceof Step.Smelt || s instanceof Step.Cook || s instanceof Step.PlaceNear
                    || s instanceof Step.Wait || s instanceof Step.Eat || s instanceof Step.DropJunk)) return false;
        return true;
    }

    private static String roomWalk(Ctx c, String item) {
        String here = c.w.rooms().hereFailed();
        List<Spot> cells = new ArrayList<>(c.w.rooms().cells());
        cells.sort(Comparator.comparingDouble(Spot::dist));
        Spot best = null;
        Walked bestW = null;
        String notKnown = null;
        List<String> out = new ArrayList<>();
        for (Spot sp : cells) {
            Walked wk = walk(c, sp.x(), sp.y(), sp.z(), sp.dist());
            String f = c.fail;
            c.fail = null;
            if (f != null) {
                out.add(sp.x() + " " + sp.y() + " " + sp.z() + ": " + f);
                if (notKnown == null && wk.kind() != PathPrice.Kind.UNREACHABLE) notKnown = f;
                continue;
            }
            if (bestW == null || wk.seconds() < bestW.seconds()) { best = sp; bestW = wk; }
        }
        if (best == null) {
            String why = "place " + sh(item) + " near me: " + here + "; no cell with room to place it near"
                    + (cells.isEmpty() ? " is known (no solid ground stood on away from there, no known station)" : ": " + String.join("; ", out));
            c.fail = notKnown != null ? notKnown : why;
            c.note(() -> why);
            return null;
        }
        final Spot at = best;
        final Walked w = bestW;
        for (Spot sp : cells) if (sp != at) {
            Walked o = c.walk(sp.x() + "," + sp.y() + "," + sp.z());
            if (o != null && o.fail() == null) drop(c, "place " + sh(item) + " near the cell with room at " + sp.x() + " " + sp.y() + " " + sp.z(), s(o.seconds()));
        }
        c.steps.add(new Step.Walk(at.x(), at.y(), at.z(), roomNear()));
        c.addS(w.seconds());
        c.atRoom = true;
        c.note(() -> "place " + sh(item) + ": placing near me failed here (" + here + "): near the cell with room at " + at.x() + " " + at.y() + " " + at.z() + ", " + s(w.seconds()));
        return noted("near the cell with room at " + at.x() + " " + at.y() + " " + at.z(), w);
    }

    /**
     * station-memory: the first use of a known station in this chain prices the walk to it: the longer of the walk back
     * from the last source ({@code away}) and the walk from here to the station (its path, or its line); then the bot is
     * at it ({@code away} 0). "at the table 20 m". Null: no position known, or already walked to in this chain.
     */
    private static String knownWalk(Ctx c, String kind, boolean table, String label) {
        if (table ? c.tableWalked : c.furnaceWalked) return null;
        Spot at = c.w.stationAt().apply(kind);
        if (at == null) return null;
        if (table) c.tableWalked = true; else c.furnaceWalked = true;
        Walked wk = walk(c, at.x(), at.y(), at.z(), at.dist());
        if (c.fail != null) return null;                    // 0.39.z41: no price for the walk: the way cannot be picked
        // 0.39.z65 item 1 (run V 07:15:19: "no path to the crafting_table ... (from -261 16 22)", the table 59 up): the walk the
        // price searched is a step of its own - Baritone's complete path to stand by the station (placing at the walk's
        // penalty, z63's ClimbGoal steering a climb), never the use's own search; a walk of 0 s (already by it): no step
        String item = ItemWorth.stationOf(kind);
        if (wk.seconds() > 0) c.steps.add(new Step.ToStation(kind, item == null ? label : sh(item), at.x(), at.y(), at.z()));
        double walkS = Math.max(c.away(), wk.seconds());
        double bonus = Math.min(walkS, Math.max(0, at.bonusS()));       // a use-stations station: seconds off its walk (never below 0)
        c.addS(walkS - bonus);
        c.setAway(0);
        // 0.39.z30, rule 15: a remembered station in a chunk not loaded is priced as remembered; the step verifies it on
        // arrival (not the station: the fact becomes none and the order re-plans)
        boolean main = c.w.isMain(at);                                  // main-stations (0.39.z35): the owner's main of the kind
        return noted("at the " + label + " " + Math.round(at.dist()) + " m" + (main ? " (" + Nearby.MAIN + ")" : "")
                + (bonus > 0 ? " (" + (main ? Nearby.MAIN + (bonus > MainStations.bonusS() ? " + " + io.github.mojolowjo.entropybot.guard.AreaTags.USE_STATIONS : "") : io.github.mojolowjo.entropybot.guard.AreaTags.USE_STATIONS) + " -" + Math.round(bonus) + " s)" : "")
                + (at.unloaded() ? " (" + UNVERIFIED + ")" : ""), wk);
    }

    /** Back to the stations after a trip to a new source: the same walk as the trip there (its path, or its line). */
    private static void walkBack(Ctx c) {
        if (c.away() > 0) { c.addS(c.away()); c.setAway(0); }
    }

    /**
     * path-cost: one trip's walk. {@code note}: how the line says it ("walk 48.2 s (path)"); {@code fail}: 0.39.z41, why it
     * is not a price ("walk: path not known yet (computing)", "walk: path not known (chunks not loaded)", "unreachable
     * (Baritone: ...)"), null with a price; {@code kind}: the provider's answer kind.
     */
    record Walked(double seconds, String note, String fail, PathPrice.Kind kind, int pickBreaks, Map<String, Integer> dug) {
        Walked(double seconds, String note, String fail, PathPrice.Kind kind) { this(seconds, note, fail, kind, 0, Map.of()); }
    }

    /** 0.39.z41: the way's text for a walk that is not a price (aa why / aa debug). */
    static final String COMPUTING = PathPrice.NOT_KNOWN + " yet (computing)";

    /**
     * path-cost: the walk to the cell at {@code dist} straight metres. 0.39.z41 (owner 2026-10-23): Baritone's COMPLETE path
     * (the world's {@link PathPrice}, each spot once a pass) or no price: a walk that is not known sets {@code c.fail} (the
     * way cannot be picked; the chain picks among the ways whose walks are known and the ways without one).
     */
    private static Walked walk(Ctx c, int x, int y, int z, double dist) { return walk(c, x, y, z, dist, false); }

    /** 0.39.z76 send-back 2: {@code into} - the feet end in the cell (the band walk, {@link PathPrice#priceInto}), not by it. */
    private static Walked walk(Ctx c, int x, int y, int z, double dist, boolean into) {
        if (c.down != null) {
            // 0.39.z75 item 1 (rule 15, rule 18; D4 1: the fork's priced path starts at the feet, never at a cell the chain
            // will reach later): a walk after the leg to another level is from where that leg leaves the bot - not priced,
            // never the feet's price (run AE: the table 1 m away priced as 1 m after "to y 16"). Not a wait either: no answer
            // will ever land for it before the bot is there; the way is dropped and the others compete (beforeDown).
            Walked out = new Walked(Double.NaN, "", fromBelow(c.down), PathPrice.Kind.NONE);
            walkNote(c, x, y, z, dist, out.fail(), out);
            c.fail = out.fail();
            return out;
        }
        String key = (into ? "in:" : "") + x + "," + y + "," + z;
        Walked out = c.walk(key);
        if (out != null) walkNote(c, x, y, z, dist, "priced before in this pass", out);
        else {
            PathPrice pp = c.w.path();
            PathPrice.Answer a = pp == null ? PathPrice.Answer.none("no path provider") : into ? pp.priceInto(x, y, z, dist) : pp.price(x, y, z, dist);
            out = estimateFloor(walked(a), dist);     // z76 send-back 3 review (rule 18): an estimate never beats the straight walk
            // 0.39.z77 round fix 2 (run BH): stood twice here with this walk still not known - it drops for the order (no
            // estimate, rule 15: never a price, never a third stand); the other ways compete
            if (out.fail() != null && out.fail().startsWith(PathPrice.NOT_KNOWN)) {
                String stood = stoodHere(c);
                if (stood != null) out = new Walked(Double.NaN, "", "walk: dropped, " + out.fail().substring("walk: ".length()) + " after the stands here (" + stood + ")", PathPrice.Kind.NONE);
            }
            c.walk(key, out);
            walkNote(c, x, y, z, dist, out.fail() == null ? "Baritone path, complete" : out.fail(), out);
        }
        if (out.fail() != null) c.fail = out.fail();
        return out;
    }

    /** 0.39.z75 item 1: the start of the reason a walk after a leg to another level is not priced. */
    public static final String FROM_BELOW = "walk from where ";

    /** 0.39.z75 item 1: why a walk after {@code leg} has no price (rule 15: "not known", never the feet's price). */
    static String fromBelow(String leg) {
        return FROM_BELOW + leg + " leaves the bot: not known (Baritone's search starts at the feet; a walk from a cell the bot will reach later is not priced, rule 15)";
    }

    /**
     * 0.39.z76 send-back 3 review (rule 18: a heuristic rank is never a price): an ESTIMATED walk is charged at least the walk of
     * the straight metres ({@code dist}, Unit.seconds) - Baritone's heuristic is a lower bound (0 s, or a target behind rock),
     * which must not beat a complete priced path - and keeps its "ranked, not priced" words. Any other walk as it is.
     */
    static Walked estimateFloor(Walked w, double dist) {
        if (w == null || w.kind() != PathPrice.Kind.ESTIMATED || !(dist >= 0)) return w;
        double floor = io.github.mojolowjo.entropybot.aa.brain.Unit.seconds(dist);
        if (w.seconds() >= floor) return w;
        return new Walked(floor, w.note() + " (at least the straight " + Math.round(dist) + " m: " + s(floor) + ")", null, w.kind(), w.pickBreaks(), w.dug());
    }

    /** One provider answer as the chain's walk: a complete path is a price, anything else says why it is not. */
    static Walked walked(PathPrice.Answer a) {
        String why = a.reason() == null || a.reason().isBlank() ? "" : a.reason();
        return switch (a.kind()) {
            case PRICED -> a.walk() == null || !(a.walk().seconds() >= 0) || Double.isInfinite(a.walk().seconds())
                    ? new Walked(Double.NaN, "", PathPrice.NOT_KNOWN + " (an unusable answer: " + a.walk() + ")", PathPrice.Kind.NONE)
                    : new Walked(a.walk().seconds(), "walk " + s(a.walk().seconds()) + " (path)" + lavaNote(a.walk()), null, PathPrice.Kind.PRICED, a.walk().pickBreaks(), a.walk().dug());
            case COMPUTING -> new Walked(Double.NaN, "", why.isEmpty() ? COMPUTING : PathPrice.NOT_KNOWN + " yet (computing; " + why + ")", a.kind());
            case CHUNKS -> new Walked(Double.NaN, "", PathPrice.NOT_KNOWN + " (" + (why.isEmpty() ? "chunks not loaded" : why) + ")", a.kind());
            case UNREACHABLE -> new Walked(Double.NaN, "", io.github.mojolowjo.entropybot.aa.moves.FactChange.unreachableText(why.isEmpty() ? "no path, the search ended" : why), a.kind());
            // z76 send-back 3 review (rule 15): no estimate after the wait is a way that drops, never another stand
            case NONE -> why.startsWith(PathPrice.NO_ESTIMATE) ? new Walked(Double.NaN, "", why, a.kind())
                    : new Walked(Double.NaN, "", PathPrice.NOT_KNOWN + " (" + (why.isEmpty() ? "no answer, no reason given" : why) + ")", a.kind());
            // 0.39.z76 send-back 3 (rule 18 amended, rule 22): past the first-answer wait the walk is taken by Baritone's estimate
            case ESTIMATED -> a.walk() == null || !(a.walk().seconds() >= 0) || Double.isInfinite(a.walk().seconds())
                    ? new Walked(Double.NaN, "", PathPrice.NOT_KNOWN + " (an unusable estimate)", PathPrice.Kind.NONE)
                    : new Walked(a.walk().seconds(), "walk ~" + s(a.walk().seconds()) + " (" + RANKED + ", not priced: Baritone's estimate after the first-answer wait; the full path is computed on the way)", null, a.kind());
        };
    }

    /** Lava distance (0.39.z40): " (+3.0 s lava detour)" when the walk's path paid the lava price (rule 14: rough), else "". */
    static String lavaNote(PathPrice.Walk a) {
        return a == null ? "" : io.github.mojolowjo.entropybot.guard.LavaNear.note(a.lavaS(), 1);
    }

    /** aa debug: one walk's line (where, how far, the estimate's kind, its seconds). */
    private static void walkNote(Ctx c, int x, int y, int z, double dist, String kind, Walked w) {
        c.note(() -> "walk to " + x + " " + y + " " + z + " (" + Math.round(dist) + " m straight): " + kind + ", " + s(w.seconds()));
    }

    private static String noted(String phrase, Walked w) { return w.note().isEmpty() ? phrase : phrase + ", " + w.note(); }

    // ---- the sources: a known block, a known animal ----

    /**
     * The ways known blocks give {@code gives} (asked for as {@code word}): one per known block (nearest others fill what it
     * lacks).
     */
    private static List<Alt> sourceAlts(Ctx c, String word, String gives, int lack, List<Spot> known) {
        List<Spot> spots = new ArrayList<>();
        List<Alt> alts = new ArrayList<>();
        for (Spot sp : known) {
            // 0.39.z48 (run J 12:05:35, S63): a known station block (the furnace I placed) is taken where it stands, by its
            // priced walk, never by the collect's search for any block of its kind; not loaded: its way is not known (rule 15)
            if (stationBlock(sp)) {
                if (sp.unloaded()) c.note(() -> "station " + shw(sp.block()) + " at " + sp.x() + " " + sp.y() + " " + sp.z() + ": " + PathPrice.NOT_KNOWN + " (its chunk is not loaded; rule 15)");
                else alts.add(new Alt("take the " + shw(sp.block()) + " " + Math.round(sp.dist()) + " m", () -> stationTake(c, word, gives, lack, sp)));
                continue;
            }
            spots.add(sp);
        }
        spots.sort(Comparator.comparingDouble(Spot::dist));
        Ranks rk = new Ranks(c, spots);                  // 0.39.z76 J3-mod item 2: rank rough, walk exact (rule 18)
        for (Spot first : rk.order()) {
            List<Spot> order = new ArrayList<>();
            order.add(first);
            for (Spot o : spots) if (o != first) order.add(o);
            Alt a = new Alt((tree(word, first) ? "tree " : shw(gives) + " ") + Math.round(first.dist()) + " m", rk.run(first, () -> sourceFrom(c, word, gives, lack, order)));
            a.rk = rk;                                       // 0.39.z78 D6
            alts.add(a);
        }
        return alts;
    }

    // ---- 0.39.z76 J3-mod item 2: rank rough, walk exact (rule 18 amended 2026-10-25 late evening) ----

    /** The start of a way's text that was ranked, not priced (aa why, the dropped list). */
    public static final String RANKED = "ranked";

    /**
     * 0.39.z76 J3-mod item 2 (rule 18 amended: "ten searches before the first step were the 2-5 s freezes"): the known
     * sources of one need in rank order - Baritone's estimate from the feet ({@link PathPrice#estimate}: its goal heuristic,
     * no search) when every source has one, else the straight metres (the lookup's order) - and the budget of full priced
     * paths: the ways run in that order and only the first {@code pathCost.pricedTop} whose walk was asked (priced, or not
     * known yet) get a priced path; a way whose walk Baritone found unreachable, or that failed before any walk, frees its
     * place for the next. The rest are "ranked": not asked, never a price (their walk is told to the provider as ranked
     * out, which withdraws a pending ask of it nobody asked again). The way the bot takes always has its complete path.
     */
    private static final class Ranks {
        private final Ctx c;
        private final List<Spot> order;
        private final int top = PathPrice.pricedTop();
        private int asked;

        Ranks(Ctx c, List<Spot> byDist) {
            this.c = c;
            this.order = rank(c.w.path(), byDist);
        }

        List<Spot> order() { return order; }

        /** The way of {@code first} run within the budget: past it, "ranked" (no walk asked, c.fail says why). */
        Supplier<String> run(Spot first, Supplier<String> way) {
            return () -> {
                if (asked >= top) {
                    PathPrice pp = c.w.path();
                    if (pp != null) c.effect(new RankedOut(first.x(), first.y(), first.z()));
                    c.fail = RANKED + " number " + (order.indexOf(first) + 1) + " of " + order.size() + " by Baritone's estimate (no search, not a price): only the top "
                            + top + " ways get a priced path (pathCost.pricedTop, rule 18)";
                    return null;
                }
                String p = way.get();
                Walked wk = c.walk(first.x() + "," + first.y() + "," + first.z());
                if (wk != null && wk.kind() != PathPrice.Kind.UNREACHABLE && (wk.fail() == null || !wk.fail().startsWith(PathPrice.NO_ESTIMATE))) asked++;   // z76: no estimate frees it too
                return p;
            };
        }
    }

    /** The spots by Baritone's estimate from the feet when every one has one, else as given (straight metres); ties keep the given order. Pure. */
    static List<Spot> rank(PathPrice pp, List<Spot> byDist) {
        if (pp == null || byDist.size() < 2) return byDist;
        Map<Spot, Double> est = new java.util.IdentityHashMap<>();
        for (Spot sp : byDist) {
            double e = pp.estimate(sp.x(), sp.y(), sp.z());
            if (Double.isNaN(e)) return byDist;          // rule 15: one not known - the straight metres for all (one unit)
            est.put(sp, e);
        }
        List<Spot> out = new ArrayList<>(byDist);
        out.sort(Comparator.comparingDouble(est::get));   // stable: a tie keeps the straight-metres order
        return out;
    }

    /** 0.39.z48: the spot's block is the station of a recipe kind (the game's recipe data names it: the table, the furnace). */
    static boolean stationBlock(Spot sp) {
        if (sp.block() == null) return false;
        String b = sp.block().contains(":") ? sp.block() : "minecraft:" + sp.block();
        for (String kind : List.of(ItemWorth.CRAFT, ItemWorth.SMELT)) {
            String st = ItemWorth.stationOf(kind);
            if (st != null && st.equals(b)) return true;
        }
        return false;
    }

    /**
     * 0.39.z48 (S63): take the known station block at {@code sp}: the pickaxe its block asks for first, the priced walk there
     * (Baritone's complete path, or no price: the way cannot be picked), its break, one item into the bag; the rest of the
     * need from the explore when one is not enough.
     */
    private static String stationTake(Ctx c, String word, String gives, int lack, Spot sp) {
        int tier = tierOf(word, sp);
        if (tier == MiningTiers.UNKNOWN) { c.fail = "the pickaxe tier of " + shw(sp.block()) + " is not known (no mining data loaded yet)"; return null; }
        String pre = null;
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null) return null;
            if (c.stuck) return "take the " + shw(sp.block()) + " " + Math.round(sp.dist()) + " m (after " + pre + ")";
        }
        Walked wk = walk(c, sp.x(), sp.y(), sp.z(), sp.dist());
        if (c.fail != null) {
            if (wk.kind() == PathPrice.Kind.UNREACHABLE) c.unreach(spotKey(word, sp), wk.fail());   // 0.39.z69 fix 1: that spot only
            return null;
        }
        c.steps.add(new Step.Walk(sp.x(), sp.y(), sp.z(), stationNear()));
        c.steps.add(new Step.TakeStation(shw(gives), sp.x(), sp.y(), sp.z()));
        c.addS(wk.seconds());
        c.setAway(wk.seconds());
        double each = breakS(c, gives, sp);
        c.addS(each);
        if (c.pickItem != null && tier >= 1) c.pickWorn += 1;       // game fact: a pickaxe loses 1 durability per block mined
        c.taken.merge(sp.key(), 1, Integer::sum);
        c.bag.merge(gives, 1, Integer::sum);
        // 0.39.z75 item 1: the base's own station taken (carried down): no station of the kind stands at the base any more
        for (String kind : List.of(ItemWorth.CRAFT, ItemWorth.SMELT)) {
            Spot at = c.w.stationAt().apply(kind);
            if (at == null || at.x() != sp.x() || at.y() != sp.y() || at.z() != sp.z()) continue;
            if (ItemWorth.CRAFT.equals(kind)) { c.tableReady = false; c.tableWalked = true; } else { c.furnaceReady = false; c.furnaceWalked = true; }
        }
        final String with = pre;
        c.note(() -> "take the " + shw(sp.block()) + " at " + sp.x() + " " + sp.y() + " " + sp.z() + ": break " + s(each) + (with == null ? "" : " with " + with));
        String how = noted("take the " + shw(sp.block()) + " " + Math.round(sp.dist()) + " m" + (pre == null ? "" : " with " + pre), wk);
        if (lack > 1) {
            String leaf = exploreLeaf(c, word, "not enough " + shw(word) + " known");
            return leaf == null ? null : how + " + " + leaf;
        }
        return how;
    }

    /** 0.39.z48: the walk before a station take ends within this many blocks of it (Tuning station.takeNear). */
    static int stationNear() { return Tuning.i("station.takeNear"); }

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
            else dig.computeIfAbsent(givesOf(c, sp, w), k -> new ArrayList<>()).add(sp);
        }
        List<Alt> alts = new ArrayList<>();
        Ranks rk = new Ranks(c, trees);                  // 0.39.z76 J3-mod item 2: rank rough, walk exact (rule 18)
        for (Spot first : rk.order()) {
            List<Spot> order = new ArrayList<>();
            order.add(first);
            for (Spot o : trees) if (o != first) order.add(o);
            String gives = givesOf(c, first, w);
            Alt a = new Alt("tree " + Math.round(first.dist()) + " m", rk.run(first, () -> sourceFrom(c, w.word, gives, lack, order)));
            a.id = gives;
            a.rk = rk;                                       // 0.39.z78 D6
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

    /**
     * What a spot of a tag need yields: what the lookup says (the loot data), else the one tag-member pick for its block
     * ({@link #memberOf}, 0.39.z76 J3-mod item 1: its drop's member, the block itself when a member), else the need's token.
     */
    private static String givesOf(Ctx c, Spot sp, Want w) {
        if (sp.gives() != null) return word(sp.gives());
        return memberOf(c, w.word, sp.block());
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
        if (s instanceof Step.TakeStation t) return "take at " + t.x() + " " + t.y() + " " + t.z();   // 0.39.z48
        if (s instanceof Step.ToStation t) return stationKey(t.x(), t.y(), t.z());                  // 0.39.z65 review 1
        if (s instanceof Step.ToBand b) return io.github.mojolowjo.entropybot.aa.moves.FactChange.bandKey(b.minY(), b.maxY());   // 0.39.z42
        // 0.39.z43 / z46: a strip that ended (lava, no path, no pickaxe) or the walk down to it; 0.39.z78 strip: held by its
        // heading at its Y, the one strip key every reader asks (stripHold)
        if (s instanceof Step.Strip st) return stripHold(st.key());
        if (s instanceof Step.ToStrip t) return stripHold(t.strip());
        if (s instanceof Step.ToSurface t) return surfaceKey(t.x(), t.y(), t.z());   // 0.39.z68: no way up: explore from here
        return null;
    }

    /** 0.39.z65 review 1: the source key of a known station used at its cell ("station at x y z"; a take is "take at"). */
    public static String stationKey(int x, int y, int z) { return STATION_AT + x + " " + y + " " + z; }

    /** 0.39.z65 review 1: the prefix of {@link #stationKey}. */
    public static final String STATION_AT = "station at ";

    /** 0.39.z77 walk: the prefix of {@link #walkTargetKey}. */
    public static final String WALK_TARGET = "walk target ";

    /**
     * 0.39.z77 walk (run BT 13:14:32): the source key of a walk target the order holds (the same walk ended short twice at
     * the same feet, FactChange.walkHeld): every walk the chain prices to that cell answers unreachable with the hold's
     * reason ({@link #heldWalks}), so the way that needs it drops and the next one competes.
     */
    public static String walkTargetKey(int x, int y, int z) { return WALK_TARGET + x + " " + y + " " + z; }

    /**
     * 0.39.z77 walk: {@code p} with the walk targets held in {@code keyWhy} ({@link #walkTargetKey}) priced unreachable with
     * their reason - the same answer Baritone's "no path" gives, so every way through that walk is dropped and listed with
     * the reason (rule 7); every other ask as {@code p}. No held target (or no provider): {@code p} itself. Pure.
     */
    static PathPrice heldWalks(PathPrice p, Map<String, String> keyWhy) {
        if (p == null || keyWhy == null) return p;
        Map<String, String> held = new java.util.HashMap<>();
        for (var e : keyWhy.entrySet()) if (e.getKey() != null && e.getKey().startsWith(WALK_TARGET)) held.put(e.getKey(), e.getValue());
        if (held.isEmpty()) return p;
        return new PathPrice() {
            private String why(int x, int y, int z) {
                String w = held.get(walkTargetKey(x, y, z));
                return w == null ? null : "the walk to " + x + " " + y + " " + z + " is held for this order: " + w;
            }
            @Override public Answer price(int x, int y, int z, double dist) { String w = why(x, y, z); return w != null ? Answer.unreachable(w) : p.price(x, y, z, dist); }
            @Override public Answer priceInto(int x, int y, int z, double dist) { String w = why(x, y, z); return w != null ? Answer.unreachable(w) : p.priceInto(x, y, z, dist); }
            @Override public double estimate(int x, int y, int z) { return why(x, y, z) != null ? Double.NaN : p.estimate(x, y, z); }
            @Override public Walk toBlock(int x, int y, int z) { return why(x, y, z) != null ? null : p.toBlock(x, y, z); }
            @Override public void rankedOut(int x, int y, int z) { p.rankedOut(x, y, z); }
            @Override public boolean pending() { return p.pending(); }
            @Override public long landed() { return p.landed(); }
            @Override public int firstAnswersPending() { return p.firstAnswersPending(); }
        };
    }

    /** The key of a spot of {@code item} (a need word; a tag's spot by the item it gives) (see {@link #sourceKey(Step)}). */
    public static String sourceKey(String item, Spot sp) {
        if (stationBlock(sp)) return "take at " + sp.x() + " " + sp.y() + " " + sp.z();     // 0.39.z48: a station is its cell
        return tree(item, sp) ? "cut at " + sp.x() + " " + sp.y() + " " + sp.z() : "collect " + shw(sp.gives() != null ? word(sp.gives()) : item);
    }

    /**
     * 0.39.z69 fix 1 (run AA 11:50: one cobblestone spot Baritone found unreachable held "collect cobblestone" for the
     * order's life, so no cobblestone source counted again and every explore for stone ended on nothing): the key of ONE
     * spot - a tree or a station is its cell already ({@link #sourceKey(String, Spot)}); a collect spot is its item AND its
     * cell ("collect cobblestone at 12 60 3"). A walk to the spot that Baritone found unreachable holds this key, so only
     * that spot is out for the order. The item key ("collect cobblestone") stays the key of a failed Collect step (the
     * fork's mine process takes any block of the item, its failure is about all of them).
     */
    public static String spotKey(String item, Spot sp) {
        String k = sourceKey(item, sp);
        return k.startsWith("collect ") ? k + " at " + sp.x() + " " + sp.y() + " " + sp.z() : k;
    }

    /** z75 review 2: the prefix of {@link #nearKey}. */
    public static final String NEAR = "near collect ";

    /**
     * z75 review 2 (run AI 20:10:05, z67 style): the source key of a collect that ended short at {@code at} - "near collect
     * raw_iron at 3 16 -160"; held for the order, the item's spots within failures.moved of there are out (the cluster it
     * emptied, the drop it could not reach), so the re-plan uses another source or another way.
     */
    public static String nearKey(String item, int[] at) {
        return NEAR + shw(word(item)) + " at " + at[0] + " " + at[1] + " " + at[2];
    }

    /** z75 review 2: the near holds among held keys, each {item word, x, y, z}. Pure. */
    static List<String[]> nearHolds(java.util.Collection<String> keys) {
        List<String[]> out = new ArrayList<>();
        for (String k : keys) {
            if (k == null || !k.startsWith(NEAR)) continue;
            String rest = k.substring(NEAR.length());
            int a = rest.lastIndexOf(" at ");
            if (a < 0) continue;
            String[] xyz = rest.substring(a + " at ".length()).trim().split(" ");
            if (xyz.length != 3 || !xyz[0].matches("-?\\d+") || !xyz[1].matches("-?\\d+") || !xyz[2].matches("-?\\d+")) continue;   // structural: x y z, written by nearKey
            out.add(new String[]{word(rest.substring(0, a)), xyz[0], xyz[1], xyz[2]});
        }
        return out;
    }

    /** z75 review 2: a spot of {@code item} lies within failures.moved of a near hold of that item. */
    static boolean nearHeld(List<String[]> near, String item, Spot sp) {
        if (near.isEmpty() || sp == null) return false;
        String gives = sp.gives() != null ? word(sp.gives()) : word(item);
        for (String[] h : near) {
            if (!h[0].equals(gives) && !h[0].equals(word(item)) && !gives(h[0], gives)) continue;
            int[] at = {Integer.parseInt(h[1]), Integer.parseInt(h[2]), Integer.parseInt(h[3])};     // checked digits (nearHolds)
            if (dist3(at, new int[]{sp.x(), sp.y(), sp.z()}) <= io.github.mojolowjo.entropybot.aa.brain.Failures.movedV()) return true;
        }
        return false;
    }

    /** 0.39.z69 fix 1: a spot is held by {@code held} under its item key (a failed Collect step) or its own spot key. */
    public static boolean spotHeld(String item, Spot sp, java.util.function.Predicate<String> held) {
        return held.test(sourceKey(item, sp)) || held.test(spotKey(item, sp));
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

    /** 0.39.z60 review 1: the words of a source whose structure fact is not loaded yet (rule 15: not known, waited for). */
    public static final String SOURCE_NOT_LOADED = "not known yet (its structure fact is not loaded; rule 15)";

    /**
     * How many of the item one block of {@code block} drops: the loot data's EXPECTED yield (LootBook: rolls x weight x the
     * random_chance / table_bonus conditions at the bot's unenchanted tool x the average count - leaves give a stick at about
     * 0.03 a block). 0.39.z74 item 1 (run AC 16:51:46: `collect stick 2` from leaves, 1 stick in ~90 s; rule 15): a block
     * the book holds no entry for gives 1 only when it is the item's own block (it drops itself, as Nearby keeps it), else
     * the yield is not known (NaN: the spot is left out, never priced as 1). No book in these facts (an older caller): 1.
     */
    private static double perBlock(Ctx c, String item, String block) {
        if (c.w.loot() == null || block == null) return 1;
        Want w = new Want(item);
        if (w.item()) {
            for (LootBook.Source s : c.w.loot().sources(item)) if (s.kind() == LootBook.Kind.BLOCK && s.from().equals(block) && s.per() > 0) return s.per();
            return block.equals(id(item)) ? 1 : Double.NaN;
        }
        // 0.39.z75 metric fix A (run AH 19:46:59: "strip ... for coal 0"), moved to the one tag-member pick in 0.39.z76 J3-mod
        // item 1: a tag need's word (#coals) has no loot entry of its own - its yield is the drop of the member the block gives
        LootBook.Source s = tagDrop(c, w, block);
        if (s != null) return s.per();
        return w.has(block) ? 1 : Double.NaN;      // a member's own block (a log for #logs)
    }

    // ---- 0.39.z76 J3-mod item 1: the one place a tag need becomes a member ----
    // (run AM 21:39:44: "craft #planks 8 ... no member of #planks can be made from what I have (11 members)" with 1 birch and
    // 1 oak log in the bag, then "made 4 birch_planks"; z75 metric fix A's #coals strip; run AH's #stone_tool_materials
    // explore). A tag need stays its word until something names the member: a block names it by its drop (tagDrop /
    // memberOf: coal_ore -> coal, stone -> cobblestone, a log -> itself), the inputs a recipe group took name it by the recipe
    // that takes them (membersMade: one member, or one craft per member the bag's inputs make). Read from the loot data, the
    // tag index and the recipes; no name list, no per-tag case.

    /** The loot source by which {@code block} drops a member of the tag need {@code w} (the book's first such entry); null: none, an item need, no book. */
    private static LootBook.Source tagDrop(Ctx c, Want w, String block) {
        if (w.item() || block == null || c.w.loot() == null) return null;
        for (LootBook.Source s : c.w.loot().sourcesMatching(w::has))
            if (s.kind() == LootBook.Kind.BLOCK && s.from().equals(block) && s.per() > 0) return s;
        return null;
    }

    /**
     * The item a block gives for a need word: an item word as it is; for a tag (or "a|b") the member the loot data says the
     * block drops ({@link #tagDrop}), else the block itself when it is a member (a log for #logs), else the word (no member
     * named: the step and the bag keep the tag's token).
     */
    private static String memberOf(Ctx c, String word, String block) {
        Want w = new Want(word);
        if (w.item() || block == null) return word;
        LootBook.Source s = tagDrop(c, w, block);
        if (s != null) return word(s.item());
        return w.has(block) ? block : word;
    }

    /**
     * The members a one-ingredient recipe group makes from what it took ({@code used}: item -> pieces; a slot's token for what a
     * way still has to get): each concrete input goes to the one member whose recipe takes it, the token to the need word (the
     * craft names the member from the bag when it runs); member (or the word) -> batches. Null: one member or none named
     * ({@link #outputOf} names it), a group of several slots, an input two members take, or a batch that would mix inputs.
     */
    static Map<String, Integer> membersMade(Group g, Map<String, Integer> used, int batches, String word) {
        if (g.slots().size() != 1) return null;
        int per = g.slots().values().iterator().next();
        if (per <= 0) return null;
        Map<String, Integer> pieces = new LinkedHashMap<>();
        for (var e : used.entrySet()) {
            String k = e.getKey(), to = null;
            if (!k.startsWith("#") && k.indexOf('|') < 0) {
                Set<String> outs = new LinkedHashSet<>();
                for (int i = 0; i < g.recs().size(); i++)
                    for (String sw : g.recs().get(i).needs()) if (new Want(sw).has(k)) { outs.add(g.outs().get(i)); break; }
                if (outs.size() == 1) to = outs.iterator().next();
            }
            if (to == null && !k.startsWith("#") && k.indexOf('|') < 0) return null;    // an input two members take (or none): not split
            pieces.merge(to == null ? word : to, e.getValue(), Integer::sum);
        }
        if (pieces.size() < 2) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        int sum = 0;
        for (var e : pieces.entrySet()) {
            if (e.getValue() % per != 0) return null;       // a batch would take two kinds: the game's recipe does not mix them
            out.put(e.getKey(), e.getValue() / per);
            sum += e.getValue() / per;
        }
        return sum == batches ? out : null;
    }

    /**
     * Break at the first spot (and on at the next while it has too few blocks), the walk priced in but no walk step (chop B2):
     * after the pickaxe tier the block asks for. {@code word}: the need (its explore when the known blocks fall short);
     * {@code gives}: what goes into the bag (the item, a tag need's member, or its token).
     */
    private static String sourceFrom(Ctx c, String word, String gives, int lack, List<Spot> order) {
        Spot first = order.get(0);
        // 0.39.z60 review 1 (rule 15): a source whose structure fact is not loaded yet (Nearby: "not loaded") is not known -
        // this way waits with the reason (said in the dropped list), never priced and never "no source"
        if (first.unloaded()) {
            c.fail = "source " + shw(blockOf(gives, first)) + " at " + first.x() + " " + first.y() + " " + first.z() + ": " + SOURCE_NOT_LOADED;
            return null;
        }
        boolean log = tree(word, first);
        String pre = null;
        Want asked = new Want(word);
        int haveBefore = have(c, asked);
        int tier = tierOf(word, first);
        if (tier == MiningTiers.UNKNOWN) { c.fail = "the pickaxe tier of " + shw(blockOf(gives, first)) + " is not known (no mining data loaded yet)"; return null; }
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null) return null;
            if (c.stuck) return "dig " + shw(gives) + " seen " + Math.round(first.dist()) + " m (after " + pre + ")";      // the pickaxe's own source is still to find: the chain ends at that explore
        }
        if (pre == null && tier >= 1) {
            pre = wornPickaxe(c, word, gives, lack, order, log, tier);
            if (c.fail != null) return null;
            if (c.stuck) return "dig " + shw(gives) + " seen " + Math.round(first.dist()) + " m (after " + pre + ")";
        }
        // 0.39.z37 (S39): a tool made first out of the asked item's own stock (the pickaxe takes 3 of the bag's cobblestone)
        // consumes before the collect counts: what it took is lacking again
        int remaining = lack + Math.max(0, haveBefore - have(c, asked));
        Walked firstWalk = null;
        // 0.39.z74 item 1 (rule 15): a spot whose block's yield of the item is not known is left out (said); none left: no way
        List<Spot> known = new ArrayList<>();
        for (Spot sp : order) {
            if (Double.isNaN(perBlock(c, gives, sp.block()))) c.note(() -> "spot " + sp.x() + " " + sp.y() + " " + sp.z() + " left out: the yield of " + shw(gives) + " from " + shw(sp.block()) + " is not known (no loot entry)");
            else known.add(sp);
        }
        if (known.isEmpty()) { c.fail = "the yield of " + shw(gives) + " from " + shw(blockOf(gives, first)) + " is not known (no loot entry; rule 15)"; return null; }
        for (Spot sp : known) {
            if (remaining <= 0) break;
            int[] tg = takeOf(c, gives, sp, remaining, log);
            int take = tg[0], got = tg[1];
            if (take <= 0) continue;
            boolean ore = !log && sp.block() != null && ItemWorth.hasTag(sp.block(), "c:ores");
            boolean whole = (log || ore) && sp.count() > 0;
            // the step's number: an ore cluster's yield (the job's count is the cluster's drops: a short pickup fails the
            // job); a cut keeps the logs asked (S17: the chop takes the whole tree whatever the number); else what is asked
            int wanted = ore && whole ? got : Math.min(remaining, got);
            Integer at = c.leafAt.get(sp.key());
            if (at != null) {
                Step prev = c.steps.get(at);
                c.steps.set(at, prev instanceof Step.ChopTree t ? new Step.ChopTree(t.at(), t.logs() + wanted, t.log())
                        : new Step.Collect(((Step.Collect) prev).item(), ((Step.Collect) prev).n() + wanted, ((Step.Collect) prev).at()));
            } else {
                // chop: no walk step before a block source (the cut/collect paths itself; the spot names the blocks and the price)
                c.leafAt.put(sp.key(), c.steps.size());
                c.steps.add(log ? new Step.ChopTree(new int[]{sp.x(), sp.y(), sp.z()}, wanted, null)
                        : new Step.Collect(shw(gives), wanted, new int[]{sp.x(), sp.y(), sp.z()}));   // z76 send-back 2: the priced spot named (run AP)
                c.note(() -> "source: known " + shw(blockOf(gives, sp)) + " at " + sp.x() + " " + sp.y() + " " + sp.z() + ", " + Math.round(sp.dist()) + " m" + (sp.count() > 0 ? ", " + sp.count() + " blocks" : ""));
                Walked wk = walk(c, sp.x(), sp.y(), sp.z(), sp.dist());
                if (c.fail != null) {                       // 0.39.z41: no price for this walk
                    if (wk.kind() == PathPrice.Kind.UNREACHABLE) c.unreach(spotKey(word, sp), wk.fail());   // the z33 source fact; z69 fix 1: that spot only
                    if (sp == first) return null;           // the way's own spot: the way cannot be picked
                    String why = c.fail;                    // a further spot: this way goes on without it (its walk is not a price)
                    c.fail = null;
                    c.leafAt.remove(sp.key());
                    c.steps.remove(c.steps.size() - 1);
                    c.note(() -> "spot " + sp.x() + " " + sp.y() + " " + sp.z() + " left out: " + why);
                    continue;
                }
                if (firstWalk == null) firstWalk = wk;
                c.addS(wk.seconds());
                c.setAway(wk.seconds());
            }
            double each = breakS(c, gives, sp);
            c.addS(each * take);
            final int took = take;
            c.note(() -> "break " + shw(blockOf(gives, sp)) + " x" + took + ": tool " + (c.pickItem == null || tierOf(gives, sp) < 1 ? "hand" : sh(c.pickItem)) + ", " + s(each) + " each = " + s(each * took));
            // 0.39.z74 item 1 (run AC 16:51:46: "stick 5 m: 11.9 s" won over "stick from #planks: 28.6 s"; the leaves gave 1 stick
            // in ~90 s): a block that gives the item only by chance (expected yield under 1 a block) is many scattered blocks,
            // each its own trip - every block after the first is charged the spot's walk again (the walk already priced; rule
            // 14: the same rough numbers, no finer term). A block that drops the item for sure is one patch, as before.
            double per = perBlock(c, gives, sp.block());
            Walked spotWalk = c.walk(sp.key());
            if (per < 1 && take > 1 && spotWalk != null && spotWalk.fail() == null && spotWalk.seconds() > 0) {
                double again = spotWalk.seconds() * (take - 1);
                c.addS(again);
                c.note(() -> shw(blockOf(gives, sp)) + " gives " + shw(gives) + " by chance (" + String.format(java.util.Locale.ROOT, "%.3f", per) + " a block, the loot data): " + (took - 1) + " more trips of " + s(spotWalk.seconds()) + " = " + s(again));
            }
            if (c.pickItem != null && tierOf(gives, sp) >= 1) c.pickWorn += take;     // 0.39.z33: one use a block (game fact: a pickaxe loses 1 durability per block mined)
            c.taken.merge(sp.key(), take, Integer::sum);
            c.bag.merge(gives, got, Integer::sum);
            remaining -= got;
        }
        String how = (log ? "cut tree" : "dig " + shw(gives)) + " seen " + Math.round(first.dist()) + " m" + (pre == null ? "" : " with " + pre);
        if (firstWalk != null) how = noted(how, firstWalk);
        if (remaining > 0) {
            String leaf = exploreLeaf(c, word, "not enough " + shw(word) + " known");
            return leaf == null ? null : how + " + " + leaf;
        }
        return how;
    }

    /**
     * The blocks a spot gives this way and the items they yield, {take, got}: S20 (owner 2026-10-22): the chop takes the whole
     * tree (the fork's ChopTree), so a counted tree is priced and bagged by all its logs, the surplus to stock; the next tree
     * only while the need is not covered. S26 (owner 2026-10-22, "a cluster of coal ore is one job"): an ore cluster the scan
     * counted is the same unit (the fork's component rule mines it whole); an uncounted spot (count 0) stays "as many blocks
     * as needed".
     */
    private static int[] takeOf(Ctx c, String gives, Spot sp, int remaining, boolean log) {
        double per = perBlock(c, gives, sp.block());
        int blocksWanted = (int) Math.ceil(remaining / per - 1e-9);  // structural: epsilon
        int avail = sp.count() <= 0 ? blocksWanted : sp.count() - c.taken.getOrDefault(sp.key(), 0);
        boolean ore = !log && sp.block() != null && ItemWorth.hasTag(sp.block(), "c:ores");
        boolean whole = (log || ore) && sp.count() > 0;
        int take = whole ? avail : Math.min(avail, blocksWanted);
        if (take <= 0) return new int[]{0, 0};
        int got = whole ? (int) Math.floor(take * per + 1e-9) : Math.min(remaining, (int) Math.floor(take * per + 1e-9));  // structural: epsilon
        // 0.39.z74 item 1: blocks whose expected yield is under one item give none here (was: one item a block, a sure drop)
        if (got <= 0) return new int[]{0, 0};
        return new int[]{take, got};
    }

    /**
     * 0.39.z33 (owner 2026-10-23, S38): the pickaxe in the bag wears one use a block; when the blocks this chain breaks with
     * it (the ones before and this way's) pass its uses left (the item's own data, {@link World#usesLeft}), a new pickaxe is
     * made first (the cheapest that digs {@code tier}, as the tier rule makes one) and priced in. Unknown durability (rule 15)
     * and none (unlimited) insert nothing; unknown is said. No new pickaxe to be had: the worn one stays, said. Returns the
     * new pickaxe's phrase, null: none inserted.
     */
    private static String wornPickaxe(Ctx c, String word, String gives, int lack, List<Spot> order, boolean log, int tier) {
        if (log || c.pickItem == null || c.pickMade) return null;
        String tool = c.pickItem;
        Integer u = c.w.usesLeft().apply(tool);
        int uses = u == null ? io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN : u;
        if (uses == io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN) { c.note(() -> sh(tool) + ": durability unknown"); return null; }
        if (uses == io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED) return null;
        int blocks = c.pickWorn, remaining = lack;
        for (Spot sp : order) {
            if (remaining <= 0) break;
            int[] tg = takeOf(c, gives, sp, remaining, log);
            if (tg[0] <= 0) continue;
            if (tierOf(gives, sp) >= 1) blocks += tg[0];
            remaining -= tg[1];
        }
        if (blocks <= uses) return null;
        final int total = blocks;
        Ctx.Snap before = c.snap();
        double s0 = c.seconds;
        c.bag.merge(tool, -1, Integer::sum);                       // it breaks before the job ends: out of the simulated bag
        c.pickItem = null;
        c.pickTier = 0;
        c.pickWorn = 0;
        for (String k : c.bag.scan(PICKAXES, PICKAXES_SCAN)) {      // 0.39.z78 D6: scanned (no other word raised the tier from 0)
            if (c.have(k) <= 0) continue;                    // z77 legs review: less what is set aside (a later leg's holdings up there)
            int t = MiningTiers.toolTier(k, "pickaxe");
            if (t > c.pickTier) { c.pickTier = t; c.pickItem = k; c.pickOut = false; }
        }
        String p = c.pickTier >= tier && c.pickItem != null && !c.pickItem.equals(tool) ? sh(c.pickItem) + " (in the bag)" : cheapestPickaxe(c, tier);
        if (c.fail != null) {
            String why = c.fail;
            c.restore(before);
            c.note(() -> sh(tool) + ": " + uses + " uses left, " + total + " blocks to break; no new pickaxe to be had (" + why + "): the worn one is used");
            return null;
        }
        double add = c.seconds - s0;
        c.note(() -> sh(tool) + ": " + uses + " uses left, " + total + " blocks to break -> a new pickaxe first (+" + s(add) + ")");
        return p;
    }

    /** The block a spot's break is priced on: its own id, else the item's own block. */
    private static String blockOf(String item, Spot sp) { return sp.block() != null ? sp.block() : item; }

    /**
     * The pickaxe tier the block asks for (0: none; a tree: none). A tag need's spot with no block named (a sighting) is
     * priced by the item it gives (its least dropping block's tier, the loot data), never by the tag word (no block: tier
     * "-1" read as 0 dropped the pickaxe before a coal-ore dig, owner-watched 2026-10-22). No mining data loaded at all:
     * {@link MiningTiers#UNKNOWN}, which the dig refuses (a missing fact is said, not priced as "no tool").
     */
    static int tierOf(String item, Spot sp) {
        if (tree(item, sp)) return 0;
        MiningTiers.Source src = MiningTiers.source();
        if (src == null) return MiningTiers.UNKNOWN;
        String gives = sp.gives() != null ? word(sp.gives()) : item;
        int t = sp.block() != null ? src.blockTier(id(sp.block())) : MiningTiers.UNKNOWN;
        if (t < 0 && gives != null && !gives.startsWith("#") && gives.indexOf('|') < 0) {
            t = src.blockTier(gives);                                             // the item's own block
            if (t < 0) t = src.dropTier(gives);                                   // the blocks that drop it
        }
        return Math.max(0, t);
    }

    /**
     * Seconds to break one block by the game's formula ({@link GameTimes#breakSeconds}: hardness x 30 / tool speed ticks,
     * x 100 and no drop without the tool a block asks for). No hardness known: the tuned break of an unknown block.
     */
    private static double breakS(Ctx c, String item, Spot sp) {
        // the hardness of the item's own block when it is one (cobblestone, a log: SCENARIOS prices "break cobblestone"), else the spot's (an ore)
        double own = ItemWorth.facts(item).hardness();
        double hardness = own > 0 ? own : ItemWorth.facts(blockOf(item, sp)).hardness();
        if (hardness <= 0) return TickPrice.breakS(tree(item, sp) ? WoodOptions.cutLog() : Tuning.get("needChain.unknownBreakS"));
        int tier = tierOf(item, sp);
        if (tier < 1) return TickPrice.breakS(GameTimes.breakSeconds(hardness, 1, true));
        double speed = c.pickItem == null ? 1 : Math.max(1, ItemWorth.facts(c.pickItem).digSpeed());
        return TickPrice.breakS(GameTimes.breakSeconds(hardness, speed, c.pickTier >= tier));
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
        // 0.39.z78 D6: scanned - a word whose attack is no more than the bare hit never raised it (NaN is kept in: it would)
        for (String k : c.bag.scan(HITS, HITS_SCAN)) {
            Integer v = c.bag.get(k);
            if (v != null && v > 0) dmg = Math.max(dmg, ItemWorth.facts(k).attack());
        }
        double killS = Math.ceil(hp / dmg - 1e-9) * HIT_S;  // structural: epsilon
        c.steps.add(new Step.HuntAt(s.from(), kills, new int[]{m.x(), m.z()}, sh(item), lack));
        Walked wk = walk(c, m.x(), m.y(), m.z(), m.dist());
        if (c.fail != null) return null;                    // 0.39.z41: no price for the walk
        c.addS(wk.seconds() + kills * killS);
        c.setAway(wk.seconds());
        c.bag.merge(item, lack, Integer::sum);
        return noted("hunt " + kills + " " + sh(s.from()) + " seen " + Math.round(m.dist()) + " m", wk);
    }

    /**
     * Game fact (vanilla SurfaceSystem#getSurfaceDepth: noise x 2.75 + 3 + a quarter of random, the noise about 0 on average):
     * the surface layer over the base block is about 3 blocks under the top block.
     */
    static final int SURFACE_LAYER = 3; // game fact: SurfaceSystem's surface depth averages 3 blocks

    /**
     * 0.39.z74 item 4 (run AD 17:10:08: charcoal's furnace needed "8 #stone_tool_materials <- explore ... (where it generates
     * is not known: stone is placed on the ground's top ...)", then the only torch way left was a 173 s coal strip): the
     * dimension's base block (its noise settings' default block, {@link io.github.mojolowjo.entropybot.aa.brain.GenWhere.Base})
     * is under the surface everywhere - no feature places it, so no "where" fact named it. One way, priced like any dig: the
     * band walk down the feet's column to the base block's top (the top block, the surface layer under it: {@link #SURFACE_LAYER}
     * deep, never below the deepslate rule's Y) on Baritone's priced path ({@link #bandLeg}, rule 18), then the dig of the
     * blocks the need takes from the base block's drop (the loot data: stone gives cobblestone), the hole's own cell priced
     * as in reach. Offered only on the surface (under ground the base block is round the bot: seen, a plain source) and when
     * the base block, the surface and the feet are known (rule 15). No block is named in the code (rule 4).
     */
    private static List<Alt> baseAlts(Ctx c, String word, int lack) {
        List<Alt> alts = new ArrayList<>();
        var base = c.w.where().base().get();
        Surface s = c.w.where().surface().get();
        int[] feet = c.w.where().feet().get();
        if (base == null || s == null || feet == null || feet.length < 3 || s.depth() != 0) return alts;   // structural: x y z
        Want want = new Want(word);
        String gives = null;
        if (c.w.loot() != null) {
            // the base block's own drops (the loot data), the one the need takes - its sure drop first
            List<LootBook.Source> drops = new ArrayList<>(c.w.loot().blockDrops(base.block()));
            drops.sort(Comparator.comparingDouble((LootBook.Source d) -> -d.per()));     // z74 review 2: per already carries the chance
            for (LootBook.Source d : drops) if (d.per() > 0 && want.has(d.item())) { gives = d.item(); break; }
        } else if (want.has(base.block())) gives = base.block();
        if (gives == null) return alts;
        // z74 review 1: the column's own top from the surface fact (under open sky: the cell stands on the top block); what lies
        // under the top block is not seen (rule 17: no x-ray), so the surface layer is assumed - SurfaceSystem's average, said
        int topBlock = s.y() - 1;
        int ty = topBlock - SURFACE_LAYER;
        if (base.deep() != null) ty = Math.max(ty, base.sureFrom());
        int depth = feet[1] - ty, top = ty;
        String assumed = " (the column's top block at y " + topBlock + "; the surface layer under it assumed " + SURFACE_LAYER
                + " deep - SurfaceSystem's average, not seen)";
        String g = gives;
        alts.add(new Alt(shw(base.block()) + " (the base block, " + depth + " deep)", () -> {
            // the pickaxe its block asks for is made up here first (a station placed in the hole would have no room)
            Spot probe = new Spot(feet[0], top - 1, feet[2], 1, base.block(), 0, g);
            int tier = tierOf(word, probe);
            if (tier == MiningTiers.UNKNOWN) { c.fail = "the pickaxe tier of " + shw(base.block()) + " is not known (no mining data loaded yet)"; return null; }
            String pre = null;
            if (tier > c.pickTier) {
                pre = cheapestPickaxe(c, tier);
                if (c.fail != null || c.stuck) return pre == null ? null : "dig down " + depth + " to " + shw(base.block()) + " (after " + pre + ")";
            }
            // 0.39.z78 dz (run DP 12:55:37: "to y 53..53" from the low spot 18 57 -20 failed, was dropped for the order and the
            // same dig down was priced again - the same way twice, parked; five orders): a band entry that failed here is a
            // fact (the order's hold and the world fact, held by distance) - this way is out from here, the next option is priced
            String bandHeld = heldWhy(c, io.github.mojolowjo.entropybot.aa.moves.FactChange.bandKey(top, top));
            if (bandHeld != null) { c.fail = "dig down " + depth + " to " + shw(base.block()) + ": the walk into y " + top + " is held here (" + bandHeld + ")"; return null; }
            String leg = bandLeg(c, new int[]{top, top});
            if (leg == null && c.fail != null) return null;
            if (BAND_DROPPED.equals(leg)) { c.fail = "no path down to " + shw(base.block()) + " under the feet"; return null; }
            c.walk(probe.key(), new Walked(0, "in reach from the hole the walk down made", null, PathPrice.Kind.PRICED));
            String how = sourceFrom(c, word, g, lack, List.of(probe));
            if (how == null) return null;
            return "dig down " + depth + " to " + base.text() + assumed + (pre == null ? "" : " with " + pre) + (leg == null || leg.isEmpty() ? "" : ": " + leg) + ", then " + how;
        }));
        return alts;
    }

    /**
     * 0.39.z43 (owner, Run D: "if it is looking for ore then it should just mine at the recommended level"): for a need with
     * no known source whose blocks generate in a band (the strip facts, {@link StripBand}), the way "go to the band's best Y
     * and strip there until the amount is in the bag" - the strip job (Step.Strip, the fork's StripProcess, with its deposit
     * leg and walk back: {@link io.github.mojolowjo.entropybot.aa.brain.Goals#stripLoop}). One way among the others (rule 1),
     * priced by its own facts; no strip facts (multiplayer, no world-gen read, no band at or below the feet): no way.
     */
    private static List<Alt> stripAlts(Ctx c, String word, String gives, int lack) {
        StripBand sb = c.w.strip().apply(word);
        if (sb == null || sb.ores().isEmpty()) return List.of();
        if (c.hoisting) {                                    // 0.39.z75 item 1: one leg to another level at a time
            c.note(() -> "strip for " + shw(word) + ": not offered before going down (the chain's one leg to another level is the other strip)");
            return List.of();
        }
        if (sb.isShut()) return shutAlts(c, word, gives, lack, sb);       // 0.39.z78 dz: the next options from a held start
        Alt a = new Alt("strip at y " + sb.y(), () -> strip(c, word, gives, lack, sb));
        a.id = gives;
        a.strip = true;
        List<Alt> out = new ArrayList<>(List.of(a));
        // 0.39.z64 item 4 (runs S-U: every heading from one origin; run U's area 969 faces, 0 iron): once the areas along
        // this heading are seen (strip.areaMinFaces), strips from fresh ground in each heading are ways too - each starting at
        // the nearest cell whose area square is not seen yet (review 6), priced by Baritone's walk there (rule 18) and its own
        // areas' yield; the cheapest wins (rule 1). Review 4: each is its own Alt, priced on a snapshot (choose): a walk not
        // known yet (computing, chunks) or failing drops that Alt only, never the strip from the feet; the walks are new goals
        // to the fork's queue, so they rank FIRST (below the running step's walk, PathMemory.Prio) and REST once answered
        var seen = c.w.along(sb.x(), sb.y(), sb.z(), sb.heading(), sb.ores());
        if (io.github.mojolowjo.entropybot.aa.brain.StripAreas.judged(seen)) {
            for (String h : io.github.mojolowjo.entropybot.aa.brain.StripOrder.HEADINGS) {
                int[] at = c.w.freshStart(sb.x(), sb.y(), sb.z(), h);
                if (at == null) continue;
                StripBand f = sb.freshAt(at[0], at[1], at[2], h);
                if (heldKey(c, f.key())) continue;
                Alt fa = new Alt("strip at y " + sb.y() + " from fresh ground " + at[2] + " m " + h, () -> strip(c, word, gives, lack, f));
                fa.id = gives;
                fa.strip = true;
                out.add(fa);
            }
        }
        return out;
    }

    /**
     * 0.39.z78 dz (rules 23 and 1, manager 2026-10-27; run DZ 19:10:15-19:28:38: water ahead held all four headings at y 16,
     * the strip way was gone from anywhere - the fork resumes a from-the-feet tunnel by its key from anywhere, so those holds
     * are rightly unscoped - and the orders explored overland for 13 min): a band whose every heading at its start is held
     * ({@link StripBand#isShut}) offers the NEXT options, each priced like any strip (rule 1):
     * <ul>
     * <li>(a) a strip from fresh ground in each heading - another start cell, at least failures.moved from this one (the
     * first unseen area square past it, StripAreas.freshStart, within strip.freshStartBlocks of there): its own tunnel key
     * ("|from area"), a priced walk there first (StripBand#freshAt, ToStrip);</li>
     * <li>(b) the band's other levels from this cell ({@link StripBand#otherYs}: the best slab under and over the held Y,
     * a slab apart, at or below the feet), each with its first heading not held at that Y.</li>
     * </ul>
     * Each one held or refused is said in the dropped ways; none left: "no strip way here" with every reason (rule 7).
     */
    private static List<Alt> shutAlts(Ctx c, String word, String gives, int lack, StripBand sb) {
        List<Alt> out = new ArrayList<>();
        List<String> held = new ArrayList<>(sb.shut());
        int away = (int) Math.ceil(io.github.mojolowjo.entropybot.aa.brain.Failures.movedV());
        for (String h : io.github.mojolowjo.entropybot.aa.brain.StripOrder.HEADINGS) {
            int[] d = WayUp.dir(h);
            int[] at = c.w.freshStart(sb.x() + (away - 1) * d[0], sb.y(), sb.z() + (away - 1) * d[1], h);
            if (at == null) { held.add("fresh ground " + h + ": every area square within strip.freshStartBlocks past " + away + " blocks is seen"); continue; }
            StripBand f = sb.freshAt(at[0], at[1], at[2] + away - 1, h);
            String why = heldKey(c, f.key()) ? "its tunnel is held" : headingHeld(c.w.held(), f, f.y(), h);
            if (why != null) { held.add("fresh ground " + h + " from " + f.from() + ": " + why); continue; }
            Alt fa = new Alt("strip at y " + sb.y() + " from fresh ground " + f.freshDist() + " m " + h + " (every heading held at the start)", () -> strip(c, word, gives, lack, f));
            fa.id = gives;
            fa.strip = true;
            out.add(fa);
        }
        for (var e : sb.otherYs().entrySet()) {
            StripBand o = sb.atY(e.getKey(), e.getValue());
            String open = null;
            List<String> no = new ArrayList<>();
            for (String h : o.headings()) {
                String why = headingHeld(c.w.held(), o, o.y(), h);
                if (why == null) { open = h; break; }
                no.add(h + ": " + why);
            }
            if (open == null) { held.add("y " + o.y() + ": every heading held (" + String.join("; ", no) + ")"); continue; }
            StripBand oy = o.toward(open);
            Alt ya = new Alt("strip at y " + oy.y() + " (the band's other level; every heading held at y " + sb.y() + ")", () -> strip(c, word, gives, lack, oy));
            ya.id = gives;
            ya.strip = true;
            out.add(ya);
        }
        // each held start said with its own label (the dropped list keeps one line a label): "strip for raw_iron at y 16
        // held: west: <why>", "... held: fresh ground north from area 0,-1: <why>", "... held: y 11: every heading held (...)"
        for (String h : held) {
            int cut = h.indexOf(": ");
            drop(c, "strip for " + shw(word) + " at y " + sb.y() + " held: " + (cut < 0 ? h : h.substring(0, cut)), cut < 0 ? "held" : h.substring(cut + 2));
        }
        if (out.isEmpty()) c.note(() -> "no strip way here for " + shw(word) + ": every start cell and Y held or refused (" + String.join("; ", held) + ")");
        return out;
    }

    /** 0.39.z78 dz: the reason a source key is held for the order (World.held's "key: why" lines), null: not held. */
    private static String heldWhy(Ctx c, String key) {
        if (c.w.held() == null) return null;
        for (String h : c.w.held()) if (h.startsWith(key + ": ")) return h.substring(key.length() + 2);
        return null;
    }

    /** A source key held for the order (World.held's "key: why" lines). */
    private static boolean heldKey(Ctx c, String key) {
        if (c.w.held() == null) return false;
        for (String h : c.w.held()) if (h.startsWith(key + ": ")) return true;
        return false;
    }

    /**
     * The strip way's price (rule 14, rough; STRIP_MINING "third version"): the pickaxe the ores ask for first (StepNeeds'
     * tier, the cheapest one made as everywhere), the walk/dig to (x, y, z) - Baritone's path (rule 18; not known yet: the
     * way waits, the chain explores meanwhile as z42 does; no path: the band is dropped for the order, the z33 fact) - then
     * per ore block wanted the blocks dug per ore found ({@link io.github.mojolowjo.entropybot.aa.brain.OreBand#blocksPerOre})
     * x the host block's break with the pickaxe in hand, plus the ore's own breaks. The step's amount: the ore blocks for the
     * lack (the loot data's drops per block). 0.39.z62 item 2: the pickaxe's wear over the walk's dug blocks, the tunnel and
     * the ores is held against its uses; past them the spares are made up front ({@link #spareTools}). 0.39.z64: a tunnel
     * cell breaks the fork's {@code IStripProcess.TUNNEL_HEIGHT} blocks (wear and dig seconds), and the band's blocks per
     * ore is scaled by what the areas along the heading already showed ({@link io.github.mojolowjo.entropybot.aa.brain.StripAreas}).
     */
    private static String strip(Ctx c, String word, String gives, int lack, StripBand sb) {
        MiningTiers.Source src = MiningTiers.source();
        if (src == null) { c.fail = "strip: the pickaxe tier of " + shw(sb.ores().get(0)) + " is not known (no mining data loaded yet)"; return null; }
        int tier = 0;
        for (String o : sb.ores()) {
            int t = src.blockTier(o);
            if (t == MiningTiers.UNKNOWN) { c.fail = "strip: the pickaxe tier of " + shw(o) + " is not known"; return null; }
            tier = Math.max(tier, t);
        }
        String head = "strip at y " + sb.y() + " (band " + sb.minY() + ".." + sb.maxY() + ")";
        String pre = null;
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null) return null;
            if (c.stuck) return head + " (after " + pre + ")";
        }
        // the straight metres (a hint; the price is Baritone's path): down to the band, plus the way to a fresh start
        Walked wk = walk(c, sb.x(), sb.y(), sb.z(), Math.abs(sb.feetY() - sb.y()) + sb.freshDist());
        if (c.fail != null) {
            if (wk.kind() == PathPrice.Kind.UNREACHABLE) c.unreach(sb.key(), wk.fail());   // the z33 source fact: the band dropped
            return null;
        }
        String ore = sb.ores().get(0);
        gives = memberOf(c, gives, ore);                        // z75 metric fix A / J3-mod item 1: the member the ore drops (#coals: coal)
        double per = perBlock(c, gives, ore);
        // 0.39.z75 metric fix A (rule 15, rule 1): a strip whose ore's yield of the need is not known is no way, and one that
        // would dig 0 ore for a lack is none either (was: "for coal 0", priced 0 s, the repair's gap 3 times, then a park)
        if (!(per > 0)) { c.fail = "strip at y " + sb.y() + ": the yield of " + shw(gives) + " from " + shw(ore) + " is not known (no loot entry; rule 15)"; return null; }
        int oreBlocks = (int) Math.ceil(lack / per - 1e-9);  // structural: epsilon
        if (oreBlocks <= 0) { c.fail = "strip at y " + sb.y() + ": 0 ore for " + lack + " " + shw(gives) + " (nothing to dig)"; return null; }
        // 0.39.z64 item 4: the areas along this heading as seen (faces, the wanted ores on them) scale the band's estimate;
        // 0.39.z77 spares: the one dig estimate (stripDig) the seconds and the wear are both priced from
        StripDig sd = stripDig(c.w, sb.perSlab(), oreBlocks, new int[]{sb.x(), sb.z()}, sb.y(), sb.heading(), sb.ores());
        double band = sd.band(), scale = sd.scale(), bpo = sd.bpo();
        var seen = sd.seen();
        int dug = sd.cells();
        double hostS = blockBreakS(c, sb.host() != null ? sb.host() : ore), oreS = blockBreakS(c, ore);
        // 0.39.z64 review 1: a tunnel cell is TUNNEL_HEIGHT blocks broken (feet and head), its seconds as its wear
        int tunnelBreaks = sd.breaks();
        double digS = tunnelBreaks * hostS + oreBlocks * oreS;
        // 0.39.z62 item 2 (run S 04:57:03: the stone pickaxe broke after the dug shaft from y 64 plus 46 strip blocks): the
        // way's pickaxe wear - the walk's pickaxe blocks (Baritone's path) + the tunnel + the ores - against the pickaxe's
        // uses; past them, the spare pickaxes are planned up front (spareTools)
        // 0.39.z64 item 2 (run U 06:08:07-06:10:19: a fresh stone pickaxe, 131 uses, broke after 67 strip cells; the chain's
        // spares ran out at y 16 and the next one needed wood from the surface): each fresh tunnel cell is two blocks
        // (the fork's GoalBlock digs the feet and the head cell), so the tunnel wears the pickaxe twice per cell dug
        int wear = wk.pickBreaks() + tunnelBreaks + oreBlocks;
        String spare = spareTools(c, wear, tier);
        c.addS(wk.seconds() + digS);
        // 0.39.z75 item 1 (D4 1; run AE 17:27:35: the furnace placed at y 16 zeroed the walk back, the table up there priced
        // 0 s): a strip at another level than the feet leaves the bot down there - the walk back up is no mirror of the way
        // down (a dug shaft is not walked up, D4 2) and no path from there is priced: later walks are "not known" (walk) and
        // later needs are met there or before the leg (beforeDown). A strip at the feet's own level stays as before.
        boolean level = sb.feetY() != sb.y();
        c.setAway(level ? 0 : wk.seconds());
        if (c.pickItem != null && spare == null) c.pickWorn += wear;
        Map<String, Integer> amounts = Map.of(io.github.mojolowjo.entropybot.aa.brain.StripOrder.countKey(ore, io.github.mojolowjo.entropybot.cave.OreSpec::family), oreBlocks);
        Step.Strip strip = new Step.Strip(sb.ores(), amounts, sb.y(), sb.heading(), 0, sb.from());
        // 0.39.z77 spares 3: the price's ore a cell, for the strip end's yield line (measured against it)
        c.effect(new Predicted(strip.key(), amounts.keySet().iterator().next(), 1 / bpo));
        // 0.39.z77 strip: the band and a cell's seconds, for the strip's own yield check (StripYield.check); its rivals' best
        // price comes from the choice it is priced in (choose)
        c.effect(new YieldPriced(strip.key(), amounts.keySet().iterator().next(), sb.perSlab(), baritone.api.process.IStripProcess.TUNNEL_HEIGHT * hostS));
        // 0.39.z46 (S58): the first leg is the priced path down to the band's peak (the walk the price above searched);
        // 0.39.z64 item 4: a strip from fresh ground walks to its start first
        int legAt = c.steps.size();
        if (sb.feetY() != sb.y() || sb.fresh()) c.steps.add(new Step.ToStrip(sb.x(), sb.y(), sb.z(), strip.key()));
        c.steps.addAll(stripLegs(strip, digS));         // 0.39.z77 J2 (R2): past steps.stepMaxS, legs of the same strip
        if (level) {
            c.down = "the strip leg to y " + sb.y();
            c.downAt = legAt;
            c.upBag = new LinkedHashMap<>(c.bag);         // 0.39.z77 legs: the bag at the leg's start (before the strip's gains)
        }
        c.bag.merge(gives, Math.max(lack, (int) Math.floor(oreBlocks * per + 1e-9)), Integer::sum);  // structural: epsilon
        final String areas = seen.faces() <= 0 ? "" : " (areas along " + sb.heading() + ": " + seen.faces() + " faces seen, " + seen.ores() + " ore on them: band "
                + Math.round(band) + " x " + String.format(java.util.Locale.ROOT, "%.2f", scale) + ")";
        final String line = head + (sb.fresh() ? " from fresh ground " + sb.from() + " " + sb.heading() : "") + " ~" + Math.round(bpo) + " blocks/ore (" + String.format(java.util.Locale.ROOT, "%.1f", sb.perSlab())
                + " ore a chunk in " + io.github.mojolowjo.entropybot.aa.brain.OreBand.slab() + " layers)" + areas + " x " + oreBlocks + " ore = " + dug + " blocks"
                + " (" + shw(sb.host() != null ? sb.host() : ore) + " " + s(hostS) + " each, " + baritone.api.process.IStripProcess.TUNNEL_HEIGHT + " a cell) -> " + s(wk.seconds() + digS);
        c.note(() -> "source: none known for " + shw(word) + ": " + line);
        return noted(line + (pre == null ? "" : " with " + pre) + (spare == null ? "" : " + " + spare), wk);
    }

    /**
     * 0.39.z77 spares (manager 2026-10-26, run CA): a strip family's expected dig from the density curve, in ONE place - the
     * band's blocks per ore at that Y ({@link io.github.mojolowjo.entropybot.aa.brain.OreBand#blocksPerOre} of the slab's ore a
     * chunk) x what the areas along the tunnel's heading showed ({@link io.github.mojolowjo.entropybot.aa.brain.StripAreas#factor};
     * no start cell known or no faces seen: x 1, the band's estimate), capped by {@code strip.maxBlocksPerOre}, x the ore
     * blocks = the tunnel's cells; a cell breaks the fork's {@code IStripProcess.TUNNEL_HEIGHT} blocks. The strip way and the
     * playbook's descent price their dig seconds AND their pickaxe wear ({@link #spareTools}) from it.
     * <p>0.39.z77 spares 3 (manager 2026-10-26: the logs beat the geometry - runs BY / BZ found ~1 iron in ~70 cells, the
     * band's own figure): no seen-cells factor; the strip end's fact line ({@code Strips.yieldLine}) measures cells dug
     * against ore found so a factor can be set from runs later.
     */
    public record StripDig(double band, double scale, double bpo, int cells, io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield seen) {
        int breaks() { return baritone.api.process.IStripProcess.TUNNEL_HEIGHT * cells; }
    }

    /**
     * {@link StripDig} for {@code oreBlocks} ore at {@code y}; {@code start}: the tunnel's first column {x, z} (null: not known).
     * 0.39.z77 strip: public - the running strip's yield check prices a tunnel from the feet in another heading by it (StripStep).
     */
    public static StripDig stripDig(double perSlab, int oreBlocks, int[] start, int y, String heading, List<String> ores) {
        return stripDig(null, perSlab, oreBlocks, start, y, heading, ores);
    }

    /** 0.39.z78 D6: {@code f} - the resolve's facts the areas are read from (null: the live areas, StripAreas). */
    private static StripDig stripDig(Facts f, double perSlab, int oreBlocks, int[] start, int y, String heading, List<String> ores) {
        double band = io.github.mojolowjo.entropybot.aa.brain.OreBand.blocksPerOre(perSlab);
        var seen = start == null || heading == null ? io.github.mojolowjo.entropybot.aa.brain.StripAreas.Yield.NONE
                : f != null ? f.along(start[0], y, start[1], heading, ores) : io.github.mojolowjo.entropybot.aa.brain.StripAreas.along(start[0], y, start[1], heading, ores);
        double scale = io.github.mojolowjo.entropybot.aa.brain.StripAreas.factor(io.github.mojolowjo.entropybot.aa.brain.StripAreas.perFace(perSlab), seen);
        double bpo = Math.min(Tuning.get("strip.maxBlocksPerOre"), band * scale);
        int cells = (int) Math.ceil(Math.max(0, oreBlocks) * bpo - 1e-9);   // structural: epsilon
        return new StripDig(band, scale, bpo, cells, seen);
    }

    /**
     * 0.39.z62 item 2 (rule 14 rough; run S: one stone pickaxe for the shaft from y 64 to 16 and the strip, broken after 46
     * strip blocks, re-planned mid-strip): a way that breaks {@code wear} blocks with the chain's pickaxe ({@code c.pickItem})
     * past its uses - a pickaxe made in this chain: its max damage (the item's MAX_DAMAGE data, {@link ItemWorth.Facts#durability});
     * one in the bag: its uses left ({@link World#usesLeft}) - less what this chain already wore. z62 review 1: the bag's other
     * pickaxes that dig {@code tier} count next (each stack's uses left; an unknown one is not counted, one that never wears
     * ends the check); only what is still over gets spare pickaxes of the same kind made up front, {@code ceil(over / fresh
     * uses)}, at most {@code tools.maxSpares} HELD - the bag's other pickaxes of the tier count against it (0.39.z77 spares,
     * run CA) - (review 3: past it, said; the rest is re-planned when a pickaxe breaks), priced
     * by the same recursion (cobblestone is cheap: the scorer sees it). The made spares wear out in the way: out of the
     * simulated bag again, the last one's wear carried. Not
     * counted: Unbreaking (its effect is the enchantment's item_damage data, evaluated by the game on the server level only;
     * reading it by the enchantment's name would be a name list) - rough and on the safe side, rule 14. Unknown durability
     * (rule 15) is said and nothing is added; no spare to be had (or only after an explore): the worn one is used, said.
     * Returns the spares' phrase, null: none added.
     */
    private static String spareTools(Ctx c, int wear, int tier) {
        if (c.pickItem == null || wear <= 0) return null;
        String tool = c.pickItem;
        int fresh = (int) Math.floor(ItemWorth.facts(tool).durability());
        int uses;
        if (c.pickMade) uses = fresh > 0 ? fresh : io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN;
        else {
            Integer u = c.w.usesLeft().apply(tool);
            uses = u == null ? io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN : u;
        }
        if (uses == io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED) return null;
        if (uses == io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN || fresh <= 0) {
            c.spare(sh(tool) + ": durability unknown (no MAX_DAMAGE in its data): " + wear + " blocks to break, no spare planned");
            return null;
        }
        int left = Math.max(0, uses - c.pickWorn);
        if (wear <= left) {
            c.spare(sh(tool) + ": " + left + " uses left, " + wear + " blocks to break: no spare needed");
            return null;
        }
        // z62 review 1: the bag's other pickaxes that dig this tier count first (their uses left; the one in hand excluded)
        int others = 0, otherCount = 0;
        // 0.39.z78 D6: scanned by the same tier test (a word below the tier was skipped)
        for (String key : c.bag.scan(t -> MiningTiers.toolTier(t, "pickaxe") >= tier, "pickaxe tier >= " + tier)) {
            // z77 legs review: less what is set aside; 0.39.z77 spares 2 (review 2): the one in hand is one of the bag's only
            // when it is in the simulated bag - spares made and worn in an earlier way are out of it (c.pickOut)
            int k = c.have(key) - (key.equals(tool) && !c.pickOut ? 1 : 0);
            if (k <= 0) continue;
            Integer u = c.w.usesLeft().apply(key);
            int each = u == null ? io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN : u;
            if (each == io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED) return null;     // one never wears out
            if (each == io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN) continue;          // not counted (rule 15): said below
            others += k * each;
            otherCount += k;
        }
        final int bagUses = others, bagCount = otherCount;
        if (wear <= left + others) {
            c.spare(sh(tool) + ": " + left + " uses left, " + wear + " blocks to break; the bag's " + bagCount + " other pickaxe(s) (" + bagUses + " uses) cover it: no spare made");
            return null;
        }
        // 0.39.z77 round fix 4 (run BF 08:57:41, iron_boots: "iron_pickaxe: 22 spares would cover 5861 blocks; tools.maxSpares 3
        // holds: 3 made" for a coal strip; manager: the kit's own iron never becomes spare tools): a spare is the lowest-tier
        // pickaxe the game data crafts that still digs this strip's tier (stone for coal or iron), never one of the tool in hand
        // when a lower one digs it - its iron stays for the orders. The tool in hand is worn first (Baritone takes the best)
        String kind = spareKind(tier, tool);
        int freshK = kind.equals(tool) ? fresh : (int) Math.floor(ItemWorth.facts(kind).durability());
        if (freshK <= 0) { kind = tool; freshK = fresh; }       // the lower kind's durability not known (rule 15): the tool's own kind
        final String sk = kind;
        int need = (int) Math.ceil((wear - left - others) / (double) freshK - 1e-9);   // structural: epsilon
        int cap = Math.max(0, Tuning.i("tools.maxSpares"));
        // 0.39.z77 spares (run CA 15:30-15:47, 22 stone pickaxes: "-> 3 spare stone_pickaxe made first" on every re-plan while
        // the bag's other pickaxes rose 0 -> 393 -> ... -> 1965 uses, until the bag was full): tools.maxSpares caps the spare
        // pickaxes HELD, not the ones one chain makes - the bag's other pickaxes of the tier (less what is set aside) count
        // against it, so a re-plan with the spares already held makes none past the cap; the rest is re-planned when one breaks.
        // 0.39.z77 spares 2 (review 1): held as new ones of the kind made - their known uses over its fresh uses, rounded down;
        // unknown uses count 0 (rule 15: not known is no value) - so worn or unknown pickaxes never fill the cap while the
        // wear is still short; the gap the cap leaves is said below
        int held = others / freshK;
        int extra = Math.min(need, Math.max(0, cap - held));
        final int heldN = held;
        Ctx.Snap before = c.snap();
        double s0 = c.seconds;
        String p = extra <= 0 ? null : obtain(c, kind, c.have(kind) + extra);
        if (extra > 0 && (c.fail != null || c.stuck)) {
            String why = c.fail != null ? c.fail : "its materials are not known yet (an explore)";
            c.restore(before);
            c.spare(sh(tool) + ": " + left + " uses left, " + wear + " blocks to break; no spare " + sh(kind) + " to be had (" + why + "): the worn one is used");
            return null;
        }
        // the cap said with what was made (z77 round fix 4: "3 made" was said before the make, which then failed)
        if (extra < need) c.spare(sh(tool) + ": " + need + " spare " + sh(kind) + " would cover " + wear + " blocks; tools.maxSpares " + cap + " holds"
                + (heldN > 0 ? " (" + heldN + " held already: the bag's " + bagUses + " uses / " + freshK + " a new one)" : "") + ": " + extra
                + " made, " + Math.max(0, wear - left - others - extra * freshK) + " blocks over, re-planned when a pickaxe breaks");
        if (extra <= 0) return null;
        c.bag.merge(kind, -extra, Integer::sum);            // they wear out in this way
        c.pickMade = true;
        c.pickOut = true;                                  // spares 2: the one in hand is out of the simulated bag
        c.pickItem = kind;                                 // the last one in hand digs on (its speed for what follows, rough)
        c.pickWorn = Math.max(0, Math.min(freshK, (wear - left - others) - (extra - 1) * freshK));   // the last spare's wear (rough)
        double add = c.seconds - s0;
        final int n = extra, fk = freshK;
        c.spare(sh(tool) + ": " + left + " uses left, " + wear + " blocks to break, the bag's other pickaxes " + bagUses + " uses (" + fk + " a new " + sh(sk) + ") -> " + n + " spare " + sh(sk) + " made first (+" + s(add) + ")");
        return n + " spare " + sh(kind) + (p == null ? "" : " (<- " + p + ")");
    }

    /**
     * 0.39.z77 round fix 4: the spare pickaxe kind for a strip of {@code tier} - the game's pickaxes (tag) with a crafting
     * recipe whose tool tier digs it, the lowest tier, ties by the less raw work (then the id); {@code tool} when none is
     * lower than it. Data only (tags, tiers, recipes; rule 4).
     */
    static String spareKind(int tier, String tool) {
        String best = tool;
        int bestT = MiningTiers.toolTier(tool, "pickaxe");
        double bestW = rawWork(tool, new ArrayList<>());
        for (String pid : new TreeSet<>(ItemWorth.itemsIn("pickaxes"))) {
            int t = MiningTiers.toolTier(pid, "pickaxe");
            if (t < tier || t > bestT || ItemWorth.recipes(pid, ItemWorth.CRAFT).isEmpty()) continue;
            double w = rawWork(pid, new ArrayList<>());
            if (t < bestT || w < bestW) { best = pid; bestT = t; bestW = w; }
        }
        return best;
    }

    /**
     * 0.39.z44 (CAVE_DETECTION option 1, owner: "most like how a player would see it"): for an ore need with no known exposed
     * source and a cave mouth known (the CaveWay facts), the way "walk to the mouth and explore the cave for the ore" (the
     * cave job, Step.Mine CAVE, bounded by {@code cave.exploreMinutes}). One way among the others (rule 1).
     * 0.39.z77 cave (manager 2026-10-26 ~15:10 on run BW 14:22:14: one raw_iron seen 8 m, held, so no cave way was offered and
     * "dig 1 + explore" won): offered whenever the planner's facts know the cave ({@link #caveOffered}), priced beside any
     * seen source; the scorer picks by price, no "prefer caves" rule.
     */
    private static List<Alt> caveAlts(Ctx c, String word, String gives, int lack) {
        CaveWay cw = caveOffered(c.w.cave(), word);
        if (cw == null) return List.of();
        Alt a = new Alt((cw.candidate() ? "cave candidate " : "cave mouth ") + Math.round(cw.dist()) + " m", () -> cave(c, word, gives, lack, cw));
        a.id = gives;
        return List.of(a);
    }

    /**
     * 0.39.z77 cave: the planner's own cave test - the cave way {@code w} offers for {@code word} (a mouth known, an ore block
     * that gives the word, a yield above 0); null: none. One test for the ways ({@link #caveAlts}) and the explore's end
     * ({@link #caveSource}).
     */
    static CaveWay caveOffered(World w, String word) {
        return w == null ? null : caveOffered(w.cave(), word);
    }

    /** 0.39.z78 D6: the same test on a cave lookup (the resolve's recorded one). */
    private static CaveWay caveOffered(Function<String, CaveWay> cave, String word) {
        if (cave == null || word == null) return null;
        CaveWay cw = cave.apply(word);
        return cw == null || cw.ore() == null || !(cw.perMin() > 0) ? null : cw;
    }

    /**
     * 0.39.z77 cave (run BW 14:24:10-14:24:35: the cave at 20 21 -10 was confirmed during "explore 5 until raw_iron" and the
     * explore ran on until it failed - a cave was no source in its until test): a new way for {@code word} that an explore
     * for it ends on - a CONFIRMED cave the planner offers on {@code w} (the order's world: a cave the order dropped is none),
     * as a spot at its mouth (block: its ore, gives: the word). A candidate is the explore's aim, never its end. Null: none.
     * Pure (JUnit: Z77CaveWayTest).
     */
    public static Spot caveSource(World w, String word) {
        CaveWay cw = caveOffered(w, word);
        if (cw == null || cw.candidate()) return null;
        return new Spot(cw.x(), cw.y(), cw.z(), cw.dist(), cw.ore(), 0, word);
    }

    /**
     * The cave way's price (rule 14, rough): the pickaxe the ore asks for first (as the strip), the walk to the mouth
     * (Baritone's complete path, rule 18; not known yet: the way waits; no path: the mouth is dropped for the order), then
     * the minutes the cave yield says the ore blocks take (ore blocks / ore a minute) plus their breaks. Past
     * {@code cave.exploreMinutes} the way does not stand (the cave job stops there). Not priced: the walk inside the cave
     * (it is in the yield's minutes), the torches.
     */
    private static String cave(Ctx c, String word, String gives, int lack, CaveWay cw) {
        MiningTiers.Source src = MiningTiers.source();
        if (src == null) { c.fail = "cave: the pickaxe tier of " + shw(cw.ore()) + " is not known (no mining data loaded yet)"; return null; }
        int tier = src.blockTier(cw.ore());
        if (tier == MiningTiers.UNKNOWN) { c.fail = "cave: the pickaxe tier of " + shw(cw.ore()) + " is not known"; return null; }
        // 0.39.z47 (owner's ruling, z46 left it out): an unconfirmed candidate (the heightmap's shape) is walked to and
        // confirmed by sight within cave.confirmM on the way; its price adds Tuning cave.candidatePriorS for that check, so
        // it wins over the band strip only when it is near; one that fails the check is dropped there (CaveMouths, said once)
        double confirmS = cw.candidate() ? Tuning.get("cave.candidatePriorS") : 0;
        String head = (cw.candidate() ? "cave candidate " : "cave mouth ") + Math.round(cw.dist()) + " m" + (cw.candidate() ? " (to confirm)" : "");
        String pre = null;
        if (tier > c.pickTier) {
            pre = cheapestPickaxe(c, tier);
            if (c.fail != null) return null;
            if (c.stuck) return head + " (after " + pre + ")";
        }
        Walked wk = walk(c, cw.x(), cw.y(), cw.z(), cw.dist());
        if (c.fail != null) {
            if (wk.kind() == PathPrice.Kind.UNREACHABLE) c.unreach(cw.key(), wk.fail());
            return null;
        }
        gives = memberOf(c, gives, cw.ore());                   // J3-mod item 1: the member the ore drops names the bag's gain
        double per = perBlock(c, gives, cw.ore());
        if (!(per > 0)) { c.fail = head + ": the yield of " + shw(gives) + " from " + shw(cw.ore()) + " is not known (no loot entry; rule 15)"; return null; }   // z75 metric fix A
        int oreBlocks = (int) Math.ceil(lack / per - 1e-9);  // structural: epsilon
        if (oreBlocks <= 0) { c.fail = head + ": 0 ore for " + lack + " " + shw(gives) + " (nothing to mine)"; return null; }
        double minutes = oreBlocks / cw.perMin(), max = Tuning.get("cave.exploreMinutes");
        String yield = "yield ~" + String.format(java.util.Locale.ROOT, "%.2f", cw.perMin()) + " ore/min (" + (cw.measured() ? "measured" : "prior") + ")";
        if (minutes > max) {
            c.fail = head + ", " + yield + ": " + oreBlocks + " ore take ~" + Math.round(minutes) + " min, past cave.exploreMinutes " + Math.round(max);
            return null;
        }
        double exploreS = minutes * 60, oreS = oreBlocks * blockBreakS(c, cw.ore());  // structural: seconds per minute
        c.addS(wk.seconds() + confirmS + exploreS + oreS);
        c.setAway(wk.seconds() + exploreS);
        if (c.pickItem != null) c.pickWorn += oreBlocks;
        c.steps.add(new Step.Walk(cw.x(), cw.y(), cw.z(), 0));
        c.steps.add(new Step.Mine(cw.family(), oreBlocks, Step.Mine.How.CAVE, (int) Math.ceil(max)));
        c.bag.merge(gives, Math.max(lack, (int) Math.floor(oreBlocks * per + 1e-9)), Integer::sum);  // structural: epsilon
        final String line = head + ", " + yield + " x " + oreBlocks + " ore" + (cw.candidate() ? " + confirm " + s(confirmS) : "")
                + " -> " + s(wk.seconds() + confirmS + exploreS + oreS);
        c.note(() -> "source: " + shw(word) + " in the cave: " + line);
        return noted("in the cave: " + line + (pre == null ? "" : " with " + pre), wk);
    }

    /** One block's break by the game's formula with the pickaxe in hand when its tier asks for one (as {@link #breakS}). */
    private static double blockBreakS(Ctx c, String block) {
        double hardness = ItemWorth.facts(block).hardness();
        if (hardness <= 0) return TickPrice.breakS(Tuning.get("needChain.unknownBreakS"));
        MiningTiers.Source src = MiningTiers.source();
        int tier = src == null ? 0 : Math.max(0, src.blockTier(block));
        if (tier < 1) return TickPrice.breakS(GameTimes.breakSeconds(hardness, 1, true));
        double speed = c.pickItem == null ? 1 : Math.max(1, ItemWorth.facts(c.pickItem).digSpeed());
        return TickPrice.breakS(GameTimes.breakSeconds(hardness, speed, c.pickTier >= tier));
    }

    /** 0.39.z42: no way of the requirement stands and the reason is a walk whose path is not known (computing, chunks not loaded). */
    private static boolean waitsOnPaths(Ctx c) {
        return c.fail != null && c.fail.startsWith(PathPrice.NOT_KNOWN) && c.pathsWaiting > 0;
    }

    /**
     * 0.39.z76 send-back 3 (rule 22, owner 2026-10-26: "the bot moves only as a priced step of the current order"; run AU
     * 01:57:53-02:02:53: the deleted "explore while paths compute" walked 5 min while the walks it waited on were searched
     * again from every new cell, so none landed): every way of the requirement waits on a walk's complete path - the bot
     * STANDS while they compute from where it stands ({@link Step.AwaitPaths}, up to pathCost.firstAnswerWaitS), each landing
     * prices the order again there; past the wait the provider answers the walks still not known by Baritone's estimate
     * ({@link PathPrice.Kind#ESTIMATED}, "ranked, not priced") and the order is priced again on those. A standing step has
     * no price of its own (the chain ends there, like an explore leaf). aa why: "stand (paths computing: N ways waiting ...)".
     * The ways' "not known" reasons stay in the dropped list.
     */
    private static String pathsWait(Ctx c, String word) {
        int n = c.pathsWaiting;
        c.fail = null;
        c.pathsWaiting = 0;
        int waitS = firstAnswerWaitS();
        c.steps.add(new Step.AwaitPaths(waitS));
        c.stuck = true;
        if (c.unknown == null) c.unknown = word;
        String out = "stand (" + PATHS_COMPUTING + n + " way" + (n == 1 ? "" : "s") + " waiting; standing up to " + waitS
                + " s while their answers compute, then the walks still not known are taken by Baritone's estimate)";
        if (c.trace != null) c.trace.note("ways of " + shw(word) + ": " + out);
        return out;
    }
    /** 0.39.z76 send-back 3: seconds the standing step waits for the walks' first answers before the estimate is taken (Tuning). */
    public static int firstAnswerWaitS() { return (int) Math.ceil(Tuning.get("pathCost.firstAnswerWaitS")); }

    /** 0.39.z42: the words of {@link #pathsWait}. */
    static final String PATHS_COMPUTING = "paths computing: ";

    /**
     * The leaf when no way stands: look for the need's source (a tag: any member's: the whole source set). It has no price.
     * A need blocks generate with (the worldgen band of the blocks that drop it) first goes to that height (one Baritone
     * goal), then explores until a source is known.
     */
    private static String exploreLeaf(Ctx c, String word, String reason) {
        // loot-book-bg: the book is still being read (a server join, in the background): the source may be in it, so no explore
        // on a missing book; the way fails with the wait (null back, c.fail set) and the chain is priced again when it lands
        if (c.w.loot() != null && !c.w.loot().ready()) {
            c.fail = c.w.loot().waiting();
            return null;
        }
        // 0.39.z75 metric fix B (rule 15; run AH 19:46:05-19:46:33: 14 explores "until #stone_tool_materials" in 30 s, each
        // ended at once on a stone seen, while every dig of it failed "the pickaxe tier ... not known (no mining data loaded
        // yet)"): with the mining data not loaded, a known block may well be diggable - no explore on a missing fact; the
        // chain waits with the reason (Chain.waiting) and is priced again once the tiers are read - when a block of the need
        // is known (its dig is what the missing fact blocks); nothing known: the explore as before
        if (!MiningTiers.ready() && !c.w.seenAll().apply(word).isEmpty()) {
            c.fail = MINING_WAIT;
            return null;
        }
        // 0.39.z66 item 2 (run X 08:40:40: "smelt 4 iron_ore <- ... explore (not enough iron_ore known)" beat "smelting
        // iron_ingot from raw_iron: walk: path not known yet" with 10 raw_iron in the bag - a way ending in an explore stands,
        // one waiting on a path does not, choose): an item nothing gives but a tool-gated drop of its own block (the loot
        // data) has no source an explore could find: the way fails with that fact, never an explore that cannot end on it
        String none = Nearby.noSource(word, c.w.loot());
        if (none != null) {
            c.fail = none;
            return null;
        }
        // 0.39.z60 review 1 (rule 15): a known block whose structure fact is not loaded yet may well be a source: no explore on
        // an unknown, the chain waits with the reason (Chain.waiting) and is priced again on the next look
        for (Spot sp : c.w.seenAll().apply(word)) {
            if (!sp.unloaded()) continue;
            c.fail = "source " + shw(sp.block() == null ? word : sp.block()) + " at " + sp.x() + " " + sp.y() + " " + sp.z() + ": " + SOURCE_NOT_LOADED;
            return null;
        }
        // 0.39.z68 (owner 2026-10-25: "if it's wood - go to surface to explore, if it's ore, explore for caves"): where the
        // target generates, from the jars' world-gen data (GenWhere: heightmap-placed = the surface, height_range = a band)
        var where = c.down != null ? null : c.w.where().of().apply(word);
        String whereText = null, upText = null;
        int[] band = c.down != null ? null : c.w.band().apply(word);
        // 0.39.z75 item 1: down there after the leg to another level, the band walk and the walk up are walks from a cell the
        // bot reaches later (not priced): the explore runs from where the leg leaves the bot
        if (c.down != null) whereText = "from where " + c.down + " leaves the bot";
        // 0.39.z77 J2 E2 (runs AX 14:23, BC 06:24:02: "Baritone found no path 3 times from -181 16 27" - the explore's GoalXZ
        // target through the rock over the bot; rule 22: never a target a player would not walk to from where it stands):
        // under aaLadder.surfaceDepth of ground the explore runs from the surface - the priced walk up first, for every target
        // kind, no band leg from down here (a strip at the feet, the way before any explore, did not stand). No walk up to be
        // had (held for the order, no path, not known): no explore from here either, said (rule 7)
        Surface over = c.down != null ? null : c.w.where().surface().get();
        boolean under = over != null && over.depth() >= AaLadder.surfaceDepth();
        if (under && !(where != null && where.surface())) {
            // 0.39.z77 round fix 5 (run BG: the walk up "done" next to the feet, the explore failed "under ground" from the same
            // cell 317 times): an explore from here already failed under ground - a walk up that leaves the bot here is no walk
            // up; the climb (its own priced row) or the stop, said
            String froze = frozenHere(c);
            String up = froze != null ? null : surfaceWalk(c);
            if (froze != null) c.note(() -> "surface: the explore from here failed under ground (" + froze + "): no walk up from here, the climb");
            if (up == null && c.fail != null) return null;      // the walk up waits on its path (rule 18)
            if (up == null) up = climbUp(c, over);           // 0.39.z77 answers (2): the climb with the bag's blocks, priced
            if (up == null) return null;
            c.steps.add(new Step.Explore(exploreMinutes(), shw(word)));
            c.stuck = true;
            if (c.unknown == null) c.unknown = word;
            String out = "explore for " + shw(word) + " from the surface (" + (where == null ? "where it generates not given" : where.text()) + "; under ground here: " + up + ") (" + reason + ")";
            if (c.trace != null) {
                c.trace.note("source: none known for " + shw(word) + ": " + out);
                c.trace.untilFrom.computeIfAbsent(shw(word), k -> new LinkedHashSet<>()).add(c.trace.way());
            }
            return out;
        }
        // 0.39.z77 J2 E2: a band explore that found no cave cell in the band from down there holds the band (Mining): the
        // explore then runs on the surface, never the band leg again (it would go back down to the same rock)
        if (where != null && where.band() && heldKey(c, io.github.mojolowjo.entropybot.aa.moves.FactChange.bandKey(where.lo(), where.hi()))) band = null;
        if (where != null && where.surface()) {
            band = null;                                     // a surface target has no band to go down to
            whereText = "on the surface (" + where.text() + ")";
            upText = surfaceWalk(c);
            if (upText == null && c.fail != null) return null;  // the walk up waits on its path (rule 18): the way waits, no explore
            if (upText == null && under) { upText = climbUp(c, over); if (upText == null) return null; }     // 0.39.z77 J2 E2 / answers (2): never an explore through rock
        } else if (where != null && where.band()) {
            // a band the ore placements did not give (a geode, an underground lake): the fact's own band, unless held
            if (band == null && !heldKey(c, io.github.mojolowjo.entropybot.aa.moves.FactChange.bandKey(where.lo(), where.hi()))) band = new int[]{where.lo(), where.hi()};
            whereText = "under ground, toward caves in or near the band (" + where.text() + ")";
        } else if (where != null) {
            whereText = "as before (" + where.text() + ")";
        }
        String legText = null;
        if (band != null) {
            // 0.39.z69 item 1 (run AA 11:39:48: "to y 5..31" with no pickaxe dug stone by hand from y 62 to 31 for 5 min,
            // then stuck 40 s on the next one): the walk down is Baritone's priced path (rule 18) with its breaks, compared
            // with making the cheapest pickaxe first (rule 1: an option with its price)
            legText = bandLeg(c, band);
            if (legText == null && c.fail != null) return null;   // its path (or the pickaxe's) is not known yet: the way waits
            if (BAND_DROPPED.equals(legText)) { band = null; legText = null; }
        }
        // 0.39.z76 send-back 2 (run AO 20:37): an explore from here that froze is held by position - with no walk up and no band
        // leg to move the bot first, the same explore is no way again until the bot moved failures.moved (said, rule 7)
        if (upText == null && (legText == null || legText.isEmpty())) {
            String froze = frozenHere(c);
            if (froze != null) {
                c.fail = "the explore from here froze (" + froze + "); held for this order until the bot moves failures.moved";
                return null;
            }
        }
        c.steps.add(new Step.Explore(exploreMinutes(), shw(word)));      // live-facts: it ends once a source of the need is known
        c.stuck = true;
        if (c.unknown == null) c.unknown = word;
        String out = "explore (" + reason + (band == null ? "" : "; it generates at Y " + band[0] + ".." + band[1] + ", going there first"
                + (legText == null || legText.isEmpty() ? "" : ": " + legText)) + ")";
        if (whereText != null) out = "explore for " + shw(word) + " " + whereText + (upText == null ? "" : "; " + upText) + " " + out.substring("explore ".length());
        if (c.trace != null) {
            c.trace.note("source: none known for " + shw(word) + ": " + out);
            c.trace.untilFrom.computeIfAbsent(shw(word), k -> new LinkedHashSet<>()).add(c.trace.way());
        }
        return out;
    }

    /**
     * 0.39.z77 round fix 5 (run BG 09:07:39-09:23:30): a walk up that Baritone ended "arrived" with {@code depth} blocks of
     * ground still over the feet ({@code at}) - at or past aaLadder.surfaceDepth ({@code under}), the planner's own "under
     * ground" - did not reach the surface: its reason (the walk up's fact); null: it did. 0.39.z77 shape review 1 (rule 15):
     * {@code depth} -1 (the surface over the column not known) is never "reached": the walk up ends with that reason.
     */
    public static String walkUpShort(int depth, int under, String at) {
        if (depth < 0) return "the walk up ended at " + at + " where the surface is not known (rule 15: not known is never on the surface)";
        if (depth < under) return null;
        return "the walk up ended under ground (" + depth + " blocks over the feet at " + at + ", aaLadder.surfaceDepth " + under + ")";
    }

    /** 0.39.z77 J2 E2: the reason an explore from under ground is no way: no walk up stands (rule 22: never through rock). */
    static String noWalkUp(Surface over) {
        return "under ground (" + over.depth() + " blocks over the feet) and no priced walk up to the surface (held for this order, no path up, or not known):"
                + " an explore from here would aim through rock (rule 22)";
    }

    /** 0.39.z77 answers (2): the source key a failed climb holds for the order (FactChange.climb). */
    public static final String CLIMB_KEY = "climb up";

    /** The reason of the first held key starting with {@code prefix} ("key: why" -> the whole line), null: none. */
    private static String heldLine(Ctx c, String prefix) {
        if (c.w.held() == null) return null;
        for (String h : c.w.held()) if (h.startsWith(prefix)) return h;
        return null;
    }

    /**
     * 0.39.z77 answers (2) (manager 2026-10-26: "walk up failed" is followed by "climb with N blocks from the bag" as a way):
     * under ground with no priced walk up (held for the order or unreachable), the climb verb's way up (Step.Climb: Baritone's
     * staircase with the pickaxe in hand, or a pillar of the bag's blocks) - priced as the climb verb's own row (depth x
     * rungOptions.climbBlock + rungOptions.climbFix walk-blocks, RungOptions). It stands with a pickaxe in hand or blocks for
     * every level. A climb that failed in this order ({@link #CLIMB_KEY} held), or none to be had: no way, with both reasons
     * (rule 7). Returns the phrase with the step added; null with {@code c.fail}.
     */
    private static String climbUp(Ctx c, Surface over) {
        String walk = heldLine(c, SURFACE_AT);
        if (walk == null) for (var e : c.unreachableRead().entrySet()) if (e.getKey().startsWith(SURFACE_AT)) { walk = e.getKey() + ": " + e.getValue(); break; }
        if (walk == null) walk = "no priced walk up";
        String climbed = heldLine(c, CLIMB_KEY);
        int blocks = 0;
        for (String k : c.bag.scan(ItemWorth::stoneLike, "stone-like")) if (c.have(k) > 0) blocks += c.have(k);   // 0.39.z78 D6: scanned
        boolean pick = c.pickTier >= 1;      // structural: any pickaxe (the bot's tier scale: 1 = the least pickaxe)
        if (climbed != null) { c.fail = noWalkUp(over) + " - the walk up: " + walk + "; the climb: " + climbed; return null; }
        if (!pick && blocks < over.depth()) {
            c.fail = noWalkUp(over) + " - the walk up: " + walk + "; no climb either: no pickaxe and " + blocks + " blocks in the bag for " + over.depth() + " up";
            return null;
        }
        double sec = Unit.seconds(over.depth() * RungOptions.climbBlock() + RungOptions.climbFix());
        c.steps.add(new Step.Climb());
        c.addS(sec);
        final String line = "climb up " + over.depth() + " with " + Math.min(blocks, over.depth()) + " blocks from the bag" + (pick ? " and the " + sh(c.pickItem) : "")
                + ": " + s(sec) + " (the walk up out: " + walk + ")";
        c.note(() -> line);
        return line;
    }

    /** 0.39.z77 J2 E2: the start of {@link #noWalkUp}'s reason. */
    public static final String UNDER_GROUND = "under ground (";

    /** 0.39.z76 send-back 2 (run AO 20:37): the key prefix of an explore that froze at a position (held for the order). */
    public static final String EXPLORE_FROZEN = "explore frozen at ";

    /** 0.39.z76 send-back 2: the key of an explore that made no progress from {@code feet} ("explore frozen at x y z"). */
    public static String exploreFrozenKey(int[] feet) { return EXPLORE_FROZEN + feet[0] + " " + feet[1] + " " + feet[2]; }

    /**
     * 0.39.z77 round fix 2 (run BH 09:32:11: two stands at -235 69 -201 with the same walks not known, then the order stopped):
     * the key prefix the second stand at a cell holds for the order (Steps): the walks still not known from within
     * failures.moved of it are dropped, "no estimate" (rule 15), so the next way runs.
     */
    public static final String STOOD_AT = "stood at ";

    /** 0.39.z77 round fix 2: the key of the stands at {@code feet} ("stood at x y z"). */
    public static String stoodKey(int[] feet) { return STOOD_AT + feet[0] + " " + feet[1] + " " + feet[2]; }

    /**
     * 0.39.z76 send-back 2 (run AO 23:54-23:56: the same explore picked again from y 24 three times, each frozen): the held
     * frozen explore within failures.moved of the feet ("explore frozen at x y z: why"), null: none, or the feet not known.
     */
    static String frozenHere(Ctx c) { return heldNear(c, EXPLORE_FROZEN); }

    /** 0.39.z77 round fix 2: the held stands within failures.moved of the feet ("stood at x y z: why"), null: none. */
    static String stoodHere(Ctx c) { return heldNear(c, STOOD_AT); }

    /** A held "prefix x y z: why" line within failures.moved of the feet, null: none, or the feet not known. */
    private static String heldNear(Ctx c, String prefix) {
        if (c.w.held() == null) return null;
        int[] feet = c.w.where().feet().get();
        if (feet == null || feet.length < 3) return null;      // structural: x y z
        for (String h : c.w.held()) {
            if (!h.startsWith(prefix)) continue;
            int colon = h.indexOf(':');
            String[] xyz = (colon < 0 ? h : h.substring(0, colon)).substring(prefix.length()).trim().split(" ");
            if (xyz.length != 3 || !xyz[0].matches("-?\\d+") || !xyz[1].matches("-?\\d+") || !xyz[2].matches("-?\\d+")) continue;   // structural: x y z
            int[] at = {Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])};
            if (dist3(at, feet) <= io.github.mojolowjo.entropybot.aa.brain.Failures.movedV()) return h;
        }
        return null;
    }

    /** 0.39.z75 metric fix B: the chain's wait while the mining tiers are not read (rule 15: not loaded is never "none"). */
    public static final String MINING_WAIT = "the mining data is not loaded yet (a known block may be diggable once it is; priced again then, rule 15)";

    /** 0.39.z69 item 1: {@link #bandLeg}'s answer when the band is out for this order (no path into it): the explore runs from here. */
    static final String BAND_DROPPED = "\u0000band dropped";

    /**
     * 0.39.z69 item 1 (run AA 11:39:17-11:44:44: the torch chain cut 1 log, then "to y 5..31" walked Baritone's path down from
     * y 62 with no pickaxe in the bag, breaking stone by hand - 7.5 s a block and no drop, the loot data's match_tool - for 5
     * min; the next one stood 40 s while its hand-dig path computed): the ToBand step's walk priced before it is offered. The
     * cell is the feet's column at the band's nearest Y ({@link Step.ToBand#target}, the cell StepHands walks to); the price is
     * Baritone's complete path (rule 18) with the bag the bot holds now, its pickaxe-mineable breaks counted
     * ({@link PathPrice.Walk#pickBreaks}, #mineable/pickaxe, their hardness). With no pickaxe in the chain and breaks on the
     * path, the way "make the cheapest pickaxe first (the game's pickaxes tag, any tier digs a #mineable/pickaxe block at the
     * tool's speed), then walk" is priced on a snapshot: the pickaxe's chain + its spares for the breaks
     * ({@link #spareTools}) + the walk less what the breaks save (the game's break formula: by hand at speed 1 and /100, with
     * the pickaxe at its speed and /30) - and the cheaper of the two is kept (rule 1). Returns the leg's phrase with its steps
     * added and its seconds charged; "" when it adds nothing priced (in the band already, or the feet not known: the ToBand
     * step as before, unpriced, said); {@link #BAND_DROPPED} with no step: Baritone found no path into the band (held for the
     * order, the z42 fact); null with {@code c.fail}: the walk's (or the pickaxe's) path is not known yet - the way waits.
     */
    private static String bandLeg(Ctx c, int[] band) {
        Step.ToBand tb = new Step.ToBand(band[0], band[1]);
        int[] feet = c.w.where().feet().get();
        if (feet == null || feet.length < 3) {     // structural: x y z
            c.steps.add(tb);
            c.note(() -> "band walk: the feet are not known: " + tb.text() + " is not priced (the step walks Baritone's path when it starts)");
            return "";
        }
        if (feet[1] >= band[0] && feet[1] <= band[1]) {
            c.note(() -> "band walk: the feet are in the band already (y " + feet[1] + ")");
            return "";
        }
        int ty = tb.target(feet[1]);
        Walked wk = walk(c, feet[0], ty, feet[2], Math.abs(feet[1] - ty), true);     // z76 send-back 2: the feet end IN the band (run AN)
        if (c.fail != null) {
            if (wk.kind() != PathPrice.Kind.UNREACHABLE) return null;
            c.unreach(io.github.mojolowjo.entropybot.aa.moves.FactChange.bandKey(band[0], band[1]), wk.fail());
            c.fail = null;
            c.note(() -> "band walk: no path into " + tb.text() + " (" + wk.fail() + "): the explore runs from here");
            return BAND_DROPPED;
        }
        int digs = wk.pickBreaks();
        double handS = wk.seconds();
        String head = (feet[1] > ty ? "dig down" : "walk") + " to y " + ty;
        if (digs <= 0) {
            c.steps.add(tb);
            c.addS(handS);
            return noted(head, wk);
        }
        if (c.pickItem != null) {
            // a pickaxe in the bag (Baritone priced the path with it) or made earlier in this chain (priced by hand: the saving)
            double saved = c.pickMade ? savedS(wk.dug(), c.pickItem) : 0;
            int tier = Math.max(1, c.pickTier);       // structural: any pickaxe (the bot's tier scale: 1 = the least pickaxe)
            String spare = spareTools(c, digs, tier);
            if (spare == null) c.pickWorn += digs;
            c.steps.add(tb);
            double s = Math.max(0, handS - saved);
            c.addS(s);
            return head + " with " + sh(c.pickItem) + " (" + digs + " blocks to break" + (saved > 0 ? ", " + s(saved) + " saved on the hand price" : "") + "): " + s(s)
                    + (spare == null ? "" : " + " + spare);
        }
        String pick = null, spare = null;
        double pickS = Double.NaN, withS = Double.NaN;
        if (!c.bandPick) {
            Ctx.Snap before = c.snap();
            double s0 = c.seconds;
            boolean stuckBefore = c.stuck;             // z69 review (a): an earlier explore in the chain is not this pickaxe's
            c.bandPick = true;
            // z69 review (e): the pickaxe's pricing notes go under a node of their own in aa debug (kept when it loses, with
            // its verdict); the unreachable facts it met stay (z41: a Baritone "no path" is true whichever way is kept)
            Trace.Node node = c.trace == null ? null : c.trace.push("way the cheapest pickaxe first (the band walk's breaks)", true);
            try {
                pick = cheapestPickaxe(c, 1);          // structural: any pickaxe (the bot's tier scale: 1 = the least pickaxe)
                if (pickFirstFails(c.fail, stuckBefore, c.stuck) == null) spare = spareTools(c, digs, Math.max(1, c.pickTier));
            } finally {
                c.bandPick = false;
                if (node != null) c.trace.pop();
            }
            String fails = pickFirstFails(c.fail, stuckBefore, c.stuck);
            if (fails != null) {
                String why = c.fail != null ? c.fail : fails + " (" + (c.unknown == null ? "?" : shw(c.unknown)) + ")";
                boolean waits = c.fail != null && c.fail.startsWith(PathPrice.NOT_KNOWN);
                c.restore(before);
                if (node != null) node.verdict = "dropped: " + why;
                if (waits) { c.fail = why; return null; }      // rule 18: the pickaxe's walk is not known yet, the leg waits
                c.note(() -> "band walk: no pickaxe to be had first (" + why + "): the dig by hand stands alone");
                pick = null;
            } else {
                pickS = c.seconds - s0;
                double saved = savedS(wk.dug(), c.pickItem);
                withS = pickS + Math.max(0, handS - saved);
                if (node != null) node.verdict = (withS < handS ? "picked: " : "dropped: ") + s(withS) + " vs by hand " + s(handS);
                if (withS < handS) {
                    if (spare == null) c.pickWorn += digs;
                    c.steps.add(tb);
                    c.seconds = s0 + grid(withS);
                    final double fp = pickS, fw = withS, fs = saved;
                    c.note(() -> "band walk: " + digs + " blocks to break - the pickaxe first " + s(fp) + " + the walk " + s(handS - fs) + " = " + s(fw) + " beats by hand " + s(handS));
                    return head + " with " + sh(c.pickItem) + " made first (" + digs + " blocks to break; pickaxe " + s(pickS) + " + walk " + s(handS - saved) + " = " + s(withS)
                            + ", by hand " + s(handS) + "; <- " + pick + ")" + (spare == null ? "" : " + " + spare);
                }
                c.restore(before);
            }
        }
        c.steps.add(tb);
        c.addS(handS);
        final double fw = withS;
        c.note(() -> "band walk: " + digs + " blocks to break by hand " + s(handS) + (Double.isNaN(fw) ? "" : " (the pickaxe first " + s(fw) + ")"));
        return head + " by hand (" + digs + " blocks to break" + (Double.isNaN(withS) ? "" : "; the pickaxe first " + s(withS)) + "): " + s(handS);
    }

    /**
     * 0.39.z69 review (a): why the band walk's pickaxe-first way does not stand after its pricing - a failure, or an explore
     * it STARTED ({@code stuckAfter} with {@code stuckBefore} false: the pickaxe's own source is still to find); an explore
     * the chain was in before it is not this way's. Null: it stands. Pure.
     */
    static String pickFirstFails(String fail, boolean stuckBefore, boolean stuckAfter) {
        if (fail != null) return fail;
        return stuckAfter && !stuckBefore ? "its own source is still to find" : null;
    }

    /**
     * 0.39.z69 item 1, review (d): seconds a walk's pickaxe-mineable breaks ({@code dug}: block id -> count, Baritone's path)
     * save with {@code pick} in hand over the hand price the path carried - per block kind by the game's break formula
     * ({@link GameTimes#breakSeconds}) with its hardness (item data) and harvest tier (MiningTiers, the needs_*_tool tags):
     * by hand speed 1, /30 when the block needs no tool, else /100; with the pickaxe its dig speed, /30 when its tier
     * harvests the block, else /100. A kind with no hardness known is left out (rule 15, said in aa debug by the caller's
     * price). 0: nothing counted.
     */
    static double savedS(Map<String, Integer> dug, String pick) {
        if (dug == null || dug.isEmpty() || pick == null) return 0;
        double speed = Math.max(1, ItemWorth.facts(pick).digSpeed());
        int have = MiningTiers.toolTier(pick, "pickaxe");
        double sum = 0;
        for (var e : dug.entrySet()) {
            double h = ItemWorth.facts(e.getKey()).hardness();
            if (!(h > 0)) continue;
            int need = MiningTiers.source() == null ? MiningTiers.UNKNOWN : MiningTiers.blockTierOf(e.getKey());
            if (need == MiningTiers.UNKNOWN) continue;
            double hand = TickPrice.breakS(GameTimes.breakSeconds(h, 1, need < 1));
            double with = TickPrice.breakS(GameTimes.breakSeconds(h, speed, have >= need));
            sum += e.getValue() * Math.max(0, hand - with);
        }
        return sum;
    }

    /**
     * 0.39.z68: the walk up before a surface target's explore (rule 1: an option with its price; rule 18: Baritone's complete
     * path to the surface cell, the fork's ClimbGoal steering the climb). Returns its phrase with the step added and its
     * seconds charged; null with no walk: under open sky already (the explore as before), the surface not known here (rule
     * 15: said, the explore runs from here), a walk up already held for this order (its failure, said), or no path up
     * (unreachable: said, the explore runs from here). Null with {@code c.fail} set: the path is not known yet (the way waits).
     */
    private static String surfaceWalk(Ctx c) {
        Surface s = c.w.where().surface().get();
        if (s == null) {
            c.note(() -> "surface: where it is over the bot is not known (its column is not loaded): the explore runs from here");
            return null;
        }
        if (s.depth() <= 0) return null;
        for (String h : c.w.held())
            if (h.startsWith(SURFACE_AT)) {
                c.note(() -> "surface: the walk up failed in this order (" + h + "): the explore runs from here");
                return null;
            }
        Walked wk = walk(c, s.x(), s.y(), s.z(), s.dist());
        if (c.fail != null) {
            if (wk.kind() != PathPrice.Kind.UNREACHABLE) return null;
            c.unreach(surfaceKey(s.x(), s.y(), s.z()), wk.fail());
            c.fail = null;
            c.note(() -> "surface: no path up to " + s.x() + " " + s.y() + " " + s.z() + " (" + wk.fail() + "): the explore runs from here");
            return null;
        }
        c.steps.add(new Step.ToSurface(s.x(), s.y(), s.z()));
        c.addS(wk.seconds());
        return noted("walk up to the surface at " + s.x() + " " + s.y() + " " + s.z() + " first (" + s.depth() + " under ground)", wk);
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
            // 0.39.z77 choose: any wait choose returns stays a wait (rule 15), never "no pickaxe"
            if (c.fail == null || !answerPending(c.fail)) c.fail = "no pickaxe of tier " + tier + " can be had to dig" + (alts.isEmpty() ? ": none in the game data can be crafted" : "");
            return null;
        }
        c.pickItem = win.alt().id;
        c.pickTier = Math.max(c.pickTier, win.alt().tier);
        c.pickMade = true;                                  // 0.39.z33: a new pickaxe: its uses are fresh
        c.pickOut = false;                                  // spares 2: made into the simulated bag
        c.pickWorn = 0;
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
