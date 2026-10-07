package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.MixinFlags;
import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.RoutePlanner;
import io.github.mojolowjo.entropybot.route.RoutePlannerHolder;
import io.github.mojolowjo.entropybot.route.RouteStats;
import io.github.mojolowjo.entropybot.routewalk.RouteWalk;
import io.github.mojolowjo.entropybot.routing.RouteRuntime;
import net.minecraft.client.player.LocalPlayer;

import java.nio.file.Path;
import java.util.List;

/**
 * The {@code route} verb (routing stage 1, R3), owner only: status, on|off, mode goal|legs, build, dump (instant), and
 * test (a job: {@link RouteTestRun}). The texts are in {@link RouteRules}.
 */
final class RouteCommand {
    private RouteCommand() {}

    static RouteStats plannerStats() {
        try {
            return RoutePlannerHolder.get().stats();
        } catch (Throwable t) {
            RouteWalker.RLOG.error("planner.stats", t);
            return null;
        }
    }

    /**
     * Review M1: the stored {@code route on|off} switches the map builder too ({@link RouteRuntime#setEnabled}): off stops
     * the engine (saving) at the next tick, {@code available()} turns false and walks are plain. Called at load and on
     * every change of the route settings. Never throws.
     */
    static void syncRuntime(Commands c) {
        try {
            RouteRuntime.INSTANCE.setEnabled(RouteWalker.on(c));
            com.google.gson.JsonObject r = c.routeSettings();          // 0.23.3: path assist (default on)
            io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.setAssist(!r.has("assist") || r.get("assist").getAsBoolean());
        } catch (Throwable t) {
            RouteWalker.RLOG.error("route on/off", t);
        }
    }

    /** The map's own line (pause reason, hooks, builder counters, tile saves). Never throws. */
    static String runtimeLine() {
        try {
            return RouteRuntime.INSTANCE.statusLine();
        } catch (Throwable t) {
            return "status failed: " + t;
        }
    }

    /** The map's health for check (review M2). */
    static RouteRules.MapHealth health() {
        RouteRuntime r = RouteRuntime.INSTANCE;
        return new RouteRules.MapHealth(MixinFlags.levelHookApplied, r.levelCalls(), r.baritoneEvents(), r.expectedRunning(),
                r.unavailableReason());
    }

    static boolean available() {
        try {
            return RoutePlannerHolder.get().available();
        } catch (Throwable t) {
            return false;
        }
    }

    /** check's routing findings. */
    static List<SelfCheck.Finding> findings(Commands c) {
        try {
            return RouteRules.check(RouteWalker.on(c), available(), RoutePlannerHolder.counters().snapshot(System.currentTimeMillis(), 0, 0),
                    plannerStats(), health());
        } catch (RuntimeException e) {
            return List.of(new SelfCheck.Finding("routecheck", "couldn't check routing: " + e, "route status"));
        }
    }

    /** Everything but "route test". */
    static String instant(Commands c, LocalPlayer p, String rest) {
        String sub = RouteRules.first(rest), arg = RouteRules.after(rest);
        switch (sub) {
            case "", "status" -> {
                return RouteRules.status(RouteWalker.on(c), RouteWalker.mode(c).word(), available(), RouteWalker.ASKED.get(),
                        RouteWalker.PLAIN.get(), RouteWalker.last, RoutePlannerHolder.counters().snapshot(System.currentTimeMillis(), 0, 0),
                        plannerStats()) + "\nmap: " + runtimeLine();
            }
            case "on", "off" -> {
                JsonObject r = c.routeSettings().deepCopy();
                r.addProperty("on", sub.equals("on"));
                c.setRouteSettings(r);     // also switches the map builder (syncRuntime)
                if (sub.equals("off")) return "ok: routing off - walks are plain and the map builder stops (it saves the map first)";
                return "ok: routing on" + (!available() ? " - the map builder starts within a second; walks stay plain until it runs (route status)" : "");
            }
            case "mode" -> {
                RouteWalk.Mode m = RouteWalk.Mode.parse(arg);
                if (m == null || m == RouteWalk.Mode.PLAIN) return "usage: route mode goal|legs (now " + RouteWalker.mode(c).word() + "; route off for plain walks)";
                JsonObject r = c.routeSettings().deepCopy();
                r.addProperty("mode", m.word());
                c.setRouteSettings(r);
                return "ok: route mode " + m.word();
            }
            case "build" -> { return build(c, p, arg); }
            case "dump" -> { return dump(p, arg); }
            case "test" -> { return "error: route test is a job (send it on its own)"; }
            default -> { return "usage: " + RouteRules.USAGE; }
        }
    }

    /** route build: the stretch from the bot to a place (or x y z), or the boxes around the bot. */
    private static String build(Commands c, LocalPlayer p, String arg) {
        int[] me = Jobs.here(p);
        String dim = Guard.dimOf(p.level());
        int d = RoutePlannerHolder.dimId(dim);
        if (d != 0) return "error: routing is for the overworld only (I'm in " + dim + ")";
        int[] to = me;
        String what = "around me";
        if (!arg.isEmpty() && !arg.equalsIgnoreCase("here")) {
            to = RouteRules.coords(arg);
            what = "from me to " + arg;
            if (to == null) {
                JsonObject pos = Core.INSTANCE.knowledge.places().get(arg.toLowerCase());
                if (pos == null) return "I have no place called " + arg + " - next: " + Hints.placeFix(arg.toLowerCase());
                if (!Jobs.dimOf(pos).equals(dim)) return "error: " + arg + " is in " + Jobs.dimOf(pos);
                to = Jobs.pos(pos);
                what = "from me to " + arg + " (" + Jobs.fmt(to) + ")";
            }
        }
        String far = RouteRules.buildTooFar(me, to, io.github.mojolowjo.entropybot.routing.RouteEngine.MAX_ALONG_BLOCKS);
        if (far != null) return far;
        RoutePlanner pl = RoutePlannerHolder.get();
        if (!available()) return "error: the route planner isn't running";
        try {
            pl.requestBuildAlong(d, new Cell(me[0], me[1], me[2]), new Cell(to[0], to[1], to[2]));
        } catch (Throwable t) {
            RouteWalker.RLOG.error("route build", t);
            return "error: " + t;
        }
        return "ok: building the route boxes " + what + " (route status shows the queue)";
    }

    /** route dump x y z: the box holding that block, as a JUnit fixture under entropybot/routes/dumps. */
    private static String dump(LocalPlayer p, String arg) {
        int[] at = arg.isEmpty() || arg.equalsIgnoreCase("here") ? Jobs.here(p) : RouteRules.coords(arg);
        if (at == null) return "usage: route dump x y z";
        int d = RoutePlannerHolder.dimId(Guard.dimOf(p.level()));
        if (d != 0) return "error: routing is for the overworld only";
        Path out = Core.INSTANCE.files().root().resolve("routes").resolve("dumps");
        try {
            boolean ok = RoutePlannerHolder.dumper().dump(d, at[0], at[1], at[2], out);
            return ok ? "ok: dumping the box at " + Jobs.fmt(at) + " to " + out : "error: the route dump isn't available (planner not running, or that box can't be read)";
        } catch (Throwable t) {
            RouteWalker.RLOG.error("route dump", t);
            return "error: " + t;
        }
    }
}
