package io.github.mojolowjo.entropybot.baritone;

import baritone.pathing.movement.CalculationContext;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.guard.BreakPolicy;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.MixinFlags;
import io.github.mojolowjo.entropybot.guard.PolicyRuntime;
import io.github.mojolowjo.entropybot.guard.PolicyStats;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Mining plan 3.4: the policy's costs into Baritone's A*. Preferred: the fork hook, our Baritone's
 * {@code CalculationContext.costAdvisor} ({@code baritone.pathing.movement.CostAdvisor}: begin / breakMultiplier /
 * placeCost), filled by reflection with a {@link Proxy} because the mod compiles against the stock jar. Fallback: the
 * mixin on {@code breakCostMultiplierAt} / {@code costOfPlacingAt} (GuardMixinCalculationContext), which calls the same
 * two methods here with no token. The ready line, {@code check} and {@code fence rules} say which one runs.
 *
 * <p>Pathfinder thread: snapshot reads and {@code bsi} only; never throws (a throw would make the move impossible in
 * the fork, and is swallowed by the mixin). {@code Double.NaN} = no opinion: Baritone's own rule.
 */
public final class CostAdvisorHook {
    private static final Logger LOG = LogUtils.getLogger();
    private static final double COST_INF = baritone.api.pathing.movement.ActionCosts.COST_INF;

    /** What one calculation holds fixed: captured on the thread that builds the context. */
    public record Token(BreakPolicy.Snapshot snapshot, Intent intent, String dim, BreakPolicy.Where me) {}

    private static volatile boolean fork;

    private CostAdvisorHook() {}

    public static boolean fork() { return fork; }

    /** Once Baritone is loaded: the fork hook when this Baritone has it, else the mixin fallback. Returns the kind. */
    public static String install() {
        PolicyRuntime pr = PolicyRuntime.INSTANCE;
        try {
            Field f = CalculationContext.class.getField("costAdvisor");
            Class<?> iface = f.getType();
            InvocationHandler h = (proxy, method, args) -> dispatch(method, args);
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
            f.set(null, proxy);
            fork = true;
            pr.setHook("fork");
            LOG.info("[entropybot] rules: cost advisor installed (fork hook)");
        } catch (NoSuchFieldException e) {
            fork = false;
            pr.setHook(MixinFlags.astarApplied ? "mixin" : "none");
            LOG.info("[entropybot] rules: this Baritone has no CostAdvisor hook - {}", pr.hookText());
        } catch (Throwable t) {
            fork = false;
            pr.setHook(MixinFlags.astarApplied ? "mixin" : "none");
            LOG.warn("[entropybot] rules: installing the cost advisor failed ({}) - {}", t.toString(), pr.hookText());
        }
        return pr.hook();
    }

    private static Object dispatch(Method m, Object[] a) {
        try {
            switch (m.getName()) {
                case "begin": return begin();
                case "breakMultiplier":
                    return breakMultiplier(a[0], (Integer) a[1], (Integer) a[2], (Integer) a[3], (BlockState) a[4], stateAt(a[5]));
                case "placeCost":
                    return placeCost(a[0], (Integer) a[1], (Integer) a[2], (Integer) a[3], (BlockState) a[4], stateAt(a[5]), (Double) a[6]);
                case "toString": return "EntropyBot cost advisor";
                case "hashCode": return System.identityHashCode(CostAdvisorHook.class);
                case "equals": return a != null && a.length == 1 && a[0] != null && Proxy.isProxyClass(a[0].getClass());
                default: return null;
            }
        } catch (Throwable t) {
            PolicyRuntime.INSTANCE.stats.error("advisor " + m.getName() + ": " + t);
            return m.getReturnType() == double.class ? COST_INF : null;
        }
    }

    private static PolicyRuntime.StateAt stateAt(Object bsi) {
        if (bsi instanceof baritone.utils.BlockStateInterface b) return PolicyRuntime.StateAt.of(b);
        return (x, y, z) -> null;
    }

    /** The token for one calculation: the snapshot, the intent on this thread, the dimension, where the bot is. */
    public static Token begin() {
        PolicyRuntime pr = PolicyRuntime.INSTANCE;
        String dim = null;
        try {
            var lv = Minecraft.getInstance().level;
            if (lv != null) dim = Guard.dimOf(lv);
        } catch (Throwable ignored) {}
        return new Token(pr.snapshot(), pr.intentNow(), dim, pr.where());
    }

    /**
     * The break multiplier for the fork hook (token from {@link #begin}) and the mixin fallback (token null: the live
     * intent and snapshot, {@code dim} supplied). NaN = Baritone's own; COST_INF = forbidden.
     */
    public static double breakMultiplier(Object token, int x, int y, int z, BlockState state, PolicyRuntime.StateAt at) {
        return breakMultiplier(token, null, x, y, z, state, at);
    }

    public static double breakMultiplier(Object token, String dimIfNoToken, int x, int y, int z, BlockState state, PolicyRuntime.StateAt at) {
        PolicyRuntime pr = PolicyRuntime.INSTANCE;
        if (!PolicyRuntime.LIVE) return Double.NaN;
        Token t = token instanceof Token tk ? tk : null;
        Intent in = t != null ? t.intent() : pr.intentNow();
        String dim = t != null ? t.dim() : dimIfNoToken;
        BreakPolicy.Snapshot s = t != null ? t.snapshot() : pr.snapshot();
        BreakPolicy.Where me = t != null ? t.me() : pr.where();
        if (in == null || in == Intent.NONE) return COST_INF;                     // walking only (the route map's context)
        if (dim == null || s == null) return in == Intent.TRAVEL ? COST_INF : Double.NaN;   // fail closed for travel
        switch (in) {
            case TRAVEL -> {
                BreakPolicy.Verdict v = pr.judge(PolicyStats.SITE_ASTAR_COST, BreakPolicy.Action.BREAK, in, dim, x, y, z, pr.facts(at, x, y, z, false), me, s);
                return v.allowed() ? v.multiplier() : COST_INF;
            }
            case MINE -> {
                // phase 1: mining keeps Baritone's rules, except the owner's: no digging down from in the water
                pr.stats.ask(PolicyStats.SITE_ASTAR_COST);
                BlockState up = at.at(x, y + 1, z);
                if (up != null && PolicyRuntime.fluidOf(up) == BreakPolicy.Fluid.WATER) {
                    pr.stats.deny(PolicyStats.SITE_ASTAR_COST, "digging down from in the water");
                    return COST_INF;
                }
                return Double.NaN;
            }
            default -> { return Double.NaN; }                                         // DIG_OUT, BUILD, RESTORE: as today
        }
    }

    /** Placing: phase 1 leaves Baritone's own cost (placing stays owned by the plan/build jobs); counted only. */
    public static double placeCost(Object token, int x, int y, int z, BlockState current, PolicyRuntime.StateAt at, double baritoneCost) {
        PolicyRuntime pr = PolicyRuntime.INSTANCE;
        pr.stats.ask(PolicyStats.SITE_ASTAR_PLACE);
        Token t = token instanceof Token tk ? tk : null;
        Intent in = t != null ? t.intent() : pr.intentNow();
        if (PolicyRuntime.LIVE && (in == null || in == Intent.NONE)) return COST_INF;
        return Double.NaN;
    }
}
