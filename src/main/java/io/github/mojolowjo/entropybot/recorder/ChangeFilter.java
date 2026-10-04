package io.github.mojolowjo.entropybot.recorder;

/** Which heard block changes the recorder keeps (pure; FlightRecorder asks it for each change). */
public final class ChangeFilter {
    private ChangeFilter() {}

    /**
     * sameBlock: the block stayed the same and only its state flipped (a furnace lit, a crop grew, redstone power):
     * kept only with states on. The change's chunk must lie within range chunks (square) of the bot's chunk.
     */
    public static boolean keep(boolean sameState, boolean sameBlock, boolean states, int botCx, int botCz, int cx, int cz, int range) {
        if (sameState) return false;
        if (sameBlock && !states) return false;
        return Math.abs(cx - botCx) <= range && Math.abs(cz - botCz) <= range;
    }
}
