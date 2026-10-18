package io.github.mojolowjo.entropybot.aa.core;

/**
 * AA v0 (AA_PLAN 5): enter/leave hysteresis for a number, against threshold flicker (aa-behavior-trees 1). The band
 * is active ("the need is on") once the value drops below {@code enterBelow} and stays active until the value reaches
 * {@code leaveAt} or more. Torches: enter < 8, leave at 16. Pure.
 */
public final class Band {
    public final double enterBelow, leaveAt;
    private boolean active;

    public Band(double enterBelow, double leaveAt) {
        if (leaveAt < enterBelow) throw new IllegalArgumentException("leaveAt " + leaveAt + " below enterBelow " + enterBelow);
        this.enterBelow = enterBelow;
        this.leaveAt = leaveAt;
    }

    /** Feeds the value; answers whether the band is active now. */
    public boolean update(double v) {
        if (!active && v < enterBelow) active = true;
        else if (active && v >= leaveAt) active = false;
        return active;
    }

    public boolean active() { return active; }

    public void reset() { active = false; }

    /**
     * A time latch for a yes/no signal: active as soon as the signal is true, and it stays active until the signal has
     * been false for {@code calmMs} in a row (the danger band: 3 s calm, AA_PLAN 5).
     */
    public static final class CalmLatch {
        public final long calmMs;
        private boolean active;
        private long lastTrue = Long.MIN_VALUE;

        public CalmLatch(long calmMs) { this.calmMs = calmMs; }

        public boolean update(boolean signal, long now) {
            if (signal) {
                active = true;
                lastTrue = now;
            } else if (active && now - lastTrue >= calmMs) active = false;
            return active;
        }

        public boolean active() { return active; }
    }
}
