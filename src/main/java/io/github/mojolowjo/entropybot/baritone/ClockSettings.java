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
    private static int gap0 = -1, settle0 = -1, stuck0 = -1, stage0 = -1;
    private static int gapSaid = -1;
    private static boolean failed;

    /**
     * Once per client tick (Core.onClientTick, after GameClock.tick): the four waits follow the rate the client REACHES.
     * Round 3c 2026-11-23 (the log at 200 asked: "interactClickGapTicks 6 -> 6 ... 200 -> 200", then "clicked 3 times,
     * nothing opened" 13 and 26 times in two games): since GameClock.latencyTicks went by the measured rate, the one write
     * at the moment the asked rate changed took the rate measured THEN (still 20) and nothing wrote again. Now written
     * whenever the stretched value changes; said when the click gap moved by half or more since the last line (rule 7,
     * not a line a second).
     */
    public static void tick() {
        if (failed) return;
        if (gap0 < 0 && GameClock.clientRate <= 20) return;       // game fact: 20 ticks a second - never sped up: nothing read, nothing written
        try {
            var s = baritone.api.BaritoneAPI.getSettings();
            if (gap0 < 0) {
                gap0 = s.interactClickGapTicks.value;
                settle0 = s.interactSettleTicks.value;
                stuck0 = s.interactStuckTicks.value;
                stage0 = s.interactStageTicks.value;
                gapSaid = gap0;
            }
            int gap = GameClock.latencyTicks(gap0);
            if (s.interactClickGapTicks.value == gap) return;
            s.interactClickGapTicks.value = gap;
            s.interactSettleTicks.value = GameClock.latencyTicks(settle0);
            // tester run 49 (iron at rate 200: the deposit's use gave up "stuck 10 s short of the chest" one wall second after its
            // walk ended, 100 m from the chest): the use's stuck and stage waits cover a path search's real compute too
            s.interactStuckTicks.value = GameClock.latencyTicks(stuck0);
            s.interactStageTicks.value = GameClock.latencyTicks(stage0);
            if (gap == gap0 || Math.abs(gap - gapSaid) * 2 >= gapSaid) {     // structural: half or more
                gapSaid = gap;
                LOG.warn("[entropybot] clock: the client ticks at {} a second ({} asked) - Baritone's interactClickGapTicks {} -> {}, interactSettleTicks {} -> {}, interactStuckTicks {} -> {}, interactStageTicks {} -> {} (test tooling: the same wall time; its own values at 20)",
                        Math.round(Math.min(GameClock.clientRate, GameClock.measuredRate())), Math.round(GameClock.clientRate), gap0, gap, settle0, s.interactSettleTicks.value, stuck0, s.interactStuckTicks.value, stage0, s.interactStageTicks.value);
            }
        } catch (Throwable t) {
            failed = true;
            io.github.mojolowjo.entropybot.Quiet.warn("clock: Baritone's interact waits could not be scaled to the client's tick rate (station uses may give up early in a sped-up game)", t);
        }
    }
}