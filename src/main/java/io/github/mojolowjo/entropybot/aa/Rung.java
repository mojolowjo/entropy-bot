package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * AA v0 (AA_PLAN 9): one rung of the basic-needs ladder: unmet? -> may it start (a skip reason)? -> the goal text ->
 * the planner's chain (null while it plans, "no plan" after {@link #PLAN_WAIT_MS}) -> feasible? -> the RunJob under a
 * Cooldown. Every "no" is a FAILURE with a readable reason, so the ladder's reason line says why each rung was passed.
 * After a SUCCESS the rung counts as met for {@link #SETTLE_MS} (the sense facts refresh at most once a minute).
 */
public final class Rung extends Node {
    public static final long PLAN_WAIT_MS = 45_000, SETTLE_MS = 75_000;
    /** The blackboard fact "what is being planned" (the status line shows it while the slot waits). */
    public static final String PLANNING_FACT = "planning";

    public final TaskKind kind;
    private final Predicate<Blackboard> unmet;
    private final Function<Blackboard, String> skip, goal;
    private final RunJob job;
    private final Cooldown cooldown;
    private String resolved;
    private long planningSince = -1, settledAt = -1;
    private String planFailed;
    private long planFailedAt = -1;

    /**
     * @param unmet the need is on (bands live in here)
     * @param skip  why it cannot start now (null = it can), e.g. "no base chests scanned"
     * @param goal  the text to run: "goal torch 16", "deposit", "house main"...
     */
    public Rung(String id, TaskKind kind, Predicate<Blackboard> unmet, Function<Blackboard, String> skip, Function<Blackboard, String> goal) {
        super(id, "rung " + kind);
        this.kind = kind;
        this.unmet = unmet;
        this.skip = skip;
        this.goal = goal;
        this.job = new RunJob(id + ".job", kind, bb -> resolved == null ? null : JobPort.TaskSpec.of(kind, resolved, "needs"));
        this.cooldown = new Cooldown(id + ".cd", job);
    }

    @Override protected Status run(Blackboard bb) {
        long now = bb.clock.now();
        if (job.running()) {
            Status s = cooldown.tick(bb);
            if (s == Status.SUCCESS) settledAt = now;
            if (s == Status.FAILURE) return fail(cooldown.reason());
            return s;
        }
        if (!unmet.test(bb)) return fail(kind + " met");
        String why = skip.apply(bb);
        if (why != null) return fail(kind + " skipped: " + why);
        String g = goal.apply(bb);
        if (g == null) return fail(kind + " has nothing to run");
        if (settledAt >= 0 && now - settledAt < SETTLE_MS && g.equals(settledGoal)) return fail(kind + " just done (" + g + "), waiting for the facts");
        settledAt = -1;
        if (cooldown.cooling(now)) {
            cooldown.tick(bb);                  // answers the cooldown reason without touching the job
            return fail(cooldown.reason());
        }
        if (planFailed != null && now - planFailedAt < Cooldown.BASE_MS) return fail(kind + " skipped: " + planFailed);
        planFailed = null;
        String chain = bb.port.resolve(g);
        if (chain == null) {
            // the planner searches on its worker: the slot waits on this rung (RUNNING, so lower rungs don't all plan
            // at once) up to PLAN_WAIT_MS, then the rung is skipped for a while
            if (planningSince < 0) {
                planningSince = now;
                bb.port.log("planning " + g + " for " + kind);
            }
            if (now - planningSince > PLAN_WAIT_MS) {
                planningSince = -1;
                bb.fact(PLANNING_FACT, null);
                planFailed = "no plan for " + g + " (the planner found none in " + PLAN_WAIT_MS / 1000 + "s)";
                planFailedAt = now;
                bb.port.log("no plan for " + g + ": skipped " + Cooldown.BASE_MS / 60000 + " min");
                return fail(kind + " skipped: " + planFailed);
            }
            bb.fact(PLANNING_FACT, kind + ": " + g + " (" + (now - planningSince) / 1000 + "s)");
            return Status.RUNNING;
        }
        planningSince = -1;
        bb.fact(PLANNING_FACT, null);
        String inf = bb.port.infeasible(chain);
        if (inf != null) return fail(kind + " skipped: " + inf);
        resolved = chain;
        settledGoal = g;
        bb.pendingReason("needs: " + kind + " unmet -> " + g);
        Status s = cooldown.tick(bb);
        if (s == Status.SUCCESS) settledAt = now;
        return s == Status.FAILURE ? fail(cooldown.reason()) : s;
    }

    private String settledGoal;

    @Override public void halt(String why) {
        if (planningSince >= 0 && board != null) board.fact(PLANNING_FACT, null);
        planningSince = -1;
        cooldown.halt(why);
    }

    @Override public List<Node> children() { return List.of(cooldown); }

    public RunJob job() { return job; }

    public Cooldown cooldown() { return cooldown; }
}
