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

    /** The nearest block of the set I may work at by Baritone's scan (a log only with leaves by it, an ore only if fair), else the fallback; null: none. */
    public static Spot nearest(Scan s, int[] me, Set<String> blocks, Supplier<Spot> fallback) {
        if (s != null && blocks != null && !blocks.isEmpty()) {
            int checked = 0;
            for (int[] p : sorted(s.find(blocks, scanR()), me)) {
                if (checked++ >= maxChecks()) break;
                String id = s.blockAt(p[0], p[1], p[2]);
                if (id == null || !blocks.contains(id) || !s.allowed(p[0], p[1], p[2]) || !s.fairOk(p[0], p[1], p[2])) continue;
                if (s.isLog(id) && !s.leavesNear(p[0], p[1], p[2], leafReach())) continue;
                return new Spot(p[0], p[1], p[2], dist(p, me), id, s.isLog(id) ? treeLogs(s.logs(scanR()), p, s.treeCap()) : 0);
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
