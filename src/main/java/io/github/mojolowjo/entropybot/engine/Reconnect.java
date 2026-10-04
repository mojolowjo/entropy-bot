package io.github.mojolowjo.entropybot.engine;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;

import java.time.ZoneId;
import java.util.ArrayDeque;

/**
 * Reconnecting after a kick or a dropped connection (B6, docs/BOT_PLAN.md 8; the owner's and the server owner's
 * answer: yes, at most 3 times an hour). The KubeJS bridge does not tick outside a world, so this lives in the mod:
 * the server last played on is noted while in a world (T4: or the one the game was launched to join); out of one, it
 * reconnects after 1, 5 and 15 minutes, then every 30 minutes for 24 hours, at most 3 times in any hour
 * ({@link ReconnectRules}). A relaunch after a crash stays the watchdog's (tools/watchdog.ps1).
 */
public final class Reconnect {
    private static final Logger LOG = LogUtils.getLogger();

    /**
     * T4: the server the game was launched to join (Prism's {@code --quickPlayMultiplayer}), set by
     * ReconnectMixinQuickPlay before the first connect; null without one (or without the Mixin).
     */
    public static volatile String launchServer;

    private final EventRing events;
    private volatile boolean on = true;
    private String server;
    private long outSince = -1;
    /** T4: wall clock when it first found itself out of a world this time (-1: in one); the 24 h count from here. */
    private long firstOutMs = -1;
    private int tries;
    private boolean gaveUpLogged, savedFlagRead;
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private volatile String statusText = "in a world";

    public Reconnect(EventRing events) {
        this.events = events;
    }

    public void setOn(boolean on) { this.on = on; }

    public boolean on() { return on; }

    public String server() { return server; }

    /** T4: state.json's "reconnect" while out of a world: when the next try comes, or that it gave up (updated each tick). */
    public String statusText() { return statusText; }

    /** Every client tick (in a world or not). Never throws. */
    public void tick(long tick) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                ServerData d = mc.getCurrentServer();
                if (d != null && d.ip != null && !d.ip.isEmpty()) server = d.ip;
                outSince = -1;
                firstOutMs = -1;
                tries = 0;
                gaveUpLogged = false;           // (recent stays: 3 tries in any hour, kicks in a row included)
                statusText = "in a world";
                return;
            }
            if (server == null && launchServer != null && !savedFlagRead) {
                // the owner's `reconnect off` lives in commands.json, which Commands only reads inside a world
                savedFlagRead = true;
                if (!ReconnectRules.savedFlag(mc.gameDirectory.toPath().resolve("entropybot").resolve("commands.json"))) on = false;
            }
            server = ReconnectRules.seed(server, launchServer);
            long now = System.currentTimeMillis();
            if (server == null || !on) {
                statusText = ReconnectRules.statusText(on, server, tries, -1, ZoneId.systemDefault());
                return;
            }
            if (mc.screen instanceof ConnectScreen) {
                statusText = "connecting to " + server + (tries > 0 ? " (try " + tries + ")" : "");
                return;
            }
            if (outSince < 0) outSince = tick;
            if (firstOutMs < 0) firstOutMs = now;
            long waited = tick - outSince;
            if (tick % 20 == 0) {
                long next = ReconnectRules.nextTryMs(waited, tries, recent, now, firstOutMs);
                statusText = ReconnectRules.statusText(true, server, tries, next, ZoneId.systemDefault());
                if (next < 0 && !gaveUpLogged) {
                    gaveUpLogged = true;
                    LOG.info("[entropybot] reconnect: gave up on {} after 24 h ({} tries)", server, tries);
                    events.push("job", "reconnect: gave up on " + server + " after 24 h", null);
                }
            }
            if (!ReconnectRules.due(waited, tries, recent, now, firstOutMs)) return;
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
}
