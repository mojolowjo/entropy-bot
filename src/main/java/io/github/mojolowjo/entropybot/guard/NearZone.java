package io.github.mojolowjo.entropybot.guard;

/**
 * 0.21.2 (the owner, 2026-10-06: "anywhere near me should be fine for the bot to work near, like a radius of 16 blocks"):
 * the moving work zone around the owner. While it is on and the owner's spot is known (in view, else the companion's
 * fresh position in the bot's dimension), the CIRCLE of radius {@code r} around their feet column (horizontal distance
 * at most r; a round {@link Box}) and {@link #Y_HALF} blocks below and above their feet counts as one more area for the
 * guard ({@link GuardCore#setNearZone}) and the commands' area checks. It only ADDS permission: protect boxes, the
 * floor (block entities, built blocks, the Nether and the End) and the lease rules are checked exactly as for any area,
 * and log/strict mode stays as it is. Owner unknown = no zone at that moment (no guessing).
 *
 * <p>Pure Java (no Minecraft types): JUnit drives the rule. Thread-safe: the box is a volatile immutable snapshot.
 */
public final class NearZone {
    public static final String NAME = "near-me";
    public static final int DEFAULT_R = 16, MIN_R = 4, MAX_R = 64;
    /** Blocks below and above the owner's feet the zone reaches. */
    public static final int Y_HALF = 16;
    /** Owner's spot unknown this long while the zone is on (and the owner online): a {@code check} finding (never whispered). */
    public static final long UNKNOWN_NOTE_MS = 10 * 60_000L;

    private volatile boolean on = true;
    private volatile int r = DEFAULT_R;
    private volatile Box box;
    private volatile long lastFixMs = -1, unknownSinceMs = -1;
    private volatile String lastSource = null;
    private volatile long updates, errors;
    private volatile String lastError;

    public boolean on() { return on; }

    public int radius() { return r; }

    /** The zone now, or null (off, owner unknown, or a denied dimension). */
    public Box box() { return on ? box : null; }

    /** Bounds 4..64; anything else is refused (null = fine, else the reason). */
    public static String checkRadius(int r) {
        return r < MIN_R || r > MAX_R ? "the near-me radius is " + MIN_R + ".." + MAX_R + " blocks" : null;
    }

    public synchronized void set(boolean on, int r) {
        this.on = on;
        if (checkRadius(r) == null) this.r = r;
        if (!on) box = null;
    }

    /**
     * The zone around a spot: the circle of radius r around the column (x, z) (horizontal distance at most r), and
     * {@link #Y_HALF} down and up; null in a denied dimension (no work there).
     */
    public static Box boxAt(String dim, int x, int y, int z, int r) {
        if (dim == null || GuardCore.DENIED_DIMS.contains(dim)) return null;
        return Box.round(NAME, dim, x, z, r, y - Y_HALF, y + Y_HALF);
    }

    /** True when the spot lies in the zone around the owner (null owner = unknown = false). */
    public static boolean inZone(int[] owner, String ownerDim, int r, String dim, int x, int y, int z) {
        if (owner == null) return false;
        Box b = boxAt(ownerDim, owner[0], owner[1], owner[2], r);
        return b != null && b.contains(dim, x, y, z);
    }

    /**
     * Once a second: the owner's block spot (null = unknown), its dimension and where it came from ("view" or
     * "companion"). Returns the new box (null: none) so the caller hands it to the guard only when it changed.
     */
    public synchronized Box update(String dim, int[] owner, String source, long nowMs) {
        updates++;
        if (!on) {
            box = null;
            unknownSinceMs = -1;
            return null;
        }
        if (owner == null) {
            if (unknownSinceMs < 0) unknownSinceMs = nowMs;
            box = null;
            return null;
        }
        unknownSinceMs = -1;
        lastFixMs = nowMs;
        lastSource = source;
        box = boxAt(dim, owner[0], owner[1], owner[2], r);
        return box;
    }

    /** A failure in the caller's update (counted; the zone goes off until the next good update). */
    public synchronized void error(String what) {
        errors++;
        lastError = what;
        box = null;
    }

    public long errors() { return errors; }

    /** How long the owner's spot has been unknown while on (ms), 0 when known or off. */
    public long unknownForMs(long nowMs) {
        long s = unknownSinceMs;
        return !on || s < 0 ? 0 : Math.max(0, nowMs - s);
    }

    /** "near me: a circle of 16 blocks (on, around you at 10 64 20 via view)" and the like. */
    public String describe(long nowMs) {
        if (!on) return "near me: off (a circle of " + r + " blocks when on)";
        Box b = box;
        String where;
        if (b != null) {
            where = "around you at " + b.cx + " " + (b.y1 + Y_HALF) + " " + b.cz + (lastSource == null ? "" : " via " + lastSource);
        } else {
            long u = unknownForMs(nowMs);
            where = "I don't know where you are" + (u > 0 ? " (" + u / 1000 + " s)" : "") + " - the zone is off until I see you or your companion";
        }
        return "near me: a circle of " + r + " blocks (on, " + where + ")" + (errors > 0 ? " [" + errors + " errors, last: " + lastError + "]" : "");
    }

    public long updates() { return updates; }
}
