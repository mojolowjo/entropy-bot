package io.github.mojolowjo.entropybot.engine;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.MutableMoveResult;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.baritone.CostAdvisorHook;
import io.github.mojolowjo.entropybot.guard.PolicyRuntime;
import org.slf4j.Logger;

/**
 * Reflex probe (0.39.s5, "probe, not theory"): one debug line per tick while a reflex leg runs. It shows who drives
 * Baritone (the process in control and its priority), the goal, the last path calculation (its result, its context's
 * allowBreak / place / parkour and the cost hook's intent captured for that path thread), the live settings, the engine
 * state, and the moves Baritone's own cost model allows from the feet now (the first cell of any path). Reads only;
 * client thread; never throws.
 *
 * <p>Loader notes: fork-only {@code PathingBehavior.entropyLastCalc} and {@code calcFailedLastTick()} (the mod compiles
 * against the fork jar); a stock jar gives "no calc record" via the LinkageError catch.
 */
public final class ReflexProbe {
    private static final Logger LOG = LogUtils.getLogger();

    private ReflexProbe() { }

    public static String line(EngineProcess engine, String way, String leg) {
        StringBuilder sb = new StringBuilder("reflex probe [").append(way).append("] ").append(leg);
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            var pcm = b.getPathingControlManager();
            var inCtl = pcm.mostRecentInControl();
            sb.append(" | ctl ").append(inCtl.map(p -> p.displayName() + "@" + p.priority()).orElse("none"));
            var pb = b.getPathingBehavior();
            sb.append(" | goal ").append(pb.getGoal());
            sb.append(" | path ").append(pb.getCurrent() != null ? "yes(" + pb.getCurrent().getPath().length() + ")" : "no")
                    .append(pb.getInProgress().isPresent() ? " calculating" : "");
            try {
                var fpb = (baritone.behavior.PathingBehavior) pb;
                sb.append(" failedLastTick=").append(fpb.calcFailedLastTick());
                var c = fpb.entropyLastCalc;
                if (c == null) sb.append(" | last calc none");
                else {
                    CalculationContext x = c.context();
                    Object tok = x.advisorToken;
                    String intent = tok instanceof CostAdvisorHook.Token t ? String.valueOf(t.intent()) : (tok == null ? "no token" : "?");
                    sb.append(" | last calc ").append(c.type()).append(" len ").append(c.length())
                            .append(" from ").append(c.start().toShortString()).append(" ").append(System.currentTimeMillis() - c.atMs()).append("ms ago")
                            .append(" ctx break=").append(x.allowBreak).append(" place=").append(x.hasThrowaway)
                            .append(" parkour=").append(x.allowParkour).append(" intent=").append(intent);
                }
            } catch (ClassCastException | LinkageError e) {
                sb.append(" | no calc record (").append(e.getClass().getSimpleName()).append(")");
            }
            var s = BaritoneAPI.getSettings();
            sb.append(" | settings break=").append(s.allowBreak.value).append(" place=").append(s.allowPlace.value)
                    .append(" parkour=").append(s.allowParkour.value);
            sb.append(" | intentNow=").append(PolicyRuntime.INSTANCE.intentNow());
            sb.append(" | engine ").append(engine.mode()).append(engine.reflexGoal() ? " reflex" : "").append(" calcFails ").append(engine.calcFails());
            sb.append(" | first cell ").append(firstCell(b));
        } catch (RuntimeException | LinkageError e) {
            sb.append(" | probe error ").append(e);
        }
        return sb.toString();
    }

    /** Baritone's own moves from the feet now: how many are possible, the cheapest, and the advisor's move multiplier there. */
    private static String firstCell(IBaritone b) {
        var ctx = b.getPlayerContext();
        if (ctx.player() == null) return "no player";
        var feet = ctx.playerFeet();
        CalculationContext c = new CalculationContext(b, true);
        MutableMoveResult r = new MutableMoveResult();
        int ok = 0;
        double best = ActionCosts.COST_INF;
        String bestAt = "-";
        for (baritone.pathing.movement.Moves m : baritone.pathing.movement.Moves.values()) {
            r.reset();
            m.apply(c, feet.x, feet.y, feet.z, r);
            if (r.cost >= ActionCosts.COST_INF) continue;
            ok++;
            if (r.cost < best) { best = r.cost; bestAt = m.name() + "->" + r.x + "," + r.y + "," + r.z; }
        }
        double mm = ok == 0 ? Double.NaN : c.moveMultiplier(r.x, r.y, r.z);
        return "from " + feet.x + "," + feet.y + "," + feet.z + " " + ok + " moves possible, cheapest " + bestAt
                + (ok == 0 ? "" : " cost " + Math.round(best * 10) / 10.0) + " (fresh ctx break=" + c.allowBreak + " intent="
                + (c.advisorToken instanceof CostAdvisorHook.Token t ? t.intent() : "-") + " move mult " + mm + ")";
    }

    public static void log(EngineProcess engine, String way, String leg) {
        LOG.info("[entropybot] {}", line(engine, way, leg));
    }
}
