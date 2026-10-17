package io.github.mojolowjo.entropybot.clear;

import java.util.Set;

/**
 * 0.33.0 (A3, Haiku run item 6: "own crafting table can't be broken even with force"): a station (crafting table,
 * furnace, chest...) that the bot placed itself, inside its own areas, may be picked up again by a {@code force} dig.
 * Every other block entity stays protected. Pure apart from the {@link #mine} hook, which the game side sets (the
 * restore ledger's "built: station" entries plus the areas test). Loader notes: none.
 */
public final class OwnStations {
    private OwnStations() {}

    /** The stations the bot places and may pick up again (ids without the namespace). */
    public static final Set<String> STATIONS = Set.of("crafting_table", "furnace", "blast_furnace", "smoker", "chest", "barrel",
            "trapped_chest", "smithing_table", "stonecutter", "cartography_table", "fletching_table", "loom", "anvil");

    public interface Mine {
        boolean at(int x, int y, int z);
    }

    /** Set by the game side: the cell holds a station I placed myself (ledger), inside my areas. Null: none. */
    public static volatile Mine mine;

    public static boolean station(String id) {
        if (id == null) return false;
        String s = id.toLowerCase();
        int c = s.indexOf(':');
        if (c >= 0) {
            if (!s.startsWith("minecraft:")) return false;
            s = s.substring(c + 1);
        }
        return STATIONS.contains(s);
    }

    /** 0.33.0 review: the stations that hold items; one is picked up only when it is known to be empty. */
    public static final Set<String> CONTAINERS = Set.of("chest", "trapped_chest", "barrel", "furnace", "blast_furnace", "smoker");

    public static boolean container(String id) {
        return id != null && CONTAINERS.contains(id.toLowerCase().replaceFirst("^minecraft:", ""));
    }

    /**
     * 0.33.0 review: may this station of mine be picked up? null = yes, else the reason. A station counts as mine only
     * when the ledger's record matches the cell, its kind and the placement: the cell has not lost that block since it
     * was placed (removedSince: a player who broke it and put their own of the same kind there makes it theirs). A
     * container is picked up only when its contents were read after the placement and were empty (contents: the item
     * count read then; null = never read since: unknown), since breaking it would throw its items out.
     */
    public static String pickUp(String recordedKind, String nowKind, boolean removedSince, String id, Integer contents, int x, int y, int z) {
        String at = x + " " + y + " " + z;
        if (recordedKind == null || nowKind == null || !recordedKind.replaceFirst("^minecraft:", "").equals(nowKind.replaceFirst("^minecraft:", "")))
            return "the " + nowKind + " at " + at + " is not the one I placed";
        if (removedSince) return "the " + nowKind + " at " + at + " was broken and put back since I placed it: not mine any more";
        if (container(id)) {
            if (contents == null) return "the " + nowKind + " at " + at + " may hold items (I haven't looked inside since I placed it): open it or take its items first";
            if (contents > 0) return "the " + nowKind + " at " + at + " holds " + contents + " items: take them first (take all " + at + ")";
        }
        return null;
    }

    /** Set by the game side: my recorded station cells inside the box x1 y1 z1 x2 y2 z2 (never the whole box read). */
    public interface Cells {
        java.util.List<int[]> in(int x1, int y1, int z1, int x2, int y2, int z2);
    }

    public static volatile Cells cells;

    /** Set by the game side: why the station of mine at the cell is not picked up (null: it may be). */
    public interface Why {
        String at(int x, int y, int z);
    }

    public static volatile Why why;

    /** My recorded station cells in the box (empty when the hook is not set; never throws). */
    public static java.util.List<int[]> cellsIn(int x1, int y1, int z1, int x2, int y2, int z2) {
        Cells c = cells;
        if (c == null) return java.util.List.of();
        try {
            return c.in(x1, y1, z1, x2, y2, z2);
        } catch (RuntimeException e) {
            return java.util.List.of();
        }
    }

    /** Why a recorded station is refused, or null (never throws; a failure is a refusal saying so). */
    public static String whyNot(int x, int y, int z) {
        Why w = why;
        if (w == null) return "no station records loaded";
        try {
            return w.at(x, y, z);
        } catch (RuntimeException e) {
            return "the station check failed: " + e;
        }
    }

    /** The cell holds a station of mine (never throws). */
    public static boolean mineAt(int x, int y, int z) {
        Mine m = mine;
        if (m == null) return false;
        try {
            return m.at(x, y, z);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
