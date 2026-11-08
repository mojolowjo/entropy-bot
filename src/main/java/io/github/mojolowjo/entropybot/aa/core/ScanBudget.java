package io.github.mojolowjo.entropybot.aa.core;

/**
 * job-tick2 (runs JAU / JAV, the first aa tick of a world: senses 542 ms, 32 Baritone scans, most of them the containers and
 * station lookups and the tree log, not the cached nearest): the milliseconds of world scans one client tick may spend
 * ({@code sense.scanBudgetMs}, Tuning). A lookup asks {@link #allow} before its scan: while the tick's scans are under the
 * budget it scans; past it, the lookup keeps its last answer (or says "waiting for the scan budget" when it has none, a
 * not-ready fact the order stands on, rule 22) and scans on a tick after. Each scan's time is added by {@link #spend}.
 * At least one scan runs per tick, so a lookup is never starved. Pure (JUnit: ScanBudgetTest).
 */
public final class ScanBudget {
    private long tick = Long.MIN_VALUE;
    private long spentNs;
    private int scansThisTick;
    private int deferredThisTick;
    private long deferred;

    private void roll(long tick) {
        if (tick != this.tick) {
            this.tick = tick;
            spentNs = 0;
            scansThisTick = 0;
            deferredThisTick = 0;
        }
    }

    /** May a scan run now in {@code tick} with a budget of {@code budgetMs}? False counts one deferred scan. */
    public boolean allow(long tick, double budgetMs) {
        roll(tick);
        if (scansThisTick == 0 || spentNs < budgetMs * 1e6) {     // structural: ns per ms
            scansThisTick++;
            return true;
        }
        deferredThisTick++;
        deferred++;
        return false;
    }

    /** Adds a scan's {@code nanos} to the tick's spend. */
    public void spend(long tick, long nanos) {
        roll(tick);
        spentNs += nanos;
    }

    /** Scans deferred in the current tick. */
    public int deferredThisTick() { return deferredThisTick; }

    /** Scans deferred in all. */
    public long deferred() { return deferred; }

    /** Milliseconds of scans spent in the current tick. */
    public double spentMs() { return spentNs / 1e6; }     // structural: ns per ms
}
