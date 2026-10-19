package io.github.mojolowjo.entropybot.aa.core;

import java.util.List;

/**
 * AA v0 (AA_PLAN 1, the TASK SLOT): a fallback with memory. Once a child answers RUNNING it is the only child ticked
 * until it ends (SUCCESS or FAILURE): nothing above or below it is re-checked meanwhile (the commitment). When no child
 * runs, the children are tried from 0 as a fallback. {@link #preempt} ends the running child for a higher one that
 * may preempt (the owner's order, AA_PLAN 4): the running child is halted and the next tick chooses again.
 */
public final class MemoryFallback extends Node {
    private final List<Node> kids;
    private int running = -1;

    public MemoryFallback(String id, String label, Node... kids) {
        super(id, label);
        this.kids = List.of(kids);
    }

    @Override protected Status run(Blackboard bb) {
        if (running >= 0) {
            Status s = kids.get(running).tick(bb);
            if (s == Status.RUNNING) return s;
            String why = kids.get(running).reason();
            running = -1;
            return s == Status.FAILURE ? fail(why) : s;
        }
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < kids.size(); i++) {
            Status s = kids.get(i).tick(bb);
            if (s == Status.RUNNING) {
                running = i;
                return s;
            }
            if (s == Status.SUCCESS) return s;
            if (why.length() > 0) why.append("; ");
            why.append(kids.get(i).reason());
        }
        return fail(why.toString());
    }

    /** The running child's index (-1 none). */
    public int running() { return running; }

    /** The running child, or null. */
    public Node runningChild() { return running < 0 ? null : kids.get(running); }

    /** Ends the running child for a higher one: its halt, then the slot is free (the caller logs the why). */
    public void preempt(String why) {
        if (running < 0) return;
        kids.get(running).halt(why);
        running = -1;
    }

    @Override public void halt(String why) {
        preempt(why);
    }

    @Override public List<Node> children() { return kids; }
}
