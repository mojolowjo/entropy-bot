package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Quiet;

import baritone.api.BaritoneAPI;
import io.github.mojolowjo.entropybot.Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 0.36.8 (owner 2026-10-18, live aa367: a flooded cave, every walk to the known iron failed "no path" with breaking off
 * and "no way out without breaking a block by water"): while a mine job runs,
 * <ul>
 *   <li>breaking next to WATER is allowed - our dig-out/escape checks (Guard.breakLiquid) and Baritone's own
 *       avoidBreaking (our fork's setting {@code allowBreakNextToWater}); lava stays forbidden everywhere;</li>
 *   <li>0.39.z8: the walk's breaking itself is no longer turned on here (the old job habit is gone: Baritone's travel
 *       settings as shipped, the fork processes own theirs).</li>
 * </ul>
 * Tied to one Jobs.Job object: once that job is done or replaced every answer is false, so a missed end can't leak.
 * Loader notes: Baritone setting looked up by name (byLowerName) so a stock Baritone without it only logs once and
 * keeps its stock rule. Client thread.
 */
public final class MineWater {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");
    private static volatile Jobs.Job job;
    private static boolean missingLogged;

    private MineWater() {}

    /** A mine job starts (Mining.hooks). */
    static void start(Jobs.Job j) {
        job = j;
        if (set(true)) LOG.info("[entropybot] mine: breaking next to water allowed while this mine job runs (lava never)");
    }

    /** The mine job {@code ending} ended (Mining's onEnd; SafetyNet.restore resets the setting too). A newer job is left alone. */
    static void end(Jobs.Job ending) {
        if (job != ending) return;
        boolean was = job != null;
        job = null;
        set(false);
        if (was) LOG.info("[entropybot] mine: breaking next to water off again");
    }

    /** True while the mine job that called start() is the running job. */
    public static boolean active() {
        Jobs.Job j = job;
        if (j == null) return false;
        try {
            Jobs.Job cur = Core.INSTANCE.commands.jobs.job;
            return cur == j && !j.done;
        } catch (Throwable t) {
            Quiet.warn("MineWater.active failed, carrying on with the fallback", t);
            return false;
        }
    }

    /** Puts Baritone's allowBreakNextToWater; false when this Baritone has no such setting (logged once). */
    @SuppressWarnings("unchecked")
    static boolean set(boolean on) {
        try {
            var st = BaritoneAPI.getSettings().byLowerName.get("allowbreaknexttowater");
            if (st == null || !(st.value instanceof Boolean)) {
                if (on && !missingLogged) {
                    missingLogged = true;
                    LOG.warn("[entropybot] mine: this Baritone has no allowBreakNextToWater setting - it still won't break next to water (our checks will)");
                }
                return false;
            }
            ((baritone.api.Settings.Setting<Boolean>) st).value = on;
            return true;
        } catch (Throwable t) {
            LOG.warn("[entropybot] mine: setting allowBreakNextToWater={} failed: {}", on, t.toString());
            return false;
        }
    }
}
