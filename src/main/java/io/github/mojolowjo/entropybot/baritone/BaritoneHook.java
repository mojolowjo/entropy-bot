package io.github.mojolowjo.entropybot.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.IBaritoneProcess;
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

    /**
     * Settings no job may turn on (B2, the drift guard): chat and prefix control would let anyone with chat
     * access drive Baritone, exploreForBlocks sends it wandering, a water-bucket fall places water. Breaking and
     * placing are the mod's own jobs' to turn on (SafetyNet turns them off when no job owns them).
     */
    static final String[] FIXED_OFF = { "chatcontrol", "chatcontrolanyway", "prefixcontrol", "exploreforblocks", "allowwaterbucketfall" };

    private boolean hooked, engineRegistered;
    private final PathEventFilter pathFilter = new PathEventFilter();
    private int attempts;
    private String lastError;

    public boolean hooked() { return hooked; }

    public boolean engineRegistered() { return engineRegistered; }

    public String lastError() { return lastError; }

    /** Puts the FIXED_OFF settings back to false; the names it had to turn off (empty when none were on). */
    @SuppressWarnings("unchecked")
    public java.util.List<String> enforceSettings() {
        java.util.List<String> turned = new java.util.ArrayList<>();
        try {
            Settings s = BaritoneAPI.getSettings();
            for (String name : FIXED_OFF) {
                Settings.Setting<?> st = s.byLowerName.get(name);
                if (st == null || !(st.value instanceof Boolean)) continue;
                if ((Boolean) st.value) {
                    ((Settings.Setting<Boolean>) st).value = false;
                    turned.add(name);
                }
            }
        } catch (Throwable t) {
            lastError = t.toString();
        }
        return turned;
    }

    /** Tries once; call again later when it fails (Baritone's primary instance may not exist yet). */
    public void tryHook(EventRing ring, IBaritoneProcess engine) {
        if (hooked) return;
        attempts++;
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b == null) { lastError = "no primary Baritone yet"; return; }
            if (!engineRegistered) {
                b.getPathingControlManager().registerProcess(engine);
                engineRegistered = true;
            }
            b.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    try {
                        // 0.23.1: Baritone's idle tick sends CANCELED 20 times a second: repeats dropped, a line a minute
                        switch (pathFilter.offer(event.name(), System.currentTimeMillis())) {
                            case PASS -> ring.push("path", event.name(), null);
                            case SUMMARY -> ring.push("path", pathFilter.summary(), null);
                            default -> { }
                        }
                    } catch (RuntimeException ignored) {}
                }

                /** 0.23.3: ready legs and the long walk's leg near a changed block are planned again. */
                @Override
                public void onBlockChange(baritone.api.event.events.BlockChangeEvent event) {
                    try {
                        for (var pr : event.getBlocks()) {
                            net.minecraft.core.BlockPos bp = pr.first();
                            io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.blockChanged(bp.getX(), bp.getY(), bp.getZ());
                        }
                    } catch (RuntimeException ignored) {}
                }
            });
            LongRouteProcess.INSTANCE.register(b);          // 0.23.3: the long-route process
            Settings s = BaritoneAPI.getSettings();
            Consumer<Component> previous = s.logger.value;
            s.logger.value = previous.andThen(msg -> {
                try { ring.push("log", msg.getString(), null); } catch (RuntimeException ignored) {}
            });
            hooked = true;
            lastError = null;
            LOG.info("[entropybot] hooked into Baritone: path events, the logger and the engine process");
        } catch (Throwable t) {
            lastError = t.toString();
            if (attempts == 1 || attempts % 300 == 0) LOG.warn("[entropybot] Baritone hook not ready ({}): {}", attempts, lastError);
        }
    }
}
