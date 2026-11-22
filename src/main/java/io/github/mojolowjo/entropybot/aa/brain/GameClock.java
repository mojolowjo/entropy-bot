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

    /** The client's tick rate now (ticks a second): 20, or the server's rate while the hook follows it (set by the mixin). */
    public static volatile float clientRate = 20;

    /**
     * Tick-rate trial 3 (cloud tester runs 36-38: "the grid did not make oak_planks" once at rate 200, every craft at 400):
     * a wait counted in client ticks for the SERVER'S ANSWER (a screen opening, a result slot, a slot sync) waits on real
     * latency, so at a raised client rate it must be the same wall time, not the same tick count - {@code ticks} as
     * written for 20 a second, in client ticks now. At 20 (the hook off): unchanged. The mirror of {@link #ms()}: game-play
     * waits follow ticks, answer waits follow the wall.
     */
    public static int latencyTicks(int ticks) {
        double r = Math.min(clientRate, measuredRate);
        return r <= 20 ? ticks : (int) Math.ceil(ticks * r / 20.0);     // game fact: 20 ticks a second
    }

    /**
     * Worlds run 2 (cloud tester 2, 2026-11-22, w055: a "stand while paths compute (up to 30 s)" stood three wall minutes):
     * the rate ASKED is not the rate the client reaches - in a heavy stretch it ticks 5 to 40 times a second at "tick rate
     * 200", and a wait stretched by the asked rate was ten times too long on the wall. The rate the client really ticks
     * at, counted over the last second of wall time ({@link #tick}); a wait is stretched by the smaller of the two.
     * Before the first full second: the asked rate.
     */
    private static volatile double measuredRate = Double.MAX_VALUE;
    private static long windowStart;
    private static int windowTicks;

    /** The client's measured ticks a wall second (the last full second), for the status line. */
    public static double measuredRate() { return measuredRate == Double.MAX_VALUE ? clientRate : measuredRate; }

    private static volatile int ticksAFrame = 10;        // game fact: vanilla's Minecraft.runTick runs 10 client ticks a frame at most

    /** The client ticks run in one frame at most: vanilla's 10, or Tuning clock.ticksAFrame while the hook is on. */
    public static int ticksAFrame(int vanilla) { return followRate ? Math.max(vanilla, ticksAFrame) : vanilla; }

    /** Once per client tick (Core.onClientTick): reads the switches, advances the tick clock. */
    public static void tick() {
        long w = wall.getAsLong();
        windowTicks++;
        if (windowStart == 0) { windowStart = w; windowTicks = 0; }
        else if (w - windowStart >= 1000) {       // structural: one second of wall time
            measuredRate = windowTicks * 1000.0 / (w - windowStart);     // structural: ms a second
            windowStart = w;
            windowTicks = 0;
        }
        followRate = Tuning.get("clock.clientTickRate") >= 1;
        ticksAFrame = Tuning.i("clock.ticksAFrame");
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
        String hook = "; client tick-rate hook " + (clientRateApplied ? (followRate ? "ON (clock.clientTickRate 1), " + Math.round(measuredRate()) + " ticks a wall second now of " + Math.round(clientRate) + " asked" : "in, off") : "NOT in");
        return (ticking ? "clock: game ticks (clock.gameTicks 1), " + ahead / 1000 + " s off the wall clock"   // structural: ms a second
                : "clock: the wall clock" + (skew > 0 ? " + " + skew / 1000 + " s kept from a ticking spell" : "")) + hook;   // structural: ms a second
    }

    /** Tests: a fixed wall clock; null: the real one. Also clears the state. */
    static synchronized void testWall(java.util.function.LongSupplier w) {
        wall = w == null ? System::currentTimeMillis : w;
        ticking = false;
        measuredRate = Double.MAX_VALUE;
        windowStart = 0;
        windowTicks = 0;
        clientRate = 20;
        anchor = 0;
        ticks = 0;
        skew = 0;
    }
}
