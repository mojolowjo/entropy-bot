package io.github.mojolowjo.entropybot.aa.core;

import java.util.List;

/**
 * AA v0: a reactive fallback (selector): every tick from child 0, the first SUCCESS or RUNNING wins; all FAILURE =
 * FAILURE with the reasons joined. A lower child that was RUNNING and is now outranked gets its halt.
 */
public final class Fallback extends Node {
    private final List<Node> kids;
    private int runningAt = -1;

    public Fallback(String id, String label, Node... kids) {
        super(id, label);
        this.kids = List.of(kids);
    }

    @Override protected Status run(Blackboard bb) {
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < kids.size(); i++) {
            Status s = kids.get(i).tick(bb);
            if (s == Status.FAILURE) {
                if (why.length() > 0) why.append("; ");
                why.append(kids.get(i).reason());
                continue;
            }
            if (runningAt > i) kids.get(runningAt).halt("outranked by " + kids.get(i).id);
            runningAt = s == Status.RUNNING ? i : -1;
            return s;
        }
        runningAt = -1;
        return fail(why.toString());
    }

    @Override public List<Node> children() { return kids; }
}
