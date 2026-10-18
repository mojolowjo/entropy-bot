package io.github.mojolowjo.entropybot.aa.core;

import java.util.function.Predicate;

/** AA v0: a yes/no test on the blackboard, no side effects (Halo 2's binary relevancy; aa-lessons 4). */
public final class Condition extends Node {
    private final Predicate<Blackboard> test;

    public Condition(String id, String label, Predicate<Blackboard> test) {
        super(id, label);
        this.test = test;
    }

    @Override protected Status run(Blackboard bb) {
        return test.test(bb) ? Status.SUCCESS : fail("not " + label);
    }
}
