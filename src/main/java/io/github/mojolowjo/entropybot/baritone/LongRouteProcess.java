package io.github.mojolowjo.entropybot.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.move.EdgePenalties;
import io.github.mojolowjo.entropybot.move.LegJudge;
import io.github.mojolowjo.entropybot.move.LegPlan;
import io.github.mojolowjo.entropybot.move.MoveStats;
import io.github.mojolowjo.entropybot.move.NoDeadStop;
import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.routing.RouteRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 0.23.3 movement: the long-route process. A far goal (over 48 blocks flat or 10 levels off, {@link LegPlan#needsAssist})
 * is one Baritone process that feeds the pathfinder one 30-40 block leg at a time from inside Baritone's own loop.
 *
 * <p>Baritone API used: {@link IBaritoneProcess} registered with {@code IPathingControlManager.registerProcess}; each
 * tick {@code PathingControlManager.executeProcesses} calls {@link #onTick(boolean, boolean)} (highest priority first)
 * and the returned {@link PathingCommand} with {@link PathingCommandType#SET_GOAL_AND_PATH} becomes the goal. A new leg
 * goal while a path still executes only replaces the goal ({@code PathingBehavior.secretInternalSetGoalAndPath} keeps
 * the current segment and plans the next one toward the new goal), so legs join without a stop. {@code calcFailed} is
 * true when this process was in control and {@code PathingBehavior.calcFailedLastTick()}: that drives {@link NoDeadStop}.
 * Priority -0.75: above Baritone's own processes (-1, so {@code CustomGoalProcess} loses control while a long walk runs),
 * below the mod's {@code EngineProcess} (-0.5, temporary: a fight or meal takes over without {@link #onLostControl};
 * {@code executeProcesses} only calls onLostControl on lower processes when a non-temporary one takes control).
 * {@code cancelEverything} (a stop, an unstick, the dig-out) calls {@link #onLostControl}: the process must then be
 * inactive (Baritone throws otherwise), so it ends with {@code cancelled}; the job resumes it ({@link #resume}).
 *
 * <p>Waypoints: the mod's route map ({@code RouteRuntime.plan}, the door graph; legs by {@code LegsSplitter}); with no map
 * (not built, another dim, breaking on) straight legs ({@code GoalXZ}). A leg that fails, or runs over 2x its straight
 * distance, penalises its map edge ({@link EdgePenalties}) and the whole route is planned again from here.
 * Loader API: none (Baritone's API and Minecraft's {@code LocalPlayer}/{@code BlockPos}).
 */
public final class LongRouteProcess implements IBaritoneProcess {
    private static final Logger LOG = LogUtils.getLogger();
    public static final LongRouteProcess INSTANCE = new LongRouteProcess();

    /** The job side: whether the walk is still wanted, and whether a dig-out is allowed here. */
    public interface Host {
        boolean alive(long walkId);

        boolean canDigOut(int[] me);
    }

    public final MoveStats stats = new MoveStats();
    private Host host;
    private boolean registered;

    private boolean active, arrived, cancelled, digOutRequested;
    private String failure;
    private long walkId;
    private int[] dest;
    private Goal finalGoal;
    private int radius;
    private List<LegPlan.Leg> legs = List.of();
    private final Map<Cell, String> edgeKeys = new HashMap<>();
    private int idx;
    private CompletableFuture<RoutePlan> planning;
    private final NoDeadStop machine = new NoDeadStop();
    private int[] legStart, lastPos, pausedAt;
    private double legWalked;
    /** Game time of the last onTick: a gap means a temporary process (a fight, a meal) had control in between. */
    private long lastTickTime;
    private String lastReason = "";
    private boolean forceReplanLeg;
    /** Raw-input keep-moving target for MovePackage: {x, z} and until which game tick (0 = none). */
    private double[] keepTo;
    private long keepUntil;
    private long ticks;

    private LongRouteProcess() {}

    public void setHost(Host h) { host = h; }

    /** Registers with Baritone once (the BaritoneHook's primary instance). */
    public void register(IBaritone b) {
        if (registered || b == null) return;
        b.getPathingControlManager().registerProcess(this);
        registered = true;
    }

    public boolean registered() { return registered; }

    // ---- the job's side ----

    /** Starts (or replaces) the long walk. ready: a ready first leg's cells (ReadyPaths), may be null. */
    public void start(long walkId, int[] me, int[] dest, Goal finalGoal, int radius) {
        this.walkId = walkId;
        this.dest = dest.clone();
        this.finalGoal = finalGoal;
        this.radius = radius;
        this.arrived = false;
        this.cancelled = false;
        this.failure = null;
        this.digOutRequested = false;
        this.pausedAt = null;
        this.lastTickTime = 0;
        this.lastPos = null;              // a new walk measures its legs from here (a /tp or another walk moved the bot)
        machine.onLegReached();
        stats.longWalks++;
        planRoute(me, "start");
        active = true;
    }

    /** After a cancel (unstick, dig-out, someone's cancelEverything): carry on from here. */
    public void resume(int[] me, String why) {
        if (dest == null || arrived || failure != null) return;
        cancelled = false;
        digOutRequested = false;
        if (LegJudge.resume(pausedAt != null ? pausedAt : lastPos, me)) {
            stats.resumes++;
            LOG.info("[entropybot] long walk resumed at {} {} {} ({}): leg {}/{} kept", me[0], me[1], me[2], why, idx + 1, legs.size());
        } else {
            stats.replansAfterPause++;
            LOG.info("[entropybot] long walk re-planned at {} {} {} ({})", me[0], me[1], me[2], why);
            planRoute(me, why);
        }
        pausedAt = null;
        lastTickTime = 0;
        active = true;
    }

    public void stop() {
        active = false;
        dest = null;
        planning = null;
        keepUntil = 0;
    }

    public boolean active() { return active; }

    public boolean arrived() { return arrived; }

    public boolean cancelled() { return cancelled; }

    public String failure() { return failure; }

    public long walkId() { return walkId; }

    public boolean takeDigOut() {
        boolean d = digOutRequested;
        digOutRequested = false;
        return d;
    }

    /** The dig-out could not start: the walk ends with the report. */
    public void digOutFailed(int[] me) {
        report(me, "no path, and no safe dig-out");
    }

    public double[] keepTo(long tick) { return tick < keepUntil ? keepTo : null; }

    /** The next waypoint for the instant start's direction (the current leg's end), or null. */
    public Cell currentTarget() {
        return idx < legs.size() ? legs.get(idx).end() : null;
    }

    public String statusLine() {
        if (dest == null) return "long walk: none";
        return "long walk to " + dest[0] + " " + dest[1] + " " + dest[2] + ": " + (active ? "on" : arrived ? "arrived" : failure != null ? "failed" : "paused")
                + ", leg " + Math.min(idx + 1, legs.size()) + "/" + legs.size() + (planning != null ? " (map planning)" : "")
                + ", fails in a row " + machine.failsInRow() + (lastReason.isEmpty() ? "" : ", last: " + lastReason);
    }

    /** A block changed: when it lies near the current leg's straight line, the leg is planned again. */
    public void blockChanged(int x, int y, int z) {
        if (!active || lastPos == null || idx >= legs.size()) return;
        Cell e = legs.get(idx).end();
        if (nearSegment(lastPos[0], lastPos[2], e.x(), e.z(), x, z, 2) && Math.abs(y - lastPos[1]) <= 4) forceReplanLeg = true;
    }

    static boolean nearSegment(double ax, double az, double bx, double bz, double px, double pz, double r) {
        double vx = bx - ax, vz = bz - az, len = vx * vx + vz * vz;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len));
        double dx = ax + t * vx - px, dz = az + t * vz - pz;
        return dx * dx + dz * dz <= r * r;
    }

    // ---- planning ----

    private void planRoute(int[] me, String why) {
        Cell start = new Cell(me[0], me[1], me[2]), goal = new Cell(dest[0], dest[1], dest[2]);
        legs = LegPlan.legs(start, List.of(), goal);           // straight until the map answers
        edgeKeys.clear();
        idx = 0;
        legStart = me.clone();
        legWalked = 0;
        stats.replans++;
        planning = null;
        try {
            if (RouteRuntime.INSTANCE.available()) planning = RouteRuntime.INSTANCE.plan(RouteRuntime.OVERWORLD, start, goal, radius);
        } catch (Throwable t) {
            LOG.warn("[entropybot] long walk: route map plan failed ({}): straight legs", t.toString());
        }
        LOG.info("[entropybot] long walk ({}): {} straight legs to {} {} {}{}", why, legs.size(), dest[0], dest[1], dest[2],
                planning != null ? ", asking the route map" : "");
    }

    private void takePlan(int[] me) {
        if (planning == null || !planning.isDone()) return;
        CompletableFuture<RoutePlan> f = planning;
        planning = null;
        try {
            RoutePlan p = f.getNow(null);
            if (p == null || p.status() != RoutePlan.Status.OK || p.path().isEmpty()) {
                LOG.info("[entropybot] long walk: route map has no way ({}): straight legs", p == null ? "none" : p.reason());
                return;
            }
            List<Cell> doors = new ArrayList<>();
            edgeKeys.clear();
            for (RoutePlan.Step s : p.path()) {
                if (s.rep() == null) continue;
                doors.add(s.rep());
                edgeKeys.put(s.rep(), EdgePenalties.key(s.box().toString(), s.rep().x(), s.rep().y(), s.rep().z()));
            }
            legs = LegPlan.legs(new Cell(me[0], me[1], me[2]), doors, new Cell(dest[0], dest[1], dest[2]));
            idx = 0;
            legStart = me.clone();
            legWalked = 0;
            LOG.info("[entropybot] long walk: route map gave {} doors -> {} legs", doors.size(), legs.size());
        } catch (Throwable t) {
            LOG.warn("[entropybot] long walk: reading the plan: {}", t.toString());
        }
    }

    private void penalise(LegPlan.Leg leg, double ticks, String why) {
        String k = edgeKeys.get(leg.end());
        if (k == null) return;
        EdgePenalties.GLOBAL.add(k, ticks, System.currentTimeMillis());
        stats.penalised++;
        LOG.info("[entropybot] long walk: edge {} penalised +{} ticks ({})", k, Math.round(ticks), why);
    }

    private void report(int[] me, String reason) {
        failure = "couldn't get there (stopped at " + me[0] + " " + me[1] + " " + me[2] + ": " + reason + ")";
        stats.reports++;
        active = false;
        keepUntil = 0;
        LOG.info("[entropybot] long walk: {}", failure);
    }

    // ---- Baritone's side ----

    @Override
    public boolean isActive() {
        try {
            return active && host != null && host.alive(walkId);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            return tick(calcFailed);
        } catch (Throwable t) {
            LOG.error("[entropybot] long walk error: {}", t.toString());
            active = false;
            failure = "couldn't get there (error: " + t + ")";
            return null;
        }
    }

    private PathingCommand tick(boolean calcFailed) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || dest == null) {
            active = false;
            return null;
        }
        ticks++;
        int[] me = {(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        // Baritone calls onTick only while no higher process (the mod's engine: a fight, a meal) answers first, so a gap
        // in the game time since the last call is a pause; lastPos is where it began
        long gt = Minecraft.getInstance().level.getGameTime();
        if (lastTickTime > 0 && gt - lastTickTime > 2 && lastPos != null) pausedAt = lastPos;
        lastTickTime = gt;
        if (pausedAt != null) {
            // back after a temporary process: the leg goes on (near) or the route is planned again (moved away)
            if (LegJudge.resume(pausedAt, me)) {
                stats.resumes++;
                LOG.info("[entropybot] long walk resumed at {} {} {} after a pause: leg {}/{} kept", me[0], me[1], me[2], idx + 1, legs.size());
            } else {
                stats.replansAfterPause++;
                LOG.info("[entropybot] long walk: moved {} blocks during the pause, planning again", Math.round(Math.sqrt(sq(pausedAt, me))));
                planRoute(me, "after a pause");
            }
            pausedAt = null;
        }
        if (lastPos != null) legWalked += Math.sqrt(sq(lastPos, me));
        lastPos = me;
        takePlan(me);
        if (finalGoal.isInGoal(new BlockPos(me[0], me[1], me[2]))) {
            arrived = true;
            active = false;
            keepUntil = 0;
            LOG.info("[entropybot] long walk arrived at {} {} {}", me[0], me[1], me[2]);
            return null;
        }
        // a leg reached (or close: the next goal is set while still walking)
        while (idx < legs.size() - 1 && LegPlan.distTo(legs.get(idx), me[0], me[1], me[2]) <= LegPlan.ADVANCE_AT) {
            LegPlan.Leg l = legs.get(idx);
            double straight = Math.sqrt(sq(legStart, new int[]{l.end().x(), l.xzOnly() ? legStart[1] : l.end().y(), l.end().z()}));
            boolean ranLong = LegJudge.ranLong(legWalked, straight);
            machine.onLegReached();
            stats.legs++;
            if (ranLong) {
                stats.legsLong++;
                lastReason = "leg " + (idx + 1) + " ran " + Math.round(legWalked) + " for " + Math.round(straight);
                penalise(l, EdgePenalties.LONG, lastReason);
            }
            if (LegJudge.replanWhole(false, ranLong)) {
                planRoute(me, "a leg ran long");
                break;
            }
            idx++;
            legStart = me.clone();
            legWalked = 0;
        }
        if (calcFailed) {
            stats.fails++;
            LegPlan.Leg l = legs.get(idx);
            NoDeadStop.Action a = machine.onFail(host != null && host.canDigOut(me));
            lastReason = "leg " + (idx + 1) + " to " + l.end().x() + " " + l.end().y() + " " + l.end().z() + ": no path (" + a.name().toLowerCase() + ")";
            LOG.info("[entropybot] long walk: {}", lastReason);
            switch (a) {
                case NEXT_WAYPOINT -> {
                    penalise(l, EdgePenalties.FAIL, "no path");
                    if (idx < legs.size() - 1) {
                        idx++;
                        legStart = me.clone();
                        legWalked = 0;
                    }
                    keepMoving(me);
                }
                case DETOUR -> {
                    penalise(l, EdgePenalties.FAIL, "no path twice");
                    planRoute(me, "detour");
                    keepMoving(me);
                }
                case DIG_OUT -> {
                    stats.digOuts++;
                    digOutRequested = true;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                case REPORT -> {
                    report(me, NoDeadStop.MAX_FAILS + " waypoints failed in a row, last " + l.end().x() + " " + l.end().y() + " " + l.end().z());
                    return null;
                }
            }
        }
        if (digOutRequested) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (forceReplanLeg) {
            forceReplanLeg = false;
            try {
                if (b.getPathingBehavior() instanceof baritone.behavior.PathingBehavior pb) pb.softCancelIfSafe();     // internal: plan the leg again
            } catch (Throwable ignored) {}
        }
        return new PathingCommand(goalOf(legs.get(idx)), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private void keepMoving(int[] me) {
        Cell t = currentTarget();
        if (t == null) return;
        keepTo = new double[]{t.x() + 0.5, t.z() + 0.5};
        keepUntil = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime() + 30;
        stats.keepMoving++;
    }

    private Goal goalOf(LegPlan.Leg l) {
        if (l.last()) return finalGoal;
        if (l.xzOnly()) return new GoalXZ(l.end().x(), l.end().z());
        return new GoalNear(new BlockPos(l.end().x(), l.end().y(), l.end().z()), 2);
    }

    static double sq(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean isTemporary() { return false; }

    @Override
    public void onLostControl() {
        if (active) {
            cancelled = true;
            pausedAt = lastPos;
        }
        active = false;
        keepUntil = 0;
    }

    @Override
    public double priority() { return -0.75; }

    @Override
    public String displayName0() { return "Entropy Bot long walk"; }
}
