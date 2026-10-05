package io.github.mojolowjo.entropybot.watchview;

import java.util.ArrayDeque;

/**
 * The visible-faces probe's small rules (docs/CAMERA_PLAN.md "Visible-faces idea", the owner's answers of 2026-10-04):
 * which seen faces are recorded (never one whose air side sees the sky; a face at light 0 only within
 * {@link #DARK_RANGE} blocks), how bright a seen face is drawn (dimmed when it was only ever seen in the dark), and the
 * sampler's error window (20 errors in a minute turn it off). Pure (JUnit: SeenRuleTest).
 */
public final class SeenRule {
    /** A face seen only at light 0 is recorded only this close (the player would see it dimly near, black far). */
    public static final double DARK_RANGE = 8;
    public static final int ERRORS_TO_STOP = 20;
    public static final long ERROR_WINDOW_MS = 60_000;

    private SeenRule() {}

    /** Record this face in the saved store? airSky: the cell in front of it (its air side) can see the sky (the surface floods the store). */
    public static boolean record(boolean airSky, int light, double dist) {
        if (airSky) return false;
        return light > 0 || dist <= DARK_RANGE;
    }

    /** Where a seen face goes (0.16.1): nowhere, the saved store, or the in-memory surface store. */
    public static final int NONE = 0, SAVED = 1, SURFACE = 2;

    /**
     * 0.16.1 (owner, 2026-10-04: "on the surface show faces"): a face whose air side sees the sky goes to the surface
     * store (in memory only, kept within the render distance of the eye, never saved, so it can never push tunnel faces
     * out of {@code seenfaces.bin}); every other face follows {@link #record}. The dark rule holds for both.
     */
    public static int where(boolean airSky, int light, double dist) {
        if (!(light > 0 || dist <= DARK_RANGE)) return NONE;
        return airSky ? SURFACE : SAVED;
    }

    /** Brightness factor for a seen face drawn in the view: 0.35 at light 0, 1.0 at light 15. */
    public static float dim(int light) {
        int l = Math.max(0, Math.min(15, light));
        return 0.35f + 0.65f * l / 15f;
    }

    /** The cyan tint of a seen face while the probe is on (so the owner can tell it from the dug-tunnel shell): rgb in, rgb out. */
    public static int[] tint(int r, int g, int b) {
        return new int[]{r / 2, g / 2 + 127, b / 2 + 127};
    }

    /** True when an error at nowMs makes it {@link #ERRORS_TO_STOP} within {@link #ERROR_WINDOW_MS}: the sampler turns itself off. */
    public static final class Errors {
        private final ArrayDeque<Long> times = new ArrayDeque<>();
        private long total;

        public synchronized boolean add(long nowMs) {
            total++;
            times.addLast(nowMs);
            while (!times.isEmpty() && nowMs - times.peekFirst() > ERROR_WINDOW_MS) times.pollFirst();
            return times.size() >= ERRORS_TO_STOP;
        }

        public synchronized long total() { return total; }

        /** Log this error in full? The first 5, then every 100th. */
        public synchronized boolean logIt() { return total <= 5 || total % 100 == 0; }

        public synchronized void reset() { times.clear(); }
    }
}
