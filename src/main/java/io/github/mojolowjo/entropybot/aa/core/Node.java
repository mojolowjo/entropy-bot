package io.github.mojolowjo.entropybot.aa.core;

import java.util.List;

/**
 * AA v0 (AA_PLAN 2): a tree node. Pure Java, no loader, Minecraft or Baritone imports. {@code tick} answers for this
 * tick and records the answer for the tracer; {@code halt} ends whatever runs below (a replaced RunJob cancels its
 * chain); {@code pause}/{@code resume} are the danger guard's softer pair (the job keeps its handle, AA_PLAN 3).
 */
public abstract class Node {
    public final String id, label;
    private Status last;
    protected String reason;
    /** The blackboard of the last tick (halt/pause/resume come from a parent without one). */
    protected Blackboard board;

    protected Node(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** The answer for this tick (recorded; the tracer reads {@link #last()}). */
    public final Status tick(Blackboard bb) {
        board = bb;
        Status s = run(bb);
        if (s != Status.FAILURE) reason = null;
        last = s;
        bb.tracer.node(this, s);
        return s;
    }

    protected abstract Status run(Blackboard bb);

    /** Stop what runs below (default: tell every child). */
    public void halt(String why) {
        for (Node c : children()) c.halt(why);
    }

    /** The danger guard took the bot: keep the handle, stop counting time (default: tell every child). */
    public void pause(String why) {
        for (Node c : children()) c.pause(why);
    }

    public void resume() {
        for (Node c : children()) c.resume();
    }

    public List<Node> children() { return List.of(); }

    /** The last answer, null before the first tick. */
    public Status last() { return last; }

    /** The last FAILURE's reason (null otherwise). */
    public String reason() { return reason; }

    /** A FAILURE with a reason (for subclasses). */
    protected Status fail(String why) {
        reason = why;
        return Status.FAILURE;
    }

    /** The node and everything below, depth first. */
    public void walk(java.util.function.Consumer<Node> f) {
        f.accept(this);
        for (Node c : children()) c.walk(f);
    }

    @Override public String toString() { return id; }
}
