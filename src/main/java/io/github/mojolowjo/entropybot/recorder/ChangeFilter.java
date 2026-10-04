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
        return inRange(botCx, botCz, cx, cz, range);
    }

    /** The chunk lies within range chunks (square) of the bot's chunk. A real change outside it is a missed one. */
    public static boolean inRange(int botCx, int botCz, int cx, int cz, int range) {
        return Math.abs(cx - botCx) <= range && Math.abs(cz - botCz) <= range;
    }

    /**
     * Keeps a flapping block (a machine switching blocks, a piston clock) or a flood from filling the memory ring and the
     * disk: at most perPos changes per position per window, and at most perSecond changes in all per second. A refused
     * change is a missed one (the caller ends the chunk's exact coverage). Game thread only.
     */
    public static final class Limiter {
        final int perPos, perSecond;
        final long window;
        private final java.util.HashMap<Long, long[]> pos = new java.util.HashMap<>();
        private long secStart = Long.MIN_VALUE / 2;
        private int secCount;
        private long dropped;

        public Limiter(int perPos, long windowMs, int perSecond) {
            this.perPos = perPos;
            this.window = windowMs;
            this.perSecond = perSecond;
        }

        public boolean allow(long posKey, long now) {
            if (now - secStart >= 1000) {
                secStart = now;
                secCount = 0;
            }
            if (secCount >= perSecond) {
                dropped++;
                return false;
            }
            long[] e = pos.get(posKey);
            if (e == null || now - e[0] >= window) {
                if (e == null && pos.size() >= 8192) pos.values().removeIf(v -> now - v[0] >= window);
                if (e == null && pos.size() >= 8192) pos.clear();
                pos.put(posKey, new long[]{now, 1});
            } else if (e[1] >= perPos) {
                dropped++;
                return false;
            } else e[1]++;
            secCount++;
            return true;
        }

        public long dropped() { return dropped; }
    }
}
