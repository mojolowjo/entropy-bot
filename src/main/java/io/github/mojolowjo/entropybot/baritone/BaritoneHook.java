package io.github.mojolowjo.entropybot.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * Listens to Baritone through its public API only: path events become "path" events in the ring,
 * its chat lines become "log" events (the logger is chained with andThen, never replaced).
 */
public final class BaritoneHook {
    private static final Logger LOG = LogUtils.getLogger();

    private boolean hooked;
    private int attempts;
    private String lastError;

    public boolean hooked() { return hooked; }

    public String lastError() { return lastError; }

    /** Tries once; call again later when it fails (Baritone's primary instance may not exist yet). */
    public void tryHook(EventRing ring) {
        if (hooked) return;
        attempts++;
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b == null) { lastError = "no primary Baritone yet"; return; }
            b.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    try { ring.push("path", event.name(), null); } catch (RuntimeException ignored) {}
                }
            });
            Settings s = BaritoneAPI.getSettings();
            Consumer<Component> previous = s.logger.value;
            s.logger.value = previous.andThen(msg -> {
                try { ring.push("log", msg.getString(), null); } catch (RuntimeException ignored) {}
            });
            hooked = true;
            lastError = null;
            LOG.info("[entropybot] hooked into Baritone: path events and the logger");
        } catch (Throwable t) {
            lastError = t.toString();
            if (attempts == 1 || attempts % 300 == 0) LOG.warn("[entropybot] Baritone hook not ready ({}): {}", attempts, lastError);
        }
    }
}
