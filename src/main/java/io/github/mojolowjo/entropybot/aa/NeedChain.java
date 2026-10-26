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
 * craft, place, smelt, kill. Pure (JUnit: NeedChainTest, ScenariosTest). Loader notes: none (the facts come from
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
    static final String STRIP_KEY = "strip ";

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
                            List<String> headings, int freshDist) {
        public StripBand {
            ores = List.copyOf(ores);
            headings = headings == null || headings.isEmpty() ? List.of(heading) : List.copyOf(headings);
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
        public StripBand toward(String h) { return new StripBand(ores, minY, maxY, y, perSlab, x, z, feetY, h, host, headings, freshDist); }
        /** 0.39.z64 item 4: the strip from fresh ground at (fx, fz), {@code dist} blocks away, heading {@code h}: a walk there first. */
        public StripBand freshAt(int fx, int fz, int dist, String h) { return new StripBand(ores, minY, maxY, y, perSlab, fx, fz, feetY, h, host, List.of(h), Math.max(1, dist)); }
    }

    /**
     * 0.39.z62 item 1b (run S 04:57:30-35: "strip east feet y 16: 0 blocks ... no path" three times, once per ore set - the
     * new crafting table stood on the tunnel's first cell): a strip blocked in its heading is a fact about the HEADING at that
     * Y, whatever the ores; held for the order under this key, every strip way at {@code y} tries its next heading.
     */
    public static final String STRIP_HEADING = "strip heading ";

    public static String headingKey(int y, String heading) { return STRIP_HEADING + "y " + y + " " + heading; }

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
            seenAll = w -> memo.computeIfAbsent(word(w), k -> { List<Spot> l = sa.apply(k); return l == null ? List.of() : List.copyOf(l); });
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

        /** chop: the same world less the sources {@code held} names (need word, spot): a failed source is out of the re-price. */
        public World without(java.util.function.BiPredicate<String, Spot> held) {
            Function<String, List<Spot>> sa = seenAll;
            return new World(bag, tableNear, furnaceNear, w -> sa.apply(w).stream().filter(sp -> !held.test(word(w), sp)).toList(), mobs, loot, band, horizonBlocks, path, stationAt, usesLeft, this.held, strip, cave, rooms, where);
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
                if (r == null) return null;
                // 0.39.z62 item 1b: a heading blocked at this Y is skipped for the next open one; the strip itself dropped
                // (its key: lava, no path down to the band, a tunnel that ended short) drops the way as before
                for (String hd : r.headings()) {
                    if (keyWhy.containsKey(headingKey(r.y(), hd))) continue;
                    StripBand t = r.toward(hd);
                    return keyWhy.containsKey(t.key()) ? null : t;
                }
                return null;
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
            return new World(bag, tn, fn, less.seenAll, mobs, loot, b, horizonBlocks, path, stationLess, usesLeft, h, stripLess, caveLess, rooms, where);
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
        public boolean waiting() { return missing != null && (missing.startsWith(LootBook.WAITING) || missing.startsWith(PathPrice.NOT_KNOWN) || missing.endsWith(SOURCE_NOT_LOADED) || missing.startsWith(MINING_WAIT)); }
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

    // ---- the recursion ----

    private static final class Ctx {
        final Trace trace = TRACE.get();
        final World w;
        final Map<String, Integer> bag = new LinkedHashMap<>();
        /** 0.39.z73 (D2 3.4): each step object -> {@link #seconds} when it was first added (Chain#stepSeconds; never restored). */
        final Map<Step, Double> addedAt = new java.util.IdentityHashMap<>();
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
                if (m != null) addedAt.putIfAbsent(s, m);
                return old;
            }
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
        /** 0.39.z75 item 1: a need is being priced before going down ({@link #beforeDown}): no second leg to another level in it. */
        boolean hoisting;
        /** 0.39.z75 item 3 (run AE 8): bag items an outer need has counted as had - set aside while its ways are priced. */
        final Map<String, Integer> aside = new LinkedHashMap<>();
        /** 0.39.z75 item 2: the burn the last {@link #burn} planned (the smelt step's counted fuel); null: none named. */
        Burn burned;

        Ctx(World w) {
            this.w = w;
            for (var e : w.bag().entrySet()) if (e.getValue() > 0) bag.merge(e.getKey(), e.getValue(), Integer::sum);
            dropped.addAll(w.held());                  // 0.39.z33: the sources the order dropped, with their reasons
            tableReady = w.tableNear();
            furnaceReady = w.furnaceNear();
            for (var e : w.bag().entrySet()) {
                if (e.getValue() <= 0) continue;
                int t = MiningTiers.toolTier(e.getKey(), "pickaxe");
                if (t > pickTier) { pickTier = t; pickItem = e.getKey(); }
            }
        }

        boolean dead() { return stuck || fail != null; }

        /** aa debug: a fact line under the way being priced (nothing outside a trace). */
        void note(Supplier<String> s) { if (trace != null) trace.note(s.get()); }

        /** What the bag holds of the item for this need: less what an outer need already counted as had (0.39.z75 item 3). */
        int have(String item) { return Math.max(0, bag.getOrDefault(item, 0) - aside.getOrDefault(item, 0)); }

        /** Everything a way changes, to put back when the way is dropped. */
        record Snap(Map<String, Integer> bag, List<Step> steps, Map<String, Integer> leafAt, Map<String, Integer> taken, List<String> dropped,
                    double seconds, double away, boolean tableReady, boolean furnaceReady, boolean stuck, String unknown, String pickItem, int pickTier,
                    boolean tableWalked, boolean furnaceWalked, int pickWorn, boolean pickMade, List<String> spares, boolean atRoom,
                    String down, int downAt, boolean tableDown, boolean furnaceDown) {}

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
            return new Snap(new LinkedHashMap<>(bag), new ArrayList<>(steps), new LinkedHashMap<>(leafAt), new LinkedHashMap<>(taken), new ArrayList<>(dropped),
                    seconds, away, tableReady, furnaceReady, stuck, unknown, pickItem, pickTier, tableWalked, furnaceWalked, pickWorn, pickMade, new ArrayList<>(spares), atRoom,
                    down, downAt, tableDown, furnaceDown);
        }

        void restore(Snap s) {
            bag.clear(); bag.putAll(s.bag());
            steps.clear(); steps.addAll(s.steps());
            leafAt.clear(); leafAt.putAll(s.leafAt());
            taken.clear(); taken.putAll(s.taken());
            dropped = new ArrayList<>(s.dropped());
            seconds = s.seconds(); away = s.away(); tableReady = s.tableReady(); furnaceReady = s.furnaceReady(); stuck = s.stuck();
            unknown = s.unknown(); pickItem = s.pickItem(); pickTier = s.pickTier();
            tableWalked = s.tableWalked(); furnaceWalked = s.furnaceWalked();
            pickWorn = s.pickWorn(); pickMade = s.pickMade();
            spares = new ArrayList<>(s.spares());
            atRoom = s.atRoom();
            down = s.down(); downAt = s.downAt(); tableDown = s.tableDown(); furnaceDown = s.furnaceDown();
            fail = null; loopOn = null;
        }
    }

    /** Rule 1 (have it): what the bag holds of the need, mixed members summed. */
    private static int have(Ctx c, Want w) {
        if (w.item()) return c.have(w.id());
        int n = 0;
        for (var e : c.bag.entrySet()) if (e.getValue() > 0 && w.has(e.getKey())) n += c.have(e.getKey());   // z75 item 3: less what is set aside
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
        for (var e : c.bag.entrySet()) {
            if (e.getValue() <= 0 || !w.has(e.getKey())) continue;
            int k = c.have(e.getKey());
            if (k > 0) out.put(e.getKey(), k);
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
        for (var e : c.bag.entrySet()) if (c.have(e.getKey()) > 0 && w.has(e.getKey())) return c.have(e.getKey()) >= n ? e.getKey() : w.word;
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
        Ctx c = new Ctx(w);
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
     */
    public record Legs(Chain chain, Map<Step, String> starts) {}

    public static Legs resolvePlaybookLegs(Playbook pb, World w) {
        Ctx c = new Ctx(w);
        List<java.util.Set<Step>> added = new ArrayList<>();
        Chain ch = playbookChain(c, pb, added);
        Map<Step, String> starts = new java.util.IdentityHashMap<>();
        if (ch.ok() && pb != null) {                              // SHAPE CHANGE: an explore trip says its legs too
            int prev = -1, n = Math.min(added.size(), pb.legs().size());
            for (int i = 0; i < n; i++) {
                int at = -1;
                for (int j = prev + 1; j < ch.steps().size(); j++) if (added.get(i).contains(ch.steps().get(j))) { at = j; break; }
                if (at < 0) continue;
                starts.put(ch.steps().get(at), "playbook leg " + (i + 1) + "/" + pb.legs().size() + ": " + legWords(pb.legs().get(i)));
                prev = at;
            }
        }
        return new Legs(ch, starts);
    }

    /** 0.39.z77 J2: a leg as the watcher reads it ("descent to y 16 for raw_iron 27, coal 4"). */
    static String legWords(Playbook.Leg leg) {
        List<String> g = new ArrayList<>();
        for (Playbook.Target t : leg.gets()) g.add(t.text());
        return switch (leg.kind()) {
            case SURFACE -> "on the surface: " + String.join(", ", g);
            case DESCENT -> (leg.on() ? "strip on at y " + leg.y() + " for " + String.join(", ", g) + " (with the new pickaxe)"
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

    private static Chain playbookChain(Ctx c, Playbook pb, List<java.util.Set<Step>> added) {
        if (pb == null || !pb.ok()) return playbookFails(c, pb == null ? "no playbook" : pb.fail());
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
        int legNo = 0;
        legs:
        for (Playbook.Leg leg : pb.legs()) {
            legNo++;
            java.util.Set<Step> before = ids(c.steps);
            if (leg.kind() == Playbook.Kind.DESCENT) {
                String p = playbookStrip(c, leg, levelY, exploreEnd >= 0);
                if (c.fail != null && legNo > 1 && !before.isEmpty() && waitsHere(c.fail)) {
                    later = "descent to y " + leg.y() + ": " + c.fail;
                    cutTo(c, before);
                    break;
                }
                if (c.fail != null) return playbookFails(c, "descent to y " + leg.y() + ": " + c.fail);
                if (c.stuck && exploreEnd >= 0) { later = secondUnknown(c); break; }     // price review 2 / SHAPE CHANGE: priced on its find
                if (c.stuck) return playbookFails(c, "descent to y " + leg.y() + ": its pickaxe ends in an explore (no known source for " + shw(c.unknown) + ")");
                if (p != null) phrases.add(p);
                levelY = leg.y();
                added.add(addedSince(c, before));
                continue;
            }
            boolean surface = leg.kind() == Playbook.Kind.SURFACE;
            String name = surface ? "surface" : "at band";
            List<String> got = new ArrayList<>();
            boolean wasHoisting = c.hoisting;
            if (surface) c.hoisting = true;                      // no leg to another level in the surface leg
            try {
                for (Playbook.Target t : leg.gets()) {
                    String item = id(t.item());
                    boolean gate = leg.gates().contains(item);
                    // a gate: the pickaxe the derivation chose for its tier (its leaves on this leg's side), made unless one digs it
                    String p = gate ? (leg.tier() > c.pickTier ? obtain(c, item, c.have(item) + 1) : null) : obtain(c, item, t.n());
                    if (c.fail == null && !c.stuck) madePick(c, item);
                    if (c.fail != null && legNo > 1 && !before.isEmpty() && waitsHere(c.fail)) {
                        later = name + ": " + sh(item) + ": " + c.fail;
                        cutTo(c, before);
                        break legs;
                    }
                    if (c.fail != null) return playbookFails(c, name + ": " + sh(item) + ": " + c.fail);
                    if (c.stuck) {
                        if (exploreEnd >= 0) { later = secondUnknown(c); break legs; }       // price review 2 / SHAPE CHANGE: priced on its find
                        if (!surface) return playbookFails(c, name + ": " + sh(item) + " ends in an explore (no known source for " + shw(c.unknown) + ")");
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
        if (exploreEnd >= 0) {
            c.stuck = true;
            c.unknown = exploreFor;
            c.steps.subList(exploreEnd, c.steps.size()).clear();       // the run's steps end at the explore; the price is all legs
        }
        String tail = later != null ? "; the legs from there are priced when the bot gets there (" + later + ")"
                : exploreEnd >= 0 ? ": " + s(c.seconds) + " priced with the legs after the explore" : "";
        return finish(c, PLAYBOOK, PLAYBOOK + " <- " + String.join(" > ", phrases) + tail);
    }

    /** 0.39.z77 SHAPE CHANGE: a leg's failure that is a wait from here (a walk not known, chunks, the loot book or tiers not read). */
    public static boolean waitsHere(String fail) {
        return fail != null && (fail.contains(PathPrice.NOT_KNOWN) || fail.startsWith(LootBook.WAITING) || fail.endsWith(SOURCE_NOT_LOADED) || fail.startsWith(MINING_WAIT));
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
     * walk priced from a future cell) - then per family its ore blocks x blocks per ore (the leg's ore per slab) x the tunnel
     * height x the host's break plus the ores' own breaks: the families summed (a tunnel finds both at once; rough on the safe
     * side). The strip leaves the bot down there ({@code c.down}): later needs are met there or before going down.
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
            if (sb == null || sb.ores().isEmpty()) { c.fail = "no strip facts for " + shw(w) + " here (no world-gen band at or below the feet, or none read)"; return null; }
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
        int tunnelBreaks = 0, oreTotal = 0;
        Map<String, Integer> amounts = new LinkedHashMap<>();
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
            double bpo = Math.min(Tuning.get("strip.maxBlocksPerOre"), io.github.mojolowjo.entropybot.aa.brain.OreBand.blocksPerOre(slab));
            int dug = (int) Math.ceil(oreBlocks * bpo - 1e-9);           // structural: epsilon
            int cells = baritone.api.process.IStripProcess.TUNNEL_HEIGHT * dug;
            double oreS = blockBreakS(c, ore);
            tunnelBreaks += cells;
            oreTotal += oreBlocks;
            double fam = cells * hostS + oreBlocks * oreS;
            digS += fam;
            amounts.merge(io.github.mojolowjo.entropybot.aa.brain.StripOrder.countKey(ore, io.github.mojolowjo.entropybot.cave.OreSpec::family), oreBlocks, Integer::sum);
            gains.merge(gives, Math.max(e.getValue(), (int) Math.floor(oreBlocks * per + 1e-9)), Integer::sum);     // structural: epsilon
            parts.add(shw(gives) + " " + e.getValue() + " (" + oreBlocks + " ore x ~" + Math.round(bpo) + " blocks = " + dug + " cells: " + s(fam) + ")");
        }
        int wear = walkBreaks + tunnelBreaks + oreTotal;
        String spare = spareTools(c, wear, tier);
        if (c.pickItem != null && spare == null) c.pickWorn += wear;
        c.seconds += walkS + digS;
        Step.Strip whole = new Step.Strip(ores, amounts, y, first.heading(), 0, null);
        int legAt = c.steps.size();
        if (firstLeg && first.feetY() != y) c.steps.add(new Step.ToStrip(first.x(), y, first.z(), whole.key()));
        List<Step> legs = stripLegs(whole, digS);
        c.steps.addAll(legs);
        if (firstLeg && first.feetY() != y) { c.down = "the playbook's descent to y " + y; c.downAt = legAt; c.away = 0; }
        else if (!firstLeg) c.down = "the playbook's descent to y " + y;
        else c.away = walkS;
        for (var e : gains.entrySet()) c.bag.merge(e.getKey(), e.getValue(), Integer::sum);
        // 0.39.z77 answers (2): the tunnel's own blocks come with the bot (the host's drop by the loot data, one a break): the
        // descent carries the blocks the climb back up and a furnace down there take (Deposit handles a full bag)
        String hostDrop = c.w.loot() == null ? host : null;
        if (c.w.loot() != null) for (LootBook.Source d : c.w.loot().blockDrops(host)) if (d.per() > 0) { hostDrop = d.item(); break; }
        int carried = tunnelBreaks + walkBreaks;
        if (hostDrop != null && carried > 0) c.bag.merge(id(hostDrop), carried, Integer::sum);
        int n = legs.size() / io.github.mojolowjo.entropybot.aa.brain.Goals.stripLoop(whole).size();
        final String line = "descent to y " + y + " (" + leg.whyY() + "): one strip of " + String.join(", ", ores.stream().map(NeedChain::shw).toList()) + " for "
                + String.join(" + ", parts) + (n > 1 ? " in " + n + " legs (steps.stepMaxS " + Math.round(Tuning.get("steps.stepMaxS")) + " s each)" : "")
                + ", walk " + s(walkS) + walkNote + " -> " + s(walkS + digS)
                + (hostDrop != null && carried > 0 ? "; carries " + carried + " " + sh(id(hostDrop)) + " (the tunnel's: the climb back's blocks)" : "");
        c.note(() -> line);
        String out = line + (pre == null ? "" : " with " + pre) + (spare == null ? "" : " + " + spare);
        return wk == null ? out : noted(out, wk);
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
        Ctx c = new Ctx(w);
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
        if (!c.steps.isEmpty() && !used.isEmpty()) c.uses.put(c.steps.get(c.steps.size() - 1), java.util.Collections.unmodifiableMap(new LinkedHashMap<>(used)));
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
        distinctLabels(alts);
        List<Trial> trials = new ArrayList<>();
        List<Trace.Node> nodes = new ArrayList<>();          // aa debug: each trial's node (empty outside a trace)
        int stepsAt = c.steps.size();
        int skipped = -1;
        Trace.Node waysNode = c.trace == null ? null : c.trace.push("ways for " + (self == null ? "this requirement" : shw(self)) + " (" + alts.size() + ")", false);
        try {
            for (int i = 0; i < alts.size(); i++) {
                if (c.trials > maxTrials() && trials.stream().anyMatch(t -> t.fail() == null && !t.stuck())) { skipped = i; break; }
                Ctx.Snap s0 = c.snap();
                double base = c.seconds;
                c.trials++;
                if (c.trace != null) nodes.add(c.trace.push("way " + alts.get(i).label, true));
                String p;
                try {
                    p = alts.get(i).run.get();
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
        c.restore(win.after());
        verdict(nodes, win, "picked" + (win.stuck() ? " (ends in an explore)" : ": " + s(win.seconds())));
        // dropped-audit (0.39.z16): every way not chosen is listed, a way ending in an explore too (it was left out when the
        // winner explored as well, or when it was a tag's or a group's member: the owner's charcoal vanished that way)
        for (Trial t : trials) {
            if (t == win) continue;
            String why;
            if (t.fail() != null) why = t.fail();
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
        if (c.w.seenAll().apply(w.word).isEmpty()) alts.addAll(caveAlts(c, w.word, w.word, lack));    // 0.39.z44
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
            c.seconds += Price.craftS() * batches;
            return verb + String.join(" + ", parts);
        }
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
        if (c.w.seenAll().apply(item).isEmpty()) alts.addAll(caveAlts(c, item, item, lack));    // 0.39.z44: mine in the cave
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
            double away0 = c.away;
            c.down = null;                                   // priced where the walks are priced from: the base, before the leg
            c.away = 0;
            c.hoisting = true;
            int moved = 0;
            try {
                Trial t = choose(c, ways.get(), self);
                if (t == null) {
                    if (c.fail == null) c.fail = "no way stands before going down either";
                    return null;
                }
                if (t.alt().id != null) a.id = t.alt().id;
                walkBack(c);                                 // back to where the leg starts, before it
                moved = c.steps.size() - s0;
                if (moved > 0) {
                    List<Step> mv = new ArrayList<>(c.steps.subList(s0, c.steps.size()));
                    c.steps.subList(s0, c.steps.size()).clear();
                    c.steps.addAll(at, mv);
                    Double mark = at + moved < c.steps.size() ? c.addedAt.get(c.steps.get(at + moved)) : null;
                    if (mark != null) for (Step s : mv) c.addedAt.put(s, mark);     // their seconds go with the leg's (stepSeconds)
                    for (var e : c.leafAt.entrySet()) {
                        int i = e.getValue();
                        if (i >= s0) e.setValue(at + (i - s0));
                        else if (i >= at) e.setValue(i + moved);
                    }
                }
                return BEFORE_DOWN + " (" + leg + "): " + t.phrase();
            } finally {
                c.down = leg;
                c.downAt = at + moved;
                c.away = away0;
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
            take(c, new Want(e.getKey()), want, g.id());
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
        c.seconds += Price.craftS() * batches;
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
        take(c, new Want(input), k, in.id());
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
        c.seconds += k * smeltTicks() / GameTimes.TPS;
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
        for (String id : c.bag.keySet()) if (c.have(id) > 0) free.put(id, c.have(id));
        var inBag = io.github.mojolowjo.entropybot.craft.CraftPlanner.pickFuel(free, k);
        if (inBag != null && have(c, new Want(inBag.id())) >= inBag.count()) {
            c.burned = new Burn(word(inBag.id()), inBag.count());
            return "burn " + counted(take(c, new Want(inBag.id()), inBag.count(), inBag.id())) + " (in the bag, the cheapest burn by worth)";
        }
        List<Alt> alts = new ArrayList<>(), woodAlts = new ArrayList<>();
        Map<Alt, Double> wood = new java.util.HashMap<>();
        for (var e : classes.entrySet()) {
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
                c.restore(before);
                for (String d : said) if (!c.dropped.contains(d)) c.dropped.add(d);
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
        String head = label + ": ";
        for (String d : c.dropped) if (d.startsWith(head) && d.contains("dearer than")) return true;
        return false;
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
                + (Nearby.isMain(null, far, null) ? " (" + Nearby.MAIN + " -" + Math.round(Math.max(0, far.bonusS())) + " s)" : "");
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
        if (c.w.rooms().hereFailed() != null && !c.atRoom && placesHere(c.steps)) {
            room = roomWalk(c, item);
            if (room == null) return null;
        }
        c.steps.add(new Step.PlaceNear(sh(item)));
        c.seconds += Price.placeS();
        c.note(() -> "place " + sh(item) + " near me: " + s(Price.placeS()));
        c.bag.merge(item, -1, Integer::sum);
        if (c.down != null) { if (table) c.tableDown = true; else c.furnaceDown = true; }        // 0.39.z75: down there, not at the base
        else if (table) { c.tableReady = true; c.tableWalked = true; } else { c.furnaceReady = true; c.furnaceWalked = true; }
        c.away = 0;                                       // placed near where the bot stands
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
            Walked o = c.walks.get(sp.x() + "," + sp.y() + "," + sp.z());
            if (o != null && o.fail() == null) drop(c, "place " + sh(item) + " near the cell with room at " + sp.x() + " " + sp.y() + " " + sp.z(), s(o.seconds()));
        }
        c.steps.add(new Step.Walk(at.x(), at.y(), at.z(), roomNear()));
        c.seconds += w.seconds();
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
        double walkS = Math.max(c.away, wk.seconds());
        double bonus = Math.min(walkS, Math.max(0, at.bonusS()));       // a use-stations station: seconds off its walk (never below 0)
        c.seconds += walkS - bonus;
        c.away = 0;
        // 0.39.z30, rule 15: a remembered station in a chunk not loaded is priced as remembered; the step verifies it on
        // arrival (not the station: the fact becomes none and the order re-plans)
        boolean main = Nearby.isMain(null, at, null);                   // main-stations (0.39.z35): the owner's main of the kind
        return noted("at the " + label + " " + Math.round(at.dist()) + " m" + (main ? " (" + Nearby.MAIN + ")" : "")
                + (bonus > 0 ? " (" + (main ? Nearby.MAIN + (bonus > MainStations.bonusS() ? " + " + io.github.mojolowjo.entropybot.guard.AreaTags.USE_STATIONS : "") : io.github.mojolowjo.entropybot.guard.AreaTags.USE_STATIONS) + " -" + Math.round(bonus) + " s)" : "")
                + (at.unloaded() ? " (" + UNVERIFIED + ")" : ""), wk);
    }

    /** Back to the stations after a trip to a new source: the same walk as the trip there (its path, or its line). */
    private static void walkBack(Ctx c) {
        if (c.away > 0) { c.seconds += c.away; c.away = 0; }
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
        Walked out = c.walks.get(key);
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
            c.walks.put(key, out);
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
            alts.add(new Alt((tree(word, first) ? "tree " : shw(gives) + " ") + Math.round(first.dist()) + " m", rk.run(first, () -> sourceFrom(c, word, gives, lack, order))));
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
                    if (pp != null) pp.rankedOut(first.x(), first.y(), first.z());
                    c.fail = RANKED + " number " + (order.indexOf(first) + 1) + " of " + order.size() + " by Baritone's estimate (no search, not a price): only the top "
                            + top + " ways get a priced path (pathCost.pricedTop, rule 18)";
                    return null;
                }
                String p = way.get();
                Walked wk = c.walks.get(first.x() + "," + first.y() + "," + first.z());
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
            if (wk.kind() == PathPrice.Kind.UNREACHABLE) c.unreachable.put(spotKey(word, sp), wk.fail());   // 0.39.z69 fix 1: that spot only
            return null;
        }
        c.steps.add(new Step.Walk(sp.x(), sp.y(), sp.z(), stationNear()));
        c.steps.add(new Step.TakeStation(shw(gives), sp.x(), sp.y(), sp.z()));
        c.seconds += wk.seconds();
        c.away = wk.seconds();
        double each = breakS(c, gives, sp);
        c.seconds += each;
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
        if (s instanceof Step.Strip st) return STRIP_KEY + st.key();      // 0.39.z43: a strip that ended lava / no path / no pickaxe
        if (s instanceof Step.ToStrip t) return STRIP_KEY + t.strip();    // 0.39.z46: no path down to the band: the same strip way
        if (s instanceof Step.ToSurface t) return surfaceKey(t.x(), t.y(), t.z());   // 0.39.z68: no way up: explore from here
        return null;
    }

    /** 0.39.z65 review 1: the source key of a known station used at its cell ("station at x y z"; a take is "take at"). */
    public static String stationKey(int x, int y, int z) { return STATION_AT + x + " " + y + " " + z; }

    /** 0.39.z65 review 1: the prefix of {@link #stationKey}. */
    public static final String STATION_AT = "station at ";

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
                    if (wk.kind() == PathPrice.Kind.UNREACHABLE) c.unreachable.put(spotKey(word, sp), wk.fail());   // the z33 source fact; z69 fix 1: that spot only
                    if (sp == first) return null;           // the way's own spot: the way cannot be picked
                    String why = c.fail;                    // a further spot: this way goes on without it (its walk is not a price)
                    c.fail = null;
                    c.leafAt.remove(sp.key());
                    c.steps.remove(c.steps.size() - 1);
                    c.note(() -> "spot " + sp.x() + " " + sp.y() + " " + sp.z() + " left out: " + why);
                    continue;
                }
                if (firstWalk == null) firstWalk = wk;
                c.seconds += wk.seconds();
                c.away = wk.seconds();
            }
            double each = breakS(c, gives, sp);
            c.seconds += each * take;
            final int took = take;
            c.note(() -> "break " + shw(blockOf(gives, sp)) + " x" + took + ": tool " + (c.pickItem == null || tierOf(gives, sp) < 1 ? "hand" : sh(c.pickItem)) + ", " + s(each) + " each = " + s(each * took));
            // 0.39.z74 item 1 (run AC 16:51:46: "stick 5 m: 11.9 s" won over "stick from #planks: 28.6 s"; the leaves gave 1 stick
            // in ~90 s): a block that gives the item only by chance (expected yield under 1 a block) is many scattered blocks,
            // each its own trip - every block after the first is charged the spot's walk again (the walk already priced; rule
            // 14: the same rough numbers, no finer term). A block that drops the item for sure is one patch, as before.
            double per = perBlock(c, gives, sp.block());
            Walked spotWalk = c.walks.get(sp.key());
            if (per < 1 && take > 1 && spotWalk != null && spotWalk.fail() == null && spotWalk.seconds() > 0) {
                double again = spotWalk.seconds() * (take - 1);
                c.seconds += again;
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
        for (var e : c.bag.entrySet()) {
            if (e.getValue() <= 0) continue;
            int t = MiningTiers.toolTier(e.getKey(), "pickaxe");
            if (t > c.pickTier) { c.pickTier = t; c.pickItem = e.getKey(); }
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
        for (var e : c.bag.entrySet()) if (e.getValue() > 0) dmg = Math.max(dmg, ItemWorth.facts(e.getKey()).attack());
        double killS = Math.ceil(hp / dmg - 1e-9) * HIT_S;  // structural: epsilon
        c.steps.add(new Step.HuntAt(s.from(), kills, new int[]{m.x(), m.z()}, sh(item), lack));
        Walked wk = walk(c, m.x(), m.y(), m.z(), m.dist());
        if (c.fail != null) return null;                    // 0.39.z41: no price for the walk
        c.seconds += wk.seconds() + kills * killS;
        c.away = wk.seconds();
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
            String leg = bandLeg(c, new int[]{top, top});
            if (leg == null && c.fail != null) return null;
            if (BAND_DROPPED.equals(leg)) { c.fail = "no path down to " + shw(base.block()) + " under the feet"; return null; }
            c.walks.put(probe.key(), new Walked(0, "in reach from the hole the walk down made", null, PathPrice.Kind.PRICED));
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
        Alt a = new Alt("strip at y " + sb.y(), () -> strip(c, word, gives, lack, sb));
        a.id = gives;
        List<Alt> out = new ArrayList<>(List.of(a));
        // 0.39.z64 item 4 (runs S-U: every heading from one origin; run U's area 969 faces, 0 iron): once the areas along
        // this heading are seen (strip.areaMinFaces), strips from fresh ground in each heading are ways too - each starting at
        // the nearest cell whose area square is not seen yet (review 6), priced by Baritone's walk there (rule 18) and its own
        // areas' yield; the cheapest wins (rule 1). Review 4: each is its own Alt, priced on a snapshot (choose): a walk not
        // known yet (computing, chunks) or failing drops that Alt only, never the strip from the feet; the walks are new goals
        // to the fork's queue, so they rank FIRST (below the running step's walk, PathMemory.Prio) and REST once answered
        var seen = io.github.mojolowjo.entropybot.aa.brain.StripAreas.along(sb.x(), sb.y(), sb.z(), sb.heading(), sb.ores());
        if (io.github.mojolowjo.entropybot.aa.brain.StripAreas.judged(seen)) {
            for (String h : io.github.mojolowjo.entropybot.aa.brain.StripOrder.HEADINGS) {
                int[] at = io.github.mojolowjo.entropybot.aa.brain.StripAreas.freshStart(sb.x(), sb.y(), sb.z(), h);
                if (at == null) continue;
                StripBand f = sb.freshAt(at[0], at[1], at[2], h);
                if (heldKey(c, f.key())) continue;
                Alt fa = new Alt("strip at y " + sb.y() + " from fresh ground " + at[2] + " m " + h, () -> strip(c, word, gives, lack, f));
                fa.id = gives;
                out.add(fa);
            }
        }
        return out;
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
            if (wk.kind() == PathPrice.Kind.UNREACHABLE) c.unreachable.put(sb.key(), wk.fail());   // the z33 source fact: the band dropped
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
        double band = io.github.mojolowjo.entropybot.aa.brain.OreBand.blocksPerOre(sb.perSlab());
        // 0.39.z64 item 4: the areas along this heading as seen (faces, the wanted ores on them) scale the band's estimate
        var seen = io.github.mojolowjo.entropybot.aa.brain.StripAreas.along(sb.x(), sb.y(), sb.z(), sb.heading(), sb.ores());
        double scale = io.github.mojolowjo.entropybot.aa.brain.StripAreas.factor(io.github.mojolowjo.entropybot.aa.brain.StripAreas.perFace(sb.perSlab()), seen);
        double bpo = Math.min(Tuning.get("strip.maxBlocksPerOre"), band * scale);
        int dug = (int) Math.ceil(oreBlocks * bpo - 1e-9);   // structural: epsilon
        double hostS = blockBreakS(c, sb.host() != null ? sb.host() : ore), oreS = blockBreakS(c, ore);
        // 0.39.z64 review 1: a tunnel cell is TUNNEL_HEIGHT blocks broken (feet and head), its seconds as its wear
        int tunnelBreaks = baritone.api.process.IStripProcess.TUNNEL_HEIGHT * dug;
        double digS = tunnelBreaks * hostS + oreBlocks * oreS;
        // 0.39.z62 item 2 (run S 04:57:03: the stone pickaxe broke after the dug shaft from y 64 plus 46 strip blocks): the
        // way's pickaxe wear - the walk's pickaxe blocks (Baritone's path) + the tunnel + the ores - against the pickaxe's
        // uses; past them, the spare pickaxes are planned up front (spareTools)
        // 0.39.z64 item 2 (run U 06:08:07-06:10:19: a fresh stone pickaxe, 131 uses, broke after 67 strip cells; the chain's
        // spares ran out at y 16 and the next one needed wood from the surface): each fresh tunnel cell is two blocks
        // (the fork's GoalBlock digs the feet and the head cell), so the tunnel wears the pickaxe twice per cell dug
        int wear = wk.pickBreaks() + tunnelBreaks + oreBlocks;
        String spare = spareTools(c, wear, tier);
        c.seconds += wk.seconds() + digS;
        // 0.39.z75 item 1 (D4 1; run AE 17:27:35: the furnace placed at y 16 zeroed the walk back, the table up there priced
        // 0 s): a strip at another level than the feet leaves the bot down there - the walk back up is no mirror of the way
        // down (a dug shaft is not walked up, D4 2) and no path from there is priced: later walks are "not known" (walk) and
        // later needs are met there or before the leg (beforeDown). A strip at the feet's own level stays as before.
        boolean level = sb.feetY() != sb.y();
        c.away = level ? 0 : wk.seconds();
        if (c.pickItem != null && spare == null) c.pickWorn += wear;
        Map<String, Integer> amounts = Map.of(io.github.mojolowjo.entropybot.aa.brain.StripOrder.countKey(ore, io.github.mojolowjo.entropybot.cave.OreSpec::family), oreBlocks);
        Step.Strip strip = new Step.Strip(sb.ores(), amounts, sb.y(), sb.heading(), 0, sb.from());
        // 0.39.z46 (S58): the first leg is the priced path down to the band's peak (the walk the price above searched);
        // 0.39.z64 item 4: a strip from fresh ground walks to its start first
        int legAt = c.steps.size();
        if (sb.feetY() != sb.y() || sb.fresh()) c.steps.add(new Step.ToStrip(sb.x(), sb.y(), sb.z(), strip.key()));
        c.steps.addAll(stripLegs(strip, digS));         // 0.39.z77 J2 (R2): past steps.stepMaxS, legs of the same strip
        if (level) {
            c.down = "the strip leg to y " + sb.y();
            c.downAt = legAt;
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
     * 0.39.z62 item 2 (rule 14 rough; run S: one stone pickaxe for the shaft from y 64 to 16 and the strip, broken after 46
     * strip blocks, re-planned mid-strip): a way that breaks {@code wear} blocks with the chain's pickaxe ({@code c.pickItem})
     * past its uses - a pickaxe made in this chain: its max damage (the item's MAX_DAMAGE data, {@link ItemWorth.Facts#durability});
     * one in the bag: its uses left ({@link World#usesLeft}) - less what this chain already wore. z62 review 1: the bag's other
     * pickaxes that dig {@code tier} count next (each stack's uses left; an unknown one is not counted, one that never wears
     * ends the check); only what is still over gets spare pickaxes of the same kind made up front, {@code ceil(over / fresh
     * uses)}, at most {@code tools.maxSpares} (review 3: past it, said; the rest is re-planned when a pickaxe breaks), priced
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
        for (var e : c.bag.entrySet()) {
            int k = e.getValue() - (e.getKey().equals(tool) ? 1 : 0);
            if (k <= 0 || MiningTiers.toolTier(e.getKey(), "pickaxe") < tier) continue;
            Integer u = c.w.usesLeft().apply(e.getKey());
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
        int extra = Math.min(need, cap);
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
        if (extra < need) c.spare(sh(tool) + ": " + need + " spare " + sh(kind) + " would cover " + wear + " blocks; tools.maxSpares " + cap + " holds: " + extra + " made, the rest re-planned when a pickaxe breaks");
        if (extra <= 0) return null;
        c.bag.merge(kind, -extra, Integer::sum);            // they wear out in this way
        c.pickMade = true;
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
     */
    private static List<Alt> caveAlts(Ctx c, String word, String gives, int lack) {
        CaveWay cw = c.w.cave().apply(word);
        if (cw == null || cw.ore() == null || !(cw.perMin() > 0)) return List.of();
        Alt a = new Alt((cw.candidate() ? "cave candidate " : "cave mouth ") + Math.round(cw.dist()) + " m", () -> cave(c, word, gives, lack, cw));
        a.id = gives;
        return List.of(a);
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
            if (wk.kind() == PathPrice.Kind.UNREACHABLE) c.unreachable.put(cw.key(), wk.fail());
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
        c.seconds += wk.seconds() + confirmS + exploreS + oreS;
        c.away = wk.seconds() + exploreS;
        if (c.pickItem != null) c.pickWorn += oreBlocks;
        c.steps.add(new Step.Walk(cw.x(), cw.y(), cw.z(), 0));
        c.steps.add(new Step.Mine(cw.family(), oreBlocks, Step.Mine.How.CAVE, (int) Math.ceil(max)));
        c.bag.merge(gives, Math.max(lack, (int) Math.floor(oreBlocks * per + 1e-9)), Integer::sum);  // structural: epsilon
        final String line = head + ", " + yield + " x " + oreBlocks + " ore" + (cw.candidate() ? " + confirm " + s(confirmS) : "")
                + " -> " + s(wk.seconds() + confirmS + exploreS + oreS);
        c.note(() -> "source: none known for " + shw(word) + ": in the cave: " + line);
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
        if (walk == null) for (var e : c.unreachable.entrySet()) if (e.getKey().startsWith(SURFACE_AT)) { walk = e.getKey() + ": " + e.getValue(); break; }
        if (walk == null) walk = "no priced walk up";
        String climbed = heldLine(c, CLIMB_KEY);
        int blocks = 0;
        for (var e : c.bag.entrySet()) if (c.have(e.getKey()) > 0 && ItemWorth.stoneLike(e.getKey())) blocks += c.have(e.getKey());
        boolean pick = c.pickTier >= 1;      // structural: any pickaxe (the bot's tier scale: 1 = the least pickaxe)
        if (climbed != null) { c.fail = noWalkUp(over) + " - the walk up: " + walk + "; the climb: " + climbed; return null; }
        if (!pick && blocks < over.depth()) {
            c.fail = noWalkUp(over) + " - the walk up: " + walk + "; no climb either: no pickaxe and " + blocks + " blocks in the bag for " + over.depth() + " up";
            return null;
        }
        double sec = Unit.seconds(over.depth() * RungOptions.climbBlock() + RungOptions.climbFix());
        c.steps.add(new Step.Climb());
        c.seconds += sec;
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
            c.unreachable.put(io.github.mojolowjo.entropybot.aa.moves.FactChange.bandKey(band[0], band[1]), wk.fail());
            c.fail = null;
            c.note(() -> "band walk: no path into " + tb.text() + " (" + wk.fail() + "): the explore runs from here");
            return BAND_DROPPED;
        }
        int digs = wk.pickBreaks();
        double handS = wk.seconds();
        String head = (feet[1] > ty ? "dig down" : "walk") + " to y " + ty;
        if (digs <= 0) {
            c.steps.add(tb);
            c.seconds += handS;
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
            c.seconds += s;
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
                    c.seconds = s0 + withS;
                    final double fp = pickS, fw = withS, fs = saved;
                    c.note(() -> "band walk: " + digs + " blocks to break - the pickaxe first " + s(fp) + " + the walk " + s(handS - fs) + " = " + s(fw) + " beats by hand " + s(handS));
                    return head + " with " + sh(c.pickItem) + " made first (" + digs + " blocks to break; pickaxe " + s(pickS) + " + walk " + s(handS - saved) + " = " + s(withS)
                            + ", by hand " + s(handS) + "; <- " + pick + ")" + (spare == null ? "" : " + " + spare);
                }
                c.restore(before);
            }
        }
        c.steps.add(tb);
        c.seconds += handS;
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
            c.unreachable.put(surfaceKey(s.x(), s.y(), s.z()), wk.fail());
            c.fail = null;
            c.note(() -> "surface: no path up to " + s.x() + " " + s.y() + " " + s.z() + " (" + wk.fail() + "): the explore runs from here");
            return null;
        }
        c.steps.add(new Step.ToSurface(s.x(), s.y(), s.z()));
        c.seconds += wk.seconds();
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
            if (c.fail == null || !c.fail.startsWith(PathPrice.NOT_KNOWN)) c.fail = "no pickaxe of tier " + tier + " can be had to dig" + (alts.isEmpty() ? ": none in the game data can be crafted" : "");
            return null;
        }
        c.pickItem = win.alt().id;
        c.pickTier = Math.max(c.pickTier, win.alt().tier);
        c.pickMade = true;                                  // 0.39.z33: a new pickaxe: its uses are fresh
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
