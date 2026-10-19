package io.github.mojolowjo.entropybot.aa.core;

import java.util.List;

/**
 * AA v0: a reactive sequence (BehaviorTree.CPP ReactiveSequence; aa-lessons 8): every tick starts again at child 0,
 * stops at the first FAILURE (with its reason) or RUNNING. A child that was RUNNING last tick and is now skipped
 * because an earlier child failed gets its halt.
 */
public final class Seq extends Node {
    private final List<Node> kids;
    private int runningAt = -1;

    public Seq(String id, String label, Node... kids) {
        super(id, label);
        this.kids = List.of(kids);
    }

    @Override protected Status run(Blackboard bb) {
        for (int i = 0; i < kids.size(); i++) {
            Status s = kids.get(i).tick(bb);
            if (s == Status.SUCCESS) continue;
            if (s == Status.RUNNING) {
                runningAt = i;
                return Status.RUNNING;
            }
            if (runningAt > i) kids.get(runningAt).halt("an earlier condition failed: " + kids.get(i).reason());
            runningAt = -1;
            return fail(kids.get(i).reason());
        }
        runningAt = -1;
        return Status.SUCCESS;
    }

    @Override public List<Node> children() { return kids; }
}
