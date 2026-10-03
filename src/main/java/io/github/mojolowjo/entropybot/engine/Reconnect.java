package io.github.mojolowjo.entropybot.engine;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;

import java.util.ArrayDeque;

/**
 * Reconnecting after a kick or a dropped connection (B6, docs/BOT_PLAN.md 8; the owner's and the server owner's
 * answer: yes, at most 3 times an hour). The KubeJS bridge does not tick outside a world, so this lives in the mod:
 * the server last played on is noted while in a world; out of one, it reconnects after 1, 5 and 15 minutes, at most
 * 3 times in any hour. A relaunch after a crash stays the watchdog's (tools/watchdog.ps1).
 */
public final class Reconnect {
    private static final Logger LOG = LogUtils.getLogger();

    private final EventRing events;
    private volatile boolean on = true;
    private String server;
    private long outSince = -1;
    private int tries;
    private final ArrayDeque<Long> recent = new ArrayDeque<>();

    public Reconnect(EventRing events) {
        this.events = events;
    }

    public void setOn(boolean on) { this.on = on; }

    public boolean on() { return on; }

    public String server() { return server; }

    /** Every client tick (in a world or not). Never throws. */
    public void tick(long tick) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                ServerData d = mc.getCurrentServer();
                if (d != null && d.ip != null && !d.ip.isEmpty()) server = d.ip;
                outSince = -1;
                tries = 0;
                return;
            }
            if (server == null || !on || mc.screen instanceof ConnectScreen) return;
            if (outSince < 0) outSince = tick;
            if (!due(tick - outSince, tries, recent, System.currentTimeMillis())) return;
            long now = System.currentTimeMillis();
            recent.addLast(now);
            tries++;
            outSince = tick;
            LOG.info("[entropybot] reconnecting to {} (try {})", server, tries);
            events.push("job", "reconnecting to " + server + " (try " + tries + ")", null);
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server),
                    new ServerData("server", server, ServerData.Type.OTHER), false, null);
        } catch (Throwable t) {
            LOG.warn("[entropybot] reconnect: {}", t.toString());
        }
    }

    static boolean due(long waited, int tries, ArrayDeque<Long> recent, long nowMs) {
        return ReconnectRules.due(waited, tries, recent, nowMs);
    }
}
