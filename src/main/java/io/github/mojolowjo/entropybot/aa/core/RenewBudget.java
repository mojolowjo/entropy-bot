package io.github.mojolowjo.entropybot.aa.core;

/**
 * D10 (0.39.z78, the second tick failure of D6's gate; docs/designs/D10-nearby-renew-budget.md): how many cached
 * nearby answers may be made again in one client tick. Run DS 15:16:40: 101 block sets expired in the same tick
 * (they were all first asked in one resolve, and every 4 blocks of walking expires them all again), 104 Baritone
 * world scans, 80 ms, a 107 ms tick. With the budget a burst of 120 is 15 ticks of {@link #PER_TICK}: a set past its
 * time keeps its last answer this tick (still a fact: the nearest usable block as of that position and moment, a few
 * blocks of walking old at most, used as rough seconds - rule 14) and renews on a tick after. A set with NO answer yet
 * is always allowed: there is no fact to keep, and "none for lack of time" would break rule 15. Pure (JUnit).
 */
public final class RenewBudget {
    /** Renewals allowed in one client tick. */
    public static final int PER_TICK = 8; // structural: 8 scans are ~5 ms; a burst of 120 sets spreads over 15 ticks

    private long tick = Long.MIN_VALUE;
    private int used;
    private long renewed, deferred;

    /**
     * May the set be scanned again now? {@code hasAnswer}: it has a cached answer to keep. True counts against this
     * tick's budget; false means the caller keeps the last answer this tick.
     */
    public boolean allow(long tick, boolean hasAnswer) {
        if (tick != this.tick) {
            this.tick = tick;
            used = 0;
        }
        if (!hasAnswer || used < PER_TICK) {
            used++;
            renewed++;
            return true;
        }
        deferred++;
        return false;
    }

    /** Renewals allowed in the current tick so far. */
    public int usedThisTick() { return used; }

    /** Renewals allowed in all (including the sets with no answer). */
    public long renewed() { return renewed; }

    /** Renewals put off to a later tick in all. */
    public long deferred() { return deferred; }
}
