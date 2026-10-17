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
