package io.github.mojolowjo.entropybot.commands;

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
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
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
 * twerk, ported from the bridge's startTravel / stepWalkJob / startHome / startSetSpawn / startWait / startTwerk with
 * the same wording. A walk listens to Baritone's path events (no path after 3 failed calculations, a stuck watchdog
 * for goals it can't reach), holds still while a reflex runs, stops for a retreat, an urgent fight (not a walk) or
 * the area fence, and reports through the request it carries (a chain waits on it).
 */
public final class Jobs {
    private static final Logger LOG = LogUtils.getLogger();
    static final int TRAVEL_FAILS = 3;
    static final long TRAVEL_STUCK_TICKS = 600;

    public static final class Job {
        public String type, status, label, goal;
        public boolean done, reflex;
        Request req;
        long startTick, until, total, ticks;
        int[] dest;
        String bed;
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
        // stepping off a block Baritone can't plan from (a modded altar, a pedestal): ticks left, tries made
        int unstickLeft, unstickTries;
        double[] unstickTo;
        // B7b part 2: a job made of steps, the goal object of its walk (unsticking plans it again), and whether the
        // open menu is closed when it ends ("always", or "fail" = unless it ended ok)
        Seq seq;
        baritone.api.pathing.goals.Goal goalObj;
        String closeOnEnd;
        /**
         * B7d (D3): an urgent fight holds this job instead of ending it (the bridge's job.holdOnFight: explore, mine
         * cave, the Baritone mine); onHold runs when a reflex starts holding it (the Baritone mine stops and breaking
         * goes off), onEnd however it ends (finish, stop, a reflex, the fence): breaking off, leases released.
         * ownsBreaking: the job turned Baritone's breaking on (SafetyNet leaves it be while Baritone's mine runs).
         */
        public boolean holdOnFight, ownsBreaking;
        public Runnable onHold, onEnd;
    }

    static final int UNSTICK_TRIES = 2, UNSTICK_TICKS = 12;
    static final long UNSTICK_EARLY = 100;

    private final Core core;
    private final Commands commands;
    public Job job;
    private long tpFailedAt = -100000, holdStart = -1;
    private Reflexes.Reflex reflexWas = Reflexes.Reflex.NONE;
    /** A follow the fence keeps an eye on: {name, from}. */
    String[] followWatch;
    /**
     * A follow of a player the bot cannot see, led by the position their companion mod sends (OwnerFix): {name, from}.
     * The walk is a plain "goto" to that spot, issued again when they move; Baritone's own follow takes over when they
     * come into view. Any other job or a stop clears it.
     */
    String[] followFix;
    private int[] followFixGoal;
    private long followFixAt;

    Jobs(Core core, Commands commands) {
        this.core = core;
        this.commands = commands;
    }

    public boolean running() { return job != null && !job.done; }

    Core core() { return core; }

    /** A job made of steps (open, scan, deposit, corpse, rs, pots...): "started: <label>". */
    String startSeq(Seq s, String closeOnEnd) {
        followWatch = null;
        followFix = null;
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
        try { return BaritoneAPI.getProvider().getPrimaryBaritone(); } catch (Throwable t) { return null; }
    }

    static boolean idle(IBaritone b) {
        return !b.getPathingBehavior().isPathing() && b.getPathingControlManager().mostRecentInControl().isEmpty();
    }

    static void cancel(IBaritone b) {
        try { b.getPathingBehavior().cancelEverything(); } catch (Throwable ignored) {}
    }

    /** A walk never breaks or places (the bridge's restoreSafeSettings, for Baritone's part). */
    static void safeSettings() {
        try {
            BaritoneAPI.getSettings().allowBreak.value = false;
            BaritoneAPI.getSettings().allowPlace.value = false;
        } catch (Throwable ignored) {}
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
    public void finish(String msg) {
        Job j = job;
        if (j == null || j.done) return;
        j.done = true;
        j.status = msg;
        if (j.onEnd != null) {
            try { j.onEnd.run(); } catch (RuntimeException e) { LOG.warn("[entropybot] job end hook: {}", e.toString()); }
        }
        Minecraft mc = Minecraft.getInstance();
        if (j.type.equals("twerk") || j.seq != null) mc.options.keyShift.setDown(false);      // twerk, a farm round's crouch
        if (j.unstickLeft > 0) endUnstick(j);
        if (j.seq != null) { try { String m = Clearing.ended(j.seq, msg); if (m != null && !m.equals(msg)) { msg = m; j.status = m; } } catch (RuntimeException e) { LOG.warn("[entropybot] clear end hook: {}", e.toString()); } }           // B7d D1: a clear's leases end with the job, however it ends (a table left is named); never keeps the request open
        if (j.seq != null) {
            LocalPlayer p = mc.player;
            boolean close = "always".equals(j.closeOnEnd) || ("fail".equals(j.closeOnEnd) && !msg.startsWith("ok"));
            if (close && p != null && (io.github.mojolowjo.entropybot.gui.Gui.open(p) || mc.screen != null)) io.github.mojolowjo.entropybot.gui.Gui.close(p);
            IBaritone b = baritone();
            if (b != null && !msg.startsWith("ok")) cancel(b);
        }
        LOG.info("[entropybot] job finished: {}", msg);
        try { core.recorder.jobEnded(j.type, j.label, msg); } catch (RuntimeException e) { LOG.warn("[entropybot] recorder job end: {}", e.toString()); }
        if (j.req != null) commands.requests.done(j.req.id, msg);
    }

    /** A new command replaces a walk: it ends quietly (the bridge's "job.done = true"). */
    public void replaceWalk() {
        if (walking()) finish(JobRequests.REPLACED);
    }

    // ---- the fence ----

    /** Null when the bot may walk to x y z, else the guard's reason (FenceRules.goalAllowed). */
    String goalAllowed(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        return FenceRules.goalAllowed(io.github.mojolowjo.entropybot.api.BotAPI.check(Guard.dimOf(mc.level), x, y, z, "go"), commands.fenceOn());
    }

    static String withAreaHint(String reason) { return FenceRules.withAreaHint(reason); }

    static String fenceError(String reason) { return FenceRules.gotoRefusal(reason); }

    // ---- walks ----

    /**
     * goal: a Baritone command ("goto x y z", "goto x z", "follow player NAME"); dest: where it ends up (a /home
     * first when that is near home and far off), or null; reflex: no fence check.
     */
    /** Package D: when the last errand walk (come, goto, follow, go, base) started (ms; 0 = none yet). */
    long lastTravelAt;

    public String startTravel(String goal, String label, int[] dest, String destDim, boolean reflex) {
        IBaritone b = baritone();
        if (b == null) return "error: baritone not loaded";
        LocalPlayer p = Minecraft.getInstance().player;
        if (!reflex) {
            int[] spot = dest != null ? dest : FenceRules.goalSpot(goal, (int) Math.floor(p.getY()));
            String why = spot == null ? null : goalAllowed(spot[0], spot[1], spot[2]);
            if (why != null) return FenceRules.gotoRefusal(why);
            lastTravelAt = System.currentTimeMillis();     // package D: an errand (come, goto, go...) holds the furnace pickups
        }
        followWatch = null;
        followFix = null;
        safeSettings();
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
        b.getCommandManager().execute(goal);
        j.status = label;
        job = j;
        watch(j);
        return "ok: " + label;
    }

    /** "home": /home right away. */
    public String startHome(LocalPlayer p) {
        JsonObject h = commands.home();
        int[] me = here(p);
        if (h != null && dimOf(h).equals(Guard.dimOf(p.level())) && distSq(pos(h), me) <= 64) return "ok: already home (" + fmt(pos(h)) + ")";
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
        String f = findBlock(p, "_bed 24");
        if (!f.startsWith("found")) return "error: no bed within 24 blocks";
        String[] w = f.split(" ");
        String[] parts = w[4].split("\\.");          // block.minecraft.white_bed
        int x = Integer.parseInt(w[1]), y = Integer.parseInt(w[2]), z = Integer.parseInt(w[3]);
        IBaritone b = baritone();
        if (b == null) return "error: baritone not loaded";
        String why = goalAllowed(x, y, z);
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
        int s = (int) Math.min(Math.max(Chains.leadingInt(text) == 0 ? 10 : Chains.leadingInt(text), 1), 3600);
        Job j = new Job();
        j.type = "wait";
        j.until = core.tick() + s * 20L;
        j.status = "waiting " + s + "s";
        job = j;
        return "started: waiting " + s + "s";
    }

    /** "twerk" is a toggle: crouch on/off every 4 ticks until "twerk" again; "twerk <s>" for that long. */
    public String startTwerk(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        long seconds = Chains.leadingInt(t);
        if (running() && job.type.equals("twerk")) {
            if (t.equals("on")) return "ok: already twerking";
            Request r = job.req;
            job.req = null;                          // one reply, not a whisper as well
            finish("ok: stopped twerking");
            if (r != null) commands.requests.done(r.id, JobRequests.REPLACED);
            return "ok: stopped twerking";
        }
        if (t.equals("off")) return "ok: not twerking";
        Job j = new Job();
        j.type = "twerk";
        if (seconds > 0) {
            j.total = Math.min(seconds, 60) * 20;
            j.status = "twerking";
            job = j;
            return "started: twerking for " + Math.min(seconds, 60) + "s";
        }
        j.total = Long.MAX_VALUE;
        j.status = "twerking (PM \"twerk\" again to stop)";
        job = j;
        return "started: twerking - PM \"twerk\" again to stop";
    }

    // ---- the teleport home ----

    /** Worth a /home on the way? Only if dest is near home and the bot is far from it (another dimension, 40+ across, 10+ up or down). */
    boolean tpWorth(Player p, int[] dest, String destDim) {
        JsonObject h = commands.home();
        return h != null && TpRules.worth(pos(h), dimOf(h), dest, destDim, here(p), Guard.dimOf(p.level()), core.tick(), tpFailedAt);
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
            if (j.evFails >= TRAVEL_FAILS) return "nopath";
        } catch (RuntimeException e) {
            j.evSeq = -1;
        }
        return null;
    }

    static int[] travelDest(Job j) {
        if (j.dest != null) return j.dest;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^goto (-?\\d+) (-?\\d+) (-?\\d+)$").matcher(j.goal == null ? "" : j.goal);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))} : null;
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
        if (j.lastStepTick < 0 || now - j.lastStepTick > 40) {
            j.bestDist = dist;
            j.bestTick = now;
        }
        j.lastStepTick = now;
        if (dist < j.bestDist - 1) {
            j.bestDist = dist;
            j.bestTick = now;
        }
        return now - j.bestTick > TRAVEL_STUCK_TICKS;
    }

    // ---- the tick ----

    /** Once a tick in a world. */
    void tick(LocalPlayer p) {
        long now = core.tick();
        reflexWatch(p);
        if (now % 20 == 10) fenceWatch(p);
        if (now % 20 == 15) followFixTick(p);
        if (!running()) return;
        if (core.reflexes.hold()) {                      // a reflex holds the job still
            if (job.unstickLeft > 0) endUnstick(job);
            return;
        }
        Job j = job;
        if (j.unstickLeft > 0) {
            unstickStep(p, j);
            return;
        }
        switch (j.type) {
            case "twerk" -> {
                j.ticks++;
                Minecraft.getInstance().options.keyShift.setDown(j.ticks < j.total && (j.ticks / 4) % 2 == 0);
                if (j.ticks >= j.total) finish("ok: twerked");
            }
            case "wait" -> { if (now >= j.until) finish("ok: waited"); }
            case "travel", "spawn" -> { if (now % 20 == 0) stepWalk(p, j); }
            case "seq" -> { if (now % 2 == 0 || j.seq.everyTick()) j.seq.tick(p); }
            default -> {}
        }
    }

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
            if (b != null) b.getCommandManager().execute(j.goal);
            j.startTick = core.tick();
            j.status = j.label;
            watch(j);
            return;
        }
        String ev = travelEvents(j);
        boolean stuck = j.type.equals("travel") && ev == null && travelStuck(p, j);
        // Baritone plans nothing from a block it doesn't understand (a modded altar or pedestal, seen live
        // 2026-10-02): step off it by hand first, sooner when the bot isn't standing on a full block
        boolean early = j.type.equals("travel") && ev == null && travelDest(j) != null && j.lastStepTick >= 0
                && !onFullBlock(p) && core.tick() - j.bestTick > UNSTICK_EARLY;
        if ((stuck || early || "nopath".equals(ev)) && j.unstickTries < UNSTICK_TRIES && startUnstick(p, j)) return;
        if (stuck) {
            if (b != null) cancel(b);
            finish(noPathResult(p, j));
            return;
        }
        if (ev == null && core.tick() - j.startTick < 40) return;
        if (b != null && !idle(b)) return;
        if ("nopath".equals(ev)) {
            finish(noPathResult(p, j));
        } else if (j.type.equals("travel")) {
            finish("ok: arrived near " + fmt(here(p)) + (j.tpNote != null ? " (" + j.tpNote + ")" : ""));
        } else {
            String r = useBlock(p, j.bed);
            finish(r.startsWith("ok") ? "ok: clicked the bed at " + j.bed + " (spawn set if it was night or the server allows it)" : r);
        }
    }

    // ---- unsticking ----

    /** Standing on a whole block (or the ground of a full-block top)? An altar, a pedestal, a slab-like machine is not. */
    static boolean onFullBlock(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        double frac = p.getY() - Math.floor(p.getY());
        if (frac > 0.01 && frac < 0.99) return false;
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.01, p.getZ());
        return mc.level.getBlockState(below).isCollisionShapeFullBlock(mc.level, below);
    }

    /** A cell the bot can stand in: nothing to bump into at the feet and head, a full block under it. */
    static boolean standable(Minecraft mc, BlockPos feet) {
        return mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                && mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()
                && mc.level.getBlockState(feet.below()).isCollisionShapeFullBlock(mc.level, feet.below())
                && mc.level.getFluidState(feet).isEmpty();
    }

    /** Picks the free spot next to the bot nearest the destination and starts walking onto it; false when there is none. */
    boolean startUnstick(LocalPlayer p, Job j) {
        Minecraft mc = Minecraft.getInstance();
        int[] me = here(p), d = travelDest(j);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = 1; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos c = new BlockPos(me[0] + dx, me[1] + dy, me[2] + dz);
                    if (!standable(mc, c)) continue;
                    double score = d == null ? Math.abs(dx) + Math.abs(dz) : Math.sqrt(distSq(new int[]{c.getX(), c.getY(), c.getZ()}, d));
                    if (score < bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
            }
        }
        if (best == null) return false;
        IBaritone b = baritone();
        if (b != null) cancel(b);
        j.unstickTries++;
        j.unstickLeft = UNSTICK_TICKS;
        j.unstickTo = new double[]{best.getX() + 0.5, best.getY(), best.getZ() + 0.5};
        LOG.info("[entropybot] stuck at {} (on {}): stepping to {} {} {}", fmt(me),
                mc.level.getBlockState(BlockPos.containing(p.getX(), p.getY() - 0.01, p.getZ())).getBlock().getDescriptionId(), best.getX(), best.getY(), best.getZ());
        core.events.push("job", "unstick: stepping off at " + fmt(me), null);
        return true;
    }

    private void unstickStep(LocalPlayer p, Job j) {
        Minecraft mc = Minecraft.getInstance();
        p.lookAt(EntityAnchorArgument.Anchor.FEET, new Vec3(j.unstickTo[0], p.getY(), j.unstickTo[2]));
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(j.unstickTo[1] >= Math.floor(p.getY()));
        if (--j.unstickLeft > 0) return;
        endUnstick(j);
        // and plan again from the new spot (a fresh stuck clock and fresh path events)
        IBaritone b = baritone();
        if (b != null && j.goalObj != null) b.getCustomGoalProcess().setGoalAndPath(j.goalObj);
        else if (b != null && j.goal != null) b.getCommandManager().execute(j.goal);
        j.startTick = core.tick();
        j.lastStepTick = -1;
        watch(j);
    }

    static void endUnstick(Job j) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        j.unstickLeft = 0;
    }

    /**
     * The reflexes against the job (the bridge's syncReflexes for its own jobs): a held job's clocks move on by the
     * hold and the reflex's own path events are not the job's; a retreat stops any job, an urgent fight stops what
     * isn't a walk, a denied dimension stops everything.
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
        if (r == Reflexes.Reflex.RETREATING && prev != Reflexes.Reflex.RETREATING) {
            stopForReflex("stopped: low health, getting away from " + target);
        } else if ((r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING) && st.has("urgent") && st.get("urgent").getAsBoolean()
                && !job.type.equals("travel") && !job.holdOnFight) {
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

    /** T3: the tick the bot first stood 2+ blocks outside the areas during a dig (-1: it doesn't). */
    private long fenceOutSince = -1;

    /** T3: the mod's own break leases (a clear holds them while it digs; a walk or a craft trip holds none). */
    private boolean holdsDigLeases() {
        for (io.github.mojolowjo.entropybot.guard.Lease l : core.guard.core.leases().values()) {
            if (!l.place && core.token.equals(l.owner)) return true;
        }
        return false;
    }

    /** Every 20 ticks with the fence on: a job outside every area stops; a follow ends when its player leaves the areas. */
    private void fenceWatch(LocalPlayer p) {
        if (!commands.fenceOn()) return;
        int[] me = here(p);
        String dim = Guard.dimOf(p.level());
        if (FenceRules.watches(true, running(), running() && job.reflex)) {
            // T3: a clear pulled 2-4 blocks out (a drop, a cave) gets 15 s to come back; everything else as before
            int gap = commands.areaGap(me[0], me[1], me[2], dim);
            long now = core.tick();
            FenceGrace.Verdict v = FenceGrace.verdict(gap, gap > 1 && holdsDigLeases(), fenceOutSince, now);
            if (v == FenceGrace.Verdict.OK) {
                fenceOutSince = -1;
            } else if (v == FenceGrace.Verdict.GRACE) {
                if (fenceOutSince < 0) {
                    fenceOutSince = now;
                    LOG.info("[entropybot] {} block(s) outside my areas at {} - 15 s to get back in", gap, fmt(me));
                }
            } else {
                boolean afterGrace = fenceOutSince >= 0;
                fenceOutSince = -1;
                IBaritone b = baritone();
                if (b != null) cancel(b);
                finish(FenceGrace.stopMessage(fmt(me), afterGrace));
                return;
            }
        } else {
            fenceOutSince = -1;
        }
        if (followWatch != null) {
            Player t = findPlayer(followWatch[0]);
            if (t == null) return;
            int[] tp = here(t);
            String why = goalAllowed(tp[0], tp[1], tp[2]);
            if (why != null) {
                IBaritone b = baritone();
                if (b != null) cancel(b);
                commands.whisper(followWatch[1], FenceRules.followLeft(followWatch[0], tp));
                LOG.info("[entropybot] follow stopped: {} left the areas at {} ({})", followWatch[0], fmt(tp), why);
                followWatch = null;
            }
        }
    }

    // ---- following a player the bot cannot see (the owner's companion mod) ----

    /** "follow <name>" with the player out of view but a fresh position t: walk there and keep walking as it changes. */
    String startFollowFix(String name, String from, int[] t) {
        String r = followFixGo(name, from, t);
        return r.startsWith("ok") ? r + " (by the position your game sends, until you're in view)" : r;
    }

    /** One walk to t, then the follow state again (startTravel clears it); like a follow, the job itself never "arrives". */
    private String followFixGo(String name, String from, int[] t) {
        String r = startTravel("goto " + fmt(t), "following " + name, null, null, false);
        if (!r.startsWith("ok")) return r;
        if (job != null) job.done = true;
        followFix = new String[]{name, from};
        followFixGoal = t;
        followFixAt = System.currentTimeMillis();
        return r;
    }

    private void stopFollowFix(String why) {
        String from = followFix[1];
        followFix = null;
        IBaritone b = baritone();
        if (b != null) cancel(b);
        commands.whisper(from, why);
        LOG.info("[entropybot] follow (companion position) ended: {}", why);
    }

    /** Once a second: hand over when the player is in view, stop when the position is stale, walk again when they moved 5+ blocks. */
    private void followFixTick(LocalPlayer p) {
        if (followFix == null) return;
        if (running()) {                         // another job took over
            followFix = null;
            return;
        }
        if (core.reflexes.hold()) return;        // a fight or a meal: not now
        String name = followFix[0], from = followFix[1];
        if (findPlayer(name) != null) {          // in view: Baritone's own follow does it better
            followFix = null;
            commands.modJob("follow", name, from, p);
            return;
        }
        int[] t = commands.ownerFixPos(name);
        if (t == null) {
            stopFollowFix("stopped: I lost track of you (no fresh position from your game, or you're in another dimension)");
            return;
        }
        // re-aim only when the player moved a fair share of the way (5 blocks, or 1/8 of the distance when far): every
        // new goal is a whole new Baritone search, and far away a few blocks change nothing about the way there
        double far = Math.sqrt(distSq(t, here(p))) / 8;
        long moved = (long) Math.max(5, far);
        if (System.currentTimeMillis() - followFixAt < 1500 || distSq(t, followFixGoal) <= moved * moved) return;
        String r = followFixGo(name, from, t);
        if (!r.startsWith("ok")) stopFollowFix("stopped: " + r.replaceFirst("^error: ", ""));
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
        return best != null ? "found " + best + " dist " + Math.round(Math.sqrt(bestDist)) : "none within " + radius;
    }

    /** Right-clicks the block at "x y z" (within reach). */
    static String useBlock(LocalPlayer p, String text) {
        String[] c = text.trim().split("\\s+");
        int x = Integer.parseInt(c[0]), y = Integer.parseInt(c[1]), z = Integer.parseInt(c[2]);
        Vec3 center = new Vec3(x + 0.5, y + 0.5, z + 0.5);
        double dist = p.getEyePosition().distanceTo(center);
        if (dist > 4.5) return "error: too far (" + Math.round(dist * 10) / 10.0 + " blocks), walk closer first";
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        Minecraft mc = Minecraft.getInstance();
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(center, Direction.UP, new BlockPos(x, y, z), false));
        p.swing(InteractionHand.MAIN_HAND);
        return "ok: " + r;
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
