package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.AaScorer;
import io.github.mojolowjo.entropybot.aa.AaTree;
import io.github.mojolowjo.entropybot.aa.AaLadder;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Request;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.brain.BrainState;
import io.github.mojolowjo.entropybot.brain.Feasibility;
import io.github.mojolowjo.entropybot.engine.Reflexes;
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
        c.chains.onEnd(this::chainEnded);
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
        LOG.info("[entropybot] aa: on");
        return "ok: aa on - danger > your orders > AI requests > basic needs > idle; one task at a time; aa status says who drives";
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
        if (CHAIN.equals(c.chains.name())) c.chains.clear();
        LOG.info("[entropybot] aa: off ({})", why);
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
    boolean chainRunning() { return c.chains.running() && CHAIN.equals(c.chains.name()); }

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
            if (p.isDeadOrDying()) {
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
            fill(bb.in, sensed, p, mc);
            progressTick(p);
            if (now < heldUntil) {
                if (bb.in.otherJob == null) {
                    tracer.beginTick();
                    snapshot(now, "held");
                    return;
                }
                heldUntil = 0;        // the owner's order lifts the hold
            }
            Status s = tree.tick(bb);
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
    private void fill(Inputs in, BrainState s, LocalPlayer p, Minecraft mc) {
        in.inWorld = s.inWorld;
        in.health = p.getHealth();
        in.maxHealth = p.getMaxHealth();
        in.food = p.getFoodData().getFoodLevel();
        in.hurtRecently = p.hurtTime > 0;
        Reflexes.Reflex r = Core.INSTANCE.reflexes.reflex();
        in.danger = r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING || r == Reflexes.Reflex.RETREATING ? 2 : Core.INSTANCE.reflexes.hold() ? 1 : 0;
        in.dangerWhy = in.danger > 0 ? r.name().toLowerCase() : "";
        in.creeperClose = false;                  // Peaceful: never; Easy package reads Reflexes' creeper watch
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
        in.pickTier = s.feas.known ? s.feas.pickTier : (s.pickaxes > 0 ? 1 : 0);
        in.pickPct = s.pickaxes == 0 ? 0 : s.pickPct;
        in.pickaxes = s.pickaxes;
        in.bedKnown = s.bedNear;
        in.bedInBag = s.bedInBag;
        in.tableKnown = s.feas.table;
        in.furnaceKnown = s.feas.furnace;
        in.tableInBag = bag.getOrDefault("crafting_table", 0) > 0;
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
        in.inArea = s.areasHere;
        in.ownerOnline = s.ownerOnline;
        in.hungerDrains = mc.level.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL;
        in.menuOpen = mc.screen != null && !chainRunning() && !c.jobs.running();
        in.parked = s.parked;
        in.otherJob = otherJob();
        in.ownerOrderPending = in.otherJob != null;
        in.aiRequestPending = bb.peek("ai") != null;
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
        if (c.chains.running()) return CHAIN.equals(c.chains.name()) ? null : "chain " + c.chains.name();
        if (c.jobs.running()) return c.jobs.job.type + " (" + c.jobs.job.status + ")";
        if (c.gathering.running()) return "gather";
        if (c.hunting.running()) return "hunt";
        if (c.mule.running()) return "carry";
        if (c.requests.busy()) return "a request";
        return null;
    }

    /** The activity counter: a chain step, a job status change, a bag change or a block moved each count one. */
    private void progressTick(LocalPlayer p) {
        String step = chainRunning() ? c.chains.stepText() : null;
        if (step != null && !step.equals(lastStep)) progress++;
        lastStep = step;
        String js = c.jobs.running() ? c.jobs.job.status : null;
        if (js != null && !js.equals(lastJobStatus)) progress++;
        lastJobStatus = js;
        int bagTotal = 0;
        for (int i = 0; i < 36; i++) bagTotal += p.getInventory().getItem(i).getCount();
        if (lastBag >= 0 && bagTotal != lastBag) progress++;
        lastBag = bagTotal;
        int[] pos = Jobs.here(p);
        if (lastPos != null && (pos[0] != lastPos[0] || pos[1] != lastPos[1] || pos[2] != lastPos[2])) progress++;
        lastPos = pos;
    }

    // ---- JobPort ----

    private String refusal;

    @Override public long start(TaskSpec t) {
        refusal = null;
        if (c.chains.running()) { refusal = "busy: " + c.chains.chainStatus(); return -1; }
        if (c.jobs.running() && !c.jobs.walking()) { refusal = "busy: " + c.jobs.job.status; return -1; }
        if (c.gathering.running()) { refusal = "busy: " + c.gathering.statusText(); return -1; }
        if (c.hunting.running()) { refusal = "busy: a hunt runs"; return -1; }
        if (c.mule.running()) { refusal = "busy: " + c.mule.statusText(); return -1; }
        if (c.requests.busy()) { refusal = "busy: a request is open"; return -1; }
        lastEnd = null;
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
        if (chainRunning()) c.chains.clear();
        if (c.jobs.running()) c.jobs.finish("stopped: aa " + why);
        if (c.gathering.running()) c.gathering.stop("stopped: aa " + why);
        if (c.hunting.running()) c.hunting.stop("stopped: aa " + why);
        if (c.mule.running()) c.mule.stop("stopped: aa " + why);
    }

    @Override public long progress(long taskId) { return progress; }

    @Override public String resolve(String chain) {
        if (chain == null || !chain.startsWith("goal ")) return chain;
        return c.planning.chainFor(chain.substring(5));
    }

    @Override public String infeasible(String chain) {
        if (sensed == null || !sensed.feas.known) return null;
        try {
            Feasibility.Verdict v = Feasibility.check(expand(chain), sensed);
            return v.infeasible() ? "infeasible: " + v.missing() + (v.stepText() == null ? "" : " for " + v.stepText()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A routine name -> its steps joined with "then" (Feasibility reads the steps). */
    private String expand(String chain) {
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
        if (CHAIN.equals(name)) lastEnd = msg;
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

    private void snapshot(long now, String driver) {
        if (now - lastSnapshotAt < 1000) return;
        lastSnapshotAt = now;
        Path d = dir();
        if (d == null) return;
        try {
            Files.createDirectories(d);
            Path tmp = d.resolve("snapshot.json.tmp"), f = d.resolve("snapshot.json");
            Files.writeString(tmp, tracer.snapshot(now, on(), driver, bb.taskJson(now), bb.in), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            if (now - lastErrorAt > 60_000) {
                lastErrorAt = now;
                LOG.warn("[entropybot] aa: snapshot write failed: {}", e.toString());
            }
        }
    }

    private void appendDecision(String line) {
        Path d = dir();
        if (d == null) return;
        try {
            Files.createDirectories(d);
            Path f = d.resolve("decisions.jsonl");
            if (++decisionLines > 2 * Tracer.KEEP_DECISIONS) {
                // keep the file short: the last 200 lines the tracer holds
                Files.writeString(f, String.join("\n", tracer.decisions()) + "\n", StandardCharsets.UTF_8);
                decisionLines = tracer.decisions().size();
                return;
            }
            Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            LOG.warn("[entropybot] aa: decisions write failed: {}", e.toString());
        }
    }
}
