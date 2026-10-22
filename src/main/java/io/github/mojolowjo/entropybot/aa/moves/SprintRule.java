package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.BaritoneAPI;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.aa.brain.HungerConserve;
import org.slf4j.Logger;

/**
 * rm-routes: the one place the hunger-conserve flag (HungerConserve, renewed by the AA tick) reaches the car: Baritone's
 * {@code allowSprint} off while conserving, except while fleeing; the value from before goes back when the flag lapses.
 * Replaces the old movement package's setting profiles. Loader API: none (Baritone settings only).
 */
public final class SprintRule {
    private static final Logger LOG = LogUtils.getLogger();
    private static Boolean held;          // allowSprint before conserving began (null: not conserving)
    private static String lastError;

    private SprintRule() {}

    /** Once a tick in a world. Never throws; a failure is logged once per distinct reason. */
    public static void tick(boolean fleeing) {
        try {
            var s = BaritoneAPI.getSettings().allowSprint;
            boolean on = HungerConserve.active(System.currentTimeMillis());
            if (!on) {
                if (held != null) {
                    s.value = held;
                    held = null;
                    LOG.info("[entropybot] sprint: conserve over, Baritone allowSprint back to {}", s.value);
                }
                return;
            }
            if (held == null) {
                held = s.value;
                LOG.info("[entropybot] sprint: conserving hunger, Baritone allowSprint off (fleeing keeps it)");
            }
            s.value = fleeing;
        } catch (Throwable t) {
            String why = t.toString();
            if (!why.equals(lastError)) LOG.warn("[entropybot] sprint rule failed at the settings stage: {}", why);
            lastError = why;
        }
    }
}
