package io.github.mojolowjo.entropybot.baritone;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.aa.brain.GameClock;
import org.slf4j.Logger;

/**
 * Test tooling (docs repo to-do/test-tooling-plan.md; cloud tester runs 40-42, 2026-11-22: with the client really at 200
 * to 400 ticks a second every crafting table use ended "clicked 3 times, nothing opened"). The fork's InteractProcess
 * waits for the server's answer in bare client ticks ({@code interactClickGapTicks} 6, {@code interactSettleTicks} 12:
 * 24 ticks from the first click to giving up, 60-120 ms at those rates). While the client tick-rate hook runs the client
 * above 20 a second (Tuning clock.clientTickRate 1, off by default), these two settings are held at the same WALL time
 * ({@link GameClock#latencyTicks}); back at 20 they get their own values back. Said in the log at every change (rule 7).
 *
 * <p>This writes two Baritone settings, in memory only, and only in a sped-up test game. The lasting fix is in the fork
 * (scale the two waits by the level's tick rate there); until the owner rules on that, this keeps the tests going.
 * A "#set" typed in such a game would save the scaled values with the rest: a test game's files are thrown away.
 * Loader notes: none (Baritone's API: BaritoneAPI.getSettings(), Settings.Setting#value).
 */
public final class ClockSettings {
    private ClockSettings() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static float applied = 20;       // game fact: 20 ticks a second
    private static int gap0 = -1, settle0 = -1;
    private static boolean failed;

    /** Once per client tick (Core.onClientTick, after GameClock.tick): follows the client's rate when it changed. */
    public static void tick() {
        if (failed) return;
        float rate = GameClock.clientRate;
        if (rate == applied) return;
        try {
            var s = baritone.api.BaritoneAPI.getSettings();
            if (gap0 < 0) {
                gap0 = s.interactClickGapTicks.value;
                settle0 = s.interactSettleTicks.value;
            }
            int gap = GameClock.latencyTicks(gap0), settle = GameClock.latencyTicks(settle0);
            s.interactClickGapTicks.value = gap;
            s.interactSettleTicks.value = settle;
            LOG.warn("[entropybot] clock: the client ticks at {} a second - Baritone's interactClickGapTicks {} -> {}, interactSettleTicks {} -> {} (test tooling: the same wall time; its own values at 20)",
                    Math.round(rate), gap0, gap, settle0, settle);
            applied = rate;
        } catch (Throwable t) {
            failed = true;
            io.github.mojolowjo.entropybot.Quiet.warn("clock: Baritone's interact waits could not be scaled to the client's tick rate (station uses may give up early in a sped-up game)", t);
        }
    }
}
