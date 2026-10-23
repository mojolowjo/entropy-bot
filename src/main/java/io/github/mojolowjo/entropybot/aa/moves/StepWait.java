package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.StationFact;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * station-race (0.39.z17), rule 15: the gate in front of a craft / smelt step. While the station fact of its recipe kind is
 * NOT LOADED (the item data is read a few ticks after a server join), the step does not start its verb (no planning, no
 * placing, no "none known" failure): it stays running with the reason, as the loot-book wait holds an order. Once the fact
 * is loaded (known or none) the verb starts and the step follows it. Pure (JUnit: StationRaceTest); the live user is
 * {@code commands.StepHands}.
 */
public final class StepWait {
    /** The kind being waited for; null: not waiting. */
    private String kind;

    /** The recipe kind whose station a step needs; null: none. */
    public static String kindOf(Step s) {
        return s instanceof Step.Craft ? ItemWorth.CRAFT : s instanceof Step.Smelt ? ItemWorth.SMELT : null;
    }

    public static boolean waits(StationFact f) { return f instanceof StationFact.NotLoaded; }

    /** The waiting step's status: running, with the not-loaded reason. */
    public static Car.Status status(StationFact.NotLoaded n) {
        return Car.Status.running(StationFact.NOT_LOADED_LINE + " (" + n.why() + ") - waiting for it");
    }

    /**
     * The step starts: not loaded -> it waits (null: started, nothing begun); else {@code begin} runs (the verb; its
     * "error: ..." or null).
     */
    public String start(Step s, Function<String, StationFact> facts, Supplier<String> begin) {
        kind = null;
        String k = kindOf(s);
        if (k != null && waits(facts.apply(k))) {
            kind = k;
            return null;
        }
        return begin.get();
    }

    /** True while the step waits for its station fact. */
    public boolean waiting() { return kind != null; }

    /**
     * A tick of a waiting step: still not loaded -> running with the reason; loaded -> {@code begin} runs now (an error
     * fails the step) and {@code follow} gives the step's status from then on.
     */
    public Car.Status tick(Function<String, StationFact> facts, Supplier<String> begin, Supplier<Car.Status> follow) {
        StationFact f = facts.apply(kind);
        if (f instanceof StationFact.NotLoaded n) return status(n);
        kind = null;
        String r = begin.get();
        if (r != null) return Car.Status.failed(r.replaceFirst("^error: ", ""));
        return follow.get();
    }

    public void reset() { kind = null; }
}
