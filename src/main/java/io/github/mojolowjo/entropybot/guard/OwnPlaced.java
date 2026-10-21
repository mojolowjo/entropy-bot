package io.github.mojolowjo.entropybot.guard;

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

    /** Never throws: a failed read counts as "not mine" (the floor stays, the safe side). */
    public static boolean at(int x, int y, int z) {
        Cells c = cells;
        if (c == null) return false;
        try {
            return c.placedByMe(x, y, z);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The guard's "built block" fact: it looks built and the bot did not place it itself. */
    public static boolean built(boolean looksBuilt, int x, int y, int z) {
        return looksBuilt && !at(x, y, z);
    }
}
