package io.github.mojolowjo.entropybot.aa.brain;

/**
 * Test tooling (docs repo to-do/test-tooling-plan.md, the owner 2026-11-21: games run with the tick rate raised): the
 * ONE clock for every wait, timeout, hold and cadence that is measured against game play - {@link #ms()}, milliseconds.
 * With Tuning {@code clock.gameTicks} 0 (the default) it is the wall clock, exactly as before. With 1 it advances 50 ms
 * a client tick, so at "tick rate 200" a 30 s wait is 30 s of GAME play (3 s on the wall), and a paused game stops it.
 * Switching either way keeps it continuous and never lets it run backwards (the difference is carried as an offset).
 *
 * <p>What stays on the wall clock (System.currentTimeMillis / nanoTime, on purpose): log and file stamps, probes and how
 * long a computation took, log rate limits, a background search's compute budget, the bridge's network timing, the
 * owner's typing waits. The sorted list: to-do/timer-audit-2026-11-21.md.
 *
 * <p>Values written to disk as times (a furnace job's due time, the world switch's held-until) are this clock's: after a
 * sped-up session they can lie ahead of the wall clock by what the session gained. Said here; a test game's files are
 * thrown away. Thread-safe to read. Loader notes: none (fed by Core.onClientTick).
 */
public final class GameClock {
    private GameClock() {}

    /** Game fact: a client tick is 50 ms at the normal 20 ticks a second. */
    public static final long TICK_MS = 50; // game fact: 20 ticks a second

    private static volatile boolean ticking;
    /** Ticking: {@link #ms()} = anchor + ticks x 50. Not ticking: the wall clock + skew. */
    private static volatile long anchor, ticks, skew;
    private static volatile java.util.function.LongSupplier wall = System::currentTimeMillis;

    /** The game-play clock, ms. */
    public static long ms() {
        return ticking ? anchor + ticks * TICK_MS : wall.getAsLong() + skew;
    }

    /** True while the clock follows client ticks (clock.gameTicks 1). */
    public static boolean ticking() { return ticking; }

    /** The client tick-rate hook went in (mixin.ClockMixinMinecraft, set at its first call). */
    public static volatile boolean clientRateApplied;
    private static volatile boolean followRate;

    /** Tuning clock.clientTickRate 1: the client ticks at the server's rate above 20 too (read once a tick). */
    public static boolean followServerRate() { return followRate; }

    /** Once per client tick (Core.onClientTick): reads the switches, advances the tick clock. */
    public static void tick() {
        followRate = Tuning.get("clock.clientTickRate") >= 1;
        boolean want = Tuning.get("clock.gameTicks") >= 1;
        if (want != ticking) set(want);
        if (ticking) ticks++;
    }

    private static synchronized void set(boolean on) {
        long now = ms();
        if (on) {
            anchor = now;
            ticks = 0;
        } else {
            skew = Math.max(0, now - wall.getAsLong());      // never backwards: what the ticks gained is kept
        }
        ticking = on;
    }

    /** One line for aa status / the log: the mode and, ticking, how far the clock runs ahead of the wall. */
    public static String line() {
        long ahead = ms() - wall.getAsLong();
        String hook = "; client tick-rate hook " + (clientRateApplied ? (followRate ? "ON (clock.clientTickRate 1)" : "in, off") : "NOT in");
        return (ticking ? "clock: game ticks (clock.gameTicks 1), " + ahead / 1000 + " s off the wall clock"   // structural: ms a second
                : "clock: the wall clock" + (skew > 0 ? " + " + skew / 1000 + " s kept from a ticking spell" : "")) + hook;   // structural: ms a second
    }

    /** Tests: a fixed wall clock; null: the real one. Also clears the state. */
    static synchronized void testWall(java.util.function.LongSupplier w) {
        wall = w == null ? System::currentTimeMillis : w;
        ticking = false;
        anchor = 0;
        ticks = 0;
        skew = 0;
    }
}
