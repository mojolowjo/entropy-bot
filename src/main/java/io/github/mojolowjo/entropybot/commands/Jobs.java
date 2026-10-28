package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.Quiet;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.JobRequests.Request;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.restore.RestoreRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The mod's own job slot (B7b, part 1): walks (goto, come, go, base, a teleport home, the walk to a bed), wait and
 * twerk, ported from the bridge's startTravel / stepWalkJob / startHome / startSetSpawn / startWait with
 * the same wording. A walk listens to Baritone's path events (no path after 3 failed calculations, a stuck watchdog
 * for goals it can't reach), holds still while a reflex runs, stops for a retreat, an urgent fight (not a walk) or
 * the area fence, and reports through the request it carries (a chain waits on it).
 */
public final class Jobs {
    private static final Logger LOG = LogUtils.getLogger();
    static int travelFails() { return Tuning.i("jobs.travelFails"); }

    public static final class Job {
        /** AA_CLEANUP 2: who started it (the door's scope at the start; null = no scope); its tick runs in that scope. */
        public final io.github.mojolowjo.entropybot.aa.door.Driver driver = io.github.mojolowjo.entropybot.aa.door.Door.current();
        public String type, status, label, goal;
        public boolean done, reflex;
        Request req;
        long startTick, until, total, ticks;
        int[] dest;
        String bed;
        boolean bedClick;
        // the teleport home: where the bot was at the last check, when /home was sent
        int[] tpLast;
        String tpDim;
        long tpTick = -1;
        String tpNote;
        // path events since the goal
        long evSeq = -1;
        int evFails;
        boolean evAtGoal;
        double bestDist;
        long bestTick, lastStepTick = -1;
        /** 0.33.1: an owner/operator "goto x z" (not a chain, a reflex or a leg): only these get the ended-short check. */
        boolean xzOrder;
        /**
         * 0.39.z77 walk (run BT 13:14:32): the feet where Baritone went idle short of the walk's goal (null: not ended short) -
         * the typed end the step hands read for the walk's fact (StepHands.walkStatus), never the end line's words.
         */
        int[] shortEnd;
        // B7b part 2: a job made of steps, the goal object of its walk (unsticking plans it again), and whether the
        // open menu is closed when it ends ("always", or "fail" = unless it ended ok)
        Seq seq;
        baritone.api.pathing.goals.Goal goalObj;
        String closeOnEnd;
        /**
         * B7d (D3): an urgent fight holds this job instead of ending it (the bridge's job.holdOnFight: explore, mine
         * cave, the Baritone mine); onHold runs when a reflex starts holding it (the Baritone mine stops and breaking
         * goes off), onEnd however it ends (finish, stop, a reflex, the fence): breaking off, leases released.
         */
        public boolean holdOnFight;
        public Runnable onHold, onEnd;
        /** rm-routes: a far walk asks Baritone for GoalXZ first; on arrival the walk sets its GoalBlock (finalGoal). */
        baritone.api.pathing.goals.Goal finalGoal;
        /** P1: unique across restarts (the restore ledger's entries name their job by it). */
        public final long id = NEXT_ID.incrementAndGet();
        /** P1: the restore that runs before the job's end line (restoreMsg: that line). */
        RestoreRun restoreRun;
        String restoreMsg;
        boolean restoreDone;
        /**
         * 0.39.z66 review 1 (rule 6): the restore is AA's own put-back - the tail restoreGate starts after a job from
         * RestoreLive.afterJob - never the owner's "restore now" / take down (startRestore leaves it false).
         */
        boolean aaPutBack;
    }

    static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    private final Core core;
    private final Commands commands;
    public Job job;
    private long tpFailedAt = -100000, holdStart = -1;  // structural: a time long ago (sentinel)
    private Reflexes.Reflex reflexWas = Reflexes.Reflex.NONE;

    Jobs(Core core, Commands commands) {
        this.core = core;
        this.commands = commands;
    }


    /**
     * 0.23.3: the surface over x z with leaves skipped. The client level keeps only the MOTION_BLOCKING and
     * WORLD_SURFACE heightmaps (MOTION_BLOCKING_NO_LEAVES read -64 live, so the dig-out never counted as underground):
     * walk down from MOTION_BLOCKING past leaves. Vanilla API only (Heightmap, BlockTags.LEAVES).
     */
    public static int surfaceNoLeaves(net.minecraft.world.level.Level level, int x, int z) {
        // 0.39.z76 surface: the one answer for every caller (world.SurfaceHeightLive, the one heightmap reader); rule 15: not
        // loaded or no heightmap data in the client's chunk -> SurfaceHeight.NOT_KNOWN, never the bottom of the world
        return io.github.mojolowjo.entropybot.world.SurfaceHeightLive.noLeaves(level, x, z);
    }

    /** 0.28.1 (trial fix A): one cell for SurfaceRule, from the level (vanilla BlockState/BlockTags only). */
    static io.github.mojolowjo.entropybot.world.SurfaceRule.Cell surfaceCell(net.minecraft.world.level.Level level, int x, int y, int z) {
        BlockPos bp = new BlockPos(x, y, z);
        if (!level.isLoaded(bp)) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.SOLID;      // unknown: stop there
        var st = level.getBlockState(bp);
        if (!st.getFluidState().isEmpty() && st.getCollisionShape(level, bp).isEmpty()) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.FLUID;
        if (st.is(net.minecraft.tags.BlockTags.LEAVES)) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.LEAVES;
        if (st.is(net.minecraft.tags.BlockTags.LOGS)) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.LOG;
        if (st.is(net.minecraft.world.level.block.Blocks.SNOW)) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.SNOW_LAYER;
        if (st.isAir()) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.AIR;
        if (st.getCollisionShape(level, bp).isEmpty()) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.PLANT;
        return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.SOLID;
    }

    /** 0.28.1: the ground under x y z (SurfaceRule.groundFeet): {x, feet y, z}; the spot itself when the level is missing. */
    public static int[] groundUnder(net.minecraft.world.level.Level level, int x, int y, int z) {
        if (level == null) return new int[]{x, y, z};
        try {
            int g = io.github.mojolowjo.entropybot.world.SurfaceRule.groundFeet(yy -> surfaceCell(level, x, yy, z), y, level.getMinBuildHeight());
            return new int[]{x, g, z};
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] ground under {} {} {}: {}", x, y, z, e.toString());
            return new int[]{x, y, z};
        }
    }

    /** 0.28.1: a walk target whose cell and the one under it are open (a mark left in the air). */
    /** 0.31.0 (5): the short id of the block filling x y z (it has a collision shape), null when free or not loaded. */
    static String solidAt(net.minecraft.world.level.Level level, int x, int y, int z) {
        if (level == null) return null;
        BlockPos g = new BlockPos(x, y, z);
        if (!level.isLoaded(g)) return null;
        var st = level.getBlockState(g);
        if (st.getCollisionShape(level, g).isEmpty()) return null;
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
    }

    /** 0.31.0 (5): {@link WalkEnd#freeSpotFor} on the level (feet and head free, a full block below, no fluid). */
    static int[] freeSpotFor(net.minecraft.world.level.Level level, int x, int y, int z) {
        return WalkEnd.freeSpotFor(x, y, z, c -> {
            BlockPos f = new BlockPos(c[0], c[1], c[2]);
            return level.isLoaded(f)
                    && level.getBlockState(f).getCollisionShape(level, f).isEmpty()
                    && level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
                    && level.getBlockState(f.below()).isCollisionShapeFullBlock(level, f.below())
                    && level.getFluidState(f).isEmpty();
        });
    }

    static boolean targetInAir(net.minecraft.world.level.Level level, int x, int y, int z) {
        if (level == null || !level.isLoaded(new BlockPos(x, y - 1, z))) return false;
        return io.github.mojolowjo.entropybot.world.SurfaceRule.inAir(yy -> surfaceCell(level, x, yy, z), y);
    }

    public boolean running() { return job != null && !job.done; }

    Core core() { return core; }

    Commands commands() { return commands; }

    /** A job made of steps (open, scan, deposit, corpse, rs, pots...): "started: <label>". */
    String startSeq(Seq s, String closeOnEnd) {
        if (!door("job " + s.label)) return io.github.mojolowjo.entropybot.aa.door.Door.refusal("job " + s.label);
        Job j = new Job();
        j.type = "seq";
        j.startTick = core.tick();
        j.label = s.label;
        j.status = s.label;
        j.seq = s;
        j.closeOnEnd = closeOnEnd;
        s.stepStart = core.tick();
        job = j;
        return "started: " + s.label;
    }

    public boolean walking() { return running() && (job.type.equals("travel") || job.type.equals("spawn")); }

    static IBaritone baritone() {
        try { return BaritoneAPI.getProvider().getPrimaryBaritone(); } catch (Throwable t) {
            Quiet.warn("Jobs.baritone failed, carrying on with the fallback", t); return null; }
    }

    static boolean idle(IBaritone b) {
        return !b.getPathingBehavior().isPathing() && b.getPathingControlManager().mostRecentInControl().isEmpty();
    }

    static void cancel(IBaritone b) {
        WalkProbe.cancelled("Jobs.cancel");          // 0.39.z54 B: who ended the walk (probe on, a walk step running)
        try { b.getPathingBehavior().cancelEverything(); } catch (Throwable caught) { Quiet.warn("Jobs.cancel failed, carrying on with the fallback", caught); }
    }

    static int[] here(Player p) {
        return new int[]{(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
    }

    static String fmt(int[] p) { return p[0] + " " + p[1] + " " + p[2]; }

    static long distSq(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- the request a started job carries ----

    /** After a start: a job that runs gets a request (the caller hands it on), else null. */
    Request attach(String kind, String from, String text, String reply, JobRequests.Listener l) {
        if (!running() || job.req != null) return null;
        job.req = commands.requests.local(kind, from, text, reply, l);
        return job.req;
    }

    /** Ends the job: its request hears msg (the requester's whisper, a chain's next step). */

    /** The job's end; its status is the status word the job put first ("ok: ..." = done its job), read before any note is added. */
    public void finish(String msg) {
        finish(msg, io.github.mojolowjo.entropybot.view.ReplyShaper.endFailed(msg));
    }

    /** 0.33.0 review: the end with the status the job itself knows (failed: it did not do its job). */
    public void finish(String msg, boolean failed) {
        Job j = job;
        if (j == null || j.done) return;
        msg = restoreGate(j, msg);
        if (msg == null) return;                          // P1: the job's blocks are put back first; finish comes again then
        j.done = true;
        j.status = msg;
        if (j.onEnd != null) {
            try { j.onEnd.run(); } catch (RuntimeException e) { LOG.warn("[entropybot] job end hook: {}", e.toString()); }
        }
        Minecraft mc = Minecraft.getInstance();
        io.github.mojolowjo.entropybot.aa.moves.JobHands.get().stop("the job ended");
        VeinMine.cancelActive("the job ended");
        if (j.seq != null) { try { String m = Clearing.ended(j.seq, msg); if (m != null && !m.equals(msg)) { msg = m; j.status = m; } } catch (RuntimeException e) { LOG.warn("[entropybot] clear end hook: {}", e.toString()); } }           // B7d D1: a clear's leases end with the job, however it ends (a table left is named); never keeps the request open
        if (j.seq != null) {
            LocalPlayer p = mc.player;
            // 0.39.z7 (owner 2026-10-22, "open a GUI, do what is needed, close it"): no auto-close of whatever screen is
            // open when a job ends; the step that opened a screen closes it
            IBaritone b = baritone();
            if (b != null && !msg.startsWith("ok")) cancel(b);
        }
        LOG.info("[entropybot] job finished: {}", msg);
        try { core.recorder.jobEnded(j.type, j.label, msg); } catch (RuntimeException e) { LOG.warn("[entropybot] recorder job end: {}", e.toString()); }
        if (j.req != null) commands.requests.done(j.req.id, msg, failed);
    }

    // ---- P1: the restore before the end line ----

    /** End messages after which nothing is put back now (the owner said stop, the bot is fleeing or gone): the entries wait. */
    static boolean noRestoreAfter(String msg) {
        return msg.equals("stopped") || msg.startsWith("stopped: low health") || msg.startsWith("stopped: I was disconnected")
                || msg.startsWith("stopped: I am in ");
    }

    /**
     * The job is about to end with msg. Null when a restore of the blocks it broke on the way starts now instead (the
     * job's own end hook runs first: breaking off, leases released); finish is called again with the full line when it
     * is done. Otherwise the line to end with (the escape report and the restore summary or "N blocks to put back" added).
     */
    private String restoreGate(Job j, String msg) {
        try {
            if (j.restoreRun != null) {
                // the restore itself was cut short (a stop, a fight, the fence)
                RestoreRun r = j.restoreRun;
                j.restoreRun = null;
                j.restoreDone = true;
                r.stopRest(msg.startsWith("stopped") ? "stopped" : "the job ended");
                r.end();
                String base = j.restoreMsg != null ? j.restoreMsg : msg;
                String text = RestoreLive.INSTANCE.endText(base, j, r);
                return j.restoreMsg != null ? text + " (restore " + msg.replaceFirst("^error: ", "") + ")" : text;
            }
            if (j.restoreDone || JobRequests.quiet(msg)) return msg;
            j.restoreDone = true;
            LocalPlayer p = Minecraft.getInstance().player;
            RestoreRun r = noRestoreAfter(msg) || p == null ? null : RestoreLive.INSTANCE.afterJob(j, here(p), Guard.dimOf(p.level()));
            if (r == null || r.empty()) {
                if (r != null) r.end();
                String text = RestoreLive.INSTANCE.endText(msg, j, r);
                if (r == null) {
                    String w = RestoreLive.INSTANCE.waitingNote(j);
                    if (w != null) text += "; " + w;
                    if (w != null && w.startsWith("put-back off")) LOG.info("[entropybot] {}: {}", j.label, w);    // 0.39.z75: one line per job end
                }
                return text;
            }
            if (j.onEnd != null) {
                try { j.onEnd.run(); } catch (RuntimeException e) { LOG.warn("[entropybot] job end hook: {}", e.toString()); }
                j.onEnd = null;
            }
            IBaritone b = baritone();
            if (b != null) cancel(b);
            j.restoreMsg = msg;
            j.restoreRun = r;
            j.aaPutBack = true;          // 0.39.z66 review 1: the afterJob tail is AA's own put-back (a step start may halt it)
            j.status = "putting back " + r.total() + (r.total() == 1 ? " block" : " blocks") + " I broke on the way";
            LOG.info("[entropybot] {} (then: {})", j.status, msg);
            return null;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] restore at the job's end: {}", e.toString());
            return msg + "; couldn't put back what I broke on the way (" + e + ") - restore status";
        }
    }

    /** "restore now": a job that only puts blocks back. */
    String startRestore(RestoreRun run, String label) {
        if (!door("restore " + label)) return io.github.mojolowjo.entropybot.aa.door.Door.refusal("restore " + label);
        Job j = new Job();
        j.type = "restore";
        j.startTick = core.tick();
        j.label = label;
        j.status = label;
        j.restoreRun = run;
        j.restoreDone = true;
        job = j;
        return "started: " + label;
    }

    private void restoreTick(LocalPlayer p, Job j) {
        RestoreRun r = j.restoreRun;
        boolean done;
        try {
            done = r.step(p, core.tick());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] restore: {}", e.toString());
            r.stopRest("error: " + e);
            done = true;
        }
        if (!done) {
            j.status = r.placedMode ? "taking down blocks I placed (" + r.placed + " of " + r.total() + ")" : "putting back blocks I broke on the way (" + r.placed + " of " + r.total() + ")";
            return;
        }
        r.end();
        j.restoreRun = null;
        String text;
        if (j.restoreMsg != null) text = RestoreLive.INSTANCE.endText(j.restoreMsg, j, r);
        else {
            String sum = r.summary();
            if (r.placedMode) text = (io.github.mojolowjo.entropybot.restore.PlacedPlan.failed(r.placed, r.left) ? "error: " : "ok: ") + (sum == null ? "nothing of mine left standing" : sum);   // aa-litter: blocks left standing fail the step
            else text = (r.placed == 0 && !r.left.isEmpty() ? "error: " : "ok: ") + (sum == null ? "nothing to put back" : sum);
        }
        finish(text);
    }

    /** A new command replaces a walk: it ends quietly (the bridge's "job.done = true"). */
    public void replaceWalk() {
        if (walking() && door("replace the walk " + job.label)) finish(JobRequests.REPLACED);
    }

    /** AA_CLEANUP 2: the one door for job starts and foreign stops (always true with AA off). */
    static boolean door(String what) { return io.github.mojolowjo.entropybot.aa.door.Door.allow(what); }

    /** The running job's driver (null when none or unscoped): Commands runs the job's tick in it. */
    public io.github.mojolowjo.entropybot.aa.door.Driver driver() { Job j = job; return j == null ? null : j.driver; }

    // ---- the fence ----

    /** Null when the bot may walk to x y z, else the guard's reason (FenceRules.walkVerdict). */
    String walkVerdict(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        return FenceRules.walkVerdict(io.github.mojolowjo.entropybot.api.BotAPI.check(Guard.dimOf(mc.level), x, y, z, "go"));
    }

    static String withAreaHint(String reason) { return FenceRules.withAreaHint(reason); }

    static String fenceError(String reason) { return FenceRules.gotoRefusal(reason); }

    // ---- walks ----

    /**
     * goal: a Baritone command ("goto x y z", "goto x z", "follow player NAME"); dest: where it ends up (a /home
     * first when that is near home and far off), or null; reflex: no fence check.
     */
    public String startTravel(String goal, String label, int[] dest, String destDim, boolean reflex) {
        if (!door("travel " + label)) return io.github.mojolowjo.entropybot.aa.door.Door.refusal("travel " + label);
        IBaritone b = baritone();
        if (b == null) return "error: baritone not loaded";
        LocalPlayer p = Minecraft.getInstance().player;
        // 0.24.1: absurd walks are refused before any planning (path maxWalk)
        {
            int[] far = dest != null ? dest : FenceRules.goalSpot(goal, (int) Math.floor(p.getY()));
            String tooFar = far == null ? null : io.github.mojolowjo.entropybot.move.ServerCmds.tooFar(here(p), far, io.github.mojolowjo.entropybot.move.ServerCmds.maxWalk());
            if (tooFar != null) return tooFar;
        }
        if (!reflex) {
            int[] spot = dest != null ? dest : FenceRules.goalSpot(goal, (int) Math.floor(p.getY()));
            String why = spot == null ? null : FenceRules.walkVerdict(io.github.mojolowjo.entropybot.api.BotAPI.check(Guard.dimOf(Minecraft.getInstance().level), spot[0], spot[1], spot[2], "go"));
            if (why != null) return FenceRules.gotoRefusal(why);
        }
        Job j = new Job();
        j.type = "travel";
        j.startTick = core.tick();
        j.label = label;
        j.goal = goal;
        j.dest = dest;
        j.reflex = reflex;
        if (dest != null && tpWorth(p, dest, destDim)) {
            // stand still for /home (in case the server has a warm-up), then walk the rest
            cancel(b);
            j.status = label + " (teleporting home first)";
            sendHome(p, j);
            job = j;
            return "ok: " + j.status;
        }
        job = j;
        launchTravel(p, j, b);
        return "ok: " + label;
    }

    /**
     * rm-routes: a travel's walk is ONE Baritone goal (Baritone plans, the fork's chunk cache remembers). A fixed "goto
     * x y z" beyond Tuning walk.farXZ across asks for GoalXZ first and sets its GoalBlock on arrival (stepWalk); every
     * other goal is the Baritone command itself.
     */
    private void launchTravel(LocalPlayer p, Job j, IBaritone b) {
        int[] d = travelDest(j);
        boolean fixed = !j.reflex && d != null && j.goal != null && j.goal.startsWith("goto ");
        j.status = j.label;
        if (fixed) {
            baritone.api.pathing.goals.Goal block = new baritone.api.pathing.goals.GoalBlock(d[0], d[1], d[2]);
            int[] me = here(p);
            double across = Math.hypot(d[0] - me[0], d[2] - me[2]);
            if (across > io.github.mojolowjo.entropybot.aa.brain.Tuning.get("walk.farXZ")) {
                j.finalGoal = block;
                walkGoal(b, j, new baritone.api.pathing.goals.GoalXZ(d[0], d[2]));
            } else walkGoal(b, j, block);
        } else b.getCommandManager().execute(j.goal);
        watch(j);
    }

    /** Sets the walk's one Baritone goal and remembers it (a reflex hold sets it again afterwards). */
    static void walkGoal(IBaritone b, Job j, baritone.api.pathing.goals.Goal g) {
        io.github.mojolowjo.entropybot.engine.RunThrowaway.walkPenalty();     // 0.39.z39: the walk's placement price, held by the fork's walk lease for its run
        b.getCustomGoalProcess().setGoalAndPath(g);
        j.goalObj = g;
    }

    /** "home": /home right away. */
    public String startHome(LocalPlayer p) {
        if (!door("home")) return io.github.mojolowjo.entropybot.aa.door.Door.refusal("home");
        JsonObject h = commands.home();
        int[] me = here(p);
        if (h != null && dimOf(h).equals(Guard.dimOf(p.level())) && distSq(pos(h), me) <= Tuning.i("jobs.homeDistSq")) return "ok: already home (" + fmt(pos(h)) + ")";
        // 0.24.1: server commands off: walk home (one Baritone goal), never /home
        if (!io.github.mojolowjo.entropybot.move.ServerCmds.allowed("home")) {
            if (h == null) return "error: server commands are off and I know no home - place base, or server commands on";
            if (!dimOf(h).equals(Guard.dimOf(p.level()))) return "error: server commands are off and my home is in " + dimOf(h) + " - I can't walk there";
            int[] hp = pos(h);
            return startTravel("goto " + hp[0] + " " + hp[1] + " " + hp[2], "walking home (server commands are off)", hp, dimOf(h), false);
        }
        IBaritone b = baritone();
        if (b != null) cancel(b);
        Job j = new Job();
        j.type = "travel";
        j.startTick = core.tick();
        j.status = "teleporting home";
        j.label = j.status;
        sendHome(p, j);
        job = j;
        return "ok: teleporting home";
    }

    /** "spawn": walk to the nearest bed and click it. */
    public String startSetSpawn(LocalPlayer p) {
        if (!door("spawn")) return io.github.mojolowjo.entropybot.aa.door.Door.refusal("spawn");
        String f = findBlock(p, "_bed 24");
        if (!f.startsWith("found")) return Hints.next("error: no bed within 24 blocks", "go base then spawn (or goto x y z next to a bed, then spawn)");
        String[] w = f.split(" ");
        String[] parts = w[4].split("\\.");          // block.minecraft.white_bed
        int x = Integer.parseInt(w[1]), y = Integer.parseInt(w[2]), z = Integer.parseInt(w[3]);
        IBaritone b = baritone();
        if (b == null) return "error: baritone not loaded";
        String why = walkVerdict(x, y, z);
        if (why != null) return fenceError(why);
        b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(new BlockPos(x, y, z)));
        Job j = new Job();
        j.type = "spawn";
        j.bed = x + " " + y + " " + z;
        j.startTick = core.tick();
        j.status = "walking to the " + String.join(".", java.util.Arrays.copyOfRange(parts, 2, parts.length)) + " at " + j.bed;
        job = j;
        return "started: " + j.status;
    }

    /** "wait <seconds>": a step that does nothing for a while. */
    public String startWait(String text) {
        if (!door("wait")) return io.github.mojolowjo.entropybot.aa.door.Door.refusal("wait");
        int s = (int) Math.min(Math.max(Chains.leadingInt(text) == 0 ? 10 : Chains.leadingInt(text), 1), 3600);
        Job j = new Job();
        j.type = "wait";
        j.until = core.tick() + s * 20L;  // game fact: 20 game ticks per second
        j.status = "waiting " + s + "s";
        job = j;
        return "started: waiting " + s + "s";
    }

    // ---- the teleport home ----

    /** Worth a /home on the way? Only if dest is near home and the bot is far from it (another dimension, 40+ across, 10+ up or down). */
    boolean tpWorth(Player p, int[] dest, String destDim) {
        JsonObject h = commands.home();
        boolean worth = h != null && TpRules.worth(pos(h), dimOf(h), dest, destDim, here(p), Guard.dimOf(p.level()), core.tick(), tpFailedAt);
        return worth && io.github.mojolowjo.entropybot.move.ServerCmds.allowed("a far trip's teleport home");
    }

    void sendHome(LocalPlayer p, Job j) {
        j.tpLast = here(p);
        j.tpDim = Guard.dimOf(p.level());
        j.tpTick = core.tick();
        p.connection.sendCommand("home");
    }

    /** null while waiting; "ok" once the bot jumped (8+ blocks since the last check); "failed" after TpRules.WAIT_TICKS. */
    String tpResult(LocalPlayer p, Job j) {
        int[] me = here(p);
        String dim = Guard.dimOf(p.level());
        String r = TpRules.result(j.tpLast, j.tpDim, me, dim, j.tpTick, core.tick());
        if ("ok".equals(r)) {
            commands.setHome(me, dim);
            return "ok";
        }
        j.tpLast = me;
        if (r == null) return null;
        tpFailedAt = core.tick();
        return "failed";
    }

    // ---- path events ----

    void watch(Job j) {
        j.evSeq = core.events.lastSeq();
        j.evFails = 0;
        j.evAtGoal = false;
    }

    /** "nopath", "goal" or null (undecided). */
    String travelEvents(Job j) {
        if (j.evSeq < 0) return null;
        try {
            JsonArray list = JsonParser.parseString(core.events.since(j.evSeq, 100)).getAsJsonArray();
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                j.evSeq = o.get("seq").getAsLong();
                if (!"path".equals(o.get("kind").getAsString())) continue;
                String what = o.get("text").getAsString();
                if (what.equals("CALC_STARTED")) j.evFails = 0;
                else if (what.equals("CALC_FAILED") || what.equals("NEXT_CALC_FAILED")) j.evFails++;
                else if (what.equals("AT_GOAL")) j.evAtGoal = true;
            }
            if (j.evAtGoal) return "goal";
            if (j.evFails >= travelFails()) return "nopath";
        } catch (RuntimeException e) {
            Quiet.warn("Jobs.travelEvents failed, carrying on with the fallback", e);
            j.evSeq = -1;
        }
        return null;
    }

    static int[] travelDest(Job j) {
        if (j.dest != null) return j.dest;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^goto (-?\\d+) (-?\\d+) (-?\\d+)$").matcher(j.goal == null ? "" : j.goal);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))} : null;
    }

    /** 0.33.1: "goto x z" (no y): {x, z}, else null. */
    static int[] travelXZ(Job j) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^goto (-?\\d+) (-?\\d+)$").matcher(j.goal == null ? "" : j.goal);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    static String noPathResult(Player p, Job j) {
        int[] d = travelDest(j), me = here(p);
        String text = "error: no path to " + (d != null ? fmt(d) : String.valueOf(j.goal).replaceFirst("^goto ", "")) + " - stuck at " + fmt(me);
        if (d != null) text += ", " + Math.round(Math.sqrt(distSq(me, d))) + " blocks short";
        return text;
    }

    /** No block of progress toward the destination for 30 s: Baritone re-plans a goal it can't reach forever. */
    boolean travelStuck(Player p, Job j) {
        if (j.evSeq < 0) return false;
        int[] d = travelDest(j);
        if (d == null) return false;
        double dist = Math.sqrt(distSq(here(p), d));
        long now = core.tick();
        if (j.lastStepTick < 0 || now - j.lastStepTick > Tuning.i("jobs.stepGapTicks")) {
            j.bestDist = dist;
            j.bestTick = now;
        }
        j.lastStepTick = now;
        if (dist < j.bestDist - 1) {
            j.bestDist = dist;
            j.bestTick = now;
        }
        return now - j.bestTick > (long) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("walk.stuckS"));
    }

    // ---- the tick ----

    /** Once a tick in a world. */
    void tick(LocalPlayer p) {
        long now = core.tick();
        io.github.mojolowjo.entropybot.aa.moves.JobHands.get().tick();      // backbone step 3: the job hands follow Baritone
        reflexWatch(p);
        if (!running()) return;
        if (core.reflexes.hold()) return;                // a reflex holds the job still
        Job j = job;
        if (j.restoreRun != null) {                     // P1: putting back what it broke on the way
            restoreTick(p, j);
            return;
        }
        switch (j.type) {
            case "wait" -> { if (now >= j.until) finish("ok: waited"); }
            case "travel", "spawn" -> {
                if (j.bedClick) bedClick(j);
                else if (now % 20 == 0) stepWalk(p, j);  // structural: periodic cadence in ticks (20 ticks = 1 s)
            }
            case "seq" -> { if (now % 2 == 0 || j.seq.everyTick()) j.seq.tick(p); }
            default -> {}
        }
    }

    /**
     * One walk check a second. rm-routes: no unstick, dig-out, trail retrace or door retry - a walk Baritone cannot
     * finish ends FAILED with Baritone's reason (no path, no progress, ended short) and the caller's next option runs.
     */
    private void stepWalk(LocalPlayer p, Job j) {
        IBaritone b = baritone();
        if (j.tpTick >= 0) {
            String t = tpResult(p, j);
            if (t == null) return;
            j.tpTick = -1;
            j.tpNote = t.equals("ok") ? "teleported home" : "/home didn't move me, so I walked";
            if (t.equals("ok") && j.goal == null) {
                if (b != null) cancel(b);
                finish("ok: teleported home (" + fmt(pos(commands.home())) + ")");
                return;
            }
            if (j.goal == null) {
                finish("error: /home didn't move me - is my home set? Stand me at the base and PM sethome");
                return;
            }
            j.startTick = core.tick();
            if (b != null) launchTravel(p, j, b);
            else finish("error: walk (start after /home): baritone not loaded");
            return;
        }
        String ev = travelEvents(j);
        boolean stuck = j.type.equals("travel") && ev == null && travelStuck(p, j);
        if (stuck) {
            if (b != null) cancel(b);
            finish(noPathResult(p, j) + " (walk: no progress for " + (long) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("walk.stuckS") + " s)");
            return;
        }
        if ("nopath".equals(ev)) {
            if (b != null) cancel(b);
            finish(noPathResult(p, j) + " (walk: Baritone found no path)");
            return;
        }
        if (ev == null && core.tick() - j.startTick < Tuning.i("jobs.startWaitTicks")) return;
        if (b != null && !idle(b)) return;
        if (j.finalGoal != null && b != null) {            // far walk: GoalXZ reached, now the block itself
            baritone.api.pathing.goals.Goal g = j.finalGoal;
            j.finalGoal = null;
            j.startTick = core.tick();
            j.lastStepTick = -1;
            walkGoal(b, j, g);
            watch(j);
            return;
        }
        if (j.type.equals("travel")) {
            // Baritone goes idle without a no-path event when the goal can't be reached: the end position decides
            int[] d = travelDest(j), me = here(p);
            // 0.39.z77 walk (run BT 13:14:32, rule 7): the end short is a typed fact (j.shortEnd) the order re-plans on; no
            // owner action and no advice the bot does not do itself ("goto a spot next to it, or come get me" is gone)
            if (!j.reflex && d != null && !WalkEnd.arrived(me, d)) {
                j.shortEnd = me.clone();
                finish(WalkEnd.shortResult(me, d) + " (walk: Baritone stopped short)");
                return;
            }
            int[] xz = d == null && j.xzOrder && !j.reflex ? travelXZ(j) : null;
            if (xz != null && !WalkEnd.arrivedXZ(me, xz)) {
                j.shortEnd = me.clone();
                finish(WalkEnd.shortResultXZ(me, xz) + " (walk: Baritone stopped short)");
                return;
            }
            String v = goalVerdict(p, j);
            finish(v != null ? v : "ok: arrived near " + fmt(here(p)) + (j.tpNote != null ? " (" + j.tpNote + ")" : ""));
        } else {
            j.bedClick = true;
            bedClick(j);
        }
    }

    /** The bed click through Baritone's hands (it looks, stands and clicks); polled every tick until it ends. */
    private void bedClick(Job j) {
        String[] c = j.bed.trim().split("\\s+");
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().use(new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])}, "the bed");
        if (r.running()) return;
        finish(r.done() ? "ok: clicked the bed at " + j.bed + " (spawn set if it was night or the server allows it)" : "error: could not click the bed at " + j.bed + ": " + r.why());
    }

    // ---- the walk's end ----

    /** A cell the bot can stand in: nothing to bump into at the feet and head, a full block under it. */
    static boolean standable(Minecraft mc, BlockPos feet) {
        return mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                && mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()
                && mc.level.getBlockState(feet.below()).isCollisionShapeFullBlock(mc.level, feet.below())
                && mc.level.getFluidState(feet).isEmpty();
    }

    /**
     * 0.23.4 (run 9a): an owner's goto x y z ending within the 3-block tolerance: is the goal cell itself inside a block
     * or walled in? Then WalkEnd.goalVerdict's answer, else null (arrived near, as before). Unloaded goal: null.
     */
    String goalVerdict(LocalPlayer p, Job j) {
        try {
            if (j.reflex || j.goal == null || !j.goal.matches("^goto -?\\d+ -?\\d+ -?\\d+$")) return null;
            int[] d = travelDest(j);
            Minecraft mc = Minecraft.getInstance();
            if (d == null || mc.level == null) return null;
            BlockPos g = new BlockPos(d[0], d[1], d[2]);
            if (!mc.level.isLoaded(g)) return null;
            WalkEnd.GoalCell cell = goalCell(mc, g, here(p));
            String v = WalkEnd.goalVerdict(here(p), d, cell);
            if (v != null) LOG.info("[entropybot] walk end at {}: goal {} {}", fmt(here(p)), fmt(d), v);
            return v;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] goal check: {}", e.toString());
            return null;
        }
    }

    private static boolean open(Minecraft mc, BlockPos c) {
        return mc.level.getBlockState(c).getCollisionShape(mc.level, c).isEmpty();
    }

    static WalkEnd.GoalCell goalCell(Minecraft mc, BlockPos g, int[] me) {
        var st = mc.level.getBlockState(g);
        if (!st.getCollisionShape(mc.level, g).isEmpty()) {
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            int[] best = null;
            double bd = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos c = g.offset(dx, dy, dz);
                if ((dx | dy | dz) == 0 || !standable(mc, c)) continue;
                double s = distSq(new int[]{c.getX(), c.getY(), c.getZ()}, me);
                if (s < bd) { bd = s; best = new int[]{c.getX(), c.getY(), c.getZ()}; }
            }
            return new WalkEnd.GoalCell(id, false, best);
        }
        // walled in: no room for the head, or closed on all four sides at feet and head height (only reached
        // from above, and the walk ended more than a block off, so it wasn't)
        boolean walled = !open(mc, g.above());
        if (!walled) {
            walled = true;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos s = g.relative(dir);
                if (open(mc, s) && open(mc, s.above())) walled = false;
            }
        }
        return new WalkEnd.GoalCell(null, walled, null);
    }


    /**
     * 0.34.3: the shared under-ground rule at a cell (RestoreRules.underDepth over the leaf-free surface): depth or 0.
     * 0.39.z76 surface 2 (rule 15): the surface not known - 0 (no depth is claimed) plus the fact ({@link #surfaceNotKnown}),
     * said once per chunk; never a guessed depth.
     */
    public static int rockOverHead(net.minecraft.world.level.Level lv, int x, int y, int z) {
        int surface = surfaceNoLeaves(lv, x, z);
        // 0.39.z76 surface (rule 15): the surface not known - the sky test alone decides, and the reason is said once
        if (!io.github.mojolowjo.entropybot.world.SurfaceHeight.known(surface)) {
            surfaceNotKnown = "the surface over " + x + " " + z + " is not known (the column is not loaded or the client's chunk has no heightmap data)";
            if (SURFACE_UNKNOWN_SAID.add((x >> 4) + "," + (z >> 4))) LOG.info("[entropybot] {}: no depth under ground is claimed there", surfaceNotKnown);
            return 0;
        }
        surfaceNotKnown = null;
        return RestoreRules.rockOverHead(lv.canSeeSky(new BlockPos(x, y + 1, z)), surface, y);
    }

    /**
     * 0.39.z77 shape review 1 (rule 15): as {@link #rockOverHead}, but -1 when the surface over the column is not known - for the
     * steps that end on "out on the surface" (the walk up, the climb), where "not known" must never read as "out".
     */
    /**
     * 0.39.z78 J6b run EH (rule 23): the ONE "below the surface" reader the planner's surface fact, the explore's under-ground
     * test and the walk-up's done test read - SurfaceHeight.belowSurface of the rock over the head ({@link #rockOverHead}) and
     * the hole round the feet (SurfaceHeight.holeDepth over the 8 neighbouring columns' leaf-free surfaces), so a sky-open
     * shaft counts. Loader notes: vanilla heightmaps through SurfaceHeightLive.noLeaves, Level#canSeeSky.
     */
    public static int belowSurface(net.minecraft.world.level.Level lv, int x, int y, int z) {
        return io.github.mojolowjo.entropybot.world.SurfaceHeight.belowSurface(rockOverHead(lv, x, y, z), holeDepth(lv, x, y, z));
    }

    /** Run EH: SurfaceHeight.holeDepth at x y z from the 8 neighbouring columns' leaf-free surfaces. */
    public static int holeDepth(net.minecraft.world.level.Level lv, int x, int y, int z) {
        int[] n = new int[8];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (dx != 0 || dz != 0) n[i++] = surfaceNoLeaves(lv, x + dx, z + dz);
        return io.github.mojolowjo.entropybot.world.SurfaceHeight.holeDepth(y, n);
    }

    public static int rockOverHeadKnown(net.minecraft.world.level.Level lv, int x, int y, int z) {
        if (!io.github.mojolowjo.entropybot.world.SurfaceHeight.known(surfaceNoLeaves(lv, x, z))) return -1;
        return rockOverHead(lv, x, y, z);
    }

    /** 0.39.z76 surface 2: the fact of the last {@link #rockOverHead} answer whose surface was not known (null: it was known). */
    private static volatile String surfaceNotKnown;

    public static String surfaceNotKnown() { return surfaceNotKnown; }

    /** 0.39.z76 surface: the chunks whose unknown surface was said (once each). */
    private static final java.util.Set<String> SURFACE_UNKNOWN_SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 0.32.3: "plan go": a plain walk to the plan's end; onJob gets the job id before the goal is set. */
    String startPlanGo(int[] e, String from, java.util.function.LongConsumer onJob) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: not in a world";
        String r = startTravel("goto " + fmt(e), "plan go to " + fmt(e), e, Guard.dimOf(p.level()), false);
        Job j = job;
        if (r == null || r.startsWith("error") || r.startsWith("busy") || j == null || j.done) return r;
        onJob.accept(j.id);
        IBaritone b = baritone();
        j.finalGoal = null;                          // the plan's own end, one GoalBlock
        if (b != null) walkGoal(b, j, new baritone.api.pathing.goals.GoalBlock(e[0], e[1], e[2]));
        return r;
    }


    /**
     * The reflexes against the job (the bridge's syncReflexes for its own jobs): a held job's clocks move on by the
     * hold and the reflex's own path events are not the job's; with AA off an urgent fight stops what isn't a walk; a
     * denied dimension stops everything.
     */
    private void reflexWatch(LocalPlayer p) {
        Reflexes.Reflex r = core.reflexes.reflex(), prev = reflexWas;
        reflexWas = r;
        long now = core.tick();
        reflexNotes(p, r, now);
        if (r != Reflexes.Reflex.NONE && prev == Reflexes.Reflex.NONE) {
            holdStart = now;
            if (running() && job.onHold != null) {
                try { job.onHold.run(); } catch (RuntimeException e) { LOG.warn("[entropybot] job hold hook: {}", e.toString()); }
            }
        }
        if (r == Reflexes.Reflex.NONE && prev != Reflexes.Reflex.NONE && holdStart >= 0) {
            long held = now - holdStart;
            holdStart = -1;
            if (running()) {
                job.startTick += held;
                if (job.seq != null) job.seq.afterHold(held, p);
                if (job.evSeq >= 0) {
                    job.evSeq = core.events.lastSeq();
                    job.evFails = 0;
                    job.lastStepTick = -1;
                }
            }
        }
        if (!running()) return;
        JsonObject st = core.reflexes.status();
        String target = st.has("target") ? st.get("target").getAsString() : "a monster";
        // AA_CLEANUP 4: no retreat stops a job any more (the danger moves hold it); under AA a fight holds it too
        if ((r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING) && st.has("urgent") && st.get("urgent").getAsBoolean()
                && !job.type.equals("travel") && !job.holdOnFight && !io.github.mojolowjo.entropybot.aa.door.Door.aaOn()) {
            stopForReflex("stopped: attacked by " + target);
        } else if (st.has("deniedDim")) {
            stopForReflex("stopped: I am in " + st.get("deniedDim").getAsString() + ", which is off limits");
        }
    }

    /** B7e (E1): the reflex whispers (the bridge's syncReflexes texts), once per transition. */
    private final io.github.mojolowjo.entropybot.engine.ReflexNotes notes = new io.github.mojolowjo.entropybot.engine.ReflexNotes();

    private void reflexNotes(LocalPlayer p, Reflexes.Reflex r, long now) {
        try {
            JsonObject st = core.reflexes.status();
            java.util.List<String> say = notes.step(r.name(), st.has("target") ? st.get("target").getAsString() : null, p.getHealth(),
                    st.has("deniedDim") ? st.get("deniedDim").getAsString() : null,
                    st.has("noFood") && st.get("noFood").getAsBoolean(), p.getFoodData().getFoodLevel());
            say = new java.util.ArrayList<>(say);
            say.addAll(core.reflexes.takeWhispers());       // B7e C: a creeper explosion near the bot
            for (String w : say) commands.whisper(commands.owner(), w);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] reflex notes: {}", e.toString());
        }
    }

    private void stopForReflex(String msg) {
        finish(msg);
        IBaritone b = baritone();
        if (b != null) cancel(b);
    }

    // ---- blocks ----

    static Player findPlayer(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || name == null) return null;
        for (Player p : mc.level.players()) if (p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        return null;
    }

    /** "find <text> [radius]": the nearest block whose id contains the text. */
    static String findBlock(LocalPlayer p, String text) {
        Minecraft mc = Minecraft.getInstance();
        String[] parts = text.trim().split("\\s+");
        String needle = parts[0].toLowerCase();
        int radius = parts.length > 1 ? (int) Math.min(Chains.leadingInt(parts[1]), 32) : 16;
        int[] me = here(p);
        String best = null;
        long bestDist = Long.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d >= bestDist) continue;
                    pos.set(me[0] + dx, me[1] + dy, me[2] + dz);
                    String id = mc.level.getBlockState(pos).getBlock().getDescriptionId();
                    if (!id.contains(needle)) continue;
                    bestDist = d;
                    best = (me[0] + dx) + " " + (me[1] + dy) + " " + (me[2] + dz) + " " + id;
                }
            }
        }
        if (best != null) return "found " + best + " dist " + Math.round(Math.sqrt(bestDist));
        return findFar(p, needle, radius);
    }

    /**
     * rm-execution: nothing within the radius - what the bot has seen: the ore index (under fair play only the ore cells
     * seen), then the trees in Sightings for a log word. The reply says which one answered.
     */
    static String findFar(LocalPlayer p, String needle, int radius) {
        int[] me = here(p);
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        String d = pass.dim();
        if (d == null) return "none within " + radius;
        String n = needle.toLowerCase(java.util.Locale.ROOT);
        var hits = pass.nearest(d, id -> id.contains(n), me[0], me[1], me[2], io.github.mojolowjo.entropybot.memory.Limits.SENSE_KNOWN_R, 1, Mining.fairAllow(d));
        if (!hits.isEmpty()) {
            var h = hits.get(0);
            return "found " + h.x() + " " + h.y() + " " + h.z() + " " + h.id() + " dist " + Math.round(Math.sqrt(h.d2())) + " (the ores I have seen)";
        }
        var sg = pass.sightings();
        if (sg != null && (n.contains("log") || n.contains("wood") || n.contains("tree"))) {
            var trees = sg.treesNear(d, me[0], me[2], 1);
            if (!trees.isEmpty()) {
                var t = trees.get(0);
                return "found " + t.where() + " a tree (" + t.count() + " logs) dist " + Math.round(Math.hypot(t.x() - me[0], t.z() - me[2])) + " (the trees I have seen)";
            }
        }
        return "none within " + radius + ", nor among the ores and trees I have seen";
    }

    /** The job as state.json shows it. */
    JsonObject stateJson() {
        if (job == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("type", job.type);
        o.addProperty("status", job.status);
        o.addProperty("done", job.done);
        if (job.seq != null && !job.done) o.addProperty("step", (job.seq.idx + 1) + "/" + job.seq.steps.size());
        o.add("requester", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("mod", true);
        return o;
    }

    static int[] pos(JsonObject o) {
        return new int[]{o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()};
    }

    static String dimOf(JsonObject o) {
        return o.has("dim") && !o.get("dim").isJsonNull() ? o.get("dim").getAsString() : "minecraft:overworld";
    }
}
