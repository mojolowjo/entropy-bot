package io.github.mojolowjo.entropybot.commands;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.baritone.RouteGoal;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.route.RoutePlanner;
import io.github.mojolowjo.entropybot.route.RoutePlannerHolder;
import io.github.mojolowjo.entropybot.routewalk.RouteDecision;
import io.github.mojolowjo.entropybot.routewalk.RouteWalk;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The game side of routed walks (routing stage 1, R3): {@link Jobs#startTravel} and {@link Seq}'s walk step ask
 * {@link #begin} whether to plan, poll the {@link RouteWalk}, take their Baritone goal from {@link #goalFor} and call
 * {@link #fallBack} after "no path" or "stuck". Whatever goes wrong here, the walk is today's plain walk: every
 * method catches and logs (rate-limited, {@link RouteLog}) and answers "plain".
 *
 * <p>Loader API: Baritone's api ({@code Goal}, {@code GoalNear}, {@code BaritoneAPI.getSettings()}), Minecraft's
 * {@code LocalPlayer}/{@code BlockPos} (the same names in Fabric's Mojang mappings); no NeoForge API.
 */
final class RouteWalker {
    private static final Logger LOG = LogUtils.getLogger();
    static final RouteLog RLOG = RouteLog.of(s -> LOG.info("[entropybot] {}", s));

    /** Walks that asked the planner, walks that stayed plain (any reason), since the game started. */
    static final AtomicLong ASKED = new AtomicLong(), PLAIN = new AtomicLong();
    /** The last decision, for route status: "plain: short walk", "goal: 12 boxes, planned in 40 ms", ... */
    static volatile String last = "none yet";

    private RouteWalker() {}

    static boolean on(Commands c) {
        JsonObject r = c.routeSettings();
        return !r.has("on") || r.get("on").getAsBoolean();
    }

    static RouteWalk.Mode mode(Commands c) {
        JsonObject r = c.routeSettings();
        RouteWalk.Mode m = r.has("mode") ? RouteWalk.Mode.parse(r.get("mode").getAsString()) : null;
        return m == null || m == RouteWalk.Mode.PLAIN ? RouteWalk.Mode.GOAL : m;
    }

    static boolean breakingOn() {
        try {
            return BaritoneAPI.getSettings().allowBreak.value || BaritoneAPI.getSettings().allowPlace.value;
        } catch (Throwable t) {
            return true;       // can't tell: don't route
        }
    }

    /** goalRadius for the planner from the goal's kind: GoalBlock 0, GoalGetToBlock 1, GoalNear 2. */
    static int radiusOf(Goal g) {
        if (g instanceof GoalNear) return 2;
        if (g instanceof baritone.api.pathing.goals.GoalGetToBlock) return 1;
        return 0;
    }

    /**
     * Asks the planner for a walk to dest when the decision says so; null = walk plain (today's walk).
     * override: a {@code route test} trip's mode (PLAIN = never route), else null (the settings).
     */
    static RouteWalk begin(Commands c, LocalPlayer p, int[] dest, String destDim, int radius, boolean fixedGoal, RouteWalk.Mode override) {
        try {
            RouteWalk.Mode mode = override != null ? override : mode(c);
            boolean enabled = override != null ? override != RouteWalk.Mode.PLAIN : on(c);
            String here = Guard.dimOf(p.level());
            int[] me = Jobs.here(p);
            String gd = destDim != null ? destDim : here;
            int sd = RoutePlannerHolder.dimId(here), gdi = RoutePlannerHolder.dimId(gd);
            RoutePlanner planner = RoutePlannerHolder.get();
            boolean available;
            try {
                available = planner.available();
            } catch (Throwable t) {
                RLOG.error("planner.available", t);
                available = false;
            }
            boolean inStart = c.inAreas(here, me[0], me[2]);
            boolean inGoal = dest != null && c.inAreas(gd, dest[0], dest[2]);
            double dist = dest == null ? 0 : Math.sqrt(Jobs.distSq(me, dest));
            RouteDecision.Result d = RouteDecision.decide(enabled, available, breakingOn(), sd, gdi, inStart, inGoal, dist,
                    fixedGoal && dest != null);
            if (!d.use()) {
                PLAIN.incrementAndGet();
                last = "plain: " + d.why();
                return null;
            }
            Cell start = new Cell(me[0], me[1], me[2]), goal = new Cell(dest[0], dest[1], dest[2]);
            CompletableFuture<RoutePlan> f = planner.plan(sd, start, goal, radius);
            ASKED.incrementAndGet();
            last = mode.word() + ": planning " + Math.round(dist) + " blocks";
            return new RouteWalk(mode, sd, start, goal, f, System.currentTimeMillis());
        } catch (Throwable t) {
            RLOG.error("route begin", t);
            RoutePlannerHolder.counters().workerException("route begin", t, null);
            PLAIN.incrementAndGet();
            last = "plain: error " + t;
            return null;
        }
    }

    /** Polls the plan; the phase (PLANNING = keep waiting). Never throws. */
    static RouteWalk.Phase poll(RouteWalk w) {
        try {
            RouteWalk.Phase ph = w.poll(System.currentTimeMillis(), RoutePlannerHolder.counters(), RLOG);
            if (ph != RouteWalk.Phase.PLANNING) {
                RoutePlan pl = w.plan();
                last = w.used() + (pl != null ? " (" + pl.status() + ": " + pl.reason() + ", " + pl.nodes() + " nodes, planned in "
                        + Math.round(pl.micros() / 1000.0) + " ms, waited " + w.planWaitMs() + " ms, " + pl.table().size() + " boxes)" : "");
                if (ph == RouteWalk.Phase.PLAIN) PLAIN.incrementAndGet();
            }
            return ph;
        } catch (Throwable t) {
            RLOG.error("route poll", t);
            return RouteWalk.Phase.PLAIN;
        }
    }

    /**
     * The goal to walk with now: the RouteGoal around plain (mode goal), the current leg (mode legs; the last leg is
     * plain itself), or plain.
     */
    static Goal goalFor(RouteWalk w, Goal plain) {
        try {
            if (w == null || !w.routed()) return plain;
            if (w.mode() == RouteWalk.Mode.GOAL) return new RouteGoal(plain, w.table());
            if (w.mode() == RouteWalk.Mode.LEGS) {
                Cell leg = w.currentLeg();
                if (leg == null || w.lastLeg()) return plain;
                return new GoalNear(new BlockPos(leg.x(), leg.y(), leg.z()), 2);
            }
        } catch (Throwable t) {
            RLOG.error("route goal", t);
        }
        return plain;
    }

    /** The current leg's end as {x, y, z} for the stuck watchdog (null: measure against the real goal). */
    static int[] legDest(RouteWalk w) {
        if (w == null || !w.routed() || w.lastLeg()) return null;
        Cell c = w.currentLeg();
        return c == null ? null : new int[]{c.x(), c.y(), c.z()};
    }

    /**
     * After "no path" or "stuck" on a routed walk: marks the stretch from here to the goal stale (the planner builds it
     * again), counts it, and returns true: the caller walks the plain goal now. False when the walk was not routed.
     */
    static boolean fallBack(RouteWalk w, String why, LocalPlayer p) {
        try {
            if (w == null || !w.fallBack(why, RoutePlannerHolder.counters())) return false;
            int[] me = Jobs.here(p);
            try {
                RoutePlannerHolder.get().requestBuildAlong(w.dim(), new Cell(me[0], me[1], me[2]), w.goal());
            } catch (Throwable t) {
                RLOG.error("requestBuildAlong", t);
            }
            RLOG.warn("walk to " + w.goal() + " fell back to plain at " + Jobs.fmt(me) + " (" + why + ")");
            last = w.used();
            return true;
        } catch (Throwable t) {
            RLOG.error("route fallback", t);
            return false;
        }
    }
}
