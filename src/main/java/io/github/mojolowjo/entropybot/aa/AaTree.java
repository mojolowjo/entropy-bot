package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Band;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Condition;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.Fallback;
import io.github.mojolowjo.entropybot.aa.core.GuardSelector;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.MemoryFallback;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.Request;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Scorer;
import io.github.mojolowjo.entropybot.aa.core.Seq;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.UtilitySelector;

import java.util.List;

/**
 * AA v0 (AA_PLAN 1): the tree, built in Java (no tree file). ROOT = GuardSelector: [1] DANGER (pauses the slot) over
 * [2] the TASK SLOT (MemoryFallback): [2a] the owner's order (adopted: a job the owner started runs in the slot until
 * it ends), [2b] an AI request from the board, [2c] the basic-needs ladder, [2d] idle (UtilitySelector with inertia).
 * {@link #mermaid()} exports the static tree for the docs; node ids are stable for the snapshot.
 */
public final class AaTree {
    public static final long CALM_MS = 3000;

    public final GuardSelector root;
    public final MemoryFallback slot;
    public final Node danger, ladder;
    public final UtilitySelector idle;
    public final Band.CalmLatch dangerLatch = new Band.CalmLatch(CALM_MS);
    private String dangerWhy = "";

    private AaTree(GuardSelector root, MemoryFallback slot, Node danger, Node ladder, UtilitySelector idle) {
        this.root = root;
        this.slot = slot;
        this.danger = danger;
        this.ladder = ladder;
        this.idle = idle;
    }

    /** A leaf that only observes: RUNNING while its condition holds (the reflexes do the moving). */
    static Node observe(String id, String label) {
        return new Node(id, label) {
            @Override protected Status run(Blackboard bb) { return Status.RUNNING; }
        };
    }

    public static AaTree build(Scorer scorer) {
        AaTree[] self = new AaTree[1];
        // [1] DANGER: the guard with the band (enter at once, leave after 3 s calm with health >= 12 and air full)
        Condition dangerGuard = new Condition("danger", "danger", bb -> {
            AaTree t = self[0];
            boolean urgent = bb.in.danger >= 2 || bb.in.health <= 8 || bb.in.air < 100 || bb.in.inLavaOrFire || bb.in.creeperClose;
            boolean notCalm = t.dangerLatch.active() && (bb.in.health < 12 || bb.in.air < 300 || bb.in.danger > 0);
            boolean on = t.dangerLatch.update(urgent || notCalm, bb.clock.now());
            if (on) t.dangerWhy = bb.in.air < 100 ? "drowning" : bb.in.inLavaOrFire ? "lava or fire" : bb.in.health <= 8 ? "low health " + Math.round(bb.in.health)
                    : bb.in.creeperClose ? "creeper close" : bb.in.danger >= 2 ? bb.in.dangerWhy : "calming down";
            return on;
        });
        Node dangerBranch = new Fallback("danger.reflex", "the reflex that moves",
                new Seq("danger.air", "drowning", new Condition("danger.air?", "air < 100", bb -> bb.in.air < 100), observe("danger.air.reflex", "air reflex")),
                new Seq("danger.lava", "lava or fire", new Condition("danger.lava?", "in lava or fire", bb -> bb.in.inLavaOrFire), observe("danger.lava.reflex", "escape reflex")),
                new Seq("danger.health", "low health", new Condition("danger.health?", "health <= 8", bb -> bb.in.health <= 8), observe("danger.health.reflex", "retreat reflex")),
                new Seq("danger.hostile", "hostile or creeper", new Condition("danger.hostile?", "fight/flee or creeper", bb -> bb.in.danger >= 2 || bb.in.creeperClose), observe("danger.hostile.reflex", "fight/flee reflex")),
                observe("danger.calm", "calming down"));
        // [2a] the owner's order: adopted while a job that is not AA's runs
        Node owner = new Node("owner", "the owner's order (a job of the owner's runs)") {
            private String seen;
            private long since;

            @Override protected Status run(Blackboard bb) {
                if (seen == null) {
                    if (bb.in.otherJob == null) return fail("no order of the owner's");
                    seen = bb.in.otherJob;
                    since = bb.clock.now();
                    bb.tracer.decision(bb.clock.now(), "owner", TaskKind.OWNER, seen, "the owner's order runs in the slot");
                    bb.port.log("owner's job in the slot: " + seen);
                }
                if (bb.in.otherJob != null) return Status.RUNNING;
                bb.tracer.outcome(bb.clock.now(), "done");
                bb.port.log("owner's job ended after " + (bb.clock.now() - since) / 1000 + "s: " + seen);
                seen = null;
                return Status.SUCCESS;
            }

            @Override public void halt(String why) { seen = null; }
        };
        // [2b] an AI request from the board (queues, never preempts)
        RunJob aiJob = new RunJob("ai.job", TaskKind.AI, bb -> {
            Request r = bb.take("ai");
            if (r == null) return null;
            bb.pendingReason("ai: " + r.reason());
            return new JobPort.TaskSpec(TaskKind.AI, r.chain(), "ai", r.priority(), JobPort.TaskSpec.TIMEOUT_MS, JobPort.TaskSpec.NO_PROGRESS_MS);
        });
        Node ai = new Seq("ai", "an AI request", new Condition("ai?", "an AI request waits", bb -> bb.peek("ai") != null), aiJob);
        // [2c] the ladder, [2d] idle
        Node ladder = AaLadder.build();
        UtilitySelector idle = new UtilitySelector("idle", "idle", scorer, UtilitySelector.BONUS,
                // 0.36.2 (40-min run: STOCK_WOOD restarted 0.25 s after each failure, 17 times): a failed idle task
                // cools down like a rung (2 min doubling); planning and busy refusals do not count
                new UtilitySelector.Option(TaskKind.STOCK_WOOD, new Cooldown("idle.wood.cd", goal("idle.wood", TaskKind.STOCK_WOOD, "goal wood 64")).ranOnly()),
                new UtilitySelector.Option(TaskKind.STOCK_STONE, new Cooldown("idle.stone.cd", goal("idle.stone", TaskKind.STOCK_STONE, "goal cobblestone 64")).ranOnly()),
                new UtilitySelector.Option(TaskKind.WAIT, new Cooldown("idle.wait.cd", RunJob.of("idle.wait", TaskKind.WAIT, "wait 60", "idle")).ranOnly()));
        MemoryFallback slot = new MemoryFallback("slot", "the task slot", owner, ai, ladder, idle);
        GuardSelector root = new GuardSelector("root", "AA",
                new GuardSelector.Branch(dangerGuard, dangerBranch),
                new GuardSelector.Branch(new Condition("slot?", "always", bb -> true), slot));
        self[0] = new AaTree(root, slot, dangerBranch, ladder, idle);
        return self[0];
    }

    /** An idle goal through the planner (null while it plans: the option fails this tick and idle re-chooses). */
    private static RunJob goal(String id, TaskKind kind, String text) {
        return new RunJob(id, kind, bb -> {
            String chain = bb.port.resolve(text);
            return chain == null ? null : JobPort.TaskSpec.of(kind, chain, "idle");
        });
    }

    /** One tick from the root; the ladder's SUCCESS of the shelter rung is remembered. */
    public Status tick(Blackboard bb) {
        bb.tracer.beginTick();
        Status s = root.tick(bb);
        for (Rung r : AaLadder.rungs(ladder)) if (r.id.equals("needs.base") && r.last() == Status.SUCCESS && bb.in.areaName == null) AaLadder.noteShelter(bb);
        bb.tracer.counts.ticks++;
        return s;
    }

    /** Who drives: "danger", "owner", "ai", "needs", "idle" or "none". */
    public String driver() {
        if (root.active() == 0) return "danger";
        Node r = slot.runningChild();
        if (r == null) return "none";
        return r.id;
    }

    public String dangerWhy() { return dangerLatch.active() ? dangerWhy : "calm"; }

    /**
     * The owner's order may preempt a needs, idle or AI task (AA_PLAN 14 Q1 yes; 0.36.1 review 4: an AI task too, its
     * request goes back to the front of the board); AI requests never preempt anything.
     */
    public boolean preemptForOwner(Blackboard bb, String verb) {
        Node r = slot.runningChild();
        if (r == null || r.id.equals("owner")) return false;
        if (r.id.equals("ai") && bb.task() != null && bb.task().spec() != null) {
            JobPort.TaskSpec t = bb.task().spec();
            bb.postFirst(new Request("ai", TaskKind.AI, t.chain(), t.priority(), bb.clock.now(), 0, "the AI asked (again after the owner's " + verb + "): " + t.chain()));
        }
        slot.preempt("preempted by owner: " + verb);
        bb.tracer.counts.preempts++;
        bb.port.log("preempted for the owner's " + verb);
        return true;
    }

    /** Every node, depth first. */
    public List<Node> nodes() {
        List<Node> out = new java.util.ArrayList<>();
        root.walk(out::add);
        return out;
    }

    /** The static tree as Mermaid (for the docs). */
    public String mermaid() {
        StringBuilder b = new StringBuilder("graph TD\n");
        root.walk(n -> {
            b.append("  ").append(mid(n.id)).append("[\"").append(n.id).append(": ").append(n.label.replace("\"", "'")).append("\"]\n");
            for (Node c : n.children()) b.append("  ").append(mid(n.id)).append(" --> ").append(mid(c.id)).append('\n');
        });
        return b.toString();
    }

    private static String mid(String id) { return id.replaceAll("[^A-Za-z0-9]", "_"); }
}
