package io.github.mojolowjo.entropybot.aa.core;

import java.util.List;

/**
 * AA v0 (AA_PLAN 5): after the child's FAILURE this node answers FAILURE without ticking it for a while: {@code baseMs}
 * (2 min), doubling on each failure in a row up to {@code maxMs} (16 min). A SUCCESS resets the run. A halt or pause
 * while the child runs is an interruption, never a failure (the brain's conflict path 2; aa-lessons 11).
 */
public final class Cooldown extends Node {
    public static final long BASE_MS = 120_000, MAX_MS = 16 * 60_000;
    private final Node child;
    private final long baseMs, maxMs;
    private long until = -1, lastFailAt = -1;
    private int fails;
    private String lastWhy;
    private boolean ranOnly, ran;

    public Cooldown(String id, Node child) { this(id, child, BASE_MS, MAX_MS); }

    public Cooldown(String id, Node child, long baseMs, long maxMs) {
        super(id, "cooldown " + child.id);
        this.child = child;
        this.baseMs = baseMs;
        this.maxMs = maxMs;
    }

    /**
     * 0.36.2 (the idle leak): only a failure after the child ran (a task that started and then failed) counts; a child
     * that fails at once (a planner still searching, a busy refusal) is passed through without a cooldown.
     */
    public Cooldown ranOnly() {
        ranOnly = true;
        return this;
    }

    @Override protected Status run(Blackboard bb) {
        long now = bb.clock.now();
        if (until > now) return fail("cooling down " + (until - now + 999) / 1000 + "s after: " + lastWhy);
        Status s = child.tick(bb);
        if (s == Status.RUNNING) ran = true;
        if (s == Status.SUCCESS) {
            fails = 0;
            until = -1;
            ran = false;
            return s;
        }
        if (s == Status.FAILURE && ranOnly && !ran) return fail(child.reason());
        if (s == Status.FAILURE) {
            ran = false;
            fails++;
            lastFailAt = now;
            lastWhy = child.reason();
            long ms = Math.min(maxMs, baseMs << Math.min(fails - 1, 20));
            until = now + ms;
            return fail(lastWhy);
        }
        return s;
    }

    /** Forget the cooldown (the cause went away: FailMemory's sweep idea). */
    public void forget() {
        until = -1;
        fails = 0;
    }

    public boolean cooling(long now) { return until > now; }

    public int fails() { return fails; }

    public long lastFailAt() { return lastFailAt; }

    @Override public void halt(String why) {
        child.halt(why);
        ran = false;
    }

    @Override public List<Node> children() { return List.of(child); }
}
