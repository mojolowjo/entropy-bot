package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.AaEscape;
import io.github.mojolowjo.entropybot.aa.AaScorer;
import io.github.mojolowjo.entropybot.aa.AaTree;
import io.github.mojolowjo.entropybot.aa.AaLadder;
import io.github.mojolowjo.entropybot.aa.IronOptions;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.Request;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.brain.BrainState;
import io.github.mojolowjo.entropybot.brain.Feasibility;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.plan.RoutineRule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 0.36.0 AA v0 (AA_PLAN 2-6, 8, 11; package P3): the thin game-side adapter of the new brain. Fills the Blackboard
 * from {@code BrainRuntime.sense()} (reused, not copied), implements {@link JobPort} over Chains/Jobs (AA's chain is
 * named {@value #CHAIN}; only AA starts and cancels it), is ticked from {@code Commands.tick} every 5 client ticks
 * (4 Hz; the sense snapshot every 10), writes the snapshot and decision files under {@code entropybot/aa/}, and turns
 * itself off after 10 tick errors in a minute (saying so in {@code aa status} and a whisper).
 *
 * <p>Loader notes: nothing of its own: the client tick through Commands (NeoForge ClientTickEvent.Post via Core;
 * Fabric END_CLIENT_TICK), LocalPlayer getters, our Chains/Jobs. No events, mixins, reflection or Baritone.
 */
public final class AaRuntime implements JobPort {
    private static final Logger LOG = LogUtils.getLogger();
    public static final String CHAIN = "aa";
    static final long HOLD_MS = 2 * 60_000;
    static final int ERRORS_PER_MINUTE_OFF = 10;

    private final Commands c;
    final AaTree tree;
    final Blackboard bb;
    private final Tracer tracer = new Tracer();
    private BrainState sensed;
    private long sensedTick = -1;
    private long seq, current = -1;
    private String currentChain, lastEnd;
    private long progress;
    private String lastStep, lastJobStatus;
    private int lastBag = -1;
    private int[] lastPos;
    /**
     * 0.36.a6: the escape from under ground inside AA's task (AaEscape): pending (the stuck walk's end is settling), the
     * way that runs ("pillar" job / "climb" chain), the chain to go on with afterwards, the stuck end (the task's
     * failure text if no way out works), escapes in this task.
     */
    private boolean escapePending;
    private String escapeWay, escapeResume, escapeFail;
    private Object escapeJob;
    private int escapes;
    private long heldUntil, lastSnapshotAt, lastErrorAt, errorsThisMinute, minuteStart, errors;
    private String lastError, offReason;
    private int decisionLines;
    private long aiRequests;

    AaRuntime(Commands c) {
        this.c = c;
        this.tree = AaTree.build(new AaScorer());
        this.bb = new Blackboard(System::currentTimeMillis, this, tracer);
        tracer.sink(this::appendDecision);
        JsonObject d = data();
        if (d.has(AaLadder.SHELTER_FACT) && d.get(AaLadder.SHELTER_FACT).getAsBoolean()) AaLadder.noteShelter(bb);
        bb.fact(IronOptions.FACTS, (java.util.function.Supplier<IronOptions.Facts>) this::ironFacts);
        bb.fact(io.github.mojolowjo.entropybot.aa.RungOptions.SURFACE_FACTS, (java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts>) this::surfaceFacts);
        bb.fact(io.github.mojolowjo.entropybot.aa.RungOptions.TOOLS_FACTS, (java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.RungOptions.ToolsFacts>) this::toolsFacts);
        c.chains.onEnd(this::chainEnded);
    }

    /**
     * 0.36.a8 (MINING_PLAN 3.6): the facts the IRON rung's option scorer reads, read once per pick (not per tick): the
     * nearest known iron with its faces, depth, water and policy verdict (Mining.aaOres), the nearest known cave, strip
     * preconditions, the camp furnace's distance and the bag. Null (the planner's chain runs as it is) when nothing can
     * be read; never throws.
     */
    IronOptions.Facts ironFacts() {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || sensed == null) return null;
            int[] me = Jobs.here(p);
            String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level);
            List<IronOptions.Ore> ores = Mining.get().aaOres(p, IronOptions.FAMILY, IRON_ORES);
            var cave = Core.INSTANCE.caves.nearest(dim, me[0], me[1], me[2]);
            int caveDist = cave == null ? -1 : (int) Math.round(Math.sqrt(Jobs.distSq(me, new int[]{cave.ex, cave.ey, cave.ez})));
            if (caveDist > io.github.mojolowjo.entropybot.cave.Caves.PICK_WITHIN) caveDist = -1;      // review: "mine cave" picks only within 64, else it starts a new cave here
            Map<String, Integer> bag = sensed.feas != null ? sensed.feas.bag : Map.of();
            boolean mineMarked = sensed.feas != null && sensed.feas.mineMarked;
            String stripWhy = !mineMarked ? "no mine marked (place mine)" : !sensed.areasHere ? "outside my areas" : null;
            int[] furnace = c.crafting.findFurnace(p);
            int furnaceDist = furnace == null ? -1 : (int) Math.round(Math.sqrt(Jobs.distSq(me, furnace)));
            return new IronOptions.Facts(ores, me[1], p.isInWater(), AaEscape.throwaway(bag),
                    io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.travelMayPlace(), caveDist, stripWhy == null, stripWhy,
                    furnaceDist, bag.getOrDefault("furnace", 0) > 0, bag.getOrDefault("crafting_table", 0) > 0,
                    bag.getOrDefault("cobblestone", 0) + bag.getOrDefault("cobbled_deepslate", 0));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: iron facts: {}", e.toString());
            return null;
        }
    }

    /**
     * S2 (MINING_PLAN 11): the SURFACE rung's facts, read once per pick: depth (or the hole's way out), the open shaft,
     * throwaway blocks, placing allowed, a pickaxe, the trail back to open sky (TrailRuntime.retrace), the nearest
     * standable open-sky cell within {@link #SKY_R} (heightmap scan of loaded columns), and whether the next rung is the
     * table/furnace one. Null when nothing can be read; a throw is caught by the picker (logged, counted).
     */
    io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts surfaceFacts() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || sensed == null) return null;
        net.minecraft.world.level.Level lv = mc.level;
        int[] me = Jobs.here(p);
        int depth = Jobs.underDepth(lv, me[0], me[1], me[2]);
        if (depth == 0) depth = Math.max(0, holeUp(lv, me));
        boolean shaft = depth > 0;
        for (int y = me[1] + 2; shaft && y <= me[1] + depth; y++) if (!openCell(lv, me[0], y, me[2])) shaft = false;
        Map<String, Integer> bag = sensed.feas != null ? sensed.feas.bag : Map.of();
        String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        int[] trailEnd = null;
        double trailLen = 0;
        List<int[]> hops = io.github.mojolowjo.entropybot.trail.TrailRuntime.INSTANCE.trail().retrace(dim, me[0], me[1], me[2], 8, 8, 400);
        if (hops != null && !hops.isEmpty()) {
            int[] prev = me;
            for (int[] h : hops) {
                trailLen += Math.sqrt(Jobs.distSq(prev, h));
                prev = h;
            }
            trailEnd = hops.get(hops.size() - 1);
        }
        int[] sky = null;
        double best = Double.MAX_VALUE;
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = me[0] - SKY_R; x <= me[0] + SKY_R; x++)
            for (int z = me[2] - SKY_R; z <= me[2] + SKY_R; z++) {
                m.set(x, 0, z);
                if (!lv.isLoaded(m)) continue;
                int y = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                double d = Math.sqrt(Jobs.distSq(me, new int[]{x, y, z}));
                m.set(x, y, z);
                if (d >= best || !lv.canSeeSky(m) || !openCell(lv, x, y, z) || !openCell(lv, x, y + 1, z)) continue;     // review: no roof (the Nether)
                m.set(x, y - 1, z);
                var below = lv.getBlockState(m);
                if (below.getCollisionShape(lv, m).isEmpty() || !below.getFluidState().isEmpty()) continue;
                best = d;
                sky = new int[]{x, y, z};
            }
        boolean tf = !(bb.in.tableKnown || bb.in.tableInBag) || !(bb.in.furnaceKnown || bb.in.furnaceInBag);
        return new io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts(depth, me, shaft, AaEscape.throwaway(bag),
                io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.travelMayPlace(), bb.in.pickaxes > 0,
                trailEnd, trailLen, sky, sky == null ? -1 : best, tf);
    }

    /** The open-sky cell search radius (columns), S2. */
    static final int SKY_R = 24;

    /** S2: the TOOLS rung's facts (the pickaxe tier, trees within 16 as the planner sees them). */
    io.github.mojolowjo.entropybot.aa.RungOptions.ToolsFacts toolsFacts() {
        if (sensed == null || sensed.feas == null) return null;
        return new io.github.mojolowjo.entropybot.aa.RungOptions.ToolsFacts(bb.in.pickTier, sensed.feas.trees);
    }

    /** The nearest known iron positions the scorer weighs. */
    static final int IRON_ORES = 8;

    /** The IRON rung's options line ("aa why"), or null. */
    String ironOptions() {
        for (var r : AaLadder.rungs(tree.ladder)) if (r.kind == TaskKind.IRON) return r.options();
        return null;
    }

    // ---- S0: options lines, counters and the owner's manual driving (AaManual) ----

    /** One "options <rung>: <line>" per rung with a picker (aa why). */
    List<String> optionLines() {
        List<String> out = new ArrayList<>();
        for (var r : AaLadder.rungs(tree.ladder)) if (r.picker() != null) out.add(r.id.substring(6) + " options: " + r.options());
        return out;
    }

    /** Per-rung picker counters and the manual state (aa status); "!!" first when a facts supplier failed in the last minute. */
    String optionStatus() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        boolean red = false;
        for (var r : AaLadder.rungs(tree.ladder)) {
            if (r.picker() == null) continue;
            red |= r.picker().factsFailedSince(now - 60_000);
            String k = r.picker().counters(now);
            if (k != null) b.append('\n').append(r.id.substring(6)).append(": ").append(k);
        }
        return (red ? "!! a facts supplier failed in the last minute (see the log: aa: <RUNG> facts:)\n" : "") + io.github.mojolowjo.entropybot.aa.AaManual.of(bb).describe(now) + b;
    }

    /** The rung AA works on now (the ladder's running rung), else the one that picked last; null none. */
    private String currentRung() {
        Node n = tree.slot.runningChild();
        if (n == tree.brain && tree.brain.runningChild() instanceof io.github.mojolowjo.entropybot.aa.Rung rr) return rr.id;
        // review: the last picker only while it still runs (a stale one would preempt an unrelated task)
        Object o = bb.fact(io.github.mojolowjo.entropybot.aa.Rung.CURRENT_RUNG);
        for (var r : AaLadder.rungs(tree.ladder)) if (r.id.equals(o) && r.last() == Status.RUNNING) return r.id;
        return null;
    }

    /** AA's own needs/idle task (not the owner's, not the AI's) is halted so the ladder chooses again now. */
    private boolean preemptOwnTask(String why) {
        Node n = tree.slot.runningChild();
        if (n == null || n.id.equals("owner") || n.id.equals("ai")) return false;
        tree.slot.preempt(why);
        LOG.info("[entropybot] aa: {}", why);
        return true;
    }

    /** aa ask on|off | pick n | do need | skip need|none | use option. */
    String manual(String verb, String arg) {
        var man = io.github.mojolowjo.entropybot.aa.AaManual.of(bb);
        String a = arg == null ? "" : arg.trim();
        switch (verb) {
            case "ask" -> {
                if (!a.equals("on") && !a.equals("off")) return "usage: aa ask on|off (now " + (man.ask ? "on" : "off") + ")";
                man.ask = a.equals("on");
                if (!man.ask) man.pending = null;
                LOG.info("[entropybot] aa: ask {}", a);
                return "ok: aa ask " + a + (man.ask ? " - before each task I show my options and wait " + io.github.mojolowjo.entropybot.aa.AaManual.ASK_MS / 1000 + " s for aa pick <n>, then take my first" : "");
            }
            case "pick" -> {
                if (man.pending == null) return "nothing to pick: no rung is asking now (aa ask on makes them ask)";
                int n;
                try { n = Integer.parseInt(a); } catch (NumberFormatException e) { n = -1; }
                if (n < 1 || n > man.pending.rows().size()) return "usage: aa pick 1.." + man.pending.rows().size() + " - " + io.github.mojolowjo.entropybot.aa.AaManual.rowsText(man.pending.rows());
                man.pick = n;
                return "ok: " + man.pending.rung() + " runs " + n + ") " + man.pending.rows().get(n - 1).label();
            }
            case "do" -> {
                if (a.equals("none")) { man.doRung = null; return "ok: aa do cleared"; }
                String id = io.github.mojolowjo.entropybot.aa.AaManual.rungId(a);
                if (id == null) return "usage: aa do <need>|none - needs: " + String.join(", ", io.github.mojolowjo.entropybot.aa.AaManual.NEEDS);
                man.doRung = id;
                man.doSince = bb.clock.now();
                man.skipped.remove(id);
                boolean pre = preemptOwnTask("aa do " + a + ": my task stops, " + id + " runs now");
                return "ok: " + id + " runs now" + (pre ? " (my task stopped)" : "") + "; the other rungs wait until it ends";
            }
            case "skip" -> {
                if (a.equals("none") || a.isEmpty()) {
                    String was = String.join(", ", man.skipped);
                    man.skipped.clear();
                    return a.isEmpty() && !was.isEmpty() ? "skipped this run: " + was + " (aa skip none clears)" : "ok: nothing skipped" + (was.isEmpty() ? "" : " (was: " + was + ")");
                }
                String id = io.github.mojolowjo.entropybot.aa.AaManual.rungId(a);
                if (id == null) return "usage: aa skip <need>|none - needs: " + String.join(", ", io.github.mojolowjo.entropybot.aa.AaManual.NEEDS);
                man.skipped.add(id);
                if (id.equals(man.doRung)) man.doRung = null;
                boolean pre = id.equals(currentRung()) && preemptOwnTask("aa skip " + a + ": its task stops");
                return "ok: " + id + " left out this run" + (pre ? " (its task stopped)" : "") + "; aa skip none brings it back";
            }
            case "use" -> {
                if (a.isEmpty()) return "usage: aa use <option> (an option of the current rung, as aa why names it, e.g. pillar, smelt here)";
                String rung = currentRung();
                man.use = a.toLowerCase(java.util.Locale.ROOT);
                man.useRung = rung;
                io.github.mojolowjo.entropybot.aa.Rung r = null;
                for (var x : AaLadder.rungs(tree.ladder)) if (x.id.equals(rung)) r = x;
                if (r != null && r.picker() == null) {
                    man.use = null;
                    man.useRung = null;
                    return rung + " has one option (its plan): nothing to choose";
                }
                boolean pre = rung != null && preemptOwnTask("aa use " + a + ": " + rung + " picks again");
                return "ok: " + (rung == null ? "the next rung that picks" : rung) + " runs \"" + a + "\" if it is a ranked option" + (pre ? " (its task stopped, picking again)" : "")
                        + (r != null ? "; options now: " + r.options() : "");
            }
            default -> { return AaCommands.USAGE; }
        }
    }

    // ---- the switch (brainData "aa": {on, since, heldUntil, shelterBuilt}) ----

    JsonObject data() {
        JsonObject root = c.brainData();
        if (!root.has("aa") || !root.get("aa").isJsonObject()) root.add("aa", new JsonObject());
        return root.getAsJsonObject("aa");
    }

    public boolean on() {
        JsonObject d = data();
        return d.has("on") && d.get("on").getAsBoolean();
    }

    String turnOn() {
        if (c.brainRuntime != null && c.brainRuntime.brain.on()) return "brain is on - brain off first (aa and brain exclude each other)";
        JsonObject d = data();
        d.addProperty("on", true);
        d.addProperty("since", System.currentTimeMillis());
        d.remove("heldUntil");
        offReason = null;
        heldUntil = 0;
        c.saved();
        // 0.36.1 (review 1): assist and escort end now (their ticks are off under AA; they could start and cancel jobs)
        String ended = "";
        LocalPlayer p = Minecraft.getInstance().player;
        if (c.assist.on() && p != null) {
            c.assist.end(p);
            ended += "; assist ended";
        }
        if (Core.INSTANCE.reflexes.escort.active()) {
            Core.INSTANCE.reflexes.escort.stop("aa on");
            c.endEscortFollow();
            ended += "; escort ended";
        }
        int queued = c.vocab.queued.size();
        if (queued > 0) ended += "; " + queued + " queued task(s) run as your orders when my slot is free";
        LOG.info("[entropybot] aa: on{}", ended);
        return "ok: aa on - danger > your orders > AI requests > basic needs > idle; one task at a time; aa status says who drives" + ended;
    }

    String turnOff(String why) {
        JsonObject d = data();
        d.addProperty("on", false);
        c.saved();
        String stopped = null;
        if (bb.task() != null) {
            stopped = bb.task().spec().chain();
        }
        tree.root.halt("aa off: " + why);
        releaseSwim(Minecraft.getInstance());
        if (chainRunning()) c.chains.clear();
        // 0.36.5 (review 6): nothing of this run carries into the next aa on (queued AI requests, the last task's state)
        int dropped = bb.drop("ai");
        bb.fact(io.github.mojolowjo.entropybot.aa.AaManual.FACT, null);     // S0: ask/do/skip/use are per run
        clearEscape();
        current = -1;
        lastEnd = null;
        myChain = CHAIN;
        progress = 0;
        lastBag = -1;
        LOG.info("[entropybot] aa: off ({}){}", why, dropped > 0 ? "; " + dropped + " AI request(s) dropped" : "");
        return "ok: aa off" + (stopped != null ? " - stopped my task " + stopped : "") + "; your jobs run on";
    }

    /** The owner's "stop": AA's task ends (justified), the slot waits 2 min or until the next owner order. */
    String ownerStopped() {
        if (!on()) return "";
        if (bb.task() != null || tree.slot.runningChild() != null) tree.root.halt("the owner's stop");
        heldUntil = System.currentTimeMillis() + HOLD_MS;
        data().addProperty("heldUntil", heldUntil);
        c.saved();
        return "; aa waits " + HOLD_MS / 60000 + " min (aa on, or your next order, to go on now)";
    }

    /** AA's chain runs (its task). */
    boolean chainRunning() { return c.chains.running() && (CHAIN.equals(c.chains.name()) || myChain.equals(c.chains.name())); }

    /** 0.36.3: the chain name of AA's task: "aa", or "climb" (climb runs as a chain of its own, never inside one). */
    private String myChain = CHAIN;
    private boolean swimming;

    /** Lets go of jump if AA holds it (aa off, death, hold, task change); leaves it when the air reflex holds it. */
    private void releaseSwim(Minecraft mc) {
        if (!swimming) return;
        swimming = false;
        if (mc != null && !Core.INSTANCE.reflexes.airSwimming()) mc.options.keyJump.setDown(false);
        LOG.info("[entropybot] aa: swim-up released (aa stopped or paused)");
    }

    /** The owner's order while AA's needs/idle task runs: the task is preempted (logged), the order runs (AA_PLAN 4). */
    void yieldToOwner(String verb) {
        if (!on() || !chainRunning()) return;
        if (tree.preemptForOwner(bb, verb)) {
            heldUntil = 0;
            LOG.info("[entropybot] aa: steps aside for the owner's {}", verb);
        }
    }

    /** The AI operator's job-starting command while AA's task runs: queued as a request (AA_PLAN 4). */
    String postAi(String cmd) {
        if (!on()) return null;
        aiRequests++;
        bb.post(new Request("ai", TaskKind.AI, cmd, 1, System.currentTimeMillis(), 0, "the AI asked: " + cmd));
        LOG.info("[entropybot] aa: AI request #{} queued: {}", aiRequests, cmd);
        return "queued as AI request #" + aiRequests + " (runs when my task ends; " + bb.requests().size() + " waiting)";
    }

    /** The AI's stop: its own queued requests go, and its running task (never the owner's or a need). */
    String cancelAi() {
        int n = bb.drop("ai");
        boolean running = bb.task() != null && "ai".equals(bb.task().source());
        if (running) tree.slot.preempt("the AI's stop");
        return "ok: " + n + " AI request(s) dropped" + (running ? ", the AI's task stopped" : "");
    }

    // ---- the tick ----

    /** Every 5 client ticks from Commands.tick, in a world. Never throws. */
    void tick(long tick) {
        if (!on()) return;
        long t0 = System.nanoTime();
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) return;
            long now = System.currentTimeMillis();
            if (c.brainRuntime != null && c.brainRuntime.brain.on()) {     // 0.36.5 (review 1): both saved on (an old save): AA wins
                c.brainRuntime.brain.command("off");
                LOG.info("[entropybot] aa: the brain was on next to aa - brain off");
            }
            if (p.isDeadOrDying()) {
                releaseSwim(mc);
                if (bb.task() != null) {
                    tree.root.halt("died");
                    c.chains.clear();
                }
                return;
            }
            if (sensedTick < 0 || tick - sensedTick >= 10) {
                sensed = c.brainRuntime.sense();
                sensedTick = tick;
            }
            escapeTick(p, mc);
            fill(bb.in, sensed, p, mc);
            progressTick(p);
            brainFacts(mc, now);
            goalsTick(p, tick);
            if (now < heldUntil) {
                releaseSwim(mc);
                if (bb.in.otherJob == null) {
                    tracer.beginTick();
                    snapshot(now, "held");
                    return;
                }
                heldUntil = 0;        // the owner's order lifts the hold
            }
            // 0.36.1 (review 1): the owner's queue ("queue <task>") is an owner order: the next queued line starts
            // when the slot is free and is adopted as the owner's job on the next tick
            if (bb.in.otherJob == null && bb.task() == null && !c.vocab.queued.isEmpty() && (tick % 20) == 2) {
                c.vocab.tick();
                return;
            }
            io.github.mojolowjo.entropybot.chop.Unreachable.world(Core.INSTANCE.files() == null ? "" : Core.INSTANCE.files().worldRoot().toString());
            Status s = tree.tick(bb);
            for (var r : AaLadder.rungs(tree.ladder)) if (r.id.equals("needs.woodArea") && r.last() == Status.SUCCESS) bb.fact(AaLadder.TREE_AREA_FACT, null);
            // 0.36.2 (review 6): while danger drives, AA's chain starts no next step and no retry (the pause ended the
            // running one); held a little past AA's next tick, so it goes on by itself once the guard lets go
            if ("danger".equals(tree.driver()) && chainRunning()) c.chains.holdFor(10);
            // 0.36.4 (aa40d: AA paused the iron job for "drowning" 10+ min while nothing moved the bot): under water with
            // air short and danger driving, AA holds jump (swim up) until the air is full again or the danger ends
            // 0.36.a1: not while the air reflex holds jump itself (one owner of the key; it also pauses Baritone)
            // 0.36.a2: only for the air danger; a hostile/lava/creeper danger leaves jump to Baritone and the fight reflex
            boolean otherDanger = bb.in.danger >= 2 || bb.in.inLavaOrFire || bb.in.creeperClose;
            boolean swim = io.github.mojolowjo.entropybot.aa.AaSwim.hold("danger".equals(tree.driver()), p.isInWater(),
                    p.getAirSupply(), p.getMaxAirSupply(), Core.INSTANCE.reflexes.airSwimming(), otherDanger, swimming);
            if (swim != swimming) {
                if (swim || !Core.INSTANCE.reflexes.airSwimming()) mc.options.keyJump.setDown(swim);
                swimming = swim;
                LOG.info(swim ? "[entropybot] aa: under water with air {} - swimming up"
                        : "[entropybot] aa: swim-up released (air {}, driver {})", p.getAirSupply(), tree.driver());
            }
            if (tree.ladder.last() == Status.SUCCESS && !data().has(AaLadder.SHELTER_FACT) && bb.flag(AaLadder.SHELTER_FACT)) {
                data().addProperty(AaLadder.SHELTER_FACT, true);
                c.saved();
            }
            if (s == Status.FAILURE && tracer.counts.ticks % 240 == 1) LOG.info("[entropybot] aa: nothing to do: {}", tree.root.reason());
            snapshot(now, tree.driver());
        } catch (Throwable e) {
            tickFailed(e);
        } finally {
            tracer.counts.tickUs = (System.nanoTime() - t0) / 1000;
        }
    }

    private void tickFailed(Throwable e) {
        long now = System.currentTimeMillis();
        errors++;
        lastError = e.toString();
        if (now - minuteStart > 60_000) {
            minuteStart = now;
            errorsThisMinute = 0;
        }
        errorsThisMinute++;
        if (errors <= 3 || now - lastErrorAt > 60_000) {
            lastErrorAt = now;
            LOG.warn("[entropybot] aa: tick failed: {}", errors <= 3 ? stack(e) : e.toString());
        }
        if (errorsThisMinute >= ERRORS_PER_MINUTE_OFF) {
            offReason = "turned itself off after " + errorsThisMinute + " tick errors in a minute: " + e;
            turnOff(offReason);
            c.whisper(c.owner(), "aa: " + offReason);
        }
    }

    private static String stack(Throwable e) {
        StringBuilder b = new StringBuilder(e.toString());
        int n = 0;
        for (StackTraceElement s : e.getStackTrace()) {
            if (n++ >= 6) break;
            b.append("\n    at ").append(s);
        }
        return b.toString();
    }

    /** The fixed inputs (AA_PLAN 7) from the sense snapshot plus a few direct reads. */
    private boolean sheepLatch;

    private void fill(Inputs in, BrainState s, LocalPlayer p, Minecraft mc) {
        in.inWorld = s.inWorld;
        in.health = p.getHealth();
        in.maxHealth = p.getMaxHealth();
        in.food = p.getFoodData().getFoodLevel();
        in.hurtRecently = p.hurtTime > 0;
        Reflexes.Reflex r = Core.INSTANCE.reflexes.reflex();
        in.danger = r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING || r == Reflexes.Reflex.RETREATING ? 2 : Core.INSTANCE.reflexes.hold() ? 1 : 0;
        in.dangerWhy = in.danger > 0 ? r.name().toLowerCase() : "";
        in.creeperClose = creeperClose();
        in.air = p.getAirSupply();
        in.inLavaOrFire = p.isInLava() || p.isOnFire();
        in.freeSlots = s.freeSlots;
        in.foodItems = s.foodItems;
        in.torches = s.torches;
        in.wood = s.woodItems;
        in.coal = s.coal;
        Map<String, Integer> bag = s.feas.bag;
        in.cobble = bag.getOrDefault("cobblestone", 0) + bag.getOrDefault("cobbled_deepslate", 0);
        in.ironIngots = bag.getOrDefault("iron_ingot", 0);
        in.wool = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) if (e.getKey().endsWith("_wool")) in.wool += e.getValue();
        in.string = bag.getOrDefault("string", 0);
        in.bag = bag;
        if (mc.level != null) in.dimension = mc.level.dimension().location().toString();
        if (!sheepLatch && mc.level != null && !mc.level.getEntitiesOfClass(net.minecraft.world.entity.animal.Sheep.class, p.getBoundingBox().inflate(48)).isEmpty()) sheepLatch = true;
        in.sheepSeen = sheepLatch;
        in.pickTier = s.feas.known ? s.feas.pickTier : (s.pickaxes > 0 ? 1 : 0);
        in.pickPct = s.pickaxes == 0 ? 0 : s.pickPct;
        in.pickaxes = s.pickaxes;
        in.bedKnown = s.bedNear;
        in.bedInBag = s.bedInBag;
        // 0.36.a6: a table/furnace seen at the camp or base counts wherever the bot is (AaLadder.campLatch, saved)
        double camp = -1;
        for (int[] m : new int[][]{s.campPos, s.basePos}) {
            if (m == null) continue;
            double d = Math.sqrt(Jobs.distSq(s.botPos, m));
            camp = camp < 0 ? d : Math.min(camp, d);
        }
        boolean tLatch = latch("tableAtCamp", s.feas.table, camp), fLatch = latch("furnaceAtCamp", s.feas.furnace, camp);
        in.tableKnown = s.feas.table || tLatch;
        in.furnaceKnown = s.feas.furnace || fLatch;
        in.tableInBag = bag.getOrDefault("crafting_table", 0) > 0;
        in.planks = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) if (e.getKey().endsWith("_planks")) in.planks += e.getValue();
        in.furnaceInBag = bag.getOrDefault("furnace", 0) > 0;
        in.baseChests = s.feas.baseChests;
        in.baseKnown = s.basePos != null;
        in.shelterBuilt = bb.flag(AaLadder.SHELTER_FACT);
        in.areaKnown = false;
        in.areaName = null;
        for (String n : c.areaNames()) {
            JsonObject a = c.policyArea(n);
            if (a == null || PolicyCommands.typeOf(a) == io.github.mojolowjo.entropybot.guard.AreaType.SAFE) continue;
            in.areaKnown = true;
            if (in.areaName == null && houseFits(a)) in.areaName = n;
        }
        in.dayTime = s.dayTime;
        in.night = s.night;
        in.thunder = s.thundering;
        in.underGround = !s.skyAbove;
        in.armorPoints = mc.player == null ? 0 : mc.player.getArmorValue();
        int[] me = Jobs.here(p);
        in.underDepth = Jobs.underDepth(mc.level, me[0], me[1], me[2]);
        in.pos = me;
        in.inArea = s.areasHere;
        in.ownerOnline = s.ownerOnline;
        in.hungerDrains = mc.level.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL;
        in.menuOpen = mc.screen != null && !chainRunning() && !c.jobs.running();
        in.parked = s.parked;
        in.otherJob = otherJob();
        in.ownerOrderPending = in.otherJob != null;
        in.aiRequestPending = bb.peek("ai") != null;
        fillOwner(in, s, p, mc);
    }

    /**
     * B6 (2.11): the owner as the help desire sees them. Position: their player entity when loaded (same dimension), else
     * the companion's position; activity and last placed block from the companion report (fresh, same name and dimension).
     * Loader notes: vanilla client classes only (level players/entities, raw brightness).
     */
    private void fillOwner(Inputs in, BrainState s, LocalPlayer p, Minecraft mc) {
        in.ownerDist = -1;
        in.ownerHostiles = 0;
        in.ownerAct = null;
        in.ownerPlaced = null;
        in.ownerDark = false;
        in.assistOn = c.assist.on();
        if (mc.level == null) return;
        try {
            String owner = c.owner();
            net.minecraft.world.phys.Vec3 at = null;
            for (net.minecraft.world.entity.player.Player pl : mc.level.players())
                if (pl != p && owner != null && pl.getGameProfile().getName().equalsIgnoreCase(owner)) at = pl.position();
            if (at == null && s.ownerKnown()) at = new net.minecraft.world.phys.Vec3(s.ownerPos[0] + 0.5, s.ownerPos[1], s.ownerPos[2] + 0.5);
            if (at == null) return;
            in.ownerDist = (int) Math.round(Math.hypot(at.x - p.getX(), at.z - p.getZ()));
            net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(at);
            if (mc.level.isLoaded(bp)) {
                in.ownerDark = mc.level.getMaxLocalRawBrightness(bp) < 8;
                in.ownerHostiles = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                        new net.minecraft.world.phys.AABB(bp).inflate(12), net.minecraft.world.entity.LivingEntity::isAlive).size();
            }
            io.github.mojolowjo.entropybot.assist.AssistRules.Report r = c.assist.report();
            long now = System.currentTimeMillis();
            if (r != null && owner != null && r.name().equalsIgnoreCase(owner) && now - r.received() <= io.github.mojolowjo.entropybot.assist.AssistRules.FRESH_MS
                    && r.dim().equals(mc.level.dimension().location().toString())) {
                in.ownerAct = io.github.mojolowjo.entropybot.assist.AssistRules.guess(r, now).word();
                in.ownerPlaced = r.placed() == null || now - r.placed().at() > io.github.mojolowjo.entropybot.assist.AssistRules.RECENT_MS ? null : r.placed().id();
            }
        } catch (RuntimeException e) {
            if (!ownerReadWarned) LOG.warn("[entropybot] aa help: owner read failed (help stays off; said once): {}", e.toString());
            ownerReadWarned = true;
        }
    }

    private boolean ownerReadWarned;

    /** A camp latch from brainData "aa" (AaLadder.campLatch); saved and logged once when it changes. */
    private boolean latch(String key, boolean seen, double distToCamp) {
        JsonObject d = data();
        boolean was = d.has(key) && d.get(key).getAsBoolean();
        boolean now = AaLadder.campLatch(was, seen, distToCamp);
        if (now != was) {
            d.addProperty(key, now);
            c.saved();
            LOG.info("[entropybot] aa: {} {}", key, now ? "noted (seen within " + AaLadder.CAMP_R + " of the camp/base): it counts away from the camp too" : "dropped (no camp or base known)");
        }
        return now;
    }

    /** 0.36.1 (review 6): the reflexes' target is a creeper within 10 (Reflexes.CREEPER_OVERRIDE), or a creeper duel runs. */
    private boolean creeperClose() {
        try {
            JsonObject r = Core.INSTANCE.reflexes.status();
            if (r.has("duel")) return true;
            if (!r.has("target")) return false;
            String t = r.get("target").getAsString();
            return t != null && t.contains("creeper") && (!r.has("dist") || r.get("dist").getAsDouble() <= 10);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code house} takes a box of 7x7 up to 4096 blocks (CampCommands.house); a round area or a bigger box does not fit. */
    private static boolean houseFits(JsonObject a) {
        try {
            if (a.has("round")) return false;
            long w = Math.abs(PolicyCommands.n(a, "x2") - PolicyCommands.n(a, "x1")) + 1, d = Math.abs(PolicyCommands.n(a, "z2") - PolicyCommands.n(a, "z1")) + 1;
            return w >= 7 && d >= 7 && w * d <= 4096;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Something that is not AA's runs: the owner's chain, a job, a gather, a hunt, a carry, a request; else null. */
    private String otherJob() {
        if (escapePending || escapeWay != null) return null;       // 0.36.a6: AA's own way out (pillar job / climb chain)
        if (c.chains.running()) return chainRunning() ? null : "chain " + c.chains.name();
        if (c.jobs.running()) return c.jobs.job.type + " (" + c.jobs.job.status + ")";
        if (c.gathering.running()) return "gather";
        if (c.hunting.running()) return "hunt";
        if (c.mule.running()) return "carry";
        if (c.requests.busy()) return "a request";
        return null;
    }

    /**
     * The activity counter (0.36.1, review 7: task-specific first): a chain step done, the job's status text changing
     * (it carries "N broken", "picking up"...), items gained, a walk's distance to its goal shrinking; the position only
     * as a fallback, and then only a move of 8+ blocks from the last counted spot (standing and shuffling is no progress).
     */
    private void progressTick(LocalPlayer p) {
        String step = chainRunning() ? c.chains.stepText() : null;
        if (step != null && !step.equals(lastStep)) progress++;
        lastStep = step;
        String js = c.jobs.running() ? c.jobs.job.status : null;
        if (js != null && !js.equals(lastJobStatus)) progress++;
        lastJobStatus = js;
        int bagTotal = 0;
        for (int i = 0; i < 36; i++) bagTotal += p.getInventory().getItem(i).getCount();
        if (lastBag >= 0 && bagTotal > lastBag) progress++;          // gained (a deposit's drop is no progress by itself)
        lastBag = bagTotal;
        int[] pos = Jobs.here(p);
        int[] dest = c.jobs.running() ? Jobs.travelDest(c.jobs.job) : null;
        if (dest != null) {
            double d = Math.abs(dest[0] - pos[0]) + Math.abs(dest[1] - pos[1]) + Math.abs(dest[2] - pos[2]);
            if (lastGoalDist >= 0 && d < lastGoalDist - 1) progress++;
            lastGoalDist = d;
        } else lastGoalDist = -1;
        if (lastPos == null || Math.abs(pos[0] - lastPos[0]) + Math.abs(pos[1] - lastPos[1]) + Math.abs(pos[2] - lastPos[2]) >= 8) {
            if (lastPos != null) progress++;
            lastPos = pos;
        }
    }

    private double lastGoalDist = -1;

    // ---- JobPort ----

    private String refusal;

    @Override public long start(TaskSpec t) {
        refusal = null;
        if (c.chains.running()) { refusal = "busy: " + c.chains.chainStatus(); return -1; }
        if (c.jobs.running()) { refusal = "busy: " + c.jobs.job.status; return -1; }      // 0.36.1: any job, as otherJob() sees it
        if (c.gathering.running()) { refusal = "busy: " + c.gathering.statusText(); return -1; }
        if (c.hunting.running()) { refusal = "busy: a hunt runs"; return -1; }
        if (c.mule.running()) { refusal = "busy: " + c.mule.statusText(); return -1; }
        if (c.requests.busy()) { refusal = "busy: a request is open"; return -1; }
        lastEnd = null;
        myChain = CHAIN;
        clearEscape();
        escapes = 0;
        if (AaLadder.CLIMB.equals(t.chain())) {
            // 0.36.3: climb plans its own staircase and runs it as its own chain "climb"; "ok: I'm not in a hole" = done
            LocalPlayer p = Minecraft.getInstance().player;
            String cr = p == null ? "error: no player" : DigCommands.climb(c, p, "", c.owner());
            if (cr != null && cr.startsWith("ok")) {
                current = ++seq;
                currentChain = t.chain();
                lastEnd = "done - " + cr;
                return current;
            }
            if (cr == null || !cr.startsWith("started")) {
                refusal = cr == null ? "error: no answer" : cr;
                return -1;
            }
            myChain = "climb";
            current = ++seq;
            currentChain = t.chain();
            return current;
        }
        String r = c.chains.startChain(c.owner(), CHAIN, t.chain(), 1);
        if (r == null || !r.startsWith("started")) {
            refusal = r == null ? "error: no answer" : r;
            return -1;
        }
        current = ++seq;
        currentChain = t.chain();
        return current;
    }

    @Override public String lastRefusal() { return refusal; }

    @Override public JobState poll(long taskId) {
        if (taskId != current) return JobState.gone("an older task");
        if (lastEnd != null) {
            String e = lastEnd;
            lastEnd = null;
            current = -1;
            return e.startsWith("done") ? JobState.done(e) : JobState.failed(e);
        }
        if (escapePending || escapeWay != null) return JobState.RUNNING;      // 0.36.a6: getting out, then the task goes on
        if (!chainRunning()) {
            current = -1;
            String who = c.chains.running() ? "chain " + c.chains.name() : c.jobs.running() ? "job " + c.jobs.job.type : "someone (stop or a clear)";
            return JobState.gone(who);
        }
        return JobState.RUNNING;
    }

    @Override public void cancel(long taskId, String why) {
        if (taskId != current) return;
        current = -1;
        clearEscape();
        if (chainRunning()) c.chains.clear();
        if (c.jobs.running()) c.jobs.finish("stopped: aa " + why);
        if (c.gathering.running()) c.gathering.stop("stopped: aa " + why);
        if (c.hunting.running()) c.hunting.stop("stopped: aa " + why);
        if (c.mule.running()) c.mule.stop("stopped: aa " + why);
    }

    @Override public long progress(long taskId) { return progress; }

    /**
     * 0.36.1 (review 6): a real pause: the chain's running step ends as "interrupted: low health ..." so Chains retries
     * that step (up to 3 times) once the reflexes are calm and health is back (Chains.stepChain's fight retry); the chain
     * itself stays, so the task is still RUNNING for AA. Resume needs nothing: the chain's own retry clock goes on.
     */
    @Override public void pause(long taskId, String why) {
        if (taskId != current || !chainRunning()) return;
        if (c.jobs.running()) c.jobs.finish("interrupted: low health or danger - aa paused the step (" + why + ")");
        if (c.gathering.running()) c.gathering.stop("interrupted: low health or danger - aa paused (" + why + ")");
    }

    @Override public String resolve(String chain) {
        if (chain == null || !chain.startsWith("goal ")) return chain;
        return c.planning.chainFor(chain.substring(5));
    }

    /** S0 (MINING_PLAN 10.1): the planner's tri-state; a text that is not a goal is READY as it is. */
    @Override public Resolved resolveState(String chain) {
        if (chain == null || !chain.startsWith("goal ")) return chain == null ? Resolved.none("nothing to run") : Resolved.ready(chain, 0);
        return c.planning.chainState(chain.substring(5));
    }

    /** 0.36.a8: a routine name (the planner's goal_...) or chain text as its plain steps, for the option scorer. */
    @Override public String expand(String chain) {
        try {
            return String.join(" then ", c.chains.expandSteps(Texts.splitChain(chain), 0));
        } catch (RuntimeException e) {
            return chain;
        }
    }

    @Override public String infeasible(String chain) {
        if (sensed == null || !sensed.feas.known) return null;
        try {
            Feasibility.Verdict v = Feasibility.check(routineSteps(chain), sensed);
            if (!v.infeasible()) return null;
            // 0.36.1: a saved goal routine the facts no longer support is deleted, so the planner plans the goal afresh
            // next time (GoalPlanning keeps a saved routine otherwise; the hook cache forgets it within a minute)
            // 0.36.2: only the planner's own text (RoutineRule OVERWRITE); a routine the owner edited is theirs, kept
            if (chain.startsWith("goal_") && c.chains.isRoutine(chain)
                    && RoutineRule.decide(c.planning.routineText(chain), c.planning.plannedText(chain)) == RoutineRule.Decision.OVERWRITE) {
                String r = c.chains.routineCommand("delete " + chain);
                LOG.info("[entropybot] aa: routine {} dropped as infeasible ({}): {}", chain, v.missing(), r);
            }
            return "infeasible: " + v.missing() + (v.stepText() == null ? "" : " for " + v.stepText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A routine name -> its steps joined with "then" (Feasibility reads the steps). */
    private String routineSteps(String chain) {
        if (c.chains.isRoutine(chain)) return c.chains.routines().get(chain.toLowerCase()).getAsString();
        return chain;
    }

    @Override public void log(String line) { LOG.info("[entropybot] aa: {}", line); }

    @Override public void incident(String what) {
        try {
            Core.INSTANCE.recorder.jobEnded("aa", what, "error: " + what);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa incident: {}", e.toString());
        }
    }

    /** Chains tells every listener how a chain ended; AA keeps its own. */
    void chainEnded(String name, String msg) {
        boolean mine = CHAIN.equals(name) || (current >= 0 && myChain.equals(name));
        if (mine && current >= 0 && "climb".equals(escapeWay)) {         // 0.36.a6: the escape's climb ended
            escapeWay = null;
            myChain = CHAIN;
            if (msg != null && msg.startsWith("done")) {
                escapePending = true;           // review: the chain starts from escapeTick, not inside the end callback
                escapeWay = "resume";
            }
            else escapeFailNow("climbing out failed too: " + msg);
            return;
        }
        if (mine && current >= 0 && escapeWay == null && !escapePending && on() && (AaEscape.stuck(msg) || holeStuckNow(msg)) && escapes < AaEscape.MAX_PER_TASK) {
            String resume = null;
            try {
                resume = AaEscape.resume(c.chains.expandSteps(Texts.splitChain(currentChain), 0), AaEscape.failedStep(msg));
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] aa: the stuck step's chain could not be read: {}", e.toString());
            }
            if (resume != null) {
                escapes++;
                escapePending = true;
                escapeResume = resume;
                escapeFail = msg;
                LOG.info("[entropybot] aa: the walk ended stuck under ground - getting out myself (escape {} of this task), then: {}", escapes, resume);
                return;
            }
        }
        if (mine) lastEnd = msg;
        // 0.36.3: no tree I can get at in my areas: the woodArea rung makes an area around the nearest reachable one
        if (mine && msg != null && (msg.contains("no trees in my areas") || msg.contains("couldn't get at its trunk"))) treeAreaFact();
    }

    /**
     * aa372 gap: the walk failed at the bottom of a hole open to the sky (a 1x1 shaft the bot dug: "not under ground" by
     * the shared rule, so the under-ground end never came). The trigger is the owner's: the walk failed and the goal is
     * above me, or I stand in a cell I dug myself; and there is a way out above (AaEscape.holeStuck). Never throws.
     */
    private boolean holeStuckNow(String msg) {
        try {
            if (!AaEscape.walkFailed(msg)) return false;
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) return false;
            int[] me = Jobs.here(p);
            String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level);
            boolean dug = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.dug(dim, me[0], me[1], me[2]);
            return AaEscape.holeStuck(msg, me[1], AaEscape.destOf(msg), dug, holeUp(mc.level, me));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: hole check failed: {}", e.toString());
            return false;
        }
    }

    /** The levels up from the feet to where the bot can step out sideways (AaEscape.holeExit): 0 = here, -1 = none. */
    private static int holeUp(net.minecraft.world.level.Level lv, int[] me) {
        java.util.function.IntPredicate open = dy -> openCell(lv, me[0], me[1] + dy, me[2]);
        java.util.function.IntPredicate stepOut = dy -> {
            for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                if (openCell(lv, me[0] + s[0], me[1] + dy, me[2] + s[1]) && openCell(lv, me[0] + s[0], me[1] + dy + 1, me[2] + s[1])) return true;
            return false;
        };
        return AaEscape.holeExit(AaEscape.HOLE_MAX_UP, open, stepOut);
    }

    private static boolean openCell(net.minecraft.world.level.Level lv, int x, int y, int z) {
        net.minecraft.core.BlockPos q = new net.minecraft.core.BlockPos(x, y, z);
        if (!lv.isLoaded(q)) return false;
        var st = lv.getBlockState(q);
        return st.getCollisionShape(lv, q).isEmpty() && st.getFluidState().isEmpty();
    }

    /** Out of the ground and out of any hole (the under-ground rule and the hole scan both say so). */
    private static boolean outNow(net.minecraft.world.level.Level lv, int[] me) {
        return Jobs.underDepth(lv, me[0], me[1], me[2]) == 0 && holeUp(lv, me) == 0;     // review: -1 (walled in) is not out
    }

    /** AA's escape from under ground runs or waits to start (Jobs: then no paper plan, no whisper). */
    boolean ownsEscape() {
        return on() && current >= 0 && (escapePending || escapeWay != null
                || (chainRunning() && CHAIN.equals(myChain) && escapes < AaEscape.MAX_PER_TASK));
    }

    private void clearEscape() {
        escapePending = false;
        escapeWay = null;
        escapeResume = null;
        escapeFail = null;
        escapeJob = null;
        myChain = CHAIN;
    }

    /** 0.36.a6: starts the pending escape once the stuck walk has settled; a pillar job's end -> go on, or climb. */
    private void escapeTick(LocalPlayer p, Minecraft mc) {
        if (current < 0) {
            if (escapePending || escapeWay != null) clearEscape();
            return;
        }
        if (escapePending) {
            if (c.chains.running() || c.jobs.running()) return;
            escapePending = false;
            if ("resume".equals(escapeWay)) {
                escapeWay = null;
                escapeResumeNow("climbed out");
            } else startEscape(p, mc, false);
        } else if ("pillar".equals(escapeWay) && !(c.jobs.running() && c.jobs.job == escapeJob) && !PaperPlan.owned()) {
            escapeWay = null;
            escapeJob = null;
            int[] me = Jobs.here(p);
            if (outNow(mc.level, me)) escapeResumeNow("pillared up");
            else {
                LOG.info("[entropybot] aa: the pillar ended still in the hole at {} - trying the staircase", Jobs.fmt(me));
                startEscape(p, mc, true);
            }
        }
    }

    private void startEscape(LocalPlayer p, Minecraft mc, boolean noPillar) {
        try {
            int[] me = Jobs.here(p);
            net.minecraft.world.level.Level lv = mc.level;
            int depth = Jobs.underDepth(lv, me[0], me[1], me[2]);
            boolean hole = depth == 0;                   // aa372 gap: open to the sky, but at the bottom of a hole
            if (hole) depth = Math.max(0, holeUp(lv, me));
            boolean shaft = !noPillar && depth > 0;
            for (int y = me[1] + 2; shaft && y <= me[1] + depth; y++) if (!openCell(lv, me[0], y, me[2])) shaft = false;
            Map<String, Integer> bag = sensed != null && sensed.feas != null ? sensed.feas.bag : Map.of();
            AaEscape.Choice ch = AaEscape.choose(depth, shaft, AaEscape.throwaway(bag), bb.in.pickaxes > 0);
            LOG.info("[entropybot] aa: stuck {} at {} ({} {} up): {} - {}", hole ? "in a hole" : "under ground", Jobs.fmt(me), hole ? "the way out" : "surface", depth,
                    ch.way().name().toLowerCase(), ch.why());
            switch (ch.way()) {
                case OUT -> escapeResumeNow("already out");
                case PILLAR -> {
                    String r = PaperPlan.INSTANCE.pillar(c, new int[]{me[0], me[1] + depth, me[2]}, c.owner());
                    if (r != null && !r.startsWith("error") && !r.startsWith("busy") && c.jobs.running()) {
                        escapeWay = "pillar";
                        escapeJob = c.jobs.job;
                    } else {
                        LOG.info("[entropybot] aa: the pillar did not start ({}) - trying the staircase", r);
                        startEscape(p, mc, true);
                    }
                }
                case CLIMB -> {
                    String cr = DigCommands.climb(c, p, "", c.owner());
                    if (cr != null && cr.startsWith("ok")) escapeResumeNow("climb: " + cr);
                    else if (cr != null && cr.startsWith("started")) {
                        escapeWay = "climb";
                        myChain = "climb";
                    } else escapeFailNow("the staircase did not start: " + cr);
                }
                case NONE -> escapeFailNow(ch.why());
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: escape failed: {}", e.toString());
            escapeFailNow("escape error: " + e);
        }
    }

    /** Out: the task goes on with the step that ended stuck (same task, no failure, no cooldown). */
    private void escapeResumeNow(String how) {
        String resume = escapeResume;
        String fail = escapeFail;
        escapeResume = null;
        myChain = CHAIN;
        String r = resume == null ? null : c.chains.startChain(c.owner(), CHAIN, resume, 1);
        if (r != null && r.startsWith("started")) {
            escapeFail = null;
            LOG.info("[entropybot] aa: out from under ground ({}) - going on with: {}", how, resume);
        } else {
            escapeFail = fail;
            escapeFailNow("out (" + how + "), but the task did not go on: " + r);
        }
    }

    /** No way out worked: the task fails with the stuck walk's end and why. */
    private void escapeFailNow(String why) {
        String fail = escapeFail;
        clearEscape();
        lastEnd = (fail == null ? "error: stuck under ground" : fail) + " (getting out: " + why + ")";
        LOG.info("[entropybot] aa: no way out from under ground: {}", why);
    }

    /** At most this many tree areas AA adds itself ("trees1".."trees3"), each a box of 2*TREE_AREA_R+1. */
    static final int TREE_AREAS = 3, TREE_AREA_R = 20, TREE_SEARCH_R = 96;

    /**
     * 0.36.3: the nearest log column outside my areas (loaded, within 96, not a remembered unreachable trunk, no protect
     * box within 8 of the box) -> "area x z x2 z2 treesN work neutral" in the blackboard fact; none -> a log line only.
     * The owner's rule (2026-10-11): the bot works inside its own areas; it never boxes in a protected build.
     */
    void treeAreaFact() {
        try {
            if (bb.fact(AaLadder.TREE_AREA_FACT) != null) return;
            int n = 0;
            for (String a : c.areaNames()) if (a.startsWith("trees")) n++;
            if (n >= TREE_AREAS) {
                LOG.info("[entropybot] aa: no tree I can get at in my areas, and {} tree areas made already - not growing further", n);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) return;
            String dim = Storage.dim();
            var prot = io.github.mojolowjo.entropybot.cave.MineRules.policyBoxes(io.github.mojolowjo.entropybot.cave.MineRules.parsePolicy(c.policyJson()), "protect", PolicyCommands.DEFAULT_DIM);
            int[] me = Jobs.here(p);
            long now = System.currentTimeMillis(), best = Long.MAX_VALUE;
            int[] at = null;
            net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int x = me[0] - TREE_SEARCH_R; x <= me[0] + TREE_SEARCH_R; x += 2)
                for (int z = me[2] - TREE_SEARCH_R; z <= me[2] + TREE_SEARCH_R; z += 2) {
                    long d = (long) (x - me[0]) * (x - me[0]) + (long) (z - me[2]) * (z - me[2]);
                    if (d >= best || c.inAreas(dim, x, z)) continue;
                    m.set(x, 0, z);
                    if (!mc.level.isLoaded(m)) continue;
                    int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    m.set(x, y, z);
                    if (!mc.level.getBlockState(m).is(net.minecraft.tags.BlockTags.LOGS)) continue;
                    if (io.github.mojolowjo.entropybot.chop.Unreachable.blocked(x, z, now)) continue;
                    if (io.github.mojolowjo.entropybot.cave.MineRules.protectNear(prot, dim, x, y, z, TREE_AREA_R + 8) != null) continue;
                    best = d;
                    at = new int[]{x, y, z};
                }
            if (at == null) {
                LOG.info("[entropybot] aa: no tree I can get at in my areas, and none loaded within {} outside them", TREE_SEARCH_R);
                return;
            }
            String cmd = "area " + (at[0] - TREE_AREA_R) + " " + (at[2] - TREE_AREA_R) + " " + (at[0] + TREE_AREA_R) + " " + (at[2] + TREE_AREA_R) + " trees" + (n + 1) + " work neutral";
            bb.fact(AaLadder.TREE_AREA_FACT, cmd);
            LOG.info("[entropybot] aa: no tree I can get at in my areas: the nearest reachable one is at {} {} {} ({} away) - {}", at[0], at[1], at[2], Math.round(Math.sqrt(best)), cmd);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: tree area: {}", e.toString());
        }
    }

    // ---- status, files ----

    /** "AA: slot>needs>...RUNNING 34s by needs | danger: calm | last switch 2m ago: done TOOLS" (AA_PLAN 6). */
    public String statusLine() {
        long now = System.currentTimeMillis();
        if (!on()) return "AA: off" + (offReason != null ? " (" + offReason + ")" : "");
        StringBuilder b = new StringBuilder("AA: ");
        if (now < heldUntil) b.append("held ").append((heldUntil - now + 59_999) / 60_000).append(" min after a stop");
        else b.append(tree.driver()).append('>').append(bb.taskText(now));
        b.append(" | danger: ").append(tree.dangerWhy());
        b.append(" | last switch ").append(tracer.lastSwitchText(now));
        return b.toString();
    }

    /** The short part for status/the HUD, or null when off. */
    String statusPart() {
        if (!on()) return null;
        long now = System.currentTimeMillis();
        return now < heldUntil ? "aa held" : "aa " + tree.driver() + (bb.task() != null ? " " + bb.task().spec().chain() : "");
    }

    String counts() {
        Tracer.Counts k = tracer.counts;
        return "switches " + k.switches + " (pointless " + k.pointless + "), preempts " + k.preempts + ", fails " + k.fails + ", lost " + k.lost
                + ", ticks " + k.ticks + ", last tick " + k.tickUs + " us, errors " + errors + (lastError != null ? " (last: " + lastError + ")" : "")
                + (writeErrors > 0 ? ", file write errors " + writeErrors : "")
                + ", AI requests " + aiRequests + " (" + bb.requests().size() + " waiting)";
    }

    /** The ladder's reasons this tick ("needs.tools: TOOLS met; needs.torches: TORCHES planning ..."). */
    String ladderLine() {
        StringBuilder b = new StringBuilder();
        for (var r : AaLadder.rungs(tree.ladder)) {
            if (b.length() > 0) b.append("; ");
            b.append(r.id.substring(6)).append(": ").append(r.last() == null ? "not checked" : r.last() == Status.FAILURE ? r.reason() : r.last().name());
        }
        return b.toString();
    }

    // ---- BRAIN_PLAN B2: the recipe tech tree, what is had, measured costs ----

    private int treeRecipes = -1;
    private long treeCheckedAt = -60_000, statsSavedAt, haveAt = -10_000;
    private boolean statsLoaded;
    private String treeError;

    /**
     * Once a minute: the tech tree from the client's RecipeManager (rebuilt when the recipe count changes; 2.8), the
     * set of items had (bag + known table/furnace), the stats file loaded once and saved when changed (2.9). Never
     * throws; a failure is logged once and shown by aa stats.
     */
    private void brainFacts(Minecraft mc, long now) {
        if (!statsLoaded && statsFile() != null) {      // review: only once the files are known
            statsLoaded = true;
            loadStats();
            loadPersonality();
        }
        // review: the bag set at most every 10 s (the tech tree's caches are per bag; mining changes it all the time)
        if (now - haveAt >= 10_000) {
            haveAt = now;
            java.util.Set<String> have = new java.util.HashSet<>();
            if (sensed != null && sensed.feas != null) for (var e : sensed.feas.bag.entrySet()) if (e.getValue() > 0) have.add(e.getKey());
            if (bb.in.tableKnown) have.add("crafting_table");
            if (bb.in.furnaceKnown) have.add("furnace");
            if (!have.equals(bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.HAVE_FACT))) bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.HAVE_FACT, java.util.Set.copyOf(have));
        }
        if (now - treeCheckedAt >= 60_000) {
            treeCheckedAt = now;
            try {
                var rm = mc.level.getRecipeManager();
                var all = rm.getRecipes();
                if (all.size() != treeRecipes) {
                    List<io.github.mojolowjo.entropybot.aa.brain.TechTree.Recipe> rs = new ArrayList<>();
                    for (var h : all) {
                        var r = h.value();
                        var out = r.getResultItem(mc.level.registryAccess());
                        if (out == null || out.isEmpty()) continue;
                        List<java.util.Set<String>> slots = new ArrayList<>();
                        for (var ing : r.getIngredients()) {
                            java.util.Set<String> s = new java.util.HashSet<>();
                            for (var st : ing.getItems()) s.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
                            if (!s.isEmpty()) slots.add(s);
                        }
                        rs.add(new io.github.mojolowjo.entropybot.aa.brain.TechTree.Recipe(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(out.getItem()).toString(), slots));
                    }
                    var t = new io.github.mojolowjo.entropybot.aa.brain.TechTree(rs);
                    bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.TECH_FACT, t);
                    treeRecipes = all.size();
                    treeError = null;
                    LOG.info("[entropybot] aa: tech tree from {} recipes ({} usable)", all.size(), t.size());
                }
            } catch (RuntimeException e) {
                if (treeError == null) LOG.warn("[entropybot] aa: tech tree: {}", e.toString());
                treeError = e.toString();
            }
        }
        Path f = statsFile();
        if (f != null && now - statsSavedAt >= 60_000 && tree.brain.stats.takeDirty()) {
            statsSavedAt = now;
            String json = tree.brain.stats.toJson();
            WRITER.submit(() -> {
                try {
                    Files.createDirectories(f.getParent());
                    Path tmp = f.resolveSibling("aa-stats.json.tmp");
                    Files.writeString(tmp, json, StandardCharsets.UTF_8);
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("[entropybot] aa: stats write: {}", e.toString());
                }
            });
        }
    }

    /** entropybot/aa-stats.json (all worlds: measured costs carry over to a fresh test world). */
    private static Path statsFile() {
        return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("aa-stats.json");
    }

    private void loadStats() {
        Path f = statsFile();
        if (f == null || !Files.exists(f)) return;
        try {
            var o = com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.entrySet()) {
                var s = e.getValue().getAsJsonObject();
                tree.brain.stats.put(e.getKey(), s.get("n").getAsInt(), s.get("ok").getAsInt(), s.get("avgS").getAsDouble());
            }
            LOG.info("[entropybot] aa: measured costs loaded for {} desires", o.size());
        } catch (Exception e) {
            LOG.warn("[entropybot] aa: {} unreadable, hand costs used: {}", f, e.toString());
            treeError = "stats file unreadable: " + e;
        }
    }

    // ---- B3 (BRAIN_PLAN 2.4): the personality file ----

    private static Path personalityFile() {
        return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("aa-personality.json");
    }

    /** Loads entropybot/aa-personality.json (written with the defaults when missing); errors: defaults, said in aa status. */
    private void loadPersonality() {
        Path f = personalityFile();
        io.github.mojolowjo.entropybot.aa.brain.Personality p;
        if (f == null || !Files.exists(f)) {
            p = new io.github.mojolowjo.entropybot.aa.brain.Personality();
            savePersonality(p);
        } else {
            try {
                var o = com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                for (var e : o.entrySet()) {
                    var v = e.getValue();
                    m.put(e.getKey(), v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? (Object) v.getAsDouble() : v.toString());
                }
                p = io.github.mojolowjo.entropybot.aa.brain.Personality.of(m);
            } catch (Exception e) {
                p = new io.github.mojolowjo.entropybot.aa.brain.Personality();
                p.errors.add(f.getFileName() + " unreadable: " + e.getMessage());
            }
        }
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Personality.FACT, p);
        if (p.errors.isEmpty()) LOG.info("[entropybot] aa: personality loaded ({})", String.join(", ", p.lines().subList(0, 3)).replaceAll(" - [^,]*", ""));
        else LOG.warn("[entropybot] aa: personality: {} - defaults used for those", String.join("; ", p.errors));
    }

    private static void savePersonality(io.github.mojolowjo.entropybot.aa.brain.Personality p) {
        Path f = personalityFile();
        if (f == null) return;
        String json = p.toJson();
        WRITER.submit(() -> {
            try {
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling("aa-personality.json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("[entropybot] aa: personality write: {}", e.toString());
            }
        });
    }

    /** aa like [key value]: lists the personality, or sets one value and saves the file. */
    String like(String arg) {
        var p = io.github.mojolowjo.entropybot.aa.brain.Personality.of(bb);
        if (bb.fact(io.github.mojolowjo.entropybot.aa.brain.Personality.FACT) == null) {      // not loaded yet: a fresh one
            p = new io.github.mojolowjo.entropybot.aa.brain.Personality();
            bb.fact(io.github.mojolowjo.entropybot.aa.brain.Personality.FACT, p);
        }
        String[] w = arg.isBlank() ? new String[0] : arg.trim().split("\\s+");
        if (w.length == 0) return "personality (aa like <key> <n>; entropybot/aa-personality.json):\n" + String.join("\n", p.lines())
                + (p.errors.isEmpty() ? "" : "\n!! " + String.join("; ", p.errors) + " (defaults used)");
        if (w.length != 2) return "usage: aa like | aa like <key> <number>";
        double v;
        try {
            v = Double.parseDouble(w[1]);
        } catch (NumberFormatException e) {
            return "error: \"" + w[1] + "\" is not a number";
        }
        if (!p.errors.isEmpty()) return "error: the personality file has errors (" + String.join("; ", p.errors) + ") - fix entropybot/aa-personality.json first; saving now would reset those entries";
        String err = p.set(w[0], v);
        if (err != null) return "error: " + err;
        savePersonality(p);
        return "ok: " + w[0] + " " + io.github.mojolowjo.entropybot.aa.brain.Personality.fmt(v) + " (saved)";
    }

    // ---- B4 (BRAIN_PLAN 2.5): aa want / drop / goals ----

    private io.github.mojolowjo.entropybot.aa.brain.Thing.Catalog catalog;
    private String catalogWorld;

    /** The registries for the resolver, read once per world: ids, names (client language), pickaxe tiers, worldgen. */
    private io.github.mojolowjo.entropybot.aa.brain.Thing.Catalog catalog(Minecraft mc) {
        String world = Core.INSTANCE.files() == null ? "" : Core.INSTANCE.files().worldRoot().toString();
        if (catalog != null && world.equals(catalogWorld)) return catalog;
        List<String> items = new ArrayList<>(), blocks = new ArrayList<>();
        Map<String, String> names = new java.util.HashMap<>();
        Map<String, Integer> tiers = new java.util.HashMap<>();
        for (var e : net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet()) {
            String id = e.getKey().location().toString();
            items.add(id);
            try {
                names.put(id, e.getValue().getDescription().getString().toLowerCase(java.util.Locale.ROOT));
            } catch (RuntimeException ignored) {}
        }
        for (var e : net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet()) {
            String id = e.getKey().location().toString();
            blocks.add(id);
            var st = e.getValue().defaultBlockState();
            tiers.put(id, io.github.mojolowjo.entropybot.aa.brain.Thing.tier(st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE),
                    st.is(net.minecraft.tags.BlockTags.NEEDS_STONE_TOOL), st.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL),
                    st.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL)));
            names.putIfAbsent(id, e.getValue().getName().getString().toLowerCase(java.util.Locale.ROOT));
        }
        Map<String, io.github.mojolowjo.entropybot.aa.brain.Thing.Gen> gen = worldgen(mc);
        catalog = new io.github.mojolowjo.entropybot.aa.brain.Thing.Catalog(items, blocks, names, tiers, gen);
        catalogWorld = world;
        LOG.info("[entropybot] aa: resolver catalog: {} items, {} blocks, worldgen {}", items.size(), blocks.size(),
                gen == null ? "none (multiplayer)" : gen.size() + " ore blocks");
        return catalog;
    }

    /**
     * Singleplayer only: every ore feature (OreConfiguration targets) in each level's possible biomes (NeoForge biome
     * modifiers included), with the Y range from its height_range placement (encoded to JSON: the anchors). Null when
     * there is no integrated server; a failure is logged and gives null (the vanilla table / guess take over).
     */
    private static Map<String, io.github.mojolowjo.entropybot.aa.brain.Thing.Gen> worldgen(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) return null;
        Map<String, java.util.Set<String>> dims = new java.util.HashMap<>();
        Map<String, int[]> ys = new java.util.HashMap<>();
        try {
            for (var lvl : server.getAllLevels()) {
                String dim = lvl.dimension().location().toString();
                int bottom = lvl.getMinBuildHeight(), top = lvl.getMaxBuildHeight();
                java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (var biome : lvl.getChunkSource().getGenerator().getBiomeSource().possibleBiomes())
                    for (var set : biome.value().getGenerationSettings().features())
                        for (var pf : set) {
                            var placed = pf.value();
                            if (!seen.add(placed)) continue;
                            if (!(placed.feature().value().config() instanceof net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration oc)) continue;
                            int[] y = heights(placed.placement(), bottom, top);
                            for (var t : oc.targetStates) {
                                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(t.state.getBlock()).toString();
                                dims.computeIfAbsent(id, k -> new java.util.TreeSet<>()).add(dim);
                                if (y != null) ys.merge(id, y, (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
                            }
                        }
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: worldgen read failed ({}) - the vanilla table and name guesses are used", e.toString());
            return null;
        }
        Map<String, io.github.mojolowjo.entropybot.aa.brain.Thing.Gen> out = new java.util.HashMap<>();
        for (var e : dims.entrySet()) {
            int[] y = ys.get(e.getKey());
            int u = io.github.mojolowjo.entropybot.aa.brain.Thing.UNKNOWN_Y;
            out.put(e.getKey(), new io.github.mojolowjo.entropybot.aa.brain.Thing.Gen(e.getValue(), y == null ? u : y[0], y == null ? u : y[1]));
        }
        return out;
    }

    /** The min/max Y of the anchors in a placement list's height_range (null when none). */
    private static int[] heights(List<net.minecraft.world.level.levelgen.placement.PlacementModifier> mods, int bottom, int top) {
        int[] r = null;
        for (var m : mods) {
            if (!(m instanceof net.minecraft.world.level.levelgen.placement.HeightRangePlacement)) continue;
            var json = net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, m).result().orElse(null);
            if (json == null) continue;
            List<Integer> vals = new ArrayList<>();
            anchors(json, bottom, top, vals);
            for (int v : vals) r = r == null ? new int[]{v, v} : new int[]{Math.min(r[0], v), Math.max(r[1], v)};
        }
        return r;
    }

    private static void anchors(com.google.gson.JsonElement j, int bottom, int top, List<Integer> out) {
        if (j.isJsonArray()) for (var x : j.getAsJsonArray()) anchors(x, bottom, top, out);
        if (!j.isJsonObject()) return;
        for (var e : j.getAsJsonObject().entrySet()) {
            String k = e.getKey();
            if ((k.equals("absolute") || k.equals("above_bottom") || k.equals("below_top")) && e.getValue().isJsonPrimitive())
                out.add(io.github.mojolowjo.entropybot.aa.brain.Thing.anchorY(k, e.getValue().getAsInt(), bottom, top));
            else anchors(e.getValue(), bottom, top, out);
        }
    }

    /** Every 10 s while goals exist: known positions per family (OreIndex, 256 around), reached goals said and dropped. */
    private boolean knownDue;

    private void goalsTick(LocalPlayer p, long tick) {
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        if (goals.isEmpty() || (tick % 200 != 7 && !knownDue)) return;
        knownDue = false;
        for (var g : io.github.mojolowjo.entropybot.aa.brain.Goals.done(bb)) {
            tree.brain.remove(g.id(), "goal reached");
            String msg = "got " + io.github.mojolowjo.entropybot.aa.brain.Goals.have(bb.in.bag, g) + " " + g.r.key() + " (goal " + g.n + " done)";
            LOG.info("[entropybot] aa: {}", msg);
            c.whisper(c.owner(), "aa: " + msg);
        }
        Map<String, Integer> known = new java.util.HashMap<>();
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        int[] me = Jobs.here(p);
        Map<String, io.github.mojolowjo.entropybot.sense.OreIndex.Count> within = pass.index().within(bb.in.dimension, me[0], me[1], me[2], 256);
        for (var g : goals) {
            if (!g.r.ore()) continue;
            int n = 0;
            for (var e : within.entrySet()) if (g.r.ids().contains(e.getKey())) n += e.getValue().n();
            known.put(g.r.key(), n);
        }
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Goals.KNOWN_FACT, known);
    }

    /** aa want <thing> [n] [high|low] (also the wants by name: house/build, explore, tidy). */
    String want(String arg) {
        var a = io.github.mojolowjo.entropybot.aa.brain.Goals.parse(arg);
        if (a.err() != null) return a.err();
        String w = a.word();
        String wantKey = switch (w) {
            case "house", "build" -> "build";
            case "explore", "tidy" -> w;
            default -> null;
        };
        long now = System.currentTimeMillis();
        if (wantKey != null) {         // a want by name: bored now (it wins by its weight when nothing more urgent runs)
            io.github.mojolowjo.entropybot.aa.brain.Wants.done(bb, "want." + wantKey, now - (long) (100 * io.github.mojolowjo.entropybot.aa.brain.Wants.DAY_MS));
            return "ok: want." + wantKey + " is bored now (weight " + io.github.mojolowjo.entropybot.aa.brain.Personality.fmt(io.github.mojolowjo.entropybot.aa.brain.Personality.of(bb).get(wantKey))
                    + "; aa like " + wantKey + " <n> to raise it)";
        }
        for (var d : tree.brain.desires())
            if (d.id.equals("needs." + w)) return w + " is one of my needs: aa do " + w + " runs it now";
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return "error: no world";
        var r = io.github.mojolowjo.entropybot.aa.brain.Thing.resolve(w, catalog(mc));
        if (r.err() != null) {
            for (var d : tree.brain.desires())       // review: an ore/item word wins; a need name only when nothing resolves
                if (d.id.equals("needs." + w)) return w + " is one of my needs: aa do " + w + " runs it now";
            return "aa want: " + r.err();
        }
        var g = io.github.mojolowjo.entropybot.aa.brain.Goals.add(bb, r, a.n(), a.prio(), now);
        tree.brain.add(io.github.mojolowjo.entropybot.aa.AaDesires.ownerGoal(g));
        knownDue = true;                              // review: known positions now, not in up to 10 s
        String blocked = io.github.mojolowjo.entropybot.aa.brain.Goals.blocked(bb, g);
        LOG.info("[entropybot] aa: owner goal {} x{} prio {}: {}{}", r.key(), g.n, g.prio, r.text(), blocked == null ? "" : " - parked: " + blocked);
        return "ok: goal " + r.key() + " x" + g.n + (g.prio > 0 ? " high" : g.prio < 0 ? " low" : "") + " (score "
                + Math.round(io.github.mojolowjo.entropybot.aa.brain.Goals.score(bb, g)) + "): " + r.text()
                + (blocked != null ? " - parked: " + blocked : "") + (on() ? "" : " (aa is off: aa on to work on it)");
    }

    /** aa drop <thing|all>. */
    String drop(String arg) {
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        String w = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "_");
        if (w.isEmpty()) return "usage: aa drop <thing>|all";
        if (goals.isEmpty()) return "no owner goals";
        List<String> gone = new ArrayList<>();
        for (var g : new ArrayList<>(goals)) {
            boolean hit = w.equals("all") || g.r.key().equals(w) || g.r.key().endsWith(":" + w) || g.r.word().trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "_").equals(w)
                    || g.r.ids().contains(w.contains(":") ? w : "minecraft:" + w);
            if (!hit) continue;
            goals.remove(g);
            tree.brain.remove(g.id(), "aa drop");
            gone.add(g.r.key());
        }
        if (gone.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (var g : goals) keys.add(g.r.key());
            return "no goal " + w + " (goals: " + String.join(", ", keys) + ")";
        }
        LOG.info("[entropybot] aa: dropped goals {}", gone);
        return "ok: dropped " + String.join(", ", gone);
    }

    /** aa goals: each goal with its count, score and next step, then the top ideas. */
    String goalsText() {
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        List<String> out = new ArrayList<>();
        for (var g : goals) out.add(io.github.mojolowjo.entropybot.aa.brain.Goals.line(bb, g, scoreOf(g.id())));
        return "owner goals" + (goals.isEmpty() ? ": none (aa want <thing> [n] [high|low])" : " (" + goals.size() + "):\n" + String.join("\n", out))
                + "\n" + ideasLine();
    }

    /** "goals: iron_ore 0/5 70" for aa status (empty when none). */
    String goalsLine() {
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        if (goals.isEmpty()) return "goals: none";
        List<String> out = new ArrayList<>();
        for (var g : goals) out.add(g.r.key() + " " + io.github.mojolowjo.entropybot.aa.brain.Goals.have(bb.in.bag, g) + "/" + g.n + " " + scoreOf(g.id()));
        return "goals: " + String.join(", ", out);
    }

    private String scoreOf(String id) {
        for (var s : tree.brain.lastScored())
            if (s.d().id.equals(id)) return "score " + Math.round(s.score()) + (s.skip() != null ? " (" + s.skip() + ")" : "")
                    + (tree.brain.runningDesire() == s.d() ? " running" : "");
        return "score - (not scored yet)";
    }

    String wantsLine() { return io.github.mojolowjo.entropybot.aa.AaDesires.wantsLine(bb, tree.brain.lastScored()); }

    /** aa stats: measured runs, the tech tree and its ideas. */
    String statsText() {
        return tree.brain.stats.text() + "\n" + io.github.mojolowjo.entropybot.aa.AaDesires.ideasLine(bb, 8) + (treeError != null ? "\n!! " + treeError : "");
    }

    String ideasLine() { return io.github.mojolowjo.entropybot.aa.AaDesires.ideasLine(bb, 5); }

    /** BRAIN_PLAN B1: the top desires with their scores (aa why / aa status). */
    List<String> brainLines(int n) { return tree.brain.whyLines(n); }

    String brainCounters() { return tree.brain.counters(System.currentTimeMillis()); }

    List<String> decisions() { return tracer.decisions(); }

    /** state.json's "aa": {on, driver, task, danger}. */
    JsonObject stateBlock() {
        JsonObject o = new JsonObject();
        o.addProperty("on", on());
        if (on()) {
            o.addProperty("driver", tree.driver());
            if (bb.task() != null) o.addProperty("task", bb.task().spec().chain());
            o.addProperty("danger", tree.dangerWhy());
            o.addProperty("status", statusLine());
        }
        return o;
    }

    /** check's findings. */
    List<String[]> findings() {
        List<String[]> out = new ArrayList<>();
        if (offReason != null) out.add(new String[]{"aa", "aa " + offReason, "aa on (after a look at the log)"});
        if (on() && tracer.counts.lost > 0) out.add(new String[]{"aalost", "aa lost " + tracer.counts.lost + " task(s) to someone else (a poster not gated)", "aa why"});
        if (on() && c.brainRuntime != null && c.brainRuntime.brain.on()) out.add(new String[]{"aabrain", "aa and the brain are both on", "brain off"});
        return out;
    }

    /** entropybot/worlds/&lt;world&gt;/aa (per world, like the map: a fresh test copy starts with empty files). */
    private Path dir() {
        return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().worldRoot().resolve("aa");
    }

    /** 0.36.1 (review 8): the file writes run on one background thread (the JSON text is built on the game thread). */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropybot-aa-writer");
        t.setDaemon(true);
        return t;
    });
    /** 0.36.2 (review 8): on quit the queued writes still land (the writer is a daemon; the JVM would drop them). */
    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(AaRuntime::flushWrites, "entropybot-aa-flush"));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: no shutdown flush: {}", e.toString());
        }
    }

    /** Lets the queued writes finish (at most 3 s; each write carries its own path, so a world change is safe). */
    static void flushWrites() {
        try {
            WRITER.submit(() -> { }).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("[entropybot] aa: flush of the aa files: {}", e.toString());
        }
    }
    private volatile long writeErrors, lastWriteErrorAt;

    private void snapshot(long now, String driver) {
        if (now - lastSnapshotAt < 1000) return;
        lastSnapshotAt = now;
        Path d = dir();
        if (d == null) return;
        String json = tracer.snapshot(now, on(), driver, bb.taskJson(now), bb.in);
        WRITER.submit(() -> {
            try {
                Files.createDirectories(d);
                Path tmp = d.resolve("snapshot.json.tmp"), f = d.resolve("snapshot.json");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                writeFailed("snapshot", e);
            }
        });
    }

    private void appendDecision(String line) {
        Path d = dir();
        if (d == null) return;
        boolean rewrite = ++decisionLines > 2 * Tracer.KEEP_DECISIONS;
        String text = rewrite ? String.join("\n", tracer.decisions()) + "\n" : line + "\n";
        if (rewrite) decisionLines = tracer.decisions().size();
        WRITER.submit(() -> {
            try {
                Files.createDirectories(d);
                Path f = d.resolve("decisions.jsonl");
                if (rewrite) Files.writeString(f, text, StandardCharsets.UTF_8);      // keep the file short: the last 200 lines
                else Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                writeFailed("decisions", e);
            }
        });
    }

    private void writeFailed(String what, Exception e) {
        writeErrors++;
        long now = System.currentTimeMillis();
        if (now - lastWriteErrorAt > 60_000) {
            lastWriteErrorAt = now;
            LOG.warn("[entropybot] aa: {} write failed ({} so far): {}", what, writeErrors, e.toString());
        }
    }
}
