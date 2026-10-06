package io.github.mojolowjo.entropybot.chop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3 (SURVIVAL_PLAN P3.2): is the log at x y z part of a tree the bot may fell? A tree = a log column (1x1 or 2x2 at its
 * base, branches 26-connected) standing on dirt or grass, with at least {@link #MIN_LEAVES} natural leaves around its
 * logs, no more player-placed ({@code persistent=true}) leaves than natural ones, and no built block within
 * {@link #BUILT_GAP} of any of its logs (a house, a treehouse, a roof over it). Pure (JUnit on a fake world).
 * Loader notes: none (plain Java); the game side ({@code commands.Chopping}) answers {@link World} from the client level
 * with vanilla {@code BlockTags.LOGS/LEAVES}, {@code LeavesBlock.PERSISTENT} and {@code BlockState.hasBlockEntity()} (the
 * same on NeoForge and Fabric).
 */
public final class TreeFinder {
    private TreeFinder() {}

    /** Natural leaves a tree needs around its logs; built blocks this close to a log make it someone's. */
    public static final int MIN_LEAVES = 3, BUILT_GAP = 6, LEAF_GAP = 2;
    /** The height cap of one tree, how far its logs may spread from the base, and the most logs it may have. */
    public static final int MAX_HEIGHT = 32, MAX_SPREAD = 6, MAX_LOGS = 200;

    /** The world as the finder sees it. */
    public interface World {
        /** The block id ("minecraft:oak_log"); null when not loaded. */
        String id(int x, int y, int z);

        boolean log(int x, int y, int z);

        boolean leaves(int x, int y, int z);

        /** Leaves a player placed (persistent=true). */
        boolean persistentLeaves(int x, int y, int z);

        /** Dirt, grass, podzol, moss, mud: what a sapling grows on. */
        boolean soil(int x, int y, int z);

        /** A building block or a block entity (never a sapling or the bot's torches). */
        boolean built(int x, int y, int z);
    }

    /** A tree: its logs, the base cells (lowest logs on soil), the commonest log id and its bounds (x1 y1 z1 x2 y2 z2). */
    public record Tree(List<int[]> logs, List<int[]> bases, String logId, int[] box) {
        public int[] base() { return bases.get(0); }

        public String at() { return base()[0] + " " + base()[1] + " " + base()[2]; }
    }

    /** The answer: a tree, or why not (and where, for the built-block reason). */
    public record Result(Tree tree, String why) {
        public boolean ok() { return tree != null; }
    }

    public static Result find(World w, int x, int y, int z) {
        if (!w.log(x, y, z)) return new Result(null, "no log at " + x + " " + y + " " + z);
        int y0 = y;
        while (y - y0 < MAX_HEIGHT && w.log(x, y0 - 1, z)) y0--;
        // the base: the logs at y0 joined side by side (2x2 trunks), at most 4
        List<int[]> bases = new ArrayList<>();
        Set<String> seenBase = new HashSet<>();
        Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{x, y0, z});
        seenBase.add(key(x, y0, z));
        while (!q.isEmpty() && bases.size() < 4) {
            int[] c = q.poll();
            bases.add(c);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c[0] + d[0], nz = c[2] + d[1];
                if (Math.abs(nx - x) > 1 || Math.abs(nz - z) > 1) continue;
                if (seenBase.add(key(nx, y0, nz)) && w.log(nx, y0, nz) && !w.log(nx, y0 - 1, nz)) q.add(new int[]{nx, y0, nz});
            }
        }
        List<int[]> onSoil = new ArrayList<>();
        for (int[] b : bases) if (w.soil(b[0], b[1] - 1, b[2])) onSoil.add(b);
        String at = x + " " + y0 + " " + z;
        if (onSoil.isEmpty()) return new Result(null, "the logs at " + at + " don't stand on dirt (a log pile or a build)");
        // the logs: 26-connected from the base, near it, up to the height cap
        List<int[]> logs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int[] b : bases) {
            seen.add(key(b[0], b[1], b[2]));
            q.add(b);
        }
        Map<String, Integer> ids = new HashMap<>();
        while (!q.isEmpty()) {
            int[] c = q.poll();
            logs.add(c);
            if (logs.size() > MAX_LOGS) return new Result(null, "the logs at " + at + " go on and on (more than " + MAX_LOGS + ": a build?)");
            String id = w.id(c[0], c[1], c[2]);
            if (id != null) ids.merge(id, 1, Integer::sum);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = c[0] + dx, ny = c[1] + dy, nz = c[2] + dz;
                        if (ny < y0 || ny >= y0 + MAX_HEIGHT || Math.abs(nx - x) > MAX_SPREAD || Math.abs(nz - z) > MAX_SPREAD) continue;
                        if (seen.add(key(nx, ny, nz)) && w.log(nx, ny, nz)) q.add(new int[]{nx, ny, nz});
                    }
        }
        int[] box = bounds(logs);
        // leaves around the logs
        int natural = 0, placed = 0;
        Set<String> counted = new HashSet<>();
        for (int[] c : logs)
            for (int dx = -LEAF_GAP; dx <= LEAF_GAP; dx++)
                for (int dy = -LEAF_GAP; dy <= LEAF_GAP; dy++)
                    for (int dz = -LEAF_GAP; dz <= LEAF_GAP; dz++) {
                        int nx = c[0] + dx, ny = c[1] + dy, nz = c[2] + dz;
                        if (!counted.add(key(nx, ny, nz)) || !w.leaves(nx, ny, nz)) continue;
                        if (w.persistentLeaves(nx, ny, nz)) placed++;
                        else natural++;
                    }
        if (natural < MIN_LEAVES && placed == 0) return new Result(null, "the logs at " + at + " have no leaves (a log pile or a build)");
        if (natural < MIN_LEAVES || placed > natural) return new Result(null, "the tree at " + at + " has leaves someone placed (a build)");
        // built blocks near any log (the box grown by BUILT_GAP: a house next to it, a roof over it)
        int[] b = built(w, box);
        if (b != null) return new Result(null, "the tree at " + at + " is next to built blocks at " + b[0] + " " + b[1] + " " + b[2]);
        String logId = ids.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("minecraft:oak_log");
        onSoil.sort(Comparator.<int[]>comparingInt(c -> c[0]).thenComparingInt(c -> c[2]));
        return new Result(new Tree(logs, onSoil, logId, box), null);
    }

    /** The nearest built block within {@link #BUILT_GAP} of the box, or null. */
    static int[] built(World w, int[] box) {
        int[] best = null;
        long bd = Long.MAX_VALUE;
        int cx = (box[0] + box[3]) / 2, cy = box[1], cz = (box[2] + box[5]) / 2;
        for (int x = box[0] - BUILT_GAP; x <= box[3] + BUILT_GAP; x++)
            for (int y = box[1] - BUILT_GAP; y <= box[4] + BUILT_GAP; y++)
                for (int z = box[2] - BUILT_GAP; z <= box[5] + BUILT_GAP; z++) {
                    if (!w.built(x, y, z)) continue;
                    long d = (long) (x - cx) * (x - cx) + (long) (y - cy) * (y - cy) + (long) (z - cz) * (z - cz);
                    if (d < bd) {
                        bd = d;
                        best = new int[]{x, y, z};
                    }
                }
        return best;
    }

    /** The logs of a felled tree still standing (its own cells only: a neighbour tree is never chased). */
    public static List<int[]> left(World w, Tree t) {
        List<int[]> out = new ArrayList<>();
        for (int[] c : t.logs()) if (w.log(c[0], c[1], c[2])) out.add(c);
        return out;
    }

    /** The lowest of these cells (the first break: Falling Trees and TreeChop fell from the bottom log). */
    public static List<int[]> lowest(List<int[]> cells) {
        int min = Integer.MAX_VALUE;
        for (int[] c : cells) min = Math.min(min, c[1]);
        List<int[]> out = new ArrayList<>();
        for (int[] c : cells) if (c[1] == min) out.add(c);
        return out;
    }

    static int[] bounds(List<int[]> cells) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] c : cells)
            for (int k = 0; k < 3; k++) {
                b[k] = Math.min(b[k], c[k]);
                b[k + 3] = Math.max(b[k + 3], c[k]);
            }
        return b;
    }

    public static String key(int x, int y, int z) { return x + " " + y + " " + z; }
}
