package io.github.mojolowjo.entropybot.baritone;

import io.github.mojolowjo.entropybot.Quiet;

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
     * placing are never set by the mod (0.39.z8): each fork process owns them for its own run.
     */
    static final String[] FIXED_OFF = { "chatcontrol", "chatcontrolanyway", "prefixcontrol", "exploreforblocks", "allowwaterbucketfall" };

    private boolean hooked, engineRegistered;
    private final PathEventFilter pathFilter = new PathEventFilter();
    private int attempts;
    private String lastError;

    /**
     * 0.39.z33 (S36): Baritone's last chat line and how many it has said (its logger, client thread). The stock mine process
     * has no end reason in its API (IMineProcess: no lastEnd); it says its end in this line ("Unable to find any path to ...").
     */
    private static volatile String lastLine;
    private static volatile long lines;

    public static String lastLine() { return lastLine; }

    public static long lines() { return lines; }

    /**
     * 0.39.z71 (design D2 3.2): Baritone's CALC_FAILED path events since the hook was wired (client thread; a running count -
     * the step monitor counts those since a step's last new best). Read only with {@link #hooked()} true: before that the
     * monitor says its signals are not loaded (an unwired hook is never a quiet zero).
     */
    private static volatile long calcFailed;

    public static long calcFailed() { return calcFailed; }

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
                        if (event == PathEvent.CALC_FAILED) calcFailed++;                          // 0.39.z71 (D2 3.2): the step monitor's no-path count
                        io.github.mojolowjo.entropybot.commands.WalkProbe.event(event.name());     // 0.39.z54 B: every event while a walk step runs (probe on)
                        io.github.mojolowjo.entropybot.commands.StepProbe.event(event.name());     // 0.39.z56 item 1: any step (debug.stepProbe)
                        io.github.mojolowjo.entropybot.commands.LoadProbe.pathEvent(event.name()); // 0.39.z56 item 3: calcs counted (debug.loadProbe)
                        // 0.23.1: Baritone's idle tick sends CANCELED 20 times a second: repeats dropped, a line a minute
                        switch (pathFilter.offer(event.name(), System.currentTimeMillis())) {
                            case PASS -> ring.push("path", event.name(), null);
                            case SUMMARY -> ring.push("path", pathFilter.summary(), null);
                            default -> { }
                        }
                    } catch (RuntimeException caught) { Quiet.warn("BaritoneHook.onPathEvent failed, carrying on with the fallback", caught); }
                }

                /** 0.29.0: the builder dig hears changed blocks. */
                @Override
                public void onBlockChange(baritone.api.event.events.BlockChangeEvent event) {
                    try {
                        for (var pr : event.getBlocks()) {
                            net.minecraft.core.BlockPos bp = pr.first();
                            io.github.mojolowjo.entropybot.commands.BuilderDig.blockChanged(bp.getX(), bp.getY(), bp.getZ(), pr.second() != null && pr.second().isAir());     // 0.29.0
                        }
                    } catch (RuntimeException caught) { Quiet.warn("BaritoneHook.onBlockChange failed, carrying on with the fallback", caught); }
                }
            });
            Settings s = BaritoneAPI.getSettings();
            Consumer<Component> previous = s.logger.value;
            s.logger.value = previous.andThen(msg -> {
                try { lastLine = msg.getString(); lines++; ring.push("log", lastLine, null); } catch (RuntimeException caught) { Quiet.warn("BaritoneHook.onBlockChange failed, carrying on with the fallback", caught); }
            });
            hooked = true;
            lastError = null;
            LOG.info("[entropybot] hooked into Baritone: path events, the logger and the engine process; {}", io.github.mojolowjo.entropybot.commands.BuilderDig.checkLine());
        } catch (Throwable t) {
            lastError = t.toString();
            if (attempts == 1 || attempts % 300 == 0) LOG.warn("[entropybot] Baritone hook not ready ({}): {}", attempts, lastError);
        }
    }
}
