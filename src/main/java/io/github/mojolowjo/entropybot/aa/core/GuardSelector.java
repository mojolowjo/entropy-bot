package io.github.mojolowjo.entropybot.aa.core;

import java.util.ArrayList;
import java.util.List;

/**
 * AA v0 (AA_PLAN 1, the ROOT): gdx-ai's dynamic guard selector with Unreal's "Lower Priority" abort as a pause
 * (aa-lessons 6, 7). Every tick the guards are re-checked in order; the first passing guard's child is ticked. When a
 * higher guard takes over from a lower child that was RUNNING, that child is {@link Node#pause}d (keeps its handle),
 * and {@link Node#resume}d when the higher guard drops again. No guard passing = FAILURE.
 */
public final class GuardSelector extends Node {
    public record Branch(Node guard, Node child) {}

    private final List<Branch> branches;
    private final List<Node> kids = new ArrayList<>();
    private int active = -1, paused = -1;

    public GuardSelector(String id, String label, Branch... branches) {
        super(id, label);
        this.branches = List.of(branches);
        for (Branch b : branches) {
            kids.add(b.guard);
            kids.add(b.child);
        }
    }

    @Override protected Status run(Blackboard bb) {
        int pick = -1;
        for (int i = 0; i < branches.size(); i++) {
            if (branches.get(i).guard.tick(bb) == Status.SUCCESS) {
                pick = i;
                break;
            }
        }
        if (pick < 0) {
            if (active >= 0) branches.get(active).child.halt("no guard passes");
            active = -1;
            return fail("no guard passes");
        }
        if (active >= 0 && active != pick) {
            Node was = branches.get(active).child;
            if (pick < active && was.last() == Status.RUNNING) {
                if (paused >= 0 && paused != active) branches.get(paused).child.halt("a second pause");     // 0.36.5 (review): one paused child at most
                was.pause(branches.get(pick).guard.label);
                paused = active;
                bb.tracer.event("paused " + was.id + ": " + branches.get(pick).guard.label);
            } else if (pick > active) {
                // the higher guard dropped: the lower child gets its tick again (resumed below)
            } else {
                was.halt("outranked by " + branches.get(pick).child.id);
            }
        }
        if (paused >= 0 && pick == paused) {
            branches.get(pick).child.resume();
            bb.tracer.event("resumed " + branches.get(pick).child.id);
            paused = -1;
        }
        active = pick;
        Status s = branches.get(pick).child.tick(bb);
        return s == Status.FAILURE ? fail(branches.get(pick).child.reason()) : s;
    }

    /** The branch whose child was ticked last (-1 none). */
    public int active() { return active; }

    @Override public void halt(String why) {
        if (active >= 0) branches.get(active).child.halt(why);
        if (paused >= 0 && paused != active) branches.get(paused).child.halt(why);     // 0.36.5 (review 2): a paused job ends too
        active = -1;
        paused = -1;
    }

    @Override public List<Node> children() { return kids; }
}
