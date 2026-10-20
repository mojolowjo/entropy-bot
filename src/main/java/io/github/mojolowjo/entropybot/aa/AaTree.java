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
    public static final long CALM_MS = 3000, AIR_HOLD_MS = 30_000;
    /** S1: the spot was lit this night. */
    public static final String LIT_FACT = "litTonight";
    private long airSince = -1;

    public final GuardSelector root;
    public final MemoryFallback slot;
    public final Node danger;
    /** BRAIN_PLAN B1: the scored desire list (the ladder's rungs and the idle options); {@code ladder} is the same node. */
    public final io.github.mojolowjo.entropybot.aa.brain.Brain brain, ladder;
    public final Band.CalmLatch dangerLatch = new Band.CalmLatch(CALM_MS);
    private String dangerWhy = "";

    private AaTree(GuardSelector root, MemoryFallback slot, Node danger, io.github.mojolowjo.entropybot.aa.brain.Brain brain) {
        this.root = root;
        this.slot = slot;
        this.danger = danger;
        this.brain = brain;
        this.ladder = brain;
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
            // 0.36.4 (aa40d/e: under a water ceiling the air stayed short and AA's job sat paused 10+ min, nothing moving
            // the bot): air alone holds the guard at most AIR_HOLD_MS; then the job resumes (its walk gets the bot out),
            // and real drowning still comes back through health <= 8
            long now0 = bb.clock.now();
            boolean airShort = bb.in.air < 300;
            if (!airShort) t.airSince = -1;
            else if (t.airSince < 0) t.airSince = now0;
            boolean airCounts = airShort && now0 - t.airSince < AIR_HOLD_MS;
            boolean urgent = bb.in.danger >= 2 || bb.in.health <= 8 || (airCounts && bb.in.air < 100) || bb.in.inLavaOrFire || bb.in.creeperClose;
            boolean notCalm = t.dangerLatch.active() && (bb.in.health < 12 || airCounts || bb.in.danger > 0);
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
        // [2c] BRAIN_PLAN B1: one scored list (the ladder's rungs and the idle options as desires)
        List<java.util.Map.Entry<TaskKind, Node>> idle = List.of(
                // 0.36.2 (40-min run: STOCK_WOOD restarted 0.25 s after each failure, 17 times): a failed idle task
                // cools down like a rung (2 min doubling); planning and busy refusals do not count
                // S1 (MINING_PLAN 12): the owner's targets; a planner-null option cools down 30 s (Cooldown.NO_RUN_MS) so the
                // next option gets its turn (the probe's hypothesis: STOCK_WOOD failing "nothing to run" every tick)
                java.util.Map.entry(TaskKind.STOCK_WOOD, (Node) idleCd("idle.wood.cd", goal("idle.wood", TaskKind.STOCK_WOOD, "goal wood " + AaScorer.WOOD_HI))),
                java.util.Map.entry(TaskKind.STOCK_STONE, (Node) idleCd("idle.stone.cd", underGoal("idle.stone", TaskKind.STOCK_STONE, "goal cobblestone " + AaScorer.STONE_HI))),
                java.util.Map.entry(TaskKind.STOCK_COAL, (Node) idleCd("idle.coal.cd", coalStock())),
                java.util.Map.entry(TaskKind.STOCK_IRON, (Node) idleCd("idle.iron.cd", underGoal("idle.iron", TaskKind.STOCK_IRON, "goal iron " + AaScorer.IRON_HI))),
                java.util.Map.entry(TaskKind.STOCK_TORCHES, (Node) idleCd("idle.torches.cd", torchStock())),
                java.util.Map.entry(TaskKind.DEPOSIT, (Node) idleCd("idle.deposit.cd", RunJob.of("idle.deposit", TaskKind.DEPOSIT, "deposit", "idle"))),
                java.util.Map.entry(TaskKind.LIGHT, (Node) idleCd("idle.light.cd", oncePerNight(RunJob.of("idle.light", TaskKind.LIGHT, "light here 8", "idle")))),
                java.util.Map.entry(TaskKind.WAIT, (Node) new RunJob("idle.wait", TaskKind.WAIT, bb -> JobPort.TaskSpec.of(TaskKind.WAIT, "wait " + io.github.mojolowjo.entropybot.aa.brain.Brain.waitSeconds(bb.clock.now(),
                        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.NEXT_LAPSE_FACT) instanceof Long l ? l : 0), "idle"))));
        io.github.mojolowjo.entropybot.aa.brain.Brain brain = AaDesires.build(AaLadder.build(), idle, scorer);
        MemoryFallback slot = new MemoryFallback("slot", "the task slot", owner, ai, brain);
        GuardSelector root = new GuardSelector("root", "AA",
                new GuardSelector.Branch(dangerGuard, dangerBranch),
                new GuardSelector.Branch(new Condition("slot?", "always", bb -> true), slot));
        self[0] = new AaTree(root, slot, dangerBranch, brain);
        return self[0];
    }

    /** An idle goal through the planner (none or still searching: the option fails this tick and cools down 30 s). */
    private static RunJob goal(String id, TaskKind kind, String text) {
        return new RunJob(id, kind, bb -> {
            String chain = bb.port.resolve(text);
            return chain == null ? null : JobPort.TaskSpec.of(kind, chain, "idle");
        });
    }

    /**
     * S1: an idle option's cooldown: 2 min doubling after a task that ran and failed, 30 s after one that never ran.
     * AA_ROUND2 3: the brain holds it now (Desire.cools over Failures); the node is the bare job.
     */
    static Node idleCd(String id, Node child) { return child; }

    /**
     * AA_ROUND2 4 (was the night.work desire at 78): a mining stock-up's UNDER option. Sheltered at a hostile night with
     * night work to do (NightWork.pick: smelt, craft, else a shaft and stairs from the shell floor), that runs instead
     * of the planner's goal: the work is cheaper under ground, the brain's exposure factor says so.
     */
    private static RunJob underGoal(String id, TaskKind kind, String text) {
        return new RunJob(id, kind, bb -> {
            if (io.github.mojolowjo.entropybot.aa.brain.NightWork.active(bb)) {
                var w = io.github.mojolowjo.entropybot.aa.brain.NightWork.pick(bb.in);
                if (w != io.github.mojolowjo.entropybot.aa.brain.NightWork.Work.NONE) {
                    int[] p = bb.in.pos == null ? new int[3] : bb.in.pos;
                    io.github.mojolowjo.entropybot.aa.brain.NightWork.noteDug(bb, w);
                    return new JobPort.TaskSpec(kind, io.github.mojolowjo.entropybot.aa.brain.NightWork.chain(bb.in, w, p[0], p[1], p[2], 1, 0),
                            "night", 1, JobPort.TaskSpec.TIMEOUT_MS, AaDesires.SHELTER_PATIENCE_MS);
                }
            }
            String chain = bb.port.resolve(text);
            return chain == null ? null : JobPort.TaskSpec.of(kind, chain, "idle");
        });
    }

    /**
     * three-tier r1: the coal stock-up through the priced coal set (mine seen / find / charcoal, CoalOptions). Night work
     * as underGoal; no facts readable: the planner's "goal coal" as before. No runnable way: logged with every reason.
     */
    private static RunJob coalStock() {
        return new RunJob("idle.coal", TaskKind.STOCK_COAL, bb -> {
            if (io.github.mojolowjo.entropybot.aa.brain.NightWork.active(bb)) {
                var w = io.github.mojolowjo.entropybot.aa.brain.NightWork.pick(bb.in);
                if (w != io.github.mojolowjo.entropybot.aa.brain.NightWork.Work.NONE) {
                    int[] p = bb.in.pos == null ? new int[3] : bb.in.pos;
                    io.github.mojolowjo.entropybot.aa.brain.NightWork.noteDug(bb, w);
                    return new JobPort.TaskSpec(TaskKind.STOCK_COAL, io.github.mojolowjo.entropybot.aa.brain.NightWork.chain(bb.in, w, p[0], p[1], p[2], 1, 0),
                            "night", 1, JobPort.TaskSpec.TIMEOUT_MS, AaDesires.SHELTER_PATIENCE_MS);
                }
            }
            Options.Choice c = CoalOptions.plan(bb, Math.max(1, AaScorer.COAL_HI - bb.in.coal));
            if (c != null) {
                if (c.best() == null) {
                    bb.port.log("STOCK_COAL has no coal way: " + c.why());
                    return null;
                }
                bb.port.log("STOCK_COAL coal ways: " + c.why());
                return JobPort.TaskSpec.of(TaskKind.STOCK_COAL, c.best().command(), "idle");
            }
            String chain = bb.port.resolve("goal coal " + AaScorer.COAL_HI);
            return chain == null ? null : JobPort.TaskSpec.of(TaskKind.STOCK_COAL, chain, "idle");
        });
    }

    /** three-tier r1: torches with no coal in the bag run the best coal way first (CoalOptions.torchesFirst), never a bare craft. */
    private static RunJob torchStock() {
        return new RunJob("idle.torches", TaskKind.STOCK_TORCHES, bb -> {
            String planned = bb.port.resolve("goal torch " + AaScorer.TORCH_HI);
            String[] why = new String[1];
            String chain = CoalOptions.torchesFirst(bb, planned, AaScorer.TORCH_HI, why);
            if (why[0] != null) bb.port.log("STOCK_TORCHES coal ways: " + why[0]);
            return chain == null ? null : JobPort.TaskSpec.of(TaskKind.STOCK_TORCHES, chain, "idle");
        });
    }
    /** S1: lighting the spot runs once per night (a SUCCESS holds it until the day). */
    static Node oncePerNight(Node child) {
        return new Node(child.id + ".night", "once per night") {
            @Override protected Status run(Blackboard bb) {
                if (bb.flag(LIT_FACT)) return fail("lit already tonight");
                Status s = child.tick(bb);
                if (s == Status.SUCCESS) bb.fact(LIT_FACT, Boolean.TRUE);
                return s == Status.FAILURE ? fail(child.reason()) : s;
            }

            @Override public List<Node> children() { return List.of(child); }
        };
    }

    /** One tick from the root; the ladder's SUCCESS of the shelter rung is remembered. */
    public Status tick(Blackboard bb) {
        bb.tracer.beginTick();
        if (!bb.in.night) bb.fact(LIT_FACT, null);         // review: reset by the tree each day (the node is not ticked by day)
        bb.in.litTonight = bb.flag(LIT_FACT);
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
        // BRAIN_PLAN B1: the brain drives as "needs" (a rung) or "idle" (an idle option), as the ladder and idle did
        if (r == brain) {
            var d = brain.runningDesire();
            return d == null ? "none" : d.id.startsWith("needs.") ? "needs" : "idle";
        }
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
