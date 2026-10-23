package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * simple-tools (0.39.w1): the one answer to "what is nearby". Baritone is asked first, through the same cached world scan its
 * mine process uses ({@code IWorldScanner.scanChunkRadius}); AA's own sightings are only the fallback when that scan finds
 * nothing. {@link NeedChain} reads this fact and no other.
 * <ul>
 * <li>{@link #log}: the nearest log block (any block the game tags as logs) that has leaves (any block tagged leaves) within
 *     {@code nearby.leafReach} blocks above or beside it, i.e. a tree and not a stack of logs in a build.</li>
 * <li>{@link #nearest}: live-facts (0.39.y3): the nearest of any block set (NeedChain's source of any item: {@link #sourceBlocks}
 *     from the game's loot data plus the item's own block); a block tagged logs only as a tree (the {@link #log} rule), an ore
 *     cell only under the fair-play rule ({@link Scan#fairOk}), the policy allowing.</li>
 * </ul>
 * Pure (JUnit: NearbyTest, NeedLiveFactsTest); the live {@link Scan} is {@code commands.NearbyLive}. Loader notes: Baritone API only.
 */
public final class Nearby {
    private Nearby() {}

    /** What Baritone's scan and the level answer. Lists need not be sorted. */
    public interface Scan {
        /** Log blocks (tagged logs) within {@code radius}, as {x, y, z}. */
        List<int[]> logs(int radius);
        /** Blocks of any of the ids within {@code radius}, as {x, y, z}. */
        List<int[]> find(Set<String> blocks, int radius);
        /** The game tags the block as logs. */
        boolean isLog(String block);
        /** S26: the game tags the block as an ore (c:ores): its spot is a cluster, mined whole. */
        default boolean isOre(String block) { return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.hasTag(block, "c:ores"); }
        /** The fair-play rule at the cell: an ore cell counts only when seen (aa fair on); any other cell: true. */
        boolean fairOk(int x, int y, int z);
        /** A block tagged leaves within {@code reach} blocks beside or above the cell. */
        boolean leavesNear(int x, int y, int z, int reach);
        /** The policy lets me work at the cell. */
        boolean allowed(int x, int y, int z);
        /** The block id at the cell. */
        String blockAt(int x, int y, int z);
        /** The most logs one chop takes as one tree (Baritone's chopComponentMax); unknown: no cap. */
        default int treeCap() { return Integer.MAX_VALUE; }  // structural: no cap known
        /** station-memory: the stations I placed myself (the ledger), any distance, loaded or not, as {x, y, z} with their block. */
        default List<Mine> own() {
            // the restore ledger's "built: station" entries through the OwnStations.placed hook (RestoreLive.load sets it;
            // unset: OwnStations logs it once per world)
            List<Mine> out = new ArrayList<>();
            for (var m : io.github.mojolowjo.entropybot.clear.OwnStations.placed()) out.add(new Mine(m.x(), m.y(), m.z(), m.block()));
            return out;
        }
        /**
         * station-stale (0.39.z18, rule 15): a station of mine in a loaded chunk holds another block now: its ledger record
         * goes (the OwnStations.forget hook) and the line is logged. Returns the line.
         */
        default String forgetOwn(Mine m, String now) {
            String line = forgotLine(m, now);
            String r = io.github.mojolowjo.entropybot.clear.OwnStations.forget(m.x(), m.y(), m.z(), "the block there is " + now + " now");
            io.github.mojolowjo.entropybot.Quiet.line(line + " (" + r + ")");
            return line;
        }
    }

    /** station-stale: "stations: forgot my crafting_table at x y z: the block there is &lt;id&gt; now". */
    public static String forgotLine(Mine m, String now) {
        return "stations: forgot my " + m.block().replaceFirst("^minecraft:", "") + " at " + m.x() + " " + m.y() + " " + m.z()
                + ": the block there is " + now + " now";
    }

    /** station-stale: how many of my stations of the set sit in unloaded chunks (kept, not verified). */
    public static int unverified(Scan s, Set<String> blocks) {
        int n = 0;
        if (s == null) return 0;
        for (Mine m : s.own()) if (m != null && m.block() != null && (blocks == null || blocks.contains(m.block())) && s.blockAt(m.x(), m.y(), m.z()) == null) n++;
        return n;
    }

    /**
     * station-note (0.39.z15): the line of one station lookup: "stations: N of mine, M scanned within R, nearest &lt;block&gt;
     * &lt;dist&gt; m at x y z", or "stations: none known". {@code spots} is {@link #stations}' answer; a spot is "mine" when
     * {@code own} has its cell with a block of the set.
     */
    public static String stationsLine(List<Spot> spots, List<Mine> own, Set<String> blocks, int scanR) {
        if (spots == null || spots.isEmpty()) return "stations: none known";
        Set<String> mine = new java.util.HashSet<>();
        if (own != null) for (Mine m : own) if (m != null && (blocks == null || blocks.contains(m.block()))) mine.add(key(m.x(), m.y(), m.z()));
        int n = 0;
        for (Spot s : spots) if (mine.contains(key(s.x(), s.y(), s.z()))) n++;
        Spot a = spots.get(0);
        String block = a.block() == null ? "?" : a.block().replaceFirst("^minecraft:", "");
        return "stations: " + n + " of mine, " + (spots.size() - n) + " scanned within " + scanR + ", nearest " + block + " "
                + Math.round(a.dist()) + " m at " + a.x() + " " + a.y() + " " + a.z();
    }

    /**
     * station-race (0.39.z17, rule 15): the line of one lookup in each of its three states: "stations: item data not loaded
     * yet", "stations: none known", or the {@link #stationsLine(List, List, Set, int)} line of the known ones.
     */
    public static String stationsLine(StationFact f, List<Mine> own, Set<String> blocks, int scanR) {
        return switch (f) {
            case StationFact.NotLoaded n -> StationFact.NOT_LOADED_LINE + (NOT_LOADED.equals(n.why()) ? "" : " (" + n.why() + ")");
            case StationFact.None n -> "stations: none known" + (NONE_KNOWN.equals(n.why()) ? "" : " (" + n.why() + ")");
            case StationFact.Known k -> stationsLine(k.spots(), own, blocks, scanR);
        };
    }

    static final String NOT_LOADED = "item data not loaded yet", NONE_KNOWN = "none known";

    /**
     * station-race (0.39.z17, rule 15): the station fact of a kind from its block set: not loaded stays not loaded (no scan,
     * no ledger read on an unknown set), none stays none, a set is looked up ({@link #stations}) and an empty result is none.
     */
    public static StationFact stationFact(Scan s, int[] me, StationBlocks.Answer blocks) {
        return switch (blocks) {
            case StationBlocks.Answer.NotLoaded n -> new StationFact.NotLoaded(n.why());
            case StationBlocks.Answer.None n -> new StationFact.None(n.why());
            case StationBlocks.Answer.Of o -> {
                List<Spot> l = stations(s, me, o.ids());
                yield l.isEmpty() ? new StationFact.None(NONE_KNOWN) : new StationFact.Known(l);
            }
        };
    }

    /** station-memory: one of my placements as the ledger has it. */
    public record Mine(int x, int y, int z, String block) {}

    /**
     * station-memory (0.39.z14, owner 2026-10-22: "it should walk to the crafting table it placed and make it there"): every
     * known station of the block set, nearest first: my own placements (the ledger, at any distance; their recorded block)
     * and every block of the set Baritone's world scan finds. One list, no distance limit on what I placed: a known station is
     * dropped only when the bot arrives and finds it gone (the ledger record goes then), never for being far.
     */
    public static List<Spot> stations(Scan s, int[] me, Set<String> blocks) {
        List<Spot> out = new ArrayList<>();
        if (s == null || me == null || blocks == null || blocks.isEmpty()) return out;
        Set<String> seen = new java.util.HashSet<>();
        for (Mine m : s.own()) {
            if (m == null || m.block() == null || !blocks.contains(m.block())) continue;
            int[] p = {m.x(), m.y(), m.z()};
            String now = s.blockAt(p[0], p[1], p[2]);          // null: the chunk is not loaded (kept, unverified)
            if (now != null && !now.equals(m.block())) {       // station-stale (0.39.z18): loaded and gone: dropped at lookup
                s.forgetOwn(m, now);
                continue;
            }
            if (seen.add(key(p[0], p[1], p[2]))) out.add(new Spot(p[0], p[1], p[2], dist(p, me), m.block()));
        }
        for (int[] p : s.find(blocks, scanR())) {
            if (!seen.add(key(p[0], p[1], p[2]))) continue;
            String id = s.blockAt(p[0], p[1], p[2]);
            if (id == null || !blocks.contains(id)) continue;
            out.add(new Spot(p[0], p[1], p[2], dist(p, me), id));
        }
        out.sort(Comparator.comparingDouble(Spot::dist));
        return out;
    }

    /**
     * live-facts, one-planner: the blocks that give an item: the blocks the game's loot data says drop it, then the item's
     * own block ({@code ownBlock}, null: none), the same pairing the collect step uses. Empty: no block gives it (a mob
     * drop, an unknown item).
     */
    public static Set<String> sourceBlocks(String item, LootBook book, Function<String, String> ownBlock) {
        Set<String> out = new LinkedHashSet<>();
        if (item == null) return out;
        String id = item.indexOf(':') < 0 ? "minecraft:" + item : item;
        if (book != null) out.addAll(book.blocksDropping(id));
        String own = ownBlock == null ? null : ownBlock.apply(id);
        if (own != null) out.add(own);
        return out;
    }

    /**
     * TAG_NEEDS: the blocks that give any item of a need word (an item id, a tag "#ns:path", or several joined by "|"): a tag's
     * part is every block whose loot drops a member (the loot data's own index, no member list) and every block whose own item
     * is a member ({@code tagBlocks}: the game's tag index; null: none); an item's part is {@link #sourceBlocks}.
     */
    public static Set<String> sourceBlocksOf(String word, LootBook book, Function<String, String> ownBlock, Function<String, Set<String>> tagBlocks) {
        Set<String> out = new LinkedHashSet<>();
        String w = NeedChain.word(word);
        if (w == null) return out;
        for (String part : w.split("\\|")) {
            if (!part.startsWith("#")) { out.addAll(sourceBlocks(part, book, ownBlock)); continue; }
            String tag = part.substring(1);
            if (book != null) out.addAll(book.blocksDroppingAny(i -> io.github.mojolowjo.entropybot.aa.brain.ItemWorth.isIn(i, tag)));
            if (tagBlocks != null) out.addAll(tagBlocks.apply(tag));
        }
        return out;
    }

    /**
     * TAG_NEEDS: what a block gives of a need word: the drop of its loot that is in the need (the loot data), else its own item
     * when that is in it ({@code ownItem}); null: neither (the chain then counts the need itself).
     */
    public static String gives(String block, String word, LootBook book, Function<String, String> ownItem) {
        if (block == null || word == null) return null;
        if (book != null) for (LootBook.Source s : book.blockDrops(block)) if (NeedChain.gives(word, s.item())) return s.item();
        String own = ownItem == null ? null : ownItem.apply(block);
        return own != null && NeedChain.gives(word, own) ? own : null;
    }

    /** The nearest block of the set I may work at by Baritone's scan (a log only with leaves by it, an ore only if fair), else the fallback; null: none. */
    public static Spot nearest(Scan s, int[] me, Set<String> blocks, Supplier<Spot> fallback) {
        if (s != null && blocks != null && !blocks.isEmpty()) {
            int checked = 0;
            for (int[] p : sorted(s.find(blocks, scanR()), me)) {
                if (checked++ >= maxChecks()) break;
                String id = s.blockAt(p[0], p[1], p[2]);
                if (id == null || !blocks.contains(id) || !s.allowed(p[0], p[1], p[2]) || !s.fairOk(p[0], p[1], p[2])) continue;
                if (s.isLog(id) && !s.leavesNear(p[0], p[1], p[2], leafReach())) continue;
                return new Spot(p[0], p[1], p[2], dist(p, me), id, s.isLog(id) ? treeLogs(s.logs(scanR()), p, s.treeCap())
                        : s.isOre(id) ? clusterSize(s, blocks, p) : 0);
            }
        }
        return fallback == null ? null : fallback.get();
    }

    public static int scanR() { return Tuning.i("nearby.scanR"); }
    public static int maxChecks() { return Tuning.i("nearby.maxChecks"); }
    public static int leafReach() { return Tuning.i("nearby.leafReach"); }

    /** The nearest tree's log by Baritone's scan, else the fallback (AA's sightings); null: none. */
    public static Spot log(Scan s, int[] me, Supplier<Spot> fallback) {
        if (s != null) {
            int checked = 0;
            List<int[]> logs = s.logs(scanR());
            for (int[] p : sorted(logs, me)) {
                if (checked++ >= maxChecks()) break;
                if (!s.allowed(p[0], p[1], p[2]) || !s.leavesNear(p[0], p[1], p[2], leafReach())) continue;
                return new Spot(p[0], p[1], p[2], dist(p, me), s.blockAt(p[0], p[1], p[2]), treeLogs(logs, p, s.treeCap()));
            }
        }
        return fallback == null ? null : fallback.get();
    }

    /**
     * S20: the tree's log count, the fork chop's own rule (its ChopTree.component): the log cells of Baritone's scan connected to
     * {@code start} through the 26 neighbours, at most {@code cap} (Baritone's chopComponentMax). At least 1 (the start).
     */
    public static int treeLogs(List<int[]> logs, int[] start, int cap) {
        Set<String> cells = new java.util.HashSet<>();
        if (logs != null) for (int[] p : logs) cells.add(key(p[0], p[1], p[2]));
        cells.add(key(start[0], start[1], start[2]));
        Set<String> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        seen.add(key(start[0], start[1], start[2]));
        queue.add(start);
        int n = 0;
        while (!queue.isEmpty() && n < cap) {
            int[] at = queue.poll();
            n++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                String k = key(at[0] + dx, at[1] + dy, at[2] + dz);
                if (cells.contains(k) && seen.add(k)) queue.add(new int[]{at[0] + dx, at[1] + dy, at[2] + dz});
            }
        }
        return n;
    }

    /**
     * S26 (owner 2026-10-22, "a cluster of coal ore is one job; we know its block count from the scan"): an ore spot's cluster,
     * the fork's component rule (ChopTree.component, 26 neighbours, cap chopComponentMax) over the scan's cells of the same
     * block set that fair play lets me count. At least 1.
     */
    public static int clusterSize(Scan s, Set<String> blocks, int[] start) {
        List<int[]> cells = new ArrayList<>();
        for (int[] q : s.find(blocks, scanR())) if (s.fairOk(q[0], q[1], q[2])) cells.add(q);
        return treeLogs(cells, start, s.treeCap());
    }

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }

    private static List<int[]> sorted(List<int[]> in, int[] me) {
        List<int[]> out = new ArrayList<>(in == null ? List.of() : in);
        out.sort(Comparator.comparingDouble(p -> dist(p, me)));
        return out;
    }

    private static double dist(int[] p, int[] me) {
        double dx = p[0] - me[0], dy = p[1] - me[1], dz = p[2] - me[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
