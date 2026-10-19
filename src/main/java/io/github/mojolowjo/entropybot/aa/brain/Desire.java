package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Node;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * BRAIN_PLAN 2.1: one desire in the brain's scored list. Its {@code node} does the work (a ladder {@code Rung}, an idle
 * option under its cooldown...); {@code score} answers 0..100, below 0 = not wanted now. Pure.
 *
 * <ul>
 * <li>GATE: a hard limit (no area, under ground for a surface task); scores what it blocks (AA_ROUND2 4: the parked
 *     desires' value flows to it), never interrupts a running task.</li>
 * <li>UPKEEP: stock and state; at {@link Brain#URGENT} or more it may interrupt (food near empty).</li>
 * <li>PROGRESS: worth what it unlocks; B2 scales it by P(success) and cost.</li>
 * <li>WANT: personality x boredom (B3).</li>
 * <li>OWNER: the owner's goal (B4): interrupts once when just given, never above urgent upkeep.</li>
 * </ul>
 */
public final class Desire {
    public enum Kind { GATE, UPKEEP, PROGRESS, WANT, OWNER }

    public final String id;
    public final Kind kind;
    public final Node node;
    final ToDoubleFunction<Blackboard> score;
    /** B2: the hand cost (seconds) until measured; the prerequisite this desire's value flows to when blocked. */
    double handCostS = 300;
    Function<Blackboard, String> prereq = bb -> null;
    /** OWNER: interrupts a running task once until this time (a goal just given). */
    long freshUntil = -1;

    public Desire(String id, Kind kind, Node node, ToDoubleFunction<Blackboard> score) {
        this.id = id;
        this.kind = kind;
        this.node = node;
        this.score = score;
    }

    /** B2: the hand cost estimate in seconds (used until 3 runs are measured). */
    public Desire cost(double seconds) {
        handCostS = seconds;
        return this;
    }

    /** B2: when this desire is blocked by something missing, the id of the desire that supplies it (else null). */
    public Desire prereq(Function<Blackboard, String> p) {
        prereq = p;
        return this;
    }

    /** Feasibility: the reason no path exists now (a required input has no source), else null. Parks the desire. */
    Function<Blackboard, String> infeasible = bb -> null;

    /** A wanted desire with no feasible path scores ~0 (parked, reason in aa status); re-checked on every scoring. */
    public Desire infeasible(Function<Blackboard, String> f) {
        infeasible = f;
        return this;
    }

    /** AA_ROUND2 1: the live cost in seconds (a rung picker's chosen option), NaN = unknown: the hand/measured cost. */
    Function<Blackboard, Double> liveCost;

    public Desire liveCost(Function<Blackboard, Double> f) {
        liveCost = f;
        return this;
    }

    /** AA_ROUND2 2: why this desire could not start a task now (null: it can); asked before it may interrupt. */
    Function<Blackboard, String> probe;

    public Desire probe(Function<Blackboard, String> f) {
        probe = f;
        return this;
    }

    /** A failed try ends this tick's picking (the idle options: one try per tick, as the idle chooser did). */
    boolean lastTry;

    public Desire lastTry() {
        lastTry = true;
        return this;
    }

    /** OWNER: a goal given now may interrupt the running task (once) until {@code until}. */
    public Desire fresh(long until) {
        freshUntil = until;
        return this;
    }

    /**
     * AA_ROUND2 4 (was B3's +/-10 night sign): where the work is done. While monsters spawn the brain multiplies the
     * score by the night's danger (Costs.nightDanger): SURFACE x 1 / (1 + danger), UNDER x (1 + danger / 2), ANY as is.
     */
    public enum Exposure { SURFACE, UNDER, ANY }

    Exposure exposure = Exposure.ANY;

    public Desire exposure(Exposure e) {
        exposure = e == null ? Exposure.ANY : e;
        return this;
    }

    public Exposure exposure() { return exposure; }

    /**
     * AA_ROUND2 3: the brain holds this desire itself (its node has no Cooldown): a failure after a started task cools it
     * (2 min doubling, Failures), a failure before any task rests it {@code noRunMs}, a success rests it {@code restMs}.
     */
    boolean selfCool;
    long noRunMs, restMs;

    public Desire cools(long noRunMs, long restMs) {
        selfCool = true;
        this.noRunMs = noRunMs;
        this.restMs = restMs;
        return this;
    }

    /** B3: how long the stuck watchdog waits without progress (a shelter waits out the night). */
    long stuckMs = Brain.STUCK_MS;

    public Desire patient(long ms) {
        stuckMs = ms;
        return this;
    }

    /** 0.37.x night fix: an urgent interrupt waits this long after the desire first turned urgent (the running step finishes: one log, one block). */
    long graceMs;
    /** When this desire's own score last turned urgent (-1 not urgent now). */
    long urgentSince = -1;

    public Desire grace(long ms) {
        graceMs = ms;
        return this;
    }

    @Override public String toString() { return id; }
}
