package io.github.mojolowjo.entropybot.aa;

/**
 * aa-water (to-do 1f): in the water with no progress for {@link #STALL_MS} = the running option failed (the AA runtime
 * ends the step with {@link #REASON}, IronOptions counts it against the option in the Failures record and switches);
 * and the moment the bot enters the water the running option is re-scored (the water ways price it now). The progress
 * counter is AaRuntime's (step, job status, bag gain, nearer the goal, 8 blocks moved). Pure (JUnit: SwimWatchTest).
 */
public final class SwimWatch {
    public static final long STALL_MS = 20_000;
    public static final String REASON = "no progress while swimming 20 s";

    private boolean wet;
    private long since = -1, progress = Long.MIN_VALUE;

    public enum Event { NONE, ENTERED, STALLED }

    /** One tick: {@code running} an AA option runs. ENTERED once on dry -> wet, STALLED once per 20 s without progress in water. */
    public Event tick(boolean inWater, boolean running, long progressNow, long now) {
        boolean entered = inWater && running && !wet;          // review: also wet at the option's start
        wet = inWater && running;
        if (!inWater || !running) { since = -1; progress = progressNow; return Event.NONE; }
        if (since < 0 || progressNow != progress) { since = now; progress = progressNow; }
        if (entered) return Event.ENTERED;
        if (now - since >= STALL_MS) { since = now; return Event.STALLED; }
        return Event.NONE;
    }
}
