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
 * <li>GATE: a hard limit (no area, under ground for a surface task); scored high but never interrupts a running task.</li>
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

    /** B3 (2.10): +1 mining (a night bonus), -1 surface work (a night malus), 0 neither; applied when monsters spawn. */
    int night;

    public Desire night(int sign) {
        night = Integer.signum(sign);
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
