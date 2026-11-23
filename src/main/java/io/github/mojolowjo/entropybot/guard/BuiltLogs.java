package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Structure;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Owner's ruling 2026-11-23 (worlds run 2, w058: the bot cut at a village building's logs): a log that belongs to a
 * village, any generated structure or a player's build is protected like every structure block; a natural tree stays
 * cuttable. The client has no structure positions (a server sends none), so the fact is read from the cells themselves,
 * with the game's data deciding each block (StructureTable's marks, no name of ours):
 *
 * <p>(1) the block kind, in the table: a block of the game's logs tag that no world-gen source of the jars names (the
 * tree features name the logs they grow; they name no stripped log and no wood block) is a structure block outright;
 * (2) the cell, here: a log world gen does place is part of a built thing when its group of joined logs (the fork chop's
 * own 26 neighbours) touches a block only a build has ({@link Mark#BUILT}: a structure tag, a station or a crafting
 * recipe's result, and no world-gen source names it: planks, stairs, doors, glass panes, a torch) that the bot did not
 * place itself. A tree touches leaves, vines, ground and a bee nest, none of them such a block.
 *
 * <p>Rule 15: a neighbour not loaded or of a kind not known yet, with no built block found, answers NOT_LOADED (never a
 * no). The walk stops at {@link #walkCap} logs: a group bigger than that with no built block among the walked cells is a
 * tree (the limit: a hall of plain logs whose every built block is further than the cap's logs from the cell is missed).
 * Pure: JUnit BuiltLogsTest. Loader notes: none.
 */
public final class BuiltLogs {
    private BuiltLogs() {}

    /** What the structure table says of a block kind for this fact. */
    public enum Mark {
        /** Nothing for this fact: ground, leaves, air, a block world gen places. */
        PLAIN,
        /** A block of the logs tag that world gen places: a tree's log, unless its cells say otherwise. */
        TREE_LOG,
        /** A block only a build has: made (a structure tag, a station, a crafting result, a log) and named by no world-gen source. */
        BUILT,
        /** Not known yet (the world-gen read or a recipe). */
        NOT_KNOWN
    }

    /** The cells as the caller's thread reads them. */
    public interface Cells {
        /** The mark of the block at the cell; null: the cell is not loaded. */
        Mark at(int x, int y, int z);

        /** The bot placed the block at the cell itself (the ledger): never a stranger's build. */
        boolean mine(int x, int y, int z);
    }

    /**
     * The answer for one log cell: its structure fact, the logs walked, and for YES the built block's cell that decided
     * ({@code bx by bz}) and the log of the group that touches it ({@code lx ly lz}).
     */
    public record Found(Structure fact, int logs, boolean whole, int bx, int by, int bz, int lx, int ly, int lz) {
        static Found plain(Structure s, int logs, boolean whole) { return new Found(s, logs, whole, 0, 0, 0, 0, 0, 0); }
    }

    /** The most logs one walk visits. */
    public static int walkCap() { return Tuning.i("guard.builtLogWalk"); }

    /** {@link #of} with the walk's logs handed back in {@code walked} (packed cells, {@link #key}); null: not wanted. */
    public static Found of(Cells c, int x, int y, int z, int cap, Set<Long> walked) {
        Set<Long> seen = walked == null ? new HashSet<>() : walked;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen.add(key(x, y, z));
        queue.add(new int[]{x, y, z});
        boolean unknown = false;
        int n = 0;
        while (!queue.isEmpty() && n < cap) {
            int[] at = queue.poll();
            n++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {   // structural: the 26 neighbours
                if (dx == 0 && dy == 0 && dz == 0) continue;
                int nx = at[0] + dx, ny = at[1] + dy, nz = at[2] + dz;
                Mark m = c.at(nx, ny, nz);
                if (m == null || m == Mark.NOT_KNOWN) { unknown = true; continue; }
                if (m == Mark.BUILT) {
                    if (!c.mine(nx, ny, nz)) return new Found(Structure.YES, n, false, nx, ny, nz, at[0], at[1], at[2]);
                    continue;
                }
                if (m == Mark.TREE_LOG && seen.add(key(nx, ny, nz))) queue.add(new int[]{nx, ny, nz});
            }
        }
        boolean whole = queue.isEmpty();
        // logs left unwalked past the cap are not part of the answer: the walked cells alone say "a tree" or "not known"
        if (walked != null && !whole) for (int[] left : queue) seen.remove(key(left[0], left[1], left[2]));
        return Found.plain(unknown ? Structure.NOT_LOADED : Structure.NO, n, whole);
    }

    /** The structure fact of the tree log at x y z ({@link Mark#TREE_LOG}; the caller asks for such a cell only). */
    public static Found of(Cells c, int x, int y, int z, int cap) { return of(c, x, y, z, cap, null); }

    /** A cell packed into one long (26 bits x and z, 12 bits y: the game's own block position packing). */
    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);   // structural: bit packing
    }
}
