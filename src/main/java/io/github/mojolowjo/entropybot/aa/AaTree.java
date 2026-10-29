package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

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
    public static long calmMs() { return Tuning.l("aaTree.calmMs"); }
    public static long airHoldMs() { return Tuning.l("aaTree.airHoldMs"); }
    /** S1: the spot was lit this night. */
    public static final String LIT_FACT = "litTonight";
    private long airSince = -1;
    /** AA_STEP3 A5: the paused time per guard tick over the last minute ({at, ms}), its sum, and the yield after a "paused too long". */
    private final java.util.ArrayDeque<long[]> pauseLog = new java.util.ArrayDeque<>();
    private long pausedSum, lastGuardAt = -1;
    private boolean airYield;

    private void pausedMs(long now, boolean paused) {
        long dt = lastGuardAt < 0 ? 0 : Math.max(0, Math.min(5_000, now - lastGuardAt));
        lastGuardAt = now;
        if (paused && dt > 0) {
            pauseLog.addLast(new long[]{now, dt});
            pausedSum += dt;
        }
        while (!pauseLog.isEmpty() && now - pauseLog.peekFirst()[0] > Tuning.i("aaTree.pauseWindowMs")) pausedSum -= pauseLog.pollFirst()[1];
    }

    public final GuardSelector root;
    public final MemoryFallback slot;
    public final Node danger;
    /** BRAIN_PLAN B1: the scored desire list (the ladder's rungs and the idle options); {@code ladder} is the same node. */
    public final io.github.mojolowjo.entropybot.aa.brain.Brain brain, ladder;
    public final Band.CalmLatch dangerLatch = new Band.CalmLatch(calmMs());
    private String dangerWhy = "";

    private AaTree(GuardSelector root, MemoryFallback slot, Node danger, io.github.mojolowjo.entropybot.aa.brain.Brain brain) {
        this.root = root;
        this.slot = slot;
        this.danger = danger;
        this.brain = brain;
        brain.optionOf = d -> d.node instanceof io.github.mojolowjo.entropybot.aa.brain.Brain in ? (in.runningDesire() == null ? null : in.runningDesire().id)
                : d.node instanceof Need n && n.picker() != null ? n.picker().chosenId() : null;
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
        // [1] DANGER: the guard with the band (enter at once, leave after 3 s calm). Round 4 (owner 2026-10-20: "AA should
        // be making all decisions so remove the danger guard"): hostiles never pause the slot any more - the danger desire
        // prices fight/flee/cover/home/dig-in/pillar against staying (DangerOptions) and competes by score; the hit
        // response runs under the slot as a reflex. Only the true reflexes that move the bot regardless still pause it:
        // drowning (air), lava or fire, and the creeper sprint.
        Condition dangerGuard = new Condition("danger", "danger", bb -> {
            AaTree t = self[0];
            // 0.36.4 (aa40d/e: under a water ceiling the air stayed short and AA's job sat paused 10+ min, nothing moving
            // the bot): air alone holds the guard at most AIR_HOLD_MS; then the job resumes (its walk gets the bot out),
            // and real drowning still comes back through health <= 8
            long now0 = bb.clock.now();
            boolean airShort = bb.in.air < Tuning.i("aaTree.airShort");
            if (!airShort) {
                t.airSince = -1;
                t.airYield = false;
            } else if (t.airSince < 0) t.airSince = now0;
            // AA_STEP3 A5 (step2-watch: "danger.job(wait 60) PAUSED 1656s by danger | danger: drowning, health 4", the air
            // reflex every 8 s, AA never re-priced): a job paused by a reflex more than Tuning pausedRepriceS within a
            // minute ends ("paused too long: <reflex>"); the air guard then yields to the brain until the air is full, so
            // it picks again with the fresh facts, and the spot is a fact (WATER_HERE_FACT: a wait here sinks)
            // review 0.39.a4 6: only the air reflex's pause counts (lava, fire and the creeper sprint are short and real)
            t.pausedMs(now0, t.root != null && t.root.hasPaused() && airShort && !bb.in.inLavaOrFire && !bb.in.creeperClose);
            double limitMs = io.github.mojolowjo.entropybot.aa.brain.Tuning.get("pausedRepriceS") * 1000;
            if (airShort && !t.airYield && t.root != null && t.root.hasPaused() && t.pausedSum > limitMs) {
                String why = "paused too long: " + t.dangerWhy + " (" + t.pausedSum / 1000 + " s paused in the last 60 s, over pausedRepriceS "  // structural: milliseconds per second
                        + Math.round(limitMs / 1000) + "): the job ends, the brain picks again";  // structural: milliseconds per second
                bb.port.log(why);
                bb.tracer.event(why);
                t.root.endPaused(why);
                if (bb.in.pos != null) bb.fact(AaDesires.WATER_HERE_FACT, new long[]{bb.in.pos[0], bb.in.pos[1], bb.in.pos[2], now0});
                t.airYield = true;
                t.pauseLog.clear();
                t.pausedSum = 0;
            }
            if (t.airYield) airShort = false;      // the air reflex still moves the bot; AA's slot is no longer held for it
            boolean airCounts = airShort && now0 - t.airSince < airHoldMs();
            // AA_CLEANUP 4: low health alone is no reflex any more (no retreat rule): the danger desire and the hide price it;
            // drowning at low health still is (air short and health <= 8)
            boolean drown = airShort && bb.in.health <= Tuning.i("aaTree.drownHealth");
            boolean urgent = drown || (airCounts && bb.in.air < Tuning.i("aaTree.airUrgent")) || bb.in.inLavaOrFire || bb.in.creeperClose;
            boolean notCalm = t.dangerLatch.active() && (airCounts || drown || bb.in.creeperClose);
            boolean on = t.dangerLatch.update(urgent || notCalm, bb.clock.now());
            if (on) t.dangerWhy = bb.in.air < Tuning.i("aaTree.airUrgent") ? "drowning" : bb.in.inLavaOrFire ? "lava or fire" : drown ? "drowning, health " + Math.round(bb.in.health)
                    : bb.in.creeperClose ? "creeper close" : "calming down";
            return on;
        });
        Node dangerBranch = new Fallback("danger.reflex", "the reflex that moves",
                new Seq("danger.air", "drowning", new Condition("danger.air?", "air < 100", bb -> bb.in.air < Tuning.i("aaTree.airUrgent")), observe("danger.air.reflex", "air reflex")),
                new Seq("danger.lava", "lava or fire", new Condition("danger.lava?", "in lava or fire", bb -> bb.in.inLavaOrFire), observe("danger.lava.reflex", "escape reflex")),
                new Seq("danger.creeper", "creeper", new Condition("danger.creeper?", "creeper close", bb -> bb.in.creeperClose), observe("danger.creeper.reflex", "creeper sprint")),
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
                bb.port.log("owner's job ended after " + (bb.clock.now() - since) / 1000 + "s: " + seen);  // structural: milliseconds per second
                seen = null;
                return Status.SUCCESS;
            }

            @Override public void halt(String why) { seen = null; }
        };

        // [2c] BRAIN_PLAN B1: one scored list (the ladder's rungs and the idle options as desires)
        List<java.util.Map.Entry<TaskKind, Node>> idle = List.of(
                // 0.36.2 (40-min run: STOCK_WOOD restarted 0.25 s after each failure, 17 times): a failed idle task
                // cools down like a rung (2 min doubling); planning and busy refusals do not count
                // S1 (MINING_PLAN 12): the owner's targets; a planner-null option cools down 30 s (Cooldown.NO_RUN_MS) so the
                // next option gets its turn (the probe's hypothesis: STOCK_WOOD failing "nothing to run" every tick)
                // one-planner: each stock-up is "I need N of X" (an item or the game's tag) through the chain
                java.util.Map.entry(TaskKind.STOCK_WOOD, (Node) idleCd("idle.wood.cd", goal("idle.wood", TaskKind.STOCK_WOOD, LOGS, AaScorer.woodHi()))),
                java.util.Map.entry(TaskKind.STOCK_STONE, (Node) idleCd("idle.stone.cd", underGoal("idle.stone", TaskKind.STOCK_STONE, STONE, AaScorer.stoneHi()))),
                java.util.Map.entry(TaskKind.STOCK_COAL, (Node) idleCd("idle.coal.cd", underGoal("idle.coal", TaskKind.STOCK_COAL, COALS, AaScorer.coalHi()))),
                java.util.Map.entry(TaskKind.STOCK_IRON, (Node) idleCd("idle.iron.cd", underGoal("idle.iron", TaskKind.STOCK_IRON, IRON, AaScorer.ironHi()))),
                java.util.Map.entry(TaskKind.STOCK_TORCHES, (Node) idleCd("idle.torches.cd", goal("idle.torches", TaskKind.STOCK_TORCHES, AaDesires.TORCH, AaScorer.torchHi()))),
                java.util.Map.entry(TaskKind.DEPOSIT, (Node) idleCd("idle.deposit.cd", RunJob.of("idle.deposit", TaskKind.DEPOSIT, List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Deposit()), "idle"))),
                java.util.Map.entry(TaskKind.LIGHT, (Node) idleCd("idle.light.cd", oncePerNight(RunJob.of("idle.light", TaskKind.LIGHT, List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Light(io.github.mojolowjo.entropybot.aa.brain.Help.lightR())), "idle")))),
                java.util.Map.entry(TaskKind.WAIT, (Node) new RunJob("idle.wait", TaskKind.WAIT, bb -> JobPort.TaskSpec.steps(TaskKind.WAIT, List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Wait((int) io.github.mojolowjo.entropybot.aa.brain.Brain.waitSeconds(bb.clock.now(),
                        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.NEXT_LAPSE_FACT) instanceof Long l ? l : 0))), "idle"))),
                // owner 2026-10-29 (the report-back spot): with nothing else to run and a base place set, the walk back to it
                // (Baritone's path, GoalNear within aaScorer.reportBackNearM); no base: the option scores -1 and the wait stands
                java.util.Map.entry(TaskKind.REPORT_BACK, (Node) idleCd("idle.report_back.cd", new RunJob(AaDesires.REPORT_BACK_ID, TaskKind.REPORT_BACK, bb -> {
                    int[] base = bb.in.basePos;
                    if (base == null || !AaScorer.awayFromBase(bb.in)) return null;
                    bb.port.log("report back: walking to the base place " + base[0] + " " + base[1] + " " + base[2] + " (nothing else to run)");
                    return JobPort.TaskSpec.steps(TaskKind.REPORT_BACK, List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Walk(base[0], base[1], base[2], AaScorer.reportBackNearM())), "idle");
                }))));
        io.github.mojolowjo.entropybot.aa.brain.Brain brain = AaDesires.build(AaDesires.needs(), idle, scorer);
        MemoryFallback slot = new MemoryFallback("slot", "the task slot", owner, brain);
        GuardSelector root = new GuardSelector("root", "AA",
                new GuardSelector.Branch(dangerGuard, dangerBranch),
                new GuardSelector.Branch(new Condition("slot?", "always", bb -> true), slot));
        self[0] = new AaTree(root, slot, dangerBranch, brain);
        return self[0];
    }

    /** The stock-ups' targets: the game's tags (logs, the stone tool materials, the coals, the iron ingots) and the torch. */
    static final String LOGS = "#minecraft:logs", STONE = "#minecraft:stone_tool_materials", COALS = "#minecraft:coals", IRON = "#c:ingots/iron";

    /** An idle stock-up through the chain (no chain: the option fails this tick and cools down 30 s). */
    private static RunJob goal(String id, TaskKind kind, String what, int n) {
        return new RunJob(id, kind, bb -> chainTask(bb, kind, what, n, "idle"));
    }

    /**
     * one-planner: "I need {@code n} of {@code what}" as a task of one {@link io.github.mojolowjo.entropybot.aa.moves.Step.Obtain}
     * (the chain is priced again when the step runs), once a chain stands now; null with the reason pending otherwise.
     */
    static JobPort.TaskSpec chainTask(Blackboard bb, TaskKind kind, String what, int n, String source) {
        var w = io.github.mojolowjo.entropybot.aa.brain.Goals.world(bb);
        if (w == null) {
            bb.pendingReason(source + " " + what + ": no facts for the chain yet");
            return null;
        }
        NeedChain.Chain c = NeedChain.resolve(what, n, w);
        if (!c.ok() || c.steps().isEmpty()) {
            bb.pendingReason(source + " " + what + ": " + (c.ok() ? "in the bag" : c.why()));
            return null;
        }
        return JobPort.TaskSpec.steps(kind, List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Obtain(what, n)), source);
    }

    /**
     * S1: an idle option's cooldown: 2 min doubling after a task that ran and failed, 30 s after one that never ran.
     * AA_ROUND2 3: the brain holds it now (Desire.cools over Failures); the node is the bare job.
     */
    static Node idleCd(String id, Node child) { return child; }

    /**
     * AA_ROUND2 4 (was the night.work desire at 78): a mining stock-up's UNDER option. Sheltered at a hostile night with
     * night work to do (NightWork.pick: smelt, craft, else a shaft and stairs from the shell floor), that runs instead
     * of the chain: the work is cheaper under ground, the brain's exposure factor says so.
     */
    private static RunJob underGoal(String id, TaskKind kind, String what, int n) {
        return new RunJob(id, kind, bb -> {
            if (io.github.mojolowjo.entropybot.aa.brain.NightWork.active(bb)) {
                var w = io.github.mojolowjo.entropybot.aa.brain.NightWork.pick(bb.in);
                if (w != io.github.mojolowjo.entropybot.aa.brain.NightWork.Work.NONE) {
                    int[] p = bb.in.pos == null ? new int[3] : bb.in.pos;
                    io.github.mojolowjo.entropybot.aa.brain.NightWork.noteDug(bb, w);
                    return JobPort.TaskSpec.steps(kind, io.github.mojolowjo.entropybot.aa.brain.NightWork.steps(bb.in, w, p[0], p[1], p[2], 1, 0),
                            "night", JobPort.TaskSpec.timeoutMsV(), AaDesires.shelterPatienceMs());
                }
            }
            return chainTask(bb, kind, what, n, "idle");
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
        for (Need r : Need.all(ladder)) if (r.id.equals("needs.base") && r.last() == Status.SUCCESS && bb.in.basePlace == null) AaLadder.noteShelter(bb);
        bb.tracer.counts.ticks++;
        return s;
    }

    /** Who drives: "danger", "owner", "needs", "idle" or "none". */
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

    /** The owner's order may preempt a needs or idle task (AA_PLAN 14 Q1 yes). */
    public boolean preemptForOwner(Blackboard bb, String verb) {
        Node r = slot.runningChild();
        if (r == null || r.id.equals("owner")) return false;
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
