package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.Quiet;

/**
 * rm-mining (OLD_CODE_REMOVAL item 9, live: "guard refused break at -58 63 -11: built block" on the bot's own table): a
 * block the bot placed itself (the restore ledger's {@code built: ...} entries: OwnStations, the builder leg, a shelter,
 * scaffold) is its own, never a "built block" the guard's floor protects from the bot. The game side sets {@link #cells}
 * (RestoreLive, from the ledger). Block entities keep their own floor (a furnace may hold items: OwnStations decides its
 * pickup). Asked only for a cell that already looks built, so the pathfinder's facts stay cheap. Pure apart from the
 * hook; JUnit: OwnPlacedTest. Loader notes: none.
 */
public final class OwnPlaced {
    private OwnPlaced() {}

    /** The cell holds a block the bot placed itself (the current dimension's ledger). */
    public interface Cells {
        boolean placedByMe(int x, int y, int z);
    }

    /** Set by the game side (RestoreLive.load); null: nothing placed by me is known. */
    public static volatile Cells cells;

    private static volatile boolean warned;

    /** A new world: an unset hook warns again (StructureTable calls this per level). */
    public static void newWorld() { warned = false; }

    /** Never throws: a failed read counts as "not mine" (the floor stays, the safe side). */
    public static boolean at(int x, int y, int z) {
        Cells c = cells;
        if (c == null) {
            if (!warned) {          // hooks wired and loud: an unset hook is said once per world
                warned = true;
                Quiet.line("own placements: the OwnPlaced.cells hook is not set - structure blocks I placed count as not mine (refused outside my areas)");
            }
            return false;
        }
        try {
            return c.placedByMe(x, y, z);
        } catch (RuntimeException e) {
            Quiet.warn("OwnPlaced.at failed, carrying on with the fallback", e);
            return false;
        }
    }
}
