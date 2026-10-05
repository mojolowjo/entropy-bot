package io.github.mojolowjo.entropybot.watchview;

/**
 * Camera v2: when the shell mesh is rebuilt. Only when something changed: the known-air set (a dig step, a new cave
 * cell), the bot moved far from where the mesh was centred, or a slow refresh for blocks that changed in the world
 * (someone filled a tunnel). Never more often than every {@link #MIN_GAP_MS}. Cells within {@link #radius} of the bot.
 * Pure (JUnit: MeshRuleTest).
 */
public final class MeshRule {
    public static final long MIN_GAP_MS = 400, REFRESH_MS = 10_000;
    public static final int MOVED_BLOCKS = 12;
    public static final int MAX_FACES = 60_000;

    private MeshRule() {}

    /** A rebuild is due now. */
    public static boolean due(boolean built, long version, long builtVersion, long nowMs, long builtMs, double movedSq) {
        if (!built) return true;
        if (nowMs - builtMs < MIN_GAP_MS) return false;
        if (version != builtVersion) return true;
        if (movedSq > (double) MOVED_BLOCKS * MOVED_BLOCKS) return true;
        return nowMs - builtMs >= REFRESH_MS;
    }

    /** Seen faces stream in while the bot looks around a cave: a rebuild for them alone waits at least this long. */
    public static final long SEEN_GAP_MS = 2000;

    /**
     * 0.16.0: also rebuild when the seen-face store changed (at most every {@link #SEEN_GAP_MS}) or the cyan tint was
     * switched (watch seen on/off), besides {@link #due}'s reasons.
     */
    public static boolean due(boolean built, long version, long builtVersion, long seenVersion, long builtSeenVersion, boolean tint, boolean builtTint,
                              long nowMs, long builtMs, double movedSq) {
        if (due(built, version, builtVersion, nowMs, builtMs, movedSq)) return true;
        if (nowMs - builtMs < MIN_GAP_MS) return false;
        if (tint != builtTint) return true;
        return seenVersion != builtSeenVersion && nowMs - builtMs >= SEEN_GAP_MS;
    }

    /**
     * The radius of faces drawn, from the render distance option in chunks: the render distance (0.16.1: up to 32 chunks
     * = 512 blocks, as far as the rays reach; was capped at 128), at least 32 blocks. The face cap keeps the nearest.
     */
    public static int radius(int renderChunks) {
        return Math.max(32, Math.min(512, renderChunks * 16));
    }
}
